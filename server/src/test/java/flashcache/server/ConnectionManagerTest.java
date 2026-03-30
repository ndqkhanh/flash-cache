package flashcache.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionManagerTest {

    private ConnectionManager manager;

    @BeforeEach
    void setUp() {
        manager = new ConnectionManager();
    }

    // Helper: open a real SocketChannel pair using a loopback server
    private SocketChannel openChannel() throws IOException {
        try (ServerSocketChannel ssc = ServerSocketChannel.open()) {
            ssc.bind(new InetSocketAddress("127.0.0.1", 0));
            SocketChannel client = SocketChannel.open(ssc.getLocalAddress());
            // accept server side (discarded — we only need a valid channel object)
            SocketChannel server = ssc.accept();
            server.close();
            return client;
        }
    }

    @Test
    void register_createsConnectionState() throws IOException {
        SocketChannel ch = openChannel();
        long id = manager.register(ch);

        ConnectionManager.ConnectionState state = manager.getConnection(id);
        assertThat(state).isNotNull();
        assertThat(state.id()).isEqualTo(id);
        assertThat(state.channel()).isSameAs(ch);
        assertThat(state.createdAt()).isNotNull();
        assertThat(state.lastActivityAt()).isNotNull();
        assertThat(state.readBuffer()).isNotNull();
        assertThat(state.writeBuffer()).isNotNull();
        ch.close();
    }

    @Test
    void deregister_removesConnection() throws IOException {
        SocketChannel ch = openChannel();
        long id = manager.register(ch);

        manager.deregister(id);

        assertThat(manager.getConnection(id)).isNull();
        ch.close();
    }

    @Test
    void markActive_updatesLastActivityAt() throws IOException, InterruptedException {
        SocketChannel ch = openChannel();
        long id = manager.register(ch);

        java.time.Instant before = manager.getConnection(id).lastActivityAt();
        Thread.sleep(5); // ensure measurable time passes
        manager.markActive(id);
        java.time.Instant after = manager.getConnection(id).lastActivityAt();

        assertThat(after).isAfterOrEqualTo(before);
        ch.close();
    }

    @Test
    void expireIdle_closesIdleConnections() throws IOException, InterruptedException {
        SocketChannel ch = openChannel();
        long id = manager.register(ch);

        Thread.sleep(50); // let it go idle
        int expired = manager.expireIdle(10); // 10ms timeout

        assertThat(expired).isEqualTo(1);
        assertThat(manager.getConnection(id)).isNull();
    }

    @Test
    void expireIdle_keepsActiveConnections() throws IOException, InterruptedException {
        SocketChannel ch = openChannel();
        long id = manager.register(ch);

        Thread.sleep(50);
        manager.markActive(id); // reset activity
        int expired = manager.expireIdle(200); // 200ms — connection just became active

        assertThat(expired).isEqualTo(0);
        assertThat(manager.getConnection(id)).isNotNull();
        ch.close();
    }

    @Test
    void activeCount_tracksCorrectly() throws IOException {
        SocketChannel ch1 = openChannel();
        SocketChannel ch2 = openChannel();

        assertThat(manager.activeCount()).isEqualTo(0);
        long id1 = manager.register(ch1);
        assertThat(manager.activeCount()).isEqualTo(1);
        long id2 = manager.register(ch2);
        assertThat(manager.activeCount()).isEqualTo(2);
        manager.deregister(id1);
        assertThat(manager.activeCount()).isEqualTo(1);
        manager.deregister(id2);
        assertThat(manager.activeCount()).isEqualTo(0);

        ch1.close();
        ch2.close();
    }

    @Test
    void getConnection_returnsNullForUnknownId() {
        assertThat(manager.getConnection(99999L)).isNull();
    }

    @Test
    void totalCreated_countsAllEverCreated() throws IOException {
        SocketChannel ch1 = openChannel();
        SocketChannel ch2 = openChannel();
        SocketChannel ch3 = openChannel();

        long id1 = manager.register(ch1);
        long id2 = manager.register(ch2);
        long id3 = manager.register(ch3);

        manager.deregister(id1);
        manager.deregister(id2);

        // Even after deregistering 2, totalCreated is still 3
        assertThat(manager.totalCreated()).isEqualTo(3);
        assertThat(manager.activeCount()).isEqualTo(1);

        ch1.close();
        ch2.close();
        ch3.close();
    }

    @Test
    void deregister_unknownId_doesNotThrow() {
        // Should silently do nothing
        manager.deregister(12345L);
        assertThat(manager.activeCount()).isEqualTo(0);
    }
}
