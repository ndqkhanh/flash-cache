package flashcache.pubsub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class PubSubEngine {

    private static final Logger log = LoggerFactory.getLogger(PubSubEngine.class);

    public interface Subscriber {
        String getId();
        void onMessage(String channel, String message);
    }

    private record PatternEntry(String pattern, Subscriber subscriber) {}

    // channel -> list of subscribers
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Subscriber>> channelSubs =
            new ConcurrentHashMap<>();

    // pattern subscriptions
    private final CopyOnWriteArrayList<PatternEntry> patternSubs = new CopyOnWriteArrayList<>();

    public void subscribe(String channel, Subscriber subscriber) {
        channelSubs.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>()).add(subscriber);
        log.debug("subscribe channel={} id={}", channel, subscriber.getId());
    }

    public void unsubscribe(String channel, String subscriberId) {
        CopyOnWriteArrayList<Subscriber> subs = channelSubs.get(channel);
        if (subs != null) {
            subs.removeIf(s -> s.getId().equals(subscriberId));
            if (subs.isEmpty()) {
                channelSubs.remove(channel, subs);
            }
        }
        log.debug("unsubscribe channel={} id={}", channel, subscriberId);
    }

    public int publish(String channel, String message) {
        int count = 0;

        // direct channel subscribers
        CopyOnWriteArrayList<Subscriber> subs = channelSubs.get(channel);
        if (subs != null) {
            for (Subscriber s : subs) {
                try {
                    s.onMessage(channel, message);
                    count++;
                } catch (Exception e) {
                    log.warn("subscriber {} threw on channel {}", s.getId(), channel, e);
                }
            }
        }

        // pattern subscribers
        for (PatternEntry entry : patternSubs) {
            if (matchesPattern(entry.pattern(), channel)) {
                try {
                    entry.subscriber().onMessage(channel, message);
                    count++;
                } catch (Exception e) {
                    log.warn("pattern subscriber {} threw on channel {}", entry.subscriber().getId(), channel, e);
                }
            }
        }

        log.debug("publish channel={} receivers={}", channel, count);
        return count;
    }

    public int getSubscriberCount(String channel) {
        CopyOnWriteArrayList<Subscriber> subs = channelSubs.get(channel);
        return subs == null ? 0 : subs.size();
    }

    public Set<String> getChannels() {
        return channelSubs.keySet();
    }

    public void psubscribe(String pattern, Subscriber subscriber) {
        patternSubs.add(new PatternEntry(pattern, subscriber));
        log.debug("psubscribe pattern={} id={}", pattern, subscriber.getId());
    }

    public void punsubscribe(String pattern, String subscriberId) {
        patternSubs.removeIf(e -> e.pattern().equals(pattern) && e.subscriber().getId().equals(subscriberId));
        log.debug("punsubscribe pattern={} id={}", pattern, subscriberId);
    }

    public int totalSubscriptions() {
        int direct = channelSubs.values().stream().mapToInt(List::size).sum();
        return direct + patternSubs.size();
    }

    /**
     * Glob pattern matching supporting '*' as a wildcard segment.
     * e.g. "news.*" matches "news.sports" but not "news.sports.live"
     */
    static boolean matchesPattern(String pattern, String channel) {
        if (!pattern.contains("*")) {
            return pattern.equals(channel);
        }
        // Split on '*' and match prefix/suffix/segments
        String[] parts = pattern.split("\\*", -1);
        if (parts.length == 2) {
            String prefix = parts[0];
            String suffix = parts[1];
            if (!channel.startsWith(prefix)) return false;
            if (!channel.endsWith(suffix)) return false;
            // ensure there is something (or nothing) between prefix and suffix without extra dots
            int remaining = channel.length() - prefix.length() - suffix.length();
            return remaining >= 0;
        }
        // General multi-wildcard: fall back to recursive match
        return matchGlob(pattern, 0, channel, 0);
    }

    private static boolean matchGlob(String pattern, int pi, String text, int ti) {
        while (pi < pattern.length() && ti < text.length()) {
            char pc = pattern.charAt(pi);
            if (pc == '*') {
                // consume consecutive stars
                while (pi < pattern.length() && pattern.charAt(pi) == '*') pi++;
                if (pi == pattern.length()) return true;
                for (int i = ti; i <= text.length(); i++) {
                    if (matchGlob(pattern, pi, text, i)) return true;
                }
                return false;
            } else {
                if (pc != text.charAt(ti)) return false;
                pi++;
                ti++;
            }
        }
        while (pi < pattern.length() && pattern.charAt(pi) == '*') pi++;
        return pi == pattern.length() && ti == text.length();
    }
}
