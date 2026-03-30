package flashcache.pubsub;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketBroadcastTest {

    private PubSubEngine pubSub;
    private WebSocketBroadcast broadcast;

    static WebSocketBroadcast.WebSocketSender wsSender(List<String> received) {
        return message -> received.add(message);
    }

    @BeforeEach
    void setUp() {
        pubSub = new PubSubEngine();
        broadcast = new WebSocketBroadcast(pubSub);
    }

    @Test
    void registeredClient_receivesMessages_fromSubscribedChannel() {
        List<String> received = new ArrayList<>();
        broadcast.registerClient("c1", wsSender(received));
        broadcast.subscribeClient("c1", "updates");
        pubSub.publish("updates", "hello");
        assertThat(received).containsExactly("hello");
    }

    @Test
    void unregisteredClient_stopsReceiving() {
        List<String> received = new ArrayList<>();
        broadcast.registerClient("c1", wsSender(received));
        broadcast.subscribeClient("c1", "updates");
        broadcast.unregisterClient("c1");
        pubSub.publish("updates", "after-unregister");
        assertThat(received).isEmpty();
    }

    @Test
    void subscribeClient_routesChannelMessages() {
        List<String> received = new ArrayList<>();
        broadcast.registerClient("c1", wsSender(received));
        broadcast.subscribeClient("c1", "prices");
        pubSub.publish("prices", "100");
        pubSub.publish("prices", "200");
        assertThat(received).containsExactly("100", "200");
    }

    @Test
    void unsubscribeClient_stopsRouting() {
        List<String> received = new ArrayList<>();
        broadcast.registerClient("c1", wsSender(received));
        broadcast.subscribeClient("c1", "prices");
        broadcast.unsubscribeClient("c1", "prices");
        pubSub.publish("prices", "after-unsub");
        assertThat(received).isEmpty();
    }

    @Test
    void multipleClients_onSameChannel_allReceive() {
        List<String> r1 = new ArrayList<>(), r2 = new ArrayList<>(), r3 = new ArrayList<>();
        broadcast.registerClient("c1", wsSender(r1));
        broadcast.registerClient("c2", wsSender(r2));
        broadcast.registerClient("c3", wsSender(r3));
        broadcast.subscribeClient("c1", "live");
        broadcast.subscribeClient("c2", "live");
        broadcast.subscribeClient("c3", "live");
        pubSub.publish("live", "event");
        assertThat(r1).containsExactly("event");
        assertThat(r2).containsExactly("event");
        assertThat(r3).containsExactly("event");
    }

    @Test
    void client_subscribedToMultipleChannels_receivesFromAll() {
        List<String> received = new ArrayList<>();
        broadcast.registerClient("c1", wsSender(received));
        broadcast.subscribeClient("c1", "news");
        broadcast.subscribeClient("c1", "sports");
        pubSub.publish("news", "headline");
        pubSub.publish("sports", "score");
        assertThat(received).containsExactlyInAnyOrder("headline", "score");
    }

    @Test
    void publishToNonSubscribedChannel_doesNotReachClient() {
        List<String> received = new ArrayList<>();
        broadcast.registerClient("c1", wsSender(received));
        broadcast.subscribeClient("c1", "news");
        pubSub.publish("weather", "sunny");
        assertThat(received).isEmpty();
    }

    @Test
    void getClientCount_tracksRegisteredClients() {
        assertThat(broadcast.getClientCount()).isEqualTo(0);
        broadcast.registerClient("c1", wsSender(new ArrayList<>()));
        broadcast.registerClient("c2", wsSender(new ArrayList<>()));
        assertThat(broadcast.getClientCount()).isEqualTo(2);
        broadcast.unregisterClient("c1");
        assertThat(broadcast.getClientCount()).isEqualTo(1);
    }

    @Test
    void broadcastCount_tracksTotalPushes() {
        List<String> received = new ArrayList<>();
        broadcast.registerClient("c1", wsSender(received));
        broadcast.subscribeClient("c1", "ch");
        pubSub.publish("ch", "m1");
        pubSub.publish("ch", "m2");
        pubSub.publish("ch", "m3");
        assertThat(broadcast.broadcastCount()).isEqualTo(3);
    }

    @Test
    void concurrentRegisterPublish_isThreadSafe() throws InterruptedException {
        int threads = 20;
        AtomicInteger totalReceived = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(threads);
        ExecutorService exec = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            exec.submit(() -> {
                String clientId = "client-" + tid;
                String channel = "channel-" + tid;
                List<String> received = new ArrayList<>();
                broadcast.registerClient(clientId, wsSender(received));
                broadcast.subscribeClient(clientId, channel);
                pubSub.publish(channel, "msg-" + tid);
                totalReceived.addAndGet(received.size());
                latch.countDown();
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        exec.shutdown();
        assertThat(totalReceived.get()).isEqualTo(threads);
    }

    @Test
    void subscribeClient_unknownClient_isNoOp() {
        // Should not throw
        broadcast.subscribeClient("unknown", "ch");
        assertThat(broadcast.getClientCount()).isEqualTo(0);
    }

    @Test
    void broadcastCount_multipleClientsOnChannel_countsEachDelivery() {
        List<String> r1 = new ArrayList<>(), r2 = new ArrayList<>();
        broadcast.registerClient("c1", wsSender(r1));
        broadcast.registerClient("c2", wsSender(r2));
        broadcast.subscribeClient("c1", "ch");
        broadcast.subscribeClient("c2", "ch");
        pubSub.publish("ch", "msg");
        assertThat(broadcast.broadcastCount()).isEqualTo(2);
    }
}
