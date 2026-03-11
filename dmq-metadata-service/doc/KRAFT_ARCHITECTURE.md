# DMQ Metadata Service - KRaft Architecture

## Overview

The DMQ Metadata Service implements **KRaft mode** (Kafka Raft) for cluster coordination, eliminating the need for external ZooKeeper. This follows the modern Kafka 3.0+ architecture where metadata service nodes form a Raft quorum to elect a controller leader.

## What is KRaft Mode?

**KRaft** (Kafka Raft) is a consensus protocol based on the Raft algorithm that provides:
- **Self-managed cluster coordination** - No external ZooKeeper dependency
- **Built-in leader election** - Controller leader elected via Raft consensus
- **Replicated metadata log** - All changes committed through Raft log
- **Simplified deployment** - One service type instead of two (Kafka + ZooKeeper)

## Architecture Components

### 1. Raft Controller (`RaftController.java`)
The main Raft state machine managing:
- **Leader Election**: Implements Raft leader election algorithm
- **Log Replication**: Replicates metadata changes to followers
- **Heartbeats**: Sends periodic heartbeats to maintain leadership
- **Vote Handling**: Processes RequestVote and AppendEntries RPCs

**States**: FOLLOWER → CANDIDATE → LEADER

```java
@Service
public class RaftController {
    // TODO: Implement Raft consensus algorithm
    // - Leader election with election timeouts
    // - Log replication to followers
    // - Heartbeat mechanism
    // - Vote request/response handling
}
```

### 2. Raft State (`RaftState.java`)
Enum defining three Raft states:
- **FOLLOWER**: Default state, follows leader, responds to RPCs
- **CANDIDATE**: Requests votes during election
- **LEADER**: Handles all client requests, replicates log

### 3. Raft Log Entry (`RaftLogEntry.java`)
Structure for log entries containing metadata operations:

```java
public class RaftLogEntry {
    private long term;              // Raft term
    private long index;             // Log index
    private CommandType type;       // Operation type
    private Map<String, Object> data; // Command payload
}
```

**Command Types**:
- `CREATE_TOPIC` - Create new topic
- `DELETE_TOPIC` - Delete topic
- `UPDATE_PARTITION` - Update partition metadata
- `UPDATE_LEADER` - Change partition leader
- `REGISTER_BROKER` - Register storage broker

### 4. Metadata State Machine (`MetadataStateMachine.java`)
Applies committed log entries to metadata state:

```java
@Service
public class MetadataStateMachine {
    public void apply(RaftLogEntry entry) {
        // TODO: Apply committed entries to PostgreSQL
        switch (entry.getType()) {
            case CREATE_TOPIC -> handleCreateTopic(entry.getData());
            case DELETE_TOPIC -> handleDeleteTopic(entry.getData());
            // ...
        }
    }
}
```

### 5. Raft Node Config (`RaftNodeConfig.java`)
Configuration for cluster nodes:

```yaml
dmq:
  kraft:
    node-id: 1  # Unique node ID in quorum
    cluster:
      nodes:
        - id: 1
          host: localhost
          port: 9091
        - id: 2
          host: localhost
          port: 9092
        - id: 3
          host: localhost
          port: 9093
    raft:
      election-timeout-ms: 5000      # 5-10 seconds randomized
      heartbeat-interval-ms: 1000    # Leader sends heartbeat every 1s
      log-dir: ./data/raft-log       # Persistent Raft log storage
```

## Raft Protocol Flow

### Leader Election Process

```
1. All nodes start as FOLLOWER
2. If no heartbeat within election timeout → become CANDIDATE
3. CANDIDATE increments term, votes for self
4. CANDIDATE sends RequestVote RPC to all peers
5. If receives majority votes → become LEADER
6. LEADER sends heartbeats to prevent new elections
```

### Metadata Write Flow

```
Client → POST /api/v1/metadata/topics
         ↓
MetadataController: Check if this node is leader
         ↓
         [If not leader] → Return 503 with leader info
         [If leader] ↓
RaftController: Append entry to local log
         ↓
RaftController: Replicate to all followers (AppendEntries RPC)
         ↓
Followers: Append entry, respond with ACK
         ↓
RaftController: Wait for majority ACK (quorum)
         ↓
RaftController: Entry committed!
         ↓
MetadataStateMachine: Apply entry (save to PostgreSQL)
         ↓
Response to Client: 200 OK
```

### Metadata Read Flow

```
Client → GET /api/v1/metadata/topics/{name}
         ↓
MetadataController: Can serve from any node
         ↓
MetadataService: Query PostgreSQL
         ↓
Response: Topic metadata
```

## API Endpoints

### Controller Information
```bash
GET /api/v1/metadata/controller

Response:
{
  "isLeader": true,
  "leaderId": 1,
  "term": 5
}
```

### Create Topic (Leader Only)
```bash
POST /api/v1/metadata/topics
Content-Type: application/json

{
  "topicName": "orders",
  "numPartitions": 3,
  "replicationFactor": 2
}

# If non-leader node:
HTTP 503 Service Unavailable
X-Controller-Leader: 1
```

### Get Topic (Any Node)
```bash
GET /api/v1/metadata/topics/orders

Response:
{
  "topicName": "orders",
  "numPartitions": 3,
  "partitions": [...]
}
```

## Running a KRaft Cluster

### 3-Node Cluster Setup

**Terminal 1** (Node 1)
```bash
cd dmq-metadata-service
mvn spring-boot:run -Dspring-boot.run.arguments="--dmq.kraft.node-id=1 --server.port=8080"
```

**Terminal 2** (Node 2)
```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--dmq.kraft.node-id=2 --server.port=8081"
```

**Terminal 3** (Node 3)
```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--dmq.kraft.node-id=3 --server.port=8082"
```

### Check Controller Status
```bash
# Query each node
curl http://localhost:8080/api/v1/metadata/controller
curl http://localhost:8081/api/v1/metadata/controller
curl http://localhost:8082/api/v1/metadata/controller

# One will be leader, others followers
```

### Create Topic on Leader
```bash
# First find leader
LEADER_PORT=$(curl -s http://localhost:8080/api/v1/metadata/controller | jq -r '.leaderId * 1000 + 8080')

# Send create request to leader
curl -X POST http://localhost:${LEADER_PORT}/api/v1/metadata/topics \
  -H "Content-Type: application/json" \
  -d '{
    "topicName": "test-topic",
    "numPartitions": 3,
    "replicationFactor": 2
  }'
```

## Fault Tolerance

### Leader Failure Scenario
```
1. Leader node crashes
2. Followers detect missing heartbeats (election timeout)
3. One follower becomes CANDIDATE, requests votes
4. New leader elected from majority quorum
5. New leader continues serving requests
6. Old leader rejoins as FOLLOWER when it recovers
```

### Network Partition Scenario
```
Scenario: 3-node cluster splits into [Node1] | [Node2, Node3]

- Partition with majority (Node2, Node3) continues operating
- Node1 (minority) cannot elect leader, rejects writes
- When partition heals, Node1 syncs log from new leader
```

### Recommended Configurations
- **3 nodes**: Tolerates 1 failure
- **5 nodes**: Tolerates 2 failures
- **7 nodes**: Tolerates 3 failures (overkill for most cases)

## Implementation Status

### ✅ Completed
- KRaft configuration structure
- Raft component classes (RaftController, RaftState, etc.)
- REST API with leader check logic
- Database entities and repositories
- Service interfaces

### ⚠️ TODO (Learning Exercises)
- **RaftController**: Implement leader election algorithm
- **RaftController**: Implement log replication
- **RaftController**: Implement heartbeat mechanism
- **MetadataStateMachine**: Apply committed entries to database
- **Networking**: Implement RequestVote and AppendEntries RPCs (use Netty)
- **Persistence**: Save/load Raft log from disk
- **Snapshot**: Implement log compaction

## Comparison: KRaft vs ZooKeeper

| Feature | KRaft Mode | ZooKeeper Mode |
|---------|------------|----------------|
| **External Dependency** | None | Requires ZooKeeper ensemble |
| **Deployment Complexity** | Low (one service) | High (two services) |
| **Latency** | Lower (no external hop) | Higher (ZK coordination) |
| **Scalability** | Better (integrated) | Limited by ZK |
| **Operational Cost** | Lower | Higher |
| **Kafka Version** | 3.0+ | All versions |
| **Maturity** | New (production-ready 3.3+) | Battle-tested |

## Key Raft Concepts

### Term
- Logical clock for leadership periods
- Incremented on each election
- Prevents split-brain scenarios

### Commit Index
- Highest log index known to be committed
- Updated when majority of followers acknowledge

### Log Compaction
- Periodic snapshots to prevent unbounded log growth
- Replay from last snapshot + remaining log entries

### Election Timeout
- Randomized (e.g., 5-10 seconds)
- Prevents split votes
- Followers become candidates if no heartbeat

### Heartbeat Interval
- Leader sends periodic heartbeats (e.g., 1 second)
- Prevents unnecessary elections
- Carries log replication data (AppendEntries)

## References

- [Raft Paper](https://raft.github.io/raft.pdf) - "In Search of an Understandable Consensus Algorithm"
- [Raft Visualization](https://raft.github.io/) - Interactive demo
- [Kafka KRaft](https://kafka.apache.org/documentation/#kraft) - Official Kafka documentation
- [KIP-500](https://cwiki.apache.org/confluence/display/KAFKA/KIP-500%3A+Replace+ZooKeeper+with+a+Self-Managed+Metadata+Quorum) - Replace ZooKeeper with Raft

## Next Steps for Implementation

1. **Study Raft Algorithm**: Understand the paper and visualization
2. **Implement Leader Election**: Start with election timeout and voting logic
3. **Add Log Replication**: Implement AppendEntries RPC
4. **Test Failure Scenarios**: Kill nodes, partition network
5. **Add Persistence**: Save Raft log to disk for recovery
6. **Optimize**: Batching, pipelining, snapshots

---

**This is a learning scaffold** - Core Raft logic marked with TODOs for educational implementation.
