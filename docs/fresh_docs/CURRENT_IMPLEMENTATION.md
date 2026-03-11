# Current Implementation Guide

## Overview
Distributed Message Queue (DMQ) system with Raft-based metadata consensus, dynamic controller discovery, and automatic failover. Built with Spring Boot, implements publish-subscribe messaging with persistent storage.

---

## System Components

### 1. Metadata Service (Ports: 9091, 9092, 9093)
**Purpose**: Cluster coordination, topic/broker management, controller leadership

**Core Modules**:
- **RaftController**: Leader election, log replication, consensus
- **MetadataStateMachine**: Applies committed commands (RegisterBroker, CreateTopic, UpdateBrokerStatus)
- **HeartbeatService**: Tracks broker health, marks OFFLINE after 30s timeout
- **MetadataController**: REST API for cluster info, topic/broker operations
- **ControllerNotificationService**: Push notifications for CONTROLLER_CHANGED events

**Key Features**:
- Raft consensus with 3-node cluster (quorum: 2 votes)
- Leader validation: Non-leaders reject heartbeats with 503 + `X-Controller-Leader` header
- Automatic state rebuild on failover: `rebuildHeartbeatState()` restores in-memory timestamps
- Persistent log storage: JSON-based Raft log in `raft-data/node-{id}/log.json`

---

### 2. Storage Service (Ports: 8081, 8082, 8083)
**Purpose**: Message persistence, broker registration, metadata synchronization

**Core Modules**:
- **HeartbeatSender**: Sends heartbeats every 5s, auto-discovers controller, handles failover
- **MetadataStore**: Caches cluster metadata (topics, partitions, controller info)
- **ControllerDiscoveryService**: Parallel discovery across all metadata nodes
- **StorageController**: REST API for produce/consume operations
- **MetadataSyncService**: WebSocket client for push notifications (CONTROLLER_CHANGED)

**Key Features**:
- **Deferred Initialization**: Registration happens in `HeartbeatSender.init()` after controller discovery
- **Controller Sync Mechanism**: `syncControllerInfoFromMetadataStore()` before each heartbeat
- **Automatic Failover**: Switches to new controller within 5s of CONTROLLER_CHANGED notification
- **Exponential Backoff**: Discovery retry (1s, 2s, 4s), Registration retry (2s, 4s, 8s, 16s)

---

## Controller Discovery & Failover

### Discovery Flow (Startup)
```
1. HeartbeatSender.init() triggered by @PostConstruct
2. ControllerDiscoveryService.discoverController()
   - Queries all nodes: [9091, 9092, 9093] in parallel
   - Uses CompletableFuture.allOf() + checks first successful response
   - Retry logic: 3 attempts with exponential backoff
3. registerWithController(discoveredUrl)
   - POST /metadata/brokers/register
   - Retry: 5 attempts with exponential backoff
4. pullInitialMetadataFromController(discoveredUrl)
   - GET /metadata with query params
5. startHeartbeats() - Periodic 5s interval
```

### Failover Flow (Controller Change)
```
1. Controller node fails → Raft election triggered
2. New leader elected → becomeLeader() called
   - Rebuilds heartbeat state: heartbeatService.rebuildHeartbeatState()
   - Pushes CONTROLLER_CHANGED notification to all brokers
3. Broker receives WebSocket notification
   - MetadataSyncService.handleControllerChanged()
   - Updates MetadataStore.currentControllerUrl
4. Next heartbeat (within 5s)
   - HeartbeatSender.syncControllerInfoFromMetadataStore()
   - Detects URL mismatch → switches to new controller
   - Log: "🔄 Controller switch detected: 2 → 3"
5. Old controller (if restarted)
   - HeartbeatController.receiveHeartbeat() checks isControllerLeader()
   - Returns 503 SERVICE_UNAVAILABLE if not leader
```

---

## Topic & Partition Management

### Topic Creation
```http
POST /metadata/topics
Content-Type: application/json

{
  "name": "test-topic",
  "partitions": 3,
  "replicationFactor": 2,
  "retentionMs": 604800000
}
```

**Validation**:
- Partitions: 1-100
- Replication Factor: 1-3
- RetentionMs: 60000-2592000000 (1 min - 30 days)

**Process**:
1. Leader receives request → proposes CreateTopicCommand
2. Raft replication → quorum commits
3. MetadataStateMachine applies command → updates in-memory state
4. Partition assignment: Round-robin across ONLINE brokers
5. Metadata version incremented → triggers broker refresh

### Partition Assignment Example
```
Topic: test-topic, Partitions: 3, RF: 2
Brokers: [101, 102, 103]

Partition 0: [101, 102]  // Primary: 101, Replica: 102
Partition 1: [102, 103]  // Primary: 102, Replica: 103
Partition 2: [103, 101]  // Primary: 103, Replica: 101
```

---

## Message Flow

### Produce
```http
POST /storage/produce
Content-Type: application/json

{
  "topic": "test-topic",
  "partition": 0,
  "key": "user-123",
  "value": "{\"event\":\"login\"}",
  "headers": {"source": "web-app"}
}
```

**Storage**:
- Partition-based file: `messages/test-topic/partition-0/messages.json`
- Appends JSON object: `{"offset":42,"key":"...","value":"...","timestamp":1698345600000}`

### Consume
```http
GET /storage/consume?topic=test-topic&partition=0&offset=42&maxMessages=10
```

**Response**:
```json
[
  {
    "offset": 42,
    "key": "user-123",
    "value": "{\"event\":\"login\"}",
    "timestamp": 1698345600000,
    "headers": {"source": "web-app"}
  }
]
```

---

## Broker Health Monitoring

### Heartbeat Protocol
```
Storage Broker → Controller (every 5s)
POST /metadata/heartbeat
{
  "brokerId": 101,
  "timestamp": 1698345600000
}

Controller Response:
200 OK + metadata version
or
503 SERVICE_UNAVAILABLE + X-Controller-Leader: 3
```

### Health States
- **ONLINE**: Last heartbeat < 30s ago
- **OFFLINE**: Last heartbeat ≥ 30s ago
- **Unknown**: Never registered

### Offline Detection
```
OfflineNodeDetectorService (runs every 15s)
1. Check all brokers: current_time - lastHeartbeat
2. If > 30s → propose UpdateBrokerStatusCommand(OFFLINE)
3. Raft commits → broker marked OFFLINE
```

---

## Configuration

### Metadata Service (application.yml)
```yaml
server:
  port: ${SERVER_PORT:9091}

app:
  raft:
    node-id: ${NODE_ID:1}
    cluster-nodes:
      - id: 1
        url: http://localhost:9091
      - id: 2
        url: http://localhost:9092
      - id: 3
        url: http://localhost:9093
    election-timeout-min: 500
    election-timeout-max: 1000
    heartbeat-interval: 100
```

### Storage Service (application.yml)
```yaml
server:
  port: ${SERVER_PORT:8081}

app:
  broker-id: ${BROKER_ID:101}
  metadata-service-urls:
    - http://localhost:9091
    - http://localhost:9092
    - http://localhost:9093
```

---

## Data Persistence

### Raft Log Structure
```
raft-data/node-1/
├── log.json          # Committed entries
├── metadata.json     # Current term, voted for
└── snapshot.json     # State snapshots (future)
```

### Message Storage
```
messages/
├── test-topic/
│   ├── partition-0/
│   │   └── messages.json
│   ├── partition-1/
│   │   └── messages.json
│   └── partition-2/
│       └── messages.json
```

---

## API Endpoints

### Metadata Service
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/metadata` | Get cluster metadata |
| GET | `/metadata/controller` | Get current controller info |
| POST | `/metadata/topics` | Create topic |
| GET | `/metadata/topics` | List all topics |
| GET | `/metadata/brokers` | List all brokers |
| POST | `/metadata/brokers/register` | Register broker |
| POST | `/metadata/heartbeat` | Broker heartbeat |

### Storage Service
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/storage/produce` | Produce message |
| GET | `/storage/consume` | Consume messages |
| GET | `/storage/health` | Broker health |
| GET | `/storage/metadata` | Cached metadata |

---

## Monitoring & Observability

### Key Log Patterns
```
🔍 Controller discovery in progress
📡 Discovered controller: ID=2, URL=http://localhost:9092, term=3
✅ Registration successful with controller
🔄 Controller switch detected: 2 → 3
⚠️ Rejecting heartbeat from broker 102 - not the controller leader
🎖️ Node 2 became leader for term 3
📝 Heartbeat ACK for broker 101
```

### Health Checks
```bash
# Check controller
curl http://localhost:9091/metadata/controller

# Check brokers
curl http://localhost:9091/metadata/brokers

# Check topics
curl http://localhost:9091/metadata/topics

# Check broker health
curl http://localhost:8081/storage/health
```

---

## Known Limitations

1. **No Replication**: Messages not replicated (partition assignment exists, replication not implemented)
2. **No Leader Election for Partitions**: Broker crash → partition unavailable
3. **Sequential Message Storage**: JSON append-only, no compaction/cleanup
4. **Single Partition Query**: Consumer must query each partition separately
5. **No Consumer Groups**: No offset management, no group coordination
6. **In-Memory Metadata**: Controller restart → loses uncommitted metadata
7. **No Authentication**: Open endpoints, no security

---

## Testing Scenarios

### 1. Basic Flow
```bash
# Start services
cd dmq-metadata-service; mvn spring-boot:run -Dspring-boot.run.arguments="--SERVER_PORT=9091 --NODE_ID=1"
cd dmq-metadata-service; mvn spring-boot:run -Dspring-boot.run.arguments="--SERVER_PORT=9092 --NODE_ID=2"
cd dmq-metadata-service; mvn spring-boot:run -Dspring-boot.run.arguments="--SERVER_PORT=9093 --NODE_ID=3"
cd dmq-storage-service; mvn spring-boot:run -Dspring-boot.run.arguments="--SERVER_PORT=8081 --BROKER_ID=101"
cd dmq-storage-service; mvn spring-boot:run -Dspring-boot.run.arguments="--SERVER_PORT=8082 --BROKER_ID=102"

# Create topic
curl -X POST http://localhost:9091/metadata/topics \
  -H "Content-Type: application/json" \
  -d '{"name":"test-topic","partitions":3,"replicationFactor":2,"retentionMs":604800000}'

# Produce message
curl -X POST http://localhost:8081/storage/produce \
  -H "Content-Type: application/json" \
  -d '{"topic":"test-topic","partition":0,"key":"k1","value":"hello"}'

# Consume messages
curl "http://localhost:8081/storage/consume?topic=test-topic&partition=0&offset=0&maxMessages=10"
```

### 2. Controller Failover
```bash
# 1. Note current controller
curl http://localhost:9091/metadata/controller

# 2. Kill controller process (Ctrl+C on that terminal)

# 3. Watch logs - new leader elected within ~1s

# 4. Verify brokers switch controllers (check logs for "🔄 Controller switch detected")

# 5. Verify brokers stay ONLINE
curl http://localhost:9092/metadata/brokers
```

### 3. Network Partition Simulation
```bash
# Stop controller node 2
# Wait 30s → node 2 should be marked OFFLINE
# Restart node 2 → re-joins cluster, may trigger re-election
```

---

## Performance Characteristics

- **Heartbeat Overhead**: 5s interval × N brokers = N/5 req/s
- **Raft Replication**: ~100ms latency for topic creation
- **Message Produce**: ~10ms (local file append)
- **Message Consume**: ~5ms (file read)
- **Controller Election**: ~500-1000ms (election timeout)
- **Broker Failover**: <5s (next heartbeat interval)

---

## Recent Bug Fixes

1. **Discovery Service**: Fixed `anyOf()` → `allOf()` to wait for all node responses
2. **Circular Dependency**: Fixed RaftController ↔ HeartbeatService with `@Lazy`
3. **Heartbeat State**: Fixed missing `rebuildHeartbeatState()` on leader election
4. **Metadata Refresh**: Fixed deprecated `pullInitialMetadata()` → `pullInitialMetadataFromController()`
5. **Controller Sync**: Added `syncControllerInfoFromMetadataStore()` before each heartbeat
6. **Leader Validation**: Added `isControllerLeader()` check in HeartbeatController

---

## Future Enhancements

1. Message replication implementation
2. Partition leader election (ISR - In-Sync Replicas)
3. Consumer groups with offset management
4. Message compaction and retention policies
5. Authentication & authorization
6. Metrics & monitoring (Prometheus integration)
7. Admin CLI tool
8. Schema registry integration
