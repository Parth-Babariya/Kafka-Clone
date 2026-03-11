# DMQ Metadata Service (KRaft Controller)

[![Version](https://img.shields.io/badge/version-1.0.0-blue.svg)](https://github.com/yourusername/dmq-metadata-service)
[![Java](https://img.shields.io/badge/Java-11-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.18-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Raft](https://img.shields.io/badge/Consensus-Raft-red.svg)](https://raft.github.io/)
[![Status](https://img.shields.io/badge/status-active-success.svg)]()

## Overview

**DMQ Metadata Service** implements the controller layer of DistributedMQ using **Raft consensus protocol** for metadata management (KRaft mode). It eliminates dependency on Apache ZooKeeper by providing self-contained cluster coordination with strong consistency guarantees.

### Key Features

- 🗳️ **Raft Consensus**: Custom implementation for leader election and log replication
- 💓 **Heartbeat Processing**: Broker health monitoring with 5-second intervals
- 📊 **Metadata Management**: Topic, partition, and broker metadata
- 🔄 **Automatic Failover**: Sub-10 second controller failover
- 📢 **Push Notifications**: Real-time CONTROLLER_CHANGED events to brokers
- 🛡️ **Leader Validation**: 503 response when not leader prevents split-brain
- 💾 **Persistent Log**: Database-backed Raft log for durability
- 📝 **Comprehensive Logging**: Emoji-based logging for debugging

---

## Table of Contents

1. [Architecture](#architecture)
2. [Core Components](#core-components)
3. [Configuration](#configuration)
4. [Running the Service](#running-the-service)
5. [API Endpoints](#api-endpoints)
6. [Raft Protocol](#raft-protocol)
7. [Heartbeat Processing](#heartbeat-processing)
8. [Controller Failover](#controller-failover)
9. [Testing](#testing)
10. [Troubleshooting](#troubleshooting)

---

## Architecture

### Component Overview

```
┌──────────────────────────────────────────────────────────────────┐
│              DMQ Metadata Service (KRaft Node)                   │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │                  RaftController                             │ │
│  │  ┌──────────────────────────────────────────────────────┐  │ │
│  │  │  State Machine (FOLLOWER → CANDIDATE → LEADER)       │  │ │
│  │  │  • currentTerm: 3                                    │  │ │
│  │  │  • votedFor: 2                                       │  │ │
│  │  │  • state: LEADER                                     │  │ │
│  │  │  • commitIndex: 42                                   │  │ │
│  │  │  • lastApplied: 42                                   │  │ │
│  │  └──────────────────────────────────────────────────────┘  │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │              RaftLogPersistence                             │ │
│  │  • Database-backed log storage                             │ │
│  │  • Persistent across restarts                              │ │
│  │  • Log entries: [term, index, command]                     │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │            MetadataStateMachine                             │ │
│  │  • Apply committed commands to database                    │ │
│  │  • RegisterBrokerCommand                                   │ │
│  │  • CreateTopicCommand                                      │ │
│  │  • UpdateBrokerStatusCommand                               │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │              HeartbeatService                               │ │
│  │  • Track broker heartbeats (in-memory map)                 │ │
│  │  • 30-second timeout threshold                             │ │
│  │  • 5-second health check interval                          │ │
│  │  • Automatic OFFLINE marking                               │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │           REST API Controllers                              │ │
│  │  • MetadataController: Topic/Broker CRUD                   │ │
│  │  • HeartbeatController: Broker heartbeat endpoint          │ │
│  │  • RaftApiController: Raft RPC endpoints                   │ │
│  │  • ISRController: ISR management                           │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
                           │
                           │ Raft RPCs
                           │
           ┌───────────────┼───────────────┐
           │               │               │
           ▼               ▼               ▼
    ┌────────────┐  ┌────────────┐  ┌────────────┐
    │  Node 1    │  │  Node 2    │  │  Node 3    │
    │  Port 9091 │  │  Port 9092 │  │  Port 9093 │
    │  FOLLOWER  │  │  LEADER    │  │  FOLLOWER  │
    └────────────┘  └────────────┘  └────────────┘
```

### Raft Cluster Topology

```
3-Node Cluster (Fault Tolerance: F=1)

┌──────────────────────────────────────────────────────────────┐
│                     Metadata Cluster                         │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌───────────────┐    ┌───────────────┐    ┌───────────────┐│
│  │  Metadata 1   │    │  Metadata 2   │    │  Metadata 3   ││
│  │  Port: 9091   │    │  Port: 9092   │    │  Port: 9093   ││
│  │  Node ID: 1   │◄──►│  Node ID: 2   │◄──►│  Node ID: 3   ││
│  │  FOLLOWER     │    │  LEADER       │    │  FOLLOWER     ││
│  │  Term: 3      │    │  Term: 3      │    │  Term: 3      ││
│  └───────────────┘    └───────────────┘    └───────────────┘│
│          │                     │                     │       │
│          └─────────────────────┼─────────────────────┘       │
│                                │                             │
│                   ┌────────────▼───────────┐                 │
│                   │   Database (Shared)    │                 │
│                   │   • Raft Log           │                 │
│                   │   • Metadata Tables    │                 │
│                   └────────────────────────┘                 │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

---

## Core Components

### 1. RaftController

**Purpose**: Implements Raft consensus state machine for cluster coordination.

**Key Responsibilities**:
- Leader election with randomized timeouts
- Log replication via AppendEntries RPC
- Vote handling via RequestVote RPC
- Heartbeat broadcasting (1.5-second interval)
- Log commitment with majority consensus

**Raft States**:
```java
public enum RaftState {
    FOLLOWER,   // Default state, follows leader
    CANDIDATE,  // Requesting votes for leadership
    LEADER      // Elected leader, replicating logs
}
```

**State Variables**:
```java
// Persistent state (survives restarts)
private volatile Integer currentTerm = 0;       // Latest term server has seen
private volatile Integer votedFor = null;       // CandidateId that received vote in current term
private RaftLogPersistence logPersistence;      // Log entries

// Volatile state (all servers)
private volatile long commitIndex = 0;          // Index of highest log entry known to be committed
private volatile long lastApplied = 0;          // Index of highest log entry applied to state machine

// Volatile state (leader only)
private Map<Integer, Long> nextIndex;           // Index of next log entry to send to each follower
private Map<Integer, Long> matchIndex;          // Index of highest log entry known to be replicated
```

**Configuration**:
```yaml
kraft:
  node-id: 1                           # Unique node ID (1, 2, or 3)
  cluster-size: 3                      # Total nodes in Raft cluster
  
  raft:
    election-timeout-ms: 5000          # Base election timeout
    election-timeout-jitter-ms: 2000   # Randomization range
    heartbeat-interval-ms: 1500        # Leader heartbeat interval
```

---

### 2. RaftLogPersistence

**Purpose**: Persistent storage for Raft log entries.

**Schema**:
```sql
CREATE TABLE raft_log (
    log_index BIGINT PRIMARY KEY,
    term INTEGER NOT NULL,
    command_type VARCHAR(255) NOT NULL,
    command_data TEXT NOT NULL,
    timestamp BIGINT NOT NULL
);
```

**Key Operations**:
- `saveLog(RaftLogEntry)`: Append entry to log
- `getLogEntry(long index)`: Retrieve specific entry
- `getLogEntries(long startIndex, long endIndex)`: Retrieve range
- `getLastLogIndex()`: Get last log index
- `getLastLogTerm()`: Get term of last log entry
- `deleteLogsFrom(long index)`: Truncate log (conflict resolution)

---

### 3. MetadataStateMachine

**Purpose**: Apply committed Raft log entries to database.

**Supported Commands**:

#### RegisterBrokerCommand
```json
{
  "commandType": "RegisterBrokerCommand",
  "brokerId": 101,
  "host": "localhost",
  "port": 8081
}
```

#### CreateTopicCommand
```json
{
  "commandType": "CreateTopicCommand",
  "topicName": "orders",
  "numPartitions": 3,
  "replicationFactor": 2
}
```

#### UpdateBrokerStatusCommand
```json
{
  "commandType": "UpdateBrokerStatusCommand",
  "brokerId": 101,
  "status": "OFFLINE",
  "timestamp": 1731345678000
}
```

**Application Flow**:
```
Raft Log Entry Committed
       │
       ▼
MetadataStateMachine.apply(entry)
       │
       ├── Extract command from entry
       │
       ├── Switch on command type
       │
       ├── Execute command logic
       │   (e.g., save topic to database)
       │
       └── Update lastApplied index
```

---

### 4. HeartbeatService

**Purpose**: Monitor broker health and mark offline brokers.

**Architecture**:
```java
@Service
public class HeartbeatService {
    // In-memory tracking (rebuilt on leader election)
    private final Map<Integer, Long> lastHeartbeatTime = new ConcurrentHashMap<>();
    
    // Configuration
    private static final long HEARTBEAT_TIMEOUT_MS = 30_000; // 30 seconds
    
    @Scheduled(fixedDelay = 5000) // Check every 5 seconds
    public void checkBrokerHealth() {
        if (!raftController.isControllerLeader()) {
            return; // Only leader processes heartbeats
        }
        
        long currentTime = System.currentTimeMillis();
        
        for (Map.Entry<Integer, Long> entry : lastHeartbeatTime.entrySet()) {
            Integer brokerId = entry.getKey();
            Long lastHeartbeat = entry.getValue();
            
            if (currentTime - lastHeartbeat > HEARTBEAT_TIMEOUT_MS) {
                log.warn("⚠️ Broker {} missed heartbeat ({}ms since last)", 
                    brokerId, currentTime - lastHeartbeat);
                
                markBrokerOffline(brokerId);
            }
        }
    }
}
```

**State Rebuild**:
```java
@PostConstruct
public void initHeartbeatState() {
    if (raftController.isControllerLeader()) {
        // Rebuild state from database
        List<Broker> brokers = brokerRepository.findAll();
        for (Broker broker : brokers) {
            if (broker.getStatus() == BrokerStatus.ONLINE) {
                lastHeartbeatTime.put(broker.getId(), broker.getLastHeartbeatTime());
            }
        }
        log.info("🔧 Rebuilt heartbeat state for {} brokers", brokers.size());
    }
}
```

---

### 5. HeartbeatController

**Purpose**: REST endpoint for broker heartbeats.

**Endpoint**: `POST /api/v1/metadata/heartbeat/{brokerId}`

**Implementation**:
```java
@PostMapping("/{brokerId}")
public ResponseEntity<HeartbeatResponse> receiveHeartbeat(
        @PathVariable Integer brokerId,
        @RequestBody HeartbeatRequest request) {
    
    // CRITICAL: Leader validation
    if (!raftController.isControllerLeader()) {
        log.warn("⚠️ Not the leader, rejecting heartbeat from Broker {}", brokerId);
        
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("X-Controller-Leader", raftController.getControllerLeaderId().toString())
            .build();
    }
    
    // Update heartbeat timestamp
    heartbeatService.updateHeartbeat(brokerId, System.currentTimeMillis());
    
    // Check if broker was offline, mark online
    Broker broker = brokerRepository.findById(brokerId).orElse(null);
    if (broker != null && broker.getStatus() == BrokerStatus.OFFLINE) {
        // Commit status change via Raft
        UpdateBrokerStatusCommand command = new UpdateBrokerStatusCommand(
            brokerId, BrokerStatus.ONLINE, System.currentTimeMillis()
        );
        raftController.appendEntry(command);
    }
    
    // Return response
    return ResponseEntity.ok(HeartbeatResponse.builder()
        .ack(true)
        .currentVersion(metadataService.getMetadataVersion())
        .controllerTerm(raftController.getCurrentTerm())
        .timestamp(System.currentTimeMillis())
        .build());
}
```

**Key Features**:
- ✅ Leader validation before processing
- ✅ Returns 503 with X-Controller-Leader if not leader
- ✅ Updates heartbeat timestamp
- ✅ Automatic OFFLINE → ONLINE transition
- ✅ Metadata version in response

---

## Configuration

### services.json (External Configuration)

```json
{
  "services": {
    "metadata-services": [
      {
        "id": 1,
        "host": "localhost",
        "port": 9091,
        "url": "http://localhost:9091"
      },
      {
        "id": 2,
        "host": "localhost",
        "port": 9092,
        "url": "http://localhost:9092"
      },
      {
        "id": 3,
        "host": "localhost",
        "port": 9093,
        "url": "http://localhost:9093"
      }
    ]
  },
  "controller": {
    "electionTimeoutMs": 5000
  }
}
```

---

### application.yml

```yaml
server:
  port: 9091  # Node-specific (9091, 9092, or 9093)

kraft:
  node-id: 1  # Node-specific (1, 2, or 3)
  cluster-size: 3
  
  raft:
    election-timeout-ms: 5000
    election-timeout-jitter-ms: 2000
    heartbeat-interval-ms: 1500

spring:
  application:
    name: dmq-metadata-service
    
  datasource:
    url: jdbc:postgresql://localhost:5432/dmq_metadata_node1
    username: postgres
    password: postgres
    driver-class-name: org.postgresql.Driver
    
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
    
logging:
  level:
    com.distributedmq.metadata: DEBUG
    com.distributedmq.metadata.coordination: INFO
```

---

### Environment Variables

Override configuration via environment variables:

```bash
# Node Configuration
export KRAFT_NODE_ID=1
export SERVER_PORT=9091

# Raft Configuration
export KRAFT_RAFT_ELECTION_TIMEOUT_MS=5000
export KRAFT_RAFT_HEARTBEAT_INTERVAL_MS=1500

# Database Configuration
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/dmq_metadata_node1
export SPRING_DATASOURCE_USERNAME=postgres
export SPRING_DATASOURCE_PASSWORD=postgres
```

---

## Running the Service

### Prerequisites

- Java 11+
- Maven 3.6+
- PostgreSQL 15+ (or H2 for testing)
- services.json in project root

### Build

```bash
cd dmq-metadata-service
mvn clean package
```

### Run 3-Node Cluster (Windows)

```powershell
# Terminal 1 - Node 1
Start-Process powershell -ArgumentList "-NoExit", "-Command", "java -jar target/dmq-metadata-service-1.0.0.jar --kraft.node-id=1 --server.port=9091 --spring.datasource.url=jdbc:postgresql://localhost:5432/dmq_metadata_node1"

# Terminal 2 - Node 2
Start-Process powershell -ArgumentList "-NoExit", "-Command", "java -jar target/dmq-metadata-service-1.0.0.jar --kraft.node-id=2 --server.port=9092 --spring.datasource.url=jdbc:postgresql://localhost:5432/dmq_metadata_node2"

# Terminal 3 - Node 3
Start-Process powershell -ArgumentList "-NoExit", "-Command", "java -jar target/dmq-metadata-service-1.0.0.jar --kraft.node-id=3 --server.port=9093 --spring.datasource.url=jdbc:postgresql://localhost:5432/dmq_metadata_node3"
```

### Expected Startup Logs

```bash
🚀 Starting DMQ Metadata Service...
📋 Node ID: 1
📋 Port: 9091
📋 Cluster Size: 3

🗳️ Initializing Raft Controller...
📚 Loading Raft log from database...
✅ Raft log loaded: 0 entries
🔄 Starting in FOLLOWER state (Term: 0)

⏱️ Election timeout set: 6200ms (base: 5000ms + jitter: 1200ms)
🗳️ Waiting for leader or election timeout...

# After ~6 seconds (election timeout)
🗳️ Election timeout, starting election for term 1
🗳️ Node 1 transitioning to CANDIDATE (term: 1)
📬 Requesting votes from peers...
✅ Vote received from Node 2
✅ Vote received from Node 3
🎉 Majority achieved (3/3 votes)
🎉 Node 1 elected as LEADER for term 1

💓 Starting heartbeat broadcast (1.5s interval)
✅ DMQ Metadata Service started successfully!
```

---

## API Endpoints

### Metadata Management

#### 1. Get Controller Info

**Endpoint**: `GET /api/v1/metadata/controller`

**Response**:
```json
{
  "controllerId": 2,
  "url": "http://localhost:9092",
  "term": 3,
  "state": "LEADER"
}
```

#### 2. Register Broker

**Endpoint**: `POST /api/v1/metadata/brokers`

**Request**:
```json
{
  "brokerId": 101,
  "host": "localhost",
  "port": 8081
}
```

**Response**:
```json
{
  "brokerId": 101,
  "host": "localhost",
  "port": 8081,
  "status": "ONLINE",
  "registeredAt": 1731345678000
}
```

#### 3. Create Topic

**Endpoint**: `POST /api/v1/metadata/topics`

**Request**:
```json
{
  "topicName": "orders",
  "numPartitions": 3,
  "replicationFactor": 2
}
```

**Response**:
```json
{
  "topicName": "orders",
  "numPartitions": 3,
  "replicationFactor": 2,
  "partitions": [
    {
      "id": 0,
      "leader": 101,
      "replicas": [102],
      "isr": [101, 102]
    },
    {
      "id": 1,
      "leader": 102,
      "replicas": [101],
      "isr": [102, 101]
    },
    {
      "id": 2,
      "leader": 101,
      "replicas": [102],
      "isr": [101, 102]
    }
  ]
}
```

#### 4. Get Cluster Metadata

**Endpoint**: `GET /api/v1/metadata/cluster`

**Response**:
```json
{
  "version": 15,
  "brokers": [
    {
      "brokerId": 101,
      "host": "localhost",
      "port": 8081,
      "status": "ONLINE"
    },
    {
      "brokerId": 102,
      "host": "localhost",
      "port": 8082,
      "status": "ONLINE"
    }
  ],
  "topics": [
    {
      "topicName": "orders",
      "numPartitions": 3,
      "partitions": [...]
    }
  ]
}
```

### Heartbeat

#### Broker Heartbeat

**Endpoint**: `POST /api/v1/metadata/heartbeat/{brokerId}`

**Request**:
```json
{
  "brokerId": 101,
  "timestamp": 1731345678000,
  "metadataVersion": 12
}
```

**Success Response (200 OK)**:
```json
{
  "ack": true,
  "currentVersion": 15,
  "controllerTerm": 3,
  "timestamp": 1731345678100
}
```

**Not Leader Response (503 Service Unavailable)**:
```
Status: 503
Headers:
  X-Controller-Leader: 3
Body: (empty)
```

### Raft Protocol

#### RequestVote RPC

**Endpoint**: `POST /api/v1/raft/vote`

**Request**:
```json
{
  "term": 4,
  "candidateId": 3,
  "lastLogIndex": 42,
  "lastLogTerm": 3
}
```

**Response**:
```json
{
  "term": 4,
  "voteGranted": true
}
```

#### AppendEntries RPC

**Endpoint**: `POST /api/v1/raft/append`

**Request**:
```json
{
  "term": 3,
  "leaderId": 2,
  "prevLogIndex": 41,
  "prevLogTerm": 3,
  "entries": [
    {
      "index": 42,
      "term": 3,
      "commandType": "CreateTopicCommand",
      "commandData": "{...}"
    }
  ],
  "leaderCommit": 41
}
```

**Response**:
```json
{
  "term": 3,
  "success": true,
  "matchIndex": 42
}
```

---

## Raft Protocol

### Leader Election

**Trigger**: Election timeout (5-7 seconds)

**Process**:
1. Increment currentTerm
2. Transition to CANDIDATE state
3. Vote for self
4. Send RequestVote RPC to all peers
5. If majority votes received → become LEADER
6. If higher term discovered → become FOLLOWER
7. If election timeout → start new election

**Election Timeout Calculation**:
```java
long electionTimeout = baseTimeout + random(0, jitterRange);
// Example: 5000ms + random(0, 2000) = 5000-7000ms
```

### Log Replication

**Trigger**: Command received by leader

**Process**:
1. Leader appends entry to local log
2. Leader sends AppendEntries RPC to all followers
3. Followers append entry if consistency check passes
4. Followers return ACK to leader
5. Leader waits for majority ACKs
6. Leader commits entry (updates commitIndex)
7. Leader applies entry to state machine
8. Leader returns response to client

**Consistency Check**:
```java
if (prevLogIndex > 0) {
    RaftLogEntry prevEntry = getLogEntry(prevLogIndex);
    if (prevEntry == null || prevEntry.getTerm() != prevLogTerm) {
        // Consistency check failed
        return AppendEntriesResponse.builder()
            .term(currentTerm)
            .success(false)
            .build();
    }
}
```

### Heartbeat Broadcasting

**Trigger**: Leader sends empty AppendEntries every 1.5 seconds

**Purpose**:
- Maintain leadership
- Prevent election timeouts on followers
- Advance commitIndex on followers

**Implementation**:
```java
@Scheduled(fixedDelayString = "${kraft.raft.heartbeat-interval-ms:1500}")
public void sendHeartbeats() {
    if (state != RaftState.LEADER) {
        return;
    }
    
    for (MetadataServiceInfo peer : peers) {
        AppendEntriesRequest request = AppendEntriesRequest.builder()
            .term(currentTerm)
            .leaderId(nodeId)
            .prevLogIndex(nextIndex.get(peer.getId()) - 1)
            .prevLogTerm(getLogEntry(prevLogIndex).getTerm())
            .entries(Collections.emptyList()) // Empty = heartbeat
            .leaderCommit(commitIndex)
            .build();
        
        raftNetworkClient.sendAppendEntries(peer.getUrl(), request);
    }
}
```

---

## Heartbeat Processing

### Sequence Diagram

```
Storage Broker              Metadata Controller
       │                            │
       │  POST /heartbeat/101       │
       ├───────────────────────────►│
       │                            │
       │                            ├─── isControllerLeader()?
       │                            │        YES → Process
       │                            │        NO  → 503 + header
       │                            │
       │                            ├─── updateHeartbeat(101, now())
       │                            │
       │                            ├─── Check broker status
       │                            │    if (OFFLINE) → ONLINE
       │                            │    Raft commit status change
       │                            │
       │  HeartbeatResponse         │
       │◄───────────────────────────┤
       │  {ack: true,               │
       │   currentVersion: 15}      │
       │                            │
```

### Health Check Cycle

```
Every 5 seconds:

HeartbeatService.checkBrokerHealth()
       │
       ├── Get current time
       │
       ├── For each broker in lastHeartbeatTime:
       │   │
       │   ├── Calculate: timeSinceLastHeartbeat
       │   │
       │   ├── If > 30 seconds:
       │   │   │
       │   │   ├── Log: ⚠️ Broker {id} missed heartbeat
       │   │   │
       │   │   ├── Create UpdateBrokerStatusCommand(OFFLINE)
       │   │   │
       │   │   └── raftController.appendEntry(command)
       │   │
       │   └── Else: Continue
       │
       └── Done
```

---

## Controller Failover

### Failover Timeline

```
Time: 0s
   Node 1 (FOLLOWER)   Node 2 (LEADER)   Node 3 (FOLLOWER)
        │                     │                   │
        │   Heartbeat         │   Heartbeat       │
        │◄────────────────────┤──────────────────►│
        │                     │                   │
Time: 1s
        │                     │                   │
        │                   ✗ CRASH ✗            │
        │                                         │
Time: 6s (Election timeout on Node 3)
        │                                         │
        │◄──── RequestVote ───────────────────── │
        ├──── VoteGranted ──────────────────────►│
        │                                         │
Time: 7s (Majority achieved - 2/3 votes)
        │                  Node 3 becomes LEADER  │
        │                  Term: 4                │
        │                                         │
        │◄──── AppendEntries (Heartbeat) ────────┤
        ├──── Success ──────────────────────────►│
        │                                         │
Time: 8s (State rebuild)
        │                                         │
        │            initHeartbeatState()         │
        │            Rebuild from database        │
        │                                         │
Time: 9s (Push notifications)
        │                                         │
        │        CONTROLLER_CHANGED push          │
        │        to all storage brokers           │
        │                                         │
```

**Total Failover Time**: 8-10 seconds

---

## Testing

### Manual Testing

#### Test 1: Leader Election

```bash
# Start all 3 nodes
# Monitor logs

# Expected: One node becomes leader within 5-10 seconds
🗳️ Node 2 starting election for term 1
✅ Vote received from Node 1
✅ Vote received from Node 3
🎉 Node 2 elected as LEADER for term 1
```

#### Test 2: Controller Failover

```bash
# 1. Identify current leader (e.g., Node 2)
curl http://localhost:9092/api/v1/metadata/controller

# 2. Kill leader (Ctrl+C in terminal)

# 3. Monitor other nodes' logs
# Expected: New leader elected within 5-10 seconds
🗳️ Node 3 starting election for term 2
🎉 Node 3 elected as LEADER for term 2
```

#### Test 3: Topic Creation

```bash
# Create topic on leader
curl -X POST http://localhost:9092/api/v1/metadata/topics \
  -H "Content-Type: application/json" \
  -d '{
    "topicName": "orders",
    "numPartitions": 3,
    "replicationFactor": 2
  }'

# Verify topic exists on all nodes
curl http://localhost:9091/api/v1/metadata/cluster
curl http://localhost:9092/api/v1/metadata/cluster
curl http://localhost:9093/api/v1/metadata/cluster
```

---

## Troubleshooting

### Issue 1: Split Vote

**Symptoms**:
```bash
🗳️ Node 1 starting election (term: 5)
❌ Lost election to Node 2
🗳️ Starting new election (term: 6)
```

**Cause**: Multiple nodes start election simultaneously.

**Solution**: Wait for next election cycle (automatic recovery).

---

### Issue 2: Log Inconsistency

**Symptoms**:
```bash
❌ AppendEntries rejected: consistency check failed
```

**Cause**: Follower's log doesn't match leader's prevLogIndex/prevLogTerm.

**Solution**: Leader automatically retries with earlier index (automatic recovery).

---

### Issue 3: Broker Not Registering

**Symptoms**:
```bash
❌ Failed to register broker: 503 Service Unavailable
```

**Cause**: Querying non-leader node.

**Solution**: Broker should use controller discovery to find leader.

---

## Monitoring

### Key Metrics

- **Election Count**: Number of elections triggered
- **Leadership Duration**: Time node is leader
- **Log Replication Lag**: Follower log lag behind leader
- **Heartbeat Success Rate**: Broker heartbeat ACK rate
- **Commit Latency**: Time from log append to commit

### Log Patterns

**Healthy Cluster**:
```bash
# Leader
💓 Heartbeat sent to followers (2/2 ACKs)
✅ Log entry committed (index: 42)

# Followers
💓 Heartbeat received from leader
✅ Log entry appended (index: 42)
```

**Unhealthy Cluster**:
```bash
❌ Heartbeat timeout from leader
🗳️ Starting election
```

---

## Related Documentation

- [Project README](../README.md) - Overall project documentation
- [Architecture Documentation](../ARCHITECTURE.md) - System architecture
- [Storage Service README](../dmq-storage-service/README.md) - Broker documentation

---

**Version**: 1.0.0  
**Last Updated**: November 2024  
**Status**: ✅ Operational