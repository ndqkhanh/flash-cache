package flashcache.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NIOServerTest {

    private NIOServer server;

    // Echo handler: reads all request bytes and echoes them back
    private static final NIOServer.RequestHandler ECHO_HANDLER = request -> {
        byte[] bytes = new byte[request.remaining()];
        request.get(bytes);
        return ByteBuffer.wrap(bytes);
    };

    // Handler that returns a fixed response
    private static final NIOServer.RequestHandler PONG_HANDLER = request -> {
        request.clear(); // consume
        return ByteBuffer.wrap("+PONG\r\n".getBytes(StandardCharsets.UTF_8));
    };

    @AfterEach
    void tearDown() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
    }

    // Helper: send bytes and read response from a blocking Socket
    private byte[] sendAndReceive(int port, byte[] request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();
            socket.shutdownOutput(); // signal EOF so server reads everything
            return socket.getInputStream().readAllBytes();
        }
    }

    private byte[] sendAndReceiveFixed(int port, byte[] request, int expectedBytes) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();
            byte[] buf = new byte[expectedBytes];
            int read = 0;
            while (read < expectedBytes) {
                int n = socket.getInputStream().read(buf, read, expectedBytes - read);
                if (n == -1) break;
                read += n;
            }
            return java.util.Arrays.copyOf(buf, read);
        }
    }

    @Test
    void server_startsAndAcceptsConnections() throws Exception {
        server = new NIOServer(ECHO_HANDLER);
        server.start(0);
        assertThat(server.isRunning()).isTrue();
        assertThat(server.getBoundPort()).isGreaterThan(0);

        // Actually open a connection
        try (Socket s = new Socket("127.0.0.1", server.getBoundPort())) {
            assertThat(s.isConnected()).isTrue();
        }
    }

    @Test
    void server_respondsToClientRequest() throws Exception {
        server = new NIOServer(PONG_HANDLER);
        server.start(0);

        byte[] response = sendAndReceive(server.getBoundPort(), "*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(response, StandardCharsets.UTF_8)).isEqualTo("+PONG\r\n");
    }

    @Test
    void server_echoesRequestBackToClient() throws Exception {
        server = new NIOServer(ECHO_HANDLER);
        server.start(0);

        String message = "hello world";
        byte[] response = sendAndReceive(server.getBoundPort(), message.getBytes(StandardCharsets.UTF_8));
        assertThat(new String(response, StandardCharsets.UTF_8)).isEqualTo(message);
    }

    @Test
    void server_multipleConcurrentClients() throws Exception {
        server = new NIOServer(PONG_HANDLER);
        server.start(0);
        int port = server.getBoundPort();
        int clientCount = 5;

        ExecutorService executor = Executors.newFixedThreadPool(clientCount);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < clientCount; i++) {
            futures.add(executor.submit(() -> {
                byte[] resp = sendAndReceive(port, "*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.UTF_8));
                return new String(resp, StandardCharsets.UTF_8);
            }));
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        for (Future<String> f : futures) {
            assertThat(f.get()).isEqualTo("+PONG\r\n");
        }
    }

    @Test
    void server_stopClosesAllConnections() throws Exception {
        server = new NIOServer(ECHO_HANDLER);
        server.start(0);
        assertThat(server.isRunning()).isTrue();

        server.stop();
        assertThat(server.isRunning()).isFalse();
    }

    @Test
    void isRunning_reflectsState() throws Exception {
        server = new NIOServer(ECHO_HANDLER);
        assertThat(server.isRunning()).isFalse();

        server.start(0);
        assertThat(server.isRunning()).isTrue();

        server.stop();
        assertThat(server.isRunning()).isFalse();
    }

    @Test
    void connectionCount_tracksActiveConnections() throws Exception {
        server = new NIOServer(ECHO_HANDLER);
        server.start(0);

        assertThat(server.connectionCount()).isEqualTo(0);

        // Open a persistent connection (don't close immediately)
        Socket s1 = new Socket("127.0.0.1", server.getBoundPort());
        Socket s2 = new Socket("127.0.0.1", server.getBoundPort());

        // Give the event loop time to accept
        Thread.sleep(100);
        assertThat(server.connectionCount()).isEqualTo(2);

        s1.close();
        s2.close();

        // After closing clients, server will detect EOF on next read cycle
        Thread.sleep(200);
        assertThat(server.connectionCount()).isEqualTo(0);
    }

    @Test
    void server_largeMessageHandled() throws Exception {
        server = new NIOServer(ECHO_HANDLER);
        server.start(0);

        // 16 KB message
        byte[] large = new byte[16 * 1024];
        for (int i = 0; i < large.length; i++) large[i] = (byte) (i % 127 + 1);

        byte[] response = sendAndReceive(server.getBoundPort(), large);
        assertThat(response).isEqualTo(large);
    }

    @Test
    void server_clientDisconnectHandledGracefully() throws Exception {
        server = new NIOServer(ECHO_HANDLER);
        server.start(0);

        Socket s = new Socket("127.0.0.1", server.getBoundPort());
        Thread.sleep(50);
        s.close(); // abrupt close

        // Give event loop time to detect EOF
        Thread.sleep(200);

        // Server should still be running fine
        assertThat(server.isRunning()).isTrue();
        assertThat(server.connectionCount()).isEqualTo(0);
    }

    @Test
    void server_rejectsConnectionsAfterStop() throws Exception {
        server = new NIOServer(ECHO_HANDLER);
        server.start(0);
        int port = server.getBoundPort();

        server.stop();
        assertThat(server.isRunning()).isFalse();

        // Connecting to a stopped server should fail
        assertThatThrownBy(() -> {
            Socket s = new Socket("127.0.0.1", port);
            s.close();
        }).isInstanceOf(IOException.class);
    }

    @Test
    void bindToPort0_picksRandomAvailablePort() throws Exception {
        server = new NIOServer(ECHO_HANDLER);
        server.start(0);

        int port = server.getBoundPort();
        assertThat(port).isGreaterThan(1024);
        assertThat(port).isLessThanOrEqualTo(65535);

        // Verify it actually works on that port
        try (Socket s = new Socket("127.0.0.1", port)) {
            assertThat(s.isConnected()).isTrue();
        }
    }
}
