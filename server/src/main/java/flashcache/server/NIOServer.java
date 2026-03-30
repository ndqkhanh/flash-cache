package flashcache.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Non-blocking TCP server using Java NIO Selector.
 *
 * <p>The event loop runs on a single virtual thread.  Accepted connections are
 * managed via the {@link ConnectionManager}.  Each read is dispatched to a
 * {@link RequestHandler} which produces a response ByteBuffer that is written
 * back to the client.
 */
public class NIOServer {

    private static final Logger log = LoggerFactory.getLogger(NIOServer.class);

    private static final int READ_BUFFER_SIZE = 8192;

    @FunctionalInterface
    public interface RequestHandler {
        /** Given a request ByteBuffer (flipped, ready to read), return a response ByteBuffer. */
        ByteBuffer handle(ByteBuffer request);
    }

    private final RequestHandler handler;
    private final ConnectionManager connectionManager = new ConnectionManager();

    private volatile Selector selector;
    private volatile ServerSocketChannel serverChannel;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread eventLoopThread;

    /** Actual port bound (useful when port 0 was requested). */
    private volatile int boundPort;

    public NIOServer(RequestHandler handler) {
        this.handler = handler;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Start the server, binding to {@code port}.  Pass 0 to pick a random
     * available port (useful in tests).  Returns only after the selector is
     * open and the server is accepting connections.
     */
    public void start(int port) throws IOException {
        if (running.get()) {
            throw new IllegalStateException("Server is already running");
        }

        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.socket().setReuseAddress(true);
        serverChannel.bind(new InetSocketAddress(port));
        boundPort = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        running.set(true);

        // Run the event loop on a virtual thread
        eventLoopThread = Thread.ofVirtual().name("nio-event-loop").start(this::eventLoop);
        log.info("NIOServer started on port {}", boundPort);
    }

    /**
     * Stop the server, close all connections and the selector.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("Stopping NIOServer on port {}", boundPort);

        // Wake up the selector so the event loop can exit
        if (selector != null) {
            selector.wakeup();
        }

        // Wait for the event loop to finish
        if (eventLoopThread != null) {
            try {
                eventLoopThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Close all registered channels
        if (selector != null) {
            try {
                for (SelectionKey key : selector.keys()) {
                    closeKey(key);
                }
                selector.close();
            } catch (IOException e) {
                log.warn("Error closing selector", e);
            }
        }

        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException e) {
                log.warn("Error closing server channel", e);
            }
        }

        log.info("NIOServer stopped");
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Number of currently active client connections (not counting the server accept channel).
     */
    public int connectionCount() {
        return connectionManager.activeCount();
    }

    /**
     * Return the port actually bound (useful when start(0) was called).
     */
    public int getBoundPort() {
        return boundPort;
    }

    // -------------------------------------------------------------------------
    // Event loop
    // -------------------------------------------------------------------------

    private void eventLoop() {
        log.debug("Event loop started");
        try {
            while (running.get()) {
                int ready;
                try {
                    ready = selector.select(200); // 200ms timeout to check running flag
                } catch (IOException e) {
                    if (!running.get()) break;
                    log.error("Select error", e);
                    break;
                }

                if (!running.get()) break;
                if (ready == 0) continue;

                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iter = selectedKeys.iterator();

                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();

                    if (!key.isValid()) continue;

                    try {
                        if (key.isAcceptable()) {
                            handleAccept(key);
                        } else if (key.isReadable()) {
                            handleRead(key);
                        } else if (key.isWritable()) {
                            handleWrite(key);
                        }
                    } catch (Exception e) {
                        log.warn("Error handling key", e);
                        closeKey(key);
                    }
                }
            }
        } finally {
            log.debug("Event loop exited");
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
        SocketChannel client = ssc.accept();
        if (client == null) return;
        client.configureBlocking(false);
        long connId = connectionManager.register(client);
        SelectionKey clientKey = client.register(selector, SelectionKey.OP_READ);
        clientKey.attach(connId);
        log.debug("Accepted connection id={} from {}", connId, client.getRemoteAddress());
    }

    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        long connId = (Long) key.attachment();

        ByteBuffer readBuf = ByteBuffer.allocate(READ_BUFFER_SIZE);
        int bytesRead;
        try {
            bytesRead = client.read(readBuf);
        } catch (IOException e) {
            log.debug("Read error on connection id={}: {}", connId, e.getMessage());
            closeKey(key);
            connectionManager.deregister(connId);
            return;
        }

        if (bytesRead == -1) {
            // Client disconnected
            log.debug("Client disconnected id={}", connId);
            closeKey(key);
            connectionManager.deregister(connId);
            return;
        }

        if (bytesRead == 0) return;

        connectionManager.markActive(connId);
        readBuf.flip();

        ByteBuffer response;
        try {
            response = handler.handle(readBuf);
        } catch (Exception e) {
            log.warn("Handler error for connection id={}", connId, e);
            closeKey(key);
            connectionManager.deregister(connId);
            return;
        }

        if (response != null && response.hasRemaining()) {
            // Attach the response buffer and switch to OP_WRITE
            key.attach(new PendingWrite(connId, response));
            key.interestOps(SelectionKey.OP_WRITE);
        }
    }

    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        PendingWrite pending = (PendingWrite) key.attachment();

        try {
            client.write(pending.buffer);
        } catch (IOException e) {
            log.debug("Write error on connection id={}: {}", pending.connId, e.getMessage());
            closeKey(key);
            connectionManager.deregister(pending.connId);
            return;
        }

        if (!pending.buffer.hasRemaining()) {
            // Done writing — switch back to reading
            key.attach(pending.connId);
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    private void closeKey(SelectionKey key) {
        key.cancel();
        try {
            key.channel().close();
        } catch (IOException e) {
            log.warn("Error closing channel", e);
        }
        Object attachment = key.attachment();
        if (attachment instanceof Long connId) {
            connectionManager.deregister(connId);
        } else if (attachment instanceof PendingWrite pw) {
            connectionManager.deregister(pw.connId);
        }
    }

    // -------------------------------------------------------------------------
    // Internal types
    // -------------------------------------------------------------------------

    private record PendingWrite(long connId, ByteBuffer buffer) {}
}
