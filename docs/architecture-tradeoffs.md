# FlashCache — Architecture Trade-offs

Every architectural decision involves a trade-off. This document explains the six most consequential design choices in FlashCache, what was sacrificed in each case, and when a different approach would be better.

---

## 1. Exact LRU via Doubly-Linked List (vs Redis Approximate LRU)

**What was chosen.** `LRUEviction` uses an intrusive doubly-linked list combined with a `ConcurrentHashMap` to implement exact LRU ordering. Every cache key maps to a `Node` in the linked list ordered by recency of access. A `get` acquires a `ReentrantLock` and calls `moveToHead()` to unlink and relink the accessed node at the head — O(1). A cache miss triggers eviction of the tail node via `evict()`, which removes both the tail from the list and its entry from the `ConcurrentHashMap` — also O(1). The hash map handles key-to-node resolution (concurrent, lock-free reads); the linked list handles ordering (serialized via a single lock).

**What's sacrificed.**

- `ReentrantLock` on every list mutation adds ~100ns contention under write-heavy workloads. Every `get` and `put` must acquire the lock for `moveToHead()`, even when the logical operation is a read. Reads are lock-free for the hash map lookup but serialized for the recency update.
- 48 bytes per-entry overhead for prev and next pointers per linked-list node, on top of the `ConcurrentHashMap` entry (~32 bytes). For a cache with 1M entries, this is ~48 MB of overhead purely for eviction bookkeeping.
- Not cache-line friendly — traversing the linked list chases pointers scattered across the heap, defeating CPU prefetching. Each `moveToHead()` touches three nodes (the moved node, its predecessor, and the current head), unlikely to be on the same cache line.
- Under read-heavy workloads with high concurrency, the single `ReentrantLock` becomes the serialization bottleneck. Even though the critical section is ~50ns, the lock acquisition itself involves a CAS on the lock state plus potential `LockSupport.park()` if contended.
- No admission policy — every key that is `put` is admitted unconditionally. A one-hit-wonder key can evict a frequently accessed key, reducing hit rate for workloads with scan pollution.

**When the alternative wins.**

- **Redis's sampling approach (pick N random keys, evict the oldest):** Zero per-entry overhead and no lock. With `maxmemory-samples=10`, Redis reports approximation within 2% of true LRU. Better when eviction accuracy is less important than raw throughput.
- **Segmented LRU or W-TinyLFU (Caffeine):** Frequency-aware policies achieve 5-10% higher hit rates for Zipfian access patterns without per-access locking. Better when hit rate matters more than eviction fairness.
- **Clock/CLOCK-Pro approximation:** A circular buffer with a clock hand approximates LRU with no pointer overhead and no lock. Better for very large caches (millions of entries) where 48 bytes per node is prohibitive.

**Engineering judgment.** Right for FlashCache because exact eviction is a demonstrable differentiator over Redis's approximate approach, and the lock hold time (sub-microsecond for `moveToHead`) is negligible at 200K ops/sec. At measured throughput, the lock is held for ~50ns with ~5000ns inter-arrival time — contention probability is approximately 1%. Exact LRU also makes cache behavior deterministic and testable, which matters for a project demonstrating algorithmic correctness over statistical approximation.

---

## 2. SWIM Gossip over Centralized Coordinator (vs Redis Cluster Bus)

**What was chosen.** `ClusterGossip` implements the SWIM (Scalable Weakly-consistent Infection-style Process Group Membership) protocol. Each node maintains a membership list with three states per peer — ALIVE, SUSPECT, DEAD — and uses incarnation-number-based conflict resolution. When a node receives a SUSPECT gossip about itself, it increments its own incarnation number and broadcasts ALIVE with the higher incarnation, overriding the suspicion without any central authority. Gossip messages are disseminated via random fan-out — O(log N) rounds to full convergence, with O(1) per-member bandwidth.

**What's sacrificed.**

- Eventual consistency of membership — O(log N) gossip rounds to converge means a node failure at time T is not known to all members until T + (log N x gossip interval). For a 10-node cluster with 1-second intervals, this is ~3 seconds during which `ShardRouter` may still route to the failed node.
- False suspicions under network jitter — a healthy node behind a slow link can be marked SUSPECT, triggering unnecessary incarnation increments and gossip traffic.
- No authoritative membership view — every node has a slightly different view of the cluster at any moment. Two concurrent `ShardRouter` lookups on different nodes for the same key may route to different physical nodes during a membership transition.
- No atomic membership changes — adding multiple nodes simultaneously produces intermediate states where members disagree on ring topology, creating transient routing inconsistencies.
- Indirect probe overhead — when a node is suspected, SWIM requires K random peers to perform indirect probes, adding one round-trip of latency to failure detection.

**When the alternative wins.**

- **Centralized coordinator (ZooKeeper, etcd, Consul):** Linearizable membership, immediate failure detection via session expiry, single authoritative source of truth. Better for small clusters (<10 nodes) where the coordinator is not a bottleneck and strong membership consistency is required.
- **Redis Cluster's PFAIL/FAIL quorum:** Requires majority agreement before declaring a node failed, reducing false-positive evictions at the cost of slower detection (minimum 2 x cluster-node-timeout). Better when false evictions are more expensive than detection latency.

**Engineering judgment.** Right because SWIM scales to hundreds of nodes with O(1) per-member bandwidth — each gossip round sends a fixed number of messages regardless of cluster size. Incarnation numbers provide sufficient consistency for cache routing: a stale route causes a cache miss (the key is fetched from the correct node on retry), not data loss. The protocol is self-healing — partitioned nodes rejoin via fresh ALIVE gossip once connectivity is restored, with no manual intervention. For a cache where data is inherently ephemeral, eventual membership consistency is the right trade-off.

---

## 3. SHA-256 Consistent Hashing with 150 Virtual Nodes (vs CRC16 mod 16384)

**What was chosen.** `ConsistentHashRing` maps cluster nodes to positions on a hash ring using SHA-256 hashes. Each physical node is represented by 150 virtual nodes (vnodes), stored in a `ConcurrentSkipListMap<Long, String>` keyed by the first 8 bytes of the SHA-256 digest. `ShardRouter` resolves a cache key to its owning node by computing SHA-256(key) and performing a `ceilingEntry()` lookup — O(log N) where N is total vnode count. Adding or removing a node remaps only the keys in the affected arc, and with 150 vnodes per physical node, key distribution standard deviation stays below 5%.

**What's sacrificed.**

- SHA-256 is ~10x slower than CRC16 per hash computation (~200ns vs ~20ns). At 200K ops/sec, this adds ~40ms of total CPU time per second.
- `ConcurrentSkipListMap` uses more memory than a fixed-size array — each skip list node has O(log N) forward pointers on average. For 150 vnodes x 10 nodes = 1,500 entries, overhead is ~100 KB.
- 150 vnodes per physical node means a 100-node cluster has 15,000 skip list entries, making `ceilingEntry()` ~14 comparisons per lookup.
- Ring rebalancing requires inserting or removing 150 entries from the skip list. While each `put`/`remove` is thread-safe, the batch is not atomic — concurrent lookups during rebalancing may see partial node addition.
- SHA-256 is cryptographic-grade — its collision resistance is unnecessary for ring placement. A non-cryptographic hash (MurmurHash3, xxHash) would provide comparable distribution at ~50x less CPU cost.

**When the alternative wins.**

- **Redis's CRC16 mod 16384 with fixed slot array:** ~10x faster hash, O(1) slot lookup via array index, 16384-slot granularity sufficient for clusters up to ~1000 nodes. Better when node count is stable and the fixed-slot model fits the operational model.
- **Jump consistent hash (Lamping & Veach, 2014):** O(1) memory, O(log N) computation, provably uniform distribution. No ring, no virtual nodes, no data structure. Better when nodes are numbered sequentially and arbitrary node naming is unnecessary.
- **Rendezvous (highest random weight) hashing:** O(N) per lookup but trivially parallelizable, and adding a node remaps only 1/N of keys. Better when N is small (<20) and simplicity is prioritized.

**Engineering judgment.** SHA-256 provides better distribution uniformity than CRC16 for arbitrary node names — the avalanche property ensures that similarly named nodes ("cache-1", "cache-2") are placed uniformly on the ring. 150 vnodes achieves <5% standard deviation, verified by `ConsistentHashRingTest`. `ConcurrentSkipListMap` allows lock-free reads — `ceilingEntry()` is thread-safe without external synchronization — keeping ring lookup off the LRU eviction lock path. The ~10x hash cost is irrelevant in practice: SHA-256 is ~200ns while network round-trip is ~100,000ns — the hash is 0.2% of total operation latency.

---

## 4. Single NIO Selector on Virtual Thread (vs Netty/Thread Pool)

**What was chosen.** `NIOServer` runs a single `java.nio.Selector` event loop on a Java 21 virtual thread. One thread multiplexes thousands of connections via `SelectionKey` registration — `OP_ACCEPT` for new connections, `OP_READ` for incoming data, `OP_WRITE` for back-pressure. The virtual thread blocks in `select()` without consuming a platform thread; the JVM's continuation scheduler parks and resumes it at zero OS-thread overhead. `PipelineHandler` chains protocol handlers (RESP, HTTP/1.1, HTTP/2, WebSocket) per connection.

**What's sacrificed.**

- Single selector is CPU-bound — can only utilize one core for I/O dispatch. On a 12-core machine, 11 cores sit idle from the selector's perspective.
- No automatic work distribution across cores — if protocol parsing becomes CPU-intensive (HTTP/2 HPACK decoding, TLS handshakes), the single selector thread becomes the throughput ceiling.
- Complex non-blocking code — every I/O operation requires continuation-passing style around `SelectionKey` readiness events. A simple "read a RESP command" becomes a multi-state affair: read into buffer, check completeness, register `OP_READ` and return if incomplete, resume on next `select()` cycle.
- No built-in back-pressure propagation — slow clients filling write buffers require manual `OP_WRITE` toggling and per-connection write queue depth tracking.
- Single point of failure — an uncaught exception or infinite loop in any handler blocks all connections, not just the affected one.

**When the alternative wins.**

- **Netty's EventLoopGroup (multiple selectors, one per core):** Distributes connections across N threads via round-robin. Maximizes I/O parallelism for CPU-intensive protocol parsing. Better when the protocol layer consumes significant CPU.
- **Thread-per-connection with virtual threads:** One virtual thread per connection with blocking `InputStream.read()` is dramatically simpler — no Selector, no SelectionKey, no readiness callbacks. Better when code simplicity is prioritized over explicit I/O control.
- **io_uring (via Netty incubating support):** Kernel-level async I/O with zero-copy. Better for latency-sensitive Linux workloads where `epoll` system call overhead matters.

**Engineering judgment.** For a cache where operations are sub-microsecond (`GET` is a `ConcurrentHashMap.get()` — ~20ns), the selector loop is never the bottleneck. Cache operations complete three orders of magnitude faster than network round-trip. The selector spends the vast majority of its time in `select()` waiting for I/O, not in application logic. Single selector + virtual thread avoids thread pool sizing complexity and eliminates thread exhaustion bugs. Benchmarks confirm: 200K ops/sec with 10K+ concurrent connections on a single selector thread.

---

## 5. SSLEngine Inline TLS (vs TLS Proxy/Sidecar)

**What was chosen.** `TLSManager` wraps Java's `SSLEngine` to perform TLS handshaking and record-layer encryption/decryption directly on the NIO non-blocking path. Plaintext bytes are threaded through to protocol handlers (RESP, HTTP/1.1, HTTP/2, WebSocket) without blocking the selector thread. The handshake is performed incrementally — each `OP_READ` event drives one step of the `wrap()`/`unwrap()` state machine, and delegated tasks (certificate verification) execute on virtual threads. A single `TLSManager` instance handles TLS for all four protocols because it operates below the protocol detection layer.

**What's sacrificed.**

- `SSLEngine` API is notoriously complex — `wrap()`/`unwrap()` has four `Status` values (`OK`, `BUFFER_UNDERFLOW`, `BUFFER_OVERFLOW`, `CLOSED`) crossed with four `HandshakeStatus` values (`NOT_HANDSHAKING`, `NEED_TASK`, `NEED_WRAP`, `NEED_UNWRAP`), producing 16 result combinations per call. Handling all 16 correctly is the primary source of TLS bugs.
- JDK TLS performance is ~20% slower than native OpenSSL — less aggressive use of AES-NI and AVX-512 intrinsics. For small cache payloads (<1 KB), the absolute difference is negligible; for bulk transfer it is measurable.
- Debugging requires deep JDK knowledge — `SSLEngine` errors are opaque, and `-Djavax.net.debug=ssl,handshake` produces voluminous output requiring expertise to interpret.
- Buffer management overhead — separate application and network `ByteBuffer` pairs per connection doubles buffer allocation (~32 KB per connection).
- No OCSP stapling or certificate transparency out of the box — production TLS deployments typically require these, and `SSLEngine` needs manual configuration.

**When the alternative wins.**

- **TLS-terminating proxy (Envoy, HAProxy, nginx):** Offloads TLS entirely, uses OpenSSL for native performance, handles certificate rotation and OCSP stapling independently. Better in production deployments with existing proxy infrastructure.
- **Netty's `SslHandler` with `netty-tcnative`:** Production-hardened buffer management, automatic handshake retry, optional OpenSSL backend via JNI. Better when TLS correctness is critical and development time is limited.
- **Service mesh TLS (Istio, Linkerd):** Automatic mTLS with zero application code changes. Better in Kubernetes environments where encryption is a platform concern.

**Engineering judgment.** Inline TLS demonstrates mastery of the `SSLEngine` API and keeps the zero-dependency philosophy intact — the entire FlashCache project has no external runtime dependencies. For a portfolio project, implementing the `SSLEngine` state machine from scratch proves understanding of TLS at the record-layer level. The measured TLS 1.3 handshake overhead of <2ms is acceptable for cache workloads where connections are long-lived and the handshake cost is amortized over thousands of operations.

---

## 6. Hand-Rolled HTTP/2 + HPACK (vs Jetty/Netty HTTP/2)

**What was chosen.** `Http2Handler` implements the HTTP/2 binary framing layer from RFC 7540 — parsing DATA, HEADERS, SETTINGS, WINDOW_UPDATE, and PING frames from raw bytes in a `ByteBuffer`. The 9-byte frame header (length, type, flags, stream identifier) is parsed first, followed by type-specific payload parsing.

Header decompression follows RFC 7541 (HPACK): static table lookups against the 61-entry predefined table, dynamic table insertions with size-bounded FIFO eviction, integer decoding with prefix-coded variable-length encoding, and Huffman decoding using the Appendix B code table (256-symbol tree with 5-to-30-bit variable-length codes). Protocol detection checks for the 24-byte HTTP/2 connection preface.

**What's sacrificed.**

- Incomplete HTTP/2 support — no server push (`PUSH_PROMISE`), no stream priority/dependency trees (RFC 7540 Section 5.3), no `GOAWAY` frame generation, no stream-level flow control. Handles the common request-response case but not edge cases.
- No connection-level error recovery — a malformed frame on one stream may affect the entire connection rather than triggering per-stream `RST_STREAM` as required by RFC 7540 Section 5.4.
- ~2000 lines of frame parser and HPACK decoder that a library handles in one import. Any spec update (RFC 9113 superseding RFC 7540) requires manual changes.
- No conformance testing against h2spec (146 RFC assertions). Edge cases in dynamic table eviction under rapid resize, Huffman padding validation, and flow control window overflow may not be fully covered.
- No HTTP/2-to-HTTP/1.1 fallback via ALPN — assumes the client speaks HTTP/2 after the connection preface.

**When the alternative wins.**

- **Netty's HTTP/2 codec (`Http2FrameCodec` + `Http2MultiplexHandler`):** Production-hardened, supports all frame types, handles edge cases per RFC, tested against h2spec. Automatic flow control back-pressure. Better for any production deployment where correctness under adversarial input matters.
- **Jetty or Undertow:** Complete HTTP/2 server implementations with lifecycle management, priority scheduling, server push, and multiplexed streams. Better when HTTP/2 is transport for a larger application rather than the subject of study.
- **gRPC:** Provides HTTP/2 framing, flow control, and multiplexing as an implementation detail. Better when HTTP/2 is a means to an end rather than the end itself.

**Engineering judgment.** The goal is demonstrating RFC-level protocol understanding, not building a production HTTP/2 server. The ~500 lines of HPACK decode — static table lookup, dynamic table management with FIFO eviction, prefix-coded integer decoding, and Huffman tree traversal — are the most technically impressive part of the protocol layer. This code answers "can you implement a wire protocol from its RFC?" with working, tested code backed by `Http2HandlerTest`. The missing features (server push, priority, GOAWAY) are omitted deliberately — they add implementation cost without demonstrating additional engineering insight. The hard part of HTTP/2 is binary framing and header compression, not connection management.

---

## Summary

| Dimension | Choice | Key Trade-off | Risk Level |
|-----------|--------|---------------|------------|
| Eviction | Exact LRU (linked list + lock) | Lock contention vs eviction accuracy | Low |
| Membership | SWIM gossip (incarnation-based) | Convergence delay vs coordinator-free scaling | Low |
| Hashing | SHA-256 + 150 virtual nodes | Hash cost vs distribution uniformity | Low |
| I/O Model | Single NIO Selector on virtual thread | Single-core dispatch vs thread pool complexity | Low |
| TLS | Inline SSLEngine | API complexity vs zero-dependency architecture | Medium |
| HTTP/2 | Hand-rolled framing + HPACK | Incomplete spec coverage vs RFC-level understanding | Medium |
