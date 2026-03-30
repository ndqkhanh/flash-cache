package flashcache.pubsub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public class KeyspaceNotify {

    private static final Logger log = LoggerFactory.getLogger(KeyspaceNotify.class);

    public enum EventType { SET, DEL, EXPIRE, EVICT }

    private final PubSubEngine pubSub;
    private final Set<EventType> enabled = Collections.synchronizedSet(EnumSet.noneOf(EventType.class));
    private final AtomicLong totalPublished = new AtomicLong();

    public KeyspaceNotify(PubSubEngine pubSub) {
        this.pubSub = pubSub;
    }

    public void enable(EventType... types) {
        for (EventType t : types) enabled.add(t);
    }

    public void disable(EventType... types) {
        for (EventType t : types) enabled.remove(t);
    }

    public void onSet(String key, String value) {
        if (!enabled.contains(EventType.SET)) return;
        publishEvent(key, "set");
    }

    public void onDel(String key) {
        if (!enabled.contains(EventType.DEL)) return;
        publishEvent(key, "del");
    }

    public void onExpire(String key) {
        if (!enabled.contains(EventType.EXPIRE)) return;
        publishEvent(key, "expire");
    }

    public void onEvict(String key) {
        if (!enabled.contains(EventType.EVICT)) return;
        publishEvent(key, "evict");
    }

    private void publishEvent(String key, String eventName) {
        String keyeventChannel = "__keyevent__:" + eventName;
        String keyspaceChannel = "__keyspace__:" + key + ":" + eventName;
        pubSub.publish(keyeventChannel, key);
        pubSub.publish(keyspaceChannel, eventName);
        totalPublished.addAndGet(2);
        log.debug("keyspace event={} key={}", eventName, key);
    }

    public long totalEventsPublished() {
        return totalPublished.get();
    }
}
