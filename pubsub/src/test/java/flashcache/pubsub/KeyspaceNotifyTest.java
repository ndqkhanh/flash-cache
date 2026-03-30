package flashcache.pubsub;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KeyspaceNotifyTest {

    private PubSubEngine pubSub;
    private KeyspaceNotify notify;

    static PubSubEngine.Subscriber subscriber(String id, List<String> received) {
        return new PubSubEngine.Subscriber() {
            @Override public String getId() { return id; }
            @Override public void onMessage(String channel, String message) { received.add(message); }
        };
    }

    @BeforeEach
    void setUp() {
        pubSub = new PubSubEngine();
        notify = new KeyspaceNotify(pubSub);
        notify.enable(KeyspaceNotify.EventType.values());
    }

    @Test
    void onSet_publishesToKeyeventChannel() {
        List<String> received = new ArrayList<>();
        pubSub.subscribe("__keyevent__:set", subscriber("s1", received));
        notify.onSet("mykey", "myvalue");
        assertThat(received).containsExactly("mykey");
    }

    @Test
    void onSet_publishesToKeyspaceChannel() {
        List<String> received = new ArrayList<>();
        pubSub.subscribe("__keyspace__:mykey:set", subscriber("s1", received));
        notify.onSet("mykey", "myvalue");
        assertThat(received).containsExactly("set");
    }

    @Test
    void onDel_publishesDeletionEvent() {
        List<String> keyevent = new ArrayList<>(), keyspace = new ArrayList<>();
        pubSub.subscribe("__keyevent__:del", subscriber("s1", keyevent));
        pubSub.subscribe("__keyspace__:delkey:del", subscriber("s2", keyspace));
        notify.onDel("delkey");
        assertThat(keyevent).containsExactly("delkey");
        assertThat(keyspace).containsExactly("del");
    }

    @Test
    void onExpire_publishesExpirationEvent() {
        List<String> received = new ArrayList<>();
        pubSub.subscribe("__keyevent__:expire", subscriber("s1", received));
        notify.onExpire("expkey");
        assertThat(received).containsExactly("expkey");
    }

    @Test
    void onEvict_publishesEvictionEvent() {
        List<String> received = new ArrayList<>();
        pubSub.subscribe("__keyevent__:evict", subscriber("s1", received));
        notify.onEvict("evictkey");
        assertThat(received).containsExactly("evictkey");
    }

    @Test
    void subscriber_receivesSetNotification_viaKeyeventChannel() {
        List<String> received = new ArrayList<>();
        pubSub.subscribe("__keyevent__:set", subscriber("listener", received));
        notify.onSet("k1", "v1");
        notify.onSet("k2", "v2");
        assertThat(received).containsExactly("k1", "k2");
    }

    @Test
    void subscriber_receivesPerKeyNotification_viaKeyspace() {
        List<String> received = new ArrayList<>();
        pubSub.subscribe("__keyspace__:user:set", subscriber("listener", received));
        notify.onSet("user", "alice");
        notify.onSet("other", "bob"); // should not arrive
        assertThat(received).containsExactly("set");
    }

    @Test
    void disabledEventType_doesNotPublish() {
        notify.disable(KeyspaceNotify.EventType.SET);
        List<String> received = new ArrayList<>();
        pubSub.subscribe("__keyevent__:set", subscriber("s1", received));
        notify.onSet("k1", "v1");
        assertThat(received).isEmpty();
    }

    @Test
    void enable_disable_controlsWhichEventsFire() {
        notify.disable(KeyspaceNotify.EventType.values());
        List<String> setReceived = new ArrayList<>(), delReceived = new ArrayList<>();
        pubSub.subscribe("__keyevent__:set", subscriber("s1", setReceived));
        pubSub.subscribe("__keyevent__:del", subscriber("s2", delReceived));

        notify.onSet("k1", "v1");
        notify.onDel("k1");
        assertThat(setReceived).isEmpty();
        assertThat(delReceived).isEmpty();

        notify.enable(KeyspaceNotify.EventType.SET);
        notify.onSet("k1", "v1");
        notify.onDel("k1");
        assertThat(setReceived).containsExactly("k1");
        assertThat(delReceived).isEmpty();
    }

    @Test
    void totalEventsPublished_countsCorrectly() {
        notify.onSet("k1", "v1");  // 2 publishes
        notify.onDel("k2");        // 2 publishes
        assertThat(notify.totalEventsPublished()).isEqualTo(4);
    }

    @Test
    void totalEventsPublished_disabledEventNotCounted() {
        notify.disable(KeyspaceNotify.EventType.SET);
        notify.onSet("k1", "v1");
        assertThat(notify.totalEventsPublished()).isEqualTo(0);
    }

    @Test
    void multipleSubscribers_onKeyeventChannel_allReceive() {
        List<String> r1 = new ArrayList<>(), r2 = new ArrayList<>(), r3 = new ArrayList<>();
        pubSub.subscribe("__keyevent__:set", subscriber("s1", r1));
        pubSub.subscribe("__keyevent__:set", subscriber("s2", r2));
        pubSub.subscribe("__keyevent__:set", subscriber("s3", r3));
        notify.onSet("k1", "v1");
        assertThat(r1).containsExactly("k1");
        assertThat(r2).containsExactly("k1");
        assertThat(r3).containsExactly("k1");
    }
}
