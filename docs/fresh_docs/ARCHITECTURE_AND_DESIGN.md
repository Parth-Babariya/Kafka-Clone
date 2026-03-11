# Architecture & Key Design Decisions

## System Architecture

### High-Level Overview
```
┌─────────────────────────────────────────────────────────────┐
│                    Client Applications                       │
└────────────┬────────────────────────────────┬───────────────┘
             │                                │
             │ Produce/Consume                │ Admin APIs
             ▼                                ▼
┌────────────────────────────┐   ┌──────────────────────────┐
│   Storage Service Layer    │   │  Metadata Service Layer  │
│  (Brokers: 8081-8083)      │◄──┤  (Controllers: 9091-9093)│
│                            │   │                          │
│  ┌──────────────────────┐  │   │  ┌────────────────────┐ │
│  │ HeartbeatSender      │──┼───┼─►│ HeartbeatController│ │
│  │ - 5s interval        │  │   │  │ - Leader validation│ │
│  │ - Auto-discovery     │  │   │  └────────────────────┘ │
│  │ - Failover handling  │  │   │                          │
│  └──────────────────────┘  │   │  ┌────────────────────┐ │
│                            │   │  │ RaftController     │ │
│  ┌──────────────────────┐  │   │  │ - Leader election  │ │
│  │ MetadataStore        │◄─┼───┼──│ - Log replication  │ │
│  │ - Local cache        │  │   │  │ - Consensus        │ │
│  │ - Controller info    │  │   │  └────────────────────┘ │
│  └──────────────────────┘  │   │                          │
│                            │   │  ┌────────────────────┐ │
│  ┌──────────────────────┐  │   │  │ MetadataStateMach. │ │
│  │ StorageController    │  │   │  │ - Apply commands   │ │
│  │ - Produce API        │  │   │  │ - State updates    │ │
│  │ - Consume API        │  │   │  └────────────────────┘ │
│  └──────────────────────┘  │   └──────────────────────────┘
│                            │
│  ┌──────────────────────┐  │   ┌──────────────────────────┐
│  │ Message Storage      │  │   │    WebSocket Push        │
│  │ - Partition files    │  │   │  CONTROLLER_CHANGED      │
│  │ - JSON append-only   │◄─┼───┤  Notifications           │
│  └──────────────────────┘  │   └──────────────────────────┘
└────────────────────────────┘
             │
             ▼
┌────────────────────────────┐
│   Persistent Storage       │
│  messages/topic/partition/ │
└────────────────────────────┘
```

---

## Key Design Decisions

### 1. **Raft Consensus for Metadata Management**

**Decision**: Use Raft algorithm for controller cluster coordination

**Rationale**:
- Strong consistency guarantees for metadata operations
- Automatic leader election with configurable timeout
- Log replication ensures all nodes converge to same state
- Simpler than Paxos, well-documented

**Implementation**:
- 3-node cluster with quorum (2 votes required)
- Heartbeat interval: 100ms (leader → followers)
- Election timeout: 500-1000ms (randomized to prevent split votes)
- Persistent log storage: JSON-based for debugging ease

**Trade-offs**:
- ✅ Strong consistency
- ✅ Automatic failover
- ❌ Write latency (~100ms for replication)
- ❌ Single leader bottleneck (all writes through leader)

---

### 2. **Deferred Initialization Pattern**

**Decision**: Separate bean creation from service registration

**Problem Solved**: 
- Storage service needed controller URL to register
- Controller URL unknown at startup (dynamic discovery required)
- Chicken-and-egg: Can't register without controller, can't get controller without metadata service

**Implementation**:
```java
@Configuration
public class StorageServiceConfig {
    @Bean
    public MetadataStore metadataStore() {
        // Bean created WITHOUT registration
        return new MetadataStore(brokerId, storageServiceUrl);
    }
}

@Component
public class HeartbeatSender {
    @PostConstruct
    public void init() {
        // Registration happens AFTER controller discovery
        discoverController();
        registerWithController();
        pullMetadata();
        startHeartbeats();
    }
}
```

**Benefits**:
- No hardcoded controller URLs
- Works with any controller port
- Graceful handling of controller unavailability
- Clear separation of concerns

---

### 3. **Controller Sync Mechanism**

**Decision**: Sync controller info before each heartbeat

**Problem Solved**:
- HeartbeatSender had cached controller URL
- Metadata refresh updated MetadataStore, but cache not synced
- Broker continued sending heartbeats to old controller after failover

**Implementation**:
```java
private void syncControllerInfoFromMetadataStore() {
    String latestControllerUrl = metadataStore.getCurrentControllerUrl();
    if (!Objects.equals(latestControllerUrl, currentControllerUrl)) {
        log.info("🔄 Controller switch detected: {} → {}", 
            currentControllerId, metadataStore.getCurrentControllerId());
        currentControllerUrl = latestControllerUrl;
        currentControllerId = metadataStore.getCurrentControllerId();
        currentControllerTerm = metadataStore.getCurrentControllerTerm();
    }
}

public void sendHeartbeat() {
    syncControllerInfoFromMetadataStore(); // Called before every heartbeat
    // ... send heartbeat to currentControllerUrl
}
```

**Benefits**:
- Automatic failover within 5s (next heartbeat)
- No manual broker restart required
- Single source of truth (MetadataStore)

---

### 4. **Leader Validation at Heartbeat Endpoint**

**Decision**: Non-leader controllers reject heartbeats with 503

**Problem Solved**:
- Old controller accepted heartbeats even after losing leadership
- Brokers thought they were healthy but talking to wrong node
- Split-brain scenario: multiple controllers thinking they're active

**Implementation**:
```java
@PostMapping("/heartbeat")
public ResponseEntity<?> receiveHeartbeat(@RequestBody HeartbeatRequest request) {
    if (!raftController.isControllerLeader()) {
        log.warn("⚠️ Rejecting heartbeat from broker {} - not the controller leader", 
            request.getBrokerId());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("X-Controller-Leader", String.valueOf(raftController.getCurrentLeaderId()))
            .body("This node is not the controller leader");
    }
    // ... process heartbeat
}
```

**Benefits**:
- Prevents stale heartbeats
- Fast failure detection (broker gets 503)
- Broker can discover new leader from response header

---

### 5. **Push Notifications for Controller Changes**

**Decision**: WebSocket push for CONTROLLER_CHANGED events (in addition to periodic refresh)

**Rationale**:
- Faster failover than polling (immediate vs 60s)
- Reduced network traffic (no unnecessary polls)
- Scalable to many brokers

**Implementation**:
```java
// Controller side
public void becomeLeader() {
    rebuildHeartbeatState();
    notificationService.notifyControllerChanged(nodeId, currentTerm);
}

// Broker side (WebSocket)
@Component
public class MetadataSyncService implements StompSessionHandler {
    @Override
    public void handleFrame(StompHeaders headers, Object payload) {
        ControllerChangedNotification notification = (ControllerChangedNotification) payload;
        metadataStore.updateController(notification.getControllerId(), 
            notification.getControllerUrl(), notification.getTerm());
    }
}
```

**Trade-offs**:
- ✅ Sub-second failover notification
- ✅ Event-driven architecture
- ❌ WebSocket connection overhead
- ❌ Fallback to polling if WebSocket fails

---

### 6. **Parallel Discovery with CompletableFuture.allOf()**

**Decision**: Query all metadata nodes in parallel, wait for all responses

**Problem Solved**:
- Original `anyOf()` returned first completed (success OR failure)
- If one node down, failure returned immediately
- False discovery failures even when 2/3 nodes healthy

**Implementation**:
```java
public ControllerInfo discoverController() {
    List<CompletableFuture<ControllerInfo>> futures = metadataServiceUrls.stream()
        .map(url -> CompletableFuture.supplyAsync(() -> queryNode(url)))
        .collect(Collectors.toList());
    
    CompletableFuture<Void> allOf = CompletableFuture.allOf(
        futures.toArray(new CompletableFuture[0]));
    
    try {
        allOf.get(5, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
        // Partial results OK
    }
    
    // Check all results, return first successful
    for (CompletableFuture<ControllerInfo> future : futures) {
        if (future.isDone() && !future.isCompletedExceptionally()) {
            ControllerInfo info = future.get();
            if (info != null) return info;
        }
    }
    throw new RuntimeException("No controller found");
}
```

**Benefits**:
- Resilient to individual node failures
- Fast discovery (parallel queries)
- Always finds controller if quorum healthy

---

### 7. **Circular Dependency Resolution with @Lazy**

**Decision**: Use `@Lazy` injection for RaftController ↔ HeartbeatService

**Problem Solved**:
```
RaftController needs HeartbeatService (to rebuild state on leader election)
HeartbeatService needs RaftController (to check leader status)
→ Circular dependency during bean initialization
```

**Implementation**:
```java
@Component
public class RaftController {
    private final HeartbeatService heartbeatService;
    
    public RaftController(@Lazy HeartbeatService heartbeatService) {
        this.heartbeatService = heartbeatService;
    }
    
    public void becomeLeader() {
        heartbeatService.rebuildHeartbeatState(); // Lazy resolution here
    }
}
```

**How it works**:
- Spring creates proxy for `HeartbeatService`
- Actual bean injected on first method call
- Breaks initialization cycle

---

### 8. **Heartbeat State Rebuild on Failover**

**Decision**: Restore in-memory heartbeat timestamps from Raft state on leader election

**Problem Solved**:
- New leader had empty in-memory heartbeat map
- All brokers marked OFFLINE despite sending heartbeats
- Required manual broker restart to re-register

**Implementation**:
```java
public void becomeLeader() {
    log.info("🎖️ Node {} became leader for term {}", nodeId, currentTerm);
    
    // Rebuild heartbeat state from committed Raft log
    heartbeatService.rebuildHeartbeatState();
    
    // Initialize leader state
    for (Integer peer : peers.keySet()) {
        nextIndex.put(peer, getLastLogIndex() + 1);
        matchIndex.put(peer, 0);
    }
}

// HeartbeatService
public void rebuildHeartbeatState() {
    Map<Integer, BrokerInfo> brokers = metadataService.getAllBrokers();
    for (BrokerInfo broker : brokers.values()) {
        if (broker.getStatus() == BrokerStatus.ONLINE) {
            lastHeartbeatTime.put(broker.getBrokerId(), broker.getLastHeartbeatTime());
        }
    }
}
```

**Benefits**:
- Seamless failover (no broker restart)
- Consistent broker status across failover
- Recovery from persisted state

---

### 9. **Exponential Backoff for Retries**

**Decision**: Exponential backoff for discovery and registration

**Configuration**:
- Discovery: 1s, 2s, 4s (max 3 attempts)
- Registration: 2s, 4s, 8s, 16s (max 5 attempts)

**Rationale**:
- Initial failures may be transient (network blip, controller election)
- Exponential spacing prevents thundering herd
- Bounded retries prevent infinite loops

**Implementation**:
```java
int attempt = 0;
while (attempt < MAX_ATTEMPTS) {
    try {
        return operation();
    } catch (Exception e) {
        long backoff = INITIAL_BACKOFF * (1 << attempt);
        Thread.sleep(backoff);
        attempt++;
    }
}
```

---

### 10. **Partition Assignment: Round-Robin Strategy**

**Decision**: Distribute partition replicas evenly across brokers

**Algorithm**:
```java
int brokerIndex = 0;
for (int partition = 0; partition < numPartitions; partition++) {
    List<Integer> replicas = new ArrayList<>();
    for (int replica = 0; replica < replicationFactor; replica++) {
        replicas.add(onlineBrokers.get(brokerIndex % numBrokers));
        brokerIndex++;
    }
    assignment.put(partition, replicas);
}
```

**Example** (3 partitions, RF=2, brokers=[101,102,103]):
```
P0: [101, 102]
P1: [102, 103]
P2: [103, 101]
```

**Benefits**:
- Even load distribution
- Fault tolerance (replicas on different brokers)
- Simple algorithm (no rack awareness)

**Limitations**:
- No rack awareness
- No broker capacity consideration
- No rebalancing on broker add/remove

---

### 11. **JSON-Based Message Storage**

**Decision**: Store messages as JSON arrays in partition-specific files

**Format**:
```json
[
  {"offset":0,"key":"k1","value":"v1","timestamp":1698345600000,"headers":{"h1":"v1"}},
  {"offset":1,"key":"k2","value":"v2","timestamp":1698345601000,"headers":{}}
]
```

**Rationale**:
- Human-readable (easy debugging)
- Self-describing schema
- No external dependencies (no Kafka log format library)
- Simple implementation (append-only)

**Trade-offs**:
- ✅ Simplicity
- ✅ Debuggability
- ❌ Storage inefficiency (JSON overhead)
- ❌ No compression
- ❌ Sequential read required (no index)

**Future**: Binary format (Avro/Protobuf) with index for performance

---

### 12. **REST over gRPC for Inter-Service Communication**

**Decision**: Use HTTP REST APIs instead of gRPC

**Rationale**:
- Simpler debugging (curl, browser, logs)
- Wider tooling support
- Lower learning curve
- Spring Boot native support

**Trade-offs**:
- ✅ Developer experience
- ✅ Debugging ease
- ❌ Higher latency (~10% vs gRPC)
- ❌ Larger payload size (JSON vs Protobuf)
- ❌ No streaming support (REST unidirectional)

**Mitigation**: WebSocket for push notifications (CONTROLLER_CHANGED)

---

### 13. **Stateful Metadata Service**

**Decision**: Keep full metadata state in memory, persist only Raft log

**Rationale**:
- Fast reads (no disk I/O)
- State rebuilt from Raft log on restart
- Raft log guarantees consistency

**Data Flow**:
```
1. CreateTopic request → Leader
2. Leader proposes CreateTopicCommand → Raft log
3. Quorum commits → Log persisted to disk
4. Leader applies to MetadataStateMachine → In-memory state updated
5. Followers apply same command → In-memory state synced
```

**Benefits**:
- Sub-millisecond metadata reads
- Strong consistency (Raft guarantees)
- Crash recovery (replay log)

**Limitations**:
- Slow restart (replay full log)
- Memory footprint grows with metadata
- No log compaction (yet)

---

### 14. **Offline Detection via Periodic Scan**

**Decision**: Background task checks broker heartbeats every 15s

**Implementation**:
```java
@Scheduled(fixedRate = 15000)
public void detectOfflineNodes() {
    long now = System.currentTimeMillis();
    for (BrokerInfo broker : getAllBrokers()) {
        if (broker.getStatus() == ONLINE && 
            now - broker.getLastHeartbeatTime() > 30000) {
            proposeUpdateBrokerStatus(broker.getId(), OFFLINE);
        }
    }
}
```

**Configuration**:
- Heartbeat interval: 5s
- Timeout threshold: 30s (6 missed heartbeats)
- Detection interval: 15s

**Trade-offs**:
- ✅ Simple implementation
- ✅ No complex failure detection
- ❌ Detection delay (up to 15s)
- ❌ Leader-only (followers don't detect)

---

## Architectural Patterns Applied

1. **State Machine Replication**: Raft-based metadata replication
2. **Command Pattern**: All mutations as Raft commands (CreateTopic, RegisterBroker, etc.)
3. **Event Sourcing**: Raft log as append-only event store
4. **CQRS**: Separate read (MetadataService) and write (RaftController) paths
5. **Dependency Injection**: Spring Boot IoC for all components
6. **Circuit Breaker Pattern**: Retry logic with exponential backoff
7. **Observer Pattern**: WebSocket notifications for state changes
8. **Singleton Pattern**: MetadataStore, HeartbeatService (Spring-managed)

---

## Consistency Guarantees

1. **Metadata**: Linearizable (Raft consensus)
2. **Message Ordering**: Per-partition FIFO
3. **Cross-Partition**: No ordering guarantees
4. **Broker Status**: Eventually consistent (30s detection delay)
5. **Controller Info**: Strongly consistent (Raft-managed)

---

## Failure Modes & Mitigation

| Failure | Detection | Recovery | Data Loss |
|---------|-----------|----------|-----------|
| Controller crash | Election timeout (~1s) | Auto re-elect | None (Raft log) |
| Broker crash | Heartbeat timeout (30s) | Manual restart | Uncompacted messages |
| Network partition | Raft quorum loss | Wait for partition heal | Writes blocked |
| All controllers down | Client timeout | Manual restart | None (persisted log) |
| Disk full (broker) | Produce error | Add capacity | None |
| Disk full (controller) | Raft append error | Add capacity | Writes blocked |

---

## Performance Tuning Knobs

1. **Raft Heartbeat Interval**: Lower = faster failure detection, higher CPU
2. **Election Timeout**: Lower = faster failover, higher split-brain risk
3. **Broker Heartbeat Interval**: Lower = faster health updates, higher network
4. **Offline Detection Interval**: Lower = faster detection, higher Raft writes
5. **Partition Count**: Higher = better parallelism, higher metadata overhead
6. **Replication Factor**: Higher = better durability, higher storage cost

---

## Security Considerations (NOT Implemented)

1. **No Authentication**: Anyone can produce/consume
2. **No Authorization**: No ACLs for topics
3. **No Encryption**: Plain HTTP (no TLS)
4. **No Rate Limiting**: Vulnerable to DoS
5. **No Input Validation**: Potential injection attacks

**Recommendation**: Add Spring Security + OAuth2 for production

---

## Scalability Limits

| Component | Limit | Bottleneck |
|-----------|-------|------------|
| Controllers | 3-7 nodes | Raft quorum overhead |
| Brokers | ~100 | Controller memory |
| Topics | ~1000 | Controller memory |
| Partitions/Topic | ~100 | Broker file handles |
| Messages/Partition | Millions | Disk space, sequential read |
| Message Size | 1MB | Network bandwidth |

---

## Comparison with Apache Kafka

| Feature | DMQ | Kafka |
|---------|-----|-------|
| Metadata Management | Raft (custom) | KRaft (Raft-based) |
| Message Replication | None (planned) | ISR-based |
| Partition Leader Election | None | ZooKeeper/KRaft |
| Consumer Groups | None | Full support |
| Offset Management | Client-side | Server-side |
| Message Format | JSON | Binary (custom) |
| Storage | JSON files | Segment files + index |
| Performance | ~1K msg/s | ~1M msg/s |
| Maturity | PoC | Production-ready |

---

## Evolution Path

**Current State**: PoC with basic pub-sub + Raft consensus

**Phase 1** (Current):
- ✅ Raft-based controller cluster
- ✅ Dynamic controller discovery
- ✅ Automatic failover
- ✅ Basic produce/consume

**Phase 2** (Next):
- [ ] Message replication
- [ ] Partition leader election
- [ ] ISR (In-Sync Replicas)
- [ ] Consumer groups

**Phase 3** (Future):
- [ ] Log compaction
- [ ] Schema registry
- [ ] Metrics & monitoring
- [ ] Admin CLI
- [ ] Performance optimization

---

## Lessons Learned

1. **Circular Dependencies**: Use `@Lazy` or refactor to break cycles
2. **CompletableFuture.anyOf()**: Returns first completed (success OR failure) - use `allOf()` instead
3. **Stateful Services**: Rebuild in-memory state on failover
4. **Leader Validation**: Always check leadership before processing requests
5. **Controller Sync**: Keep multiple caches in sync (single source of truth pattern)
6. **JSON Debugging**: Human-readable formats invaluable during development
7. **Emoji Logging**: Visual markers improve log readability (🔍, 📡, ✅, ❌, 🔄)

---

## References

- Raft Consensus: https://raft.github.io/
- Spring Boot: https://spring.io/projects/spring-boot
- Apache Kafka Design: https://kafka.apache.org/documentation/#design
- Distributed Systems Patterns: Martin Kleppmann's "Designing Data-Intensive Applications"
