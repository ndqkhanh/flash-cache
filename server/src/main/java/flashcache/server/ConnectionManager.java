package flashcache.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages connection lifecycle and idle timeout for NIO connections.
 */
public class ConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);

    private static final int DEFAULT_BUFFER_SIZE = 4096;

    public record ConnectionState(
            long id,
            SocketChannel channel,
            Instant createdAt,
            Instant lastActivityAt,
            ByteBuffer readBuffer,
            ByteBuffer writeBuffer
    ) {
        /** Return a copy of this record with lastActivityAt set to now. */
        ConnectionState withActivity(Instant now) {
            return new ConnectionState(id, channel, createdAt, now, readBuffer, writeBuffer);
        }
    }

    private final ConcurrentHashMap<Long, ConnectionState> connections = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(0);
    private final AtomicLong totalCreatedCounter = new AtomicLong(0);

    /**
     * Register a new channel and return its assigned connection id.
     */
    public long register(SocketChannel channel) {
        long id = idCounter.incrementAndGet();
        Instant now = Instant.now();
        ConnectionState state = new ConnectionState(
                id,
                channel,
                now,
                now,
                ByteBuffer.allocate(DEFAULT_BUFFER_SIZE),
                ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
        );
        connections.put(id, state);
        totalCreatedCounter.incrementAndGet();
        log.debug("Registered connection id={}", id);
        return id;
    }

    /**
     * Deregister and remove a connection by id. Does not close the channel.
     */
    public void deregister(long connectionId) {
        ConnectionState removed = connections.remove(connectionId);
        if (removed != null) {
            log.debug("Deregistered connection id={}", connectionId);
        }
    }

    /**
     * Return the ConnectionState for the given id, or null if not found.
     */
    public ConnectionState getConnection(long connectionId) {
        return connections.get(connectionId);
    }

    /**
     * Update lastActivityAt for the given connection to now.
     */
    public void markActive(long connectionId) {
        connections.computeIfPresent(connectionId, (id, state) -> state.withActivity(Instant.now()));
    }

    /**
     * Close and remove all connections that have been idle longer than the given
     * timeout in milliseconds.
     *
     * @param timeoutMillis idle threshold in milliseconds
     * @return number of connections expired
     */
    public int expireIdle(long timeoutMillis) {
        Instant cutoff = Instant.now().minusMillis(timeoutMillis);
        List<Long> toExpire = new ArrayList<>();
        for (ConnectionState state : connections.values()) {
            if (state.lastActivityAt().isBefore(cutoff)) {
                toExpire.add(state.id());
            }
        }
        int count = 0;
        for (long id : toExpire) {
            ConnectionState state = connections.remove(id);
            if (state != null) {
                try {
                    state.channel().close();
                } catch (IOException e) {
                    log.warn("Error closing idle connection id={}", id, e);
                }
                log.debug("Expired idle connection id={}", id);
                count++;
            }
        }
        return count;
    }

    /**
     * Return the number of currently active (registered) connections.
     */
    public int activeCount() {
        return connections.size();
    }

    /**
     * Return the total number of connections ever registered (including closed ones).
     */
    public long totalCreated() {
        return totalCreatedCounter.get();
    }
}
