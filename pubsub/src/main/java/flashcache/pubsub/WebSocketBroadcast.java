package flashcache.pubsub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class WebSocketBroadcast {

    private static final Logger log = LoggerFactory.getLogger(WebSocketBroadcast.class);

    public interface WebSocketSender {
        void send(String message);
    }

    // clientId -> sender
    private final ConcurrentHashMap<String, WebSocketSender> clients = new ConcurrentHashMap<>();

    // clientId -> set of channels subscribed
    private final ConcurrentHashMap<String, Set<String>> clientChannels = new ConcurrentHashMap<>();

    private final AtomicLong broadcastCount = new AtomicLong();
    private final PubSubEngine pubSub;

    public WebSocketBroadcast(PubSubEngine pubSub) {
        this.pubSub = pubSub;
    }

    public void registerClient(String clientId, WebSocketSender sender) {
        clients.put(clientId, sender);
        clientChannels.putIfAbsent(clientId, ConcurrentHashMap.newKeySet());
        log.debug("registerClient clientId={}", clientId);
    }

    public void unregisterClient(String clientId) {
        WebSocketSender removed = clients.remove(clientId);
        if (removed != null) {
            Set<String> channels = clientChannels.remove(clientId);
            if (channels != null) {
                for (String channel : channels) {
                    pubSub.unsubscribe(channel, clientId);
                }
            }
        }
        log.debug("unregisterClient clientId={}", clientId);
    }

    public void subscribeClient(String clientId, String channel) {
        if (!clients.containsKey(clientId)) {
            log.warn("subscribeClient: unknown clientId={}", clientId);
            return;
        }
        Set<String> channels = clientChannels.computeIfAbsent(clientId, k -> ConcurrentHashMap.newKeySet());
        if (channels.add(channel)) {
            pubSub.subscribe(channel, new PubSubEngine.Subscriber() {
                @Override
                public String getId() { return clientId; }

                @Override
                public void onMessage(String ch, String message) {
                    WebSocketSender sender = clients.get(clientId);
                    if (sender != null) {
                        sender.send(message);
                        broadcastCount.incrementAndGet();
                        log.debug("broadcast clientId={} channel={}", clientId, ch);
                    }
                }
            });
        }
        log.debug("subscribeClient clientId={} channel={}", clientId, channel);
    }

    public void unsubscribeClient(String clientId, String channel) {
        Set<String> channels = clientChannels.get(clientId);
        if (channels != null) {
            channels.remove(channel);
        }
        pubSub.unsubscribe(channel, clientId);
        log.debug("unsubscribeClient clientId={} channel={}", clientId, channel);
    }

    public int getClientCount() {
        return clients.size();
    }

    public long broadcastCount() {
        return broadcastCount.get();
    }
}
