package flashcache.pubsub;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PubSubEngineTest {

    private PubSubEngine engine;

    static PubSubEngine.Subscriber subscriber(String id, List<String> received) {
        return new PubSubEngine.Subscriber() {
            @Override public String getId() { return id; }
            @Override public void onMessage(String channel, String message) { received.add(message); }
        };
    }

    @BeforeEach
    void setUp() {
        engine = new PubSubEngine();
    }

    @Test
    void subscribe_andPublish_deliversMessage() {
        List<String> received = new ArrayList<>();
        engine.subscribe("chat", subscriber("s1", received));
        int count = engine.publish("chat", "hello");
        assertThat(count).isEqualTo(1);
        assertThat(received).containsExactly("hello");
    }

    @Test
    void multipleSubscribers_onSameChannel_allReceive() {
        List<String> r1 = new ArrayList<>(), r2 = new ArrayList<>();
        engine.subscribe("chat", subscriber("s1", r1));
        engine.subscribe("chat", subscriber("s2", r2));
        int count = engine.publish("chat", "hi");
        assertThat(count).isEqualTo(2);
        assertThat(r1).containsExactly("hi");
        assertThat(r2).containsExactly("hi");
    }

    @Test
    void unsubscribe_stopsDelivery() {
        List<String> received = new ArrayList<>();
        engine.subscribe("chat", subscriber("s1", received));
        engine.unsubscribe("chat", "s1");
        int count = engine.publish("chat", "bye");
        assertThat(count).isEqualTo(0);
        assertThat(received).isEmpty();
    }

    @Test
    void publish_toChannelWithNoSubscribers_returnsZero() {
        int count = engine.publish("empty", "msg");
        assertThat(count).isEqualTo(0);
    }

    @Test
    void getSubscriberCount_reflectsActiveSubscribers() {
        engine.subscribe("ch", subscriber("s1", new ArrayList<>()));
        engine.subscribe("ch", subscriber("s2", new ArrayList<>()));
        assertThat(engine.getSubscriberCount("ch")).isEqualTo(2);
        engine.unsubscribe("ch", "s1");
        assertThat(engine.getSubscriberCount("ch")).isEqualTo(1);
    }

    @Test
    void getChannels_returnsChannelsWithActiveSubscribers() {
        engine.subscribe("news", subscriber("s1", new ArrayList<>()));
        engine.subscribe("sports", subscriber("s2", new ArrayList<>()));
        assertThat(engine.getChannels()).contains("news", "sports");
    }

    @Test
    void getChannels_excludesEmptyChannels() {
        engine.subscribe("temp", subscriber("s1", new ArrayList<>()));
        engine.unsubscribe("temp", "s1");
        assertThat(engine.getChannels()).doesNotContain("temp");
    }

    @Test
    void psubscribe_matchesGlobPattern() {
        List<String> received = new ArrayList<>();
        engine.psubscribe("news.*", subscriber("ps1", received));
        engine.publish("news.sports", "goal!");
        assertThat(received).containsExactly("goal!");
    }

    @Test
    void psubscribe_doesNotMatchNonMatchingChannel() {
        List<String> received = new ArrayList<>();
        engine.psubscribe("news.*", subscriber("ps1", received));
        engine.publish("weather.today", "sunny");
        assertThat(received).isEmpty();
    }

    @Test
    void punsubscribe_stopsPatternDelivery() {
        List<String> received = new ArrayList<>();
        engine.psubscribe("news.*", subscriber("ps1", received));
        engine.punsubscribe("news.*", "ps1");
        engine.publish("news.sports", "score");
        assertThat(received).isEmpty();
    }

    @Test
    void publish_reachesPatternSubscribers() {
        List<String> direct = new ArrayList<>(), pattern = new ArrayList<>();
        engine.subscribe("user.login", subscriber("d1", direct));
        engine.psubscribe("user.*", subscriber("p1", pattern));
        engine.publish("user.login", "alice");
        assertThat(direct).containsExactly("alice");
        assertThat(pattern).containsExactly("alice");
    }

    @Test
    void totalSubscriptions_tracksAllSubscriptions() {
        engine.subscribe("ch1", subscriber("s1", new ArrayList<>()));
        engine.subscribe("ch2", subscriber("s2", new ArrayList<>()));
        engine.psubscribe("ch.*", subscriber("p1", new ArrayList<>()));
        assertThat(engine.totalSubscriptions()).isEqualTo(3);
    }

    @Test
    void concurrentSubscribePublish_isThreadSafe() throws InterruptedException {
        int threads = 20;
        AtomicInteger totalReceived = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(threads);
        ExecutorService exec = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            exec.submit(() -> {
                String channel = "ch-" + tid;
                List<String> received = new ArrayList<>();
                engine.subscribe(channel, subscriber("s-" + tid, received));
                engine.publish(channel, "msg-" + tid);
                totalReceived.addAndGet(received.size());
                latch.countDown();
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        exec.shutdown();
        assertThat(totalReceived.get()).isEqualTo(threads);
    }

    @Test
    void matchesPattern_exactMatch() {
        assertThat(PubSubEngine.matchesPattern("news", "news")).isTrue();
        assertThat(PubSubEngine.matchesPattern("news", "news.sports")).isFalse();
    }

    @Test
    void matchesPattern_prefixWildcard() {
        assertThat(PubSubEngine.matchesPattern("news.*", "news.sports")).isTrue();
        assertThat(PubSubEngine.matchesPattern("news.*", "news.sports.live")).isTrue();
        assertThat(PubSubEngine.matchesPattern("news.*", "other.sports")).isFalse();
    }

    @Test
    void matchesPattern_suffixWildcard() {
        assertThat(PubSubEngine.matchesPattern("*.sports", "news.sports")).isTrue();
        assertThat(PubSubEngine.matchesPattern("*.sports", "news.football")).isFalse();
    }
}
