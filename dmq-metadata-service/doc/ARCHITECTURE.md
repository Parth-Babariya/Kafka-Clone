# DMQ Metadata Service Architecture

## Table of Contents

1. [Overview](#overview)
2. [Raft Consensus Architecture](#raft-consensus-architecture)
3. [Component Design](#component-design)
4. [State Machine Design](#state-machine-design)
5. [Log Replication Architecture](#log-replication-architecture)
6. [Leader Election Architecture](#leader-election-architecture)
7. [Heartbeat & Health Monitoring](#heartbeat--health-monitoring)
8. [Metadata State Machine](#metadata-state-machine)
9. [Thread Safety & Concurrency](#thread-safety--concurrency)
10. [Failure Scenarios & Recovery](#failure-scenarios--recovery)

---

## Overview

The DMQ Metadata Service implements **Raft consensus protocol** for distributed metadata management in KRaft mode. The architecture prioritizes strong consistency, fault tolerance, and automatic recovery with a 3-node cluster providing F=1 fault tolerance.

### Design Principles

- **Strong Consistency**: Raft guarantees linearizable operations
- **Leader-Based**: Single leader simplifies coordination
- **Majority Consensus**: Requires 2/3 nodes for commit
- **Persistent Log**: Survives crashes and restarts
- **Automatic Recovery**: Self-healing with minimal downtime

### High-Level Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                    Raft Consensus Layer                        │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │              RaftController (State Machine)               │ │
│  │  ┌────────────────────────────────────────────────────┐  │ │
│  │  │  Persistent State:                                 │  │ │
│  │  │  • currentTerm: 3                                  │  │ │
│  │  │  • votedFor: 2                                     │  │ │
│  │  │  • log[]: [entry1, entry2, ...]                    │  │ │
│  │  └────────────────────────────────────────────────────┘  │ │
│  │  ┌────────────────────────────────────────────────────┐  │ │
│  │  │  Volatile State:                                   │  │ │
│  │  │  • state: LEADER                                   │  │ │
│  │  │  • commitIndex: 42                                 │  │ │
│  │  │  • lastApplied: 42                                 │  │ │
│  │  └────────────────────────────────────────────────────┘  │ │
│  │  ┌────────────────────────────────────────────────────┐  │ │
│  │  │  Leader State:                                     │  │ │
│  │  │  • nextIndex[peer]: index of next log entry       │  │ │
│  │  │  • matchIndex[peer]: highest known replicated     │  │ │
│  │  └────────────────────────────────────────────────────┘  │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │              RaftLogPersistence                           │ │
│  │  • Database-backed persistent storage                    │ │
│  │  • Survives crashes and restarts                         │ │
│  │  • Indexed by log entry number                           │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                │
└────────────────────────────────────────────────────────────────┘
                           │
                           │ Apply committed entries
                           ▼
┌────────────────────────────────────────────────────────────────┐
│                    Application Layer                           │
├────────────────────────────────────────────────────────────────┤
│  ┌──────────────────────────────────────────────────────────┐ │
│  │            MetadataStateMachine                           │ │
│  │  • RegisterBrokerCommand                                 │ │
│  │  • CreateTopicCommand                                    │ │
│  │  • UpdateBrokerStatusCommand                             │ │
│  │  • UpdatePartitionLeaderCommand                          │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │            Database (Metadata Tables)                     │ │
│  │  • brokers                                               │ │
│  │  • topics                                                │ │
│  │  • partitions                                            │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

---

## Raft Consensus Architecture

### State Diagram

```
┌─────────────────┐
│    FOLLOWER     │◄─────────────────────────────────┐
│  • Follow leader│                                  │
│  • Vote in      │                                  │
│    elections    │                                  │
└────────┬────────┘                                  │
         │                                           │
         │ Election timeout                          │
         │ (5-7 seconds)                            │
         │                                           │
         ▼                                           │
┌─────────────────┐                                  │
│   CANDIDATE     │                                  │
│  • Request votes│                                  │
│  • Vote for self│                                  │
└────────┬────────┘                                  │
         │                                           │
         │ Receive votes from majority               │
         │ (2/3 nodes)                              │
         │                                           │
         ▼                                           │
┌─────────────────┐                                  │
│     LEADER      │                                  │
│  • Accept client│                                  │
│    requests     │                                  │
│  • Replicate log│                                  │
│  • Send         │                                  │
│    heartbeats   │                                  │
└────────┬────────┘                                  │
         │                                           │
         │ Discover higher term                      │
         │ OR lose leadership                        │
         └───────────────────────────────────────────┘
```

### Raft Variables

#### Persistent State (Survives Crashes)

```java
// Latest term server has seen
private volatile Integer currentTerm = 0;

// CandidateId that received vote in current term (null = none)
private volatile Integer votedFor = null;

// Log entries (each entry contains command and term)
private RaftLogPersistence logPersistence;
```

#### Volatile State (All Servers)

```java
// Index of highest log entry known to be committed
private volatile long commitIndex = 0;

// Index of highest log entry applied to state machine
private volatile long lastApplied = 0;
```

#### Volatile State (Leader Only)

```java
// For each peer: index of next log entry to send
private final Map<Integer, Long> nextIndex = new ConcurrentHashMap<>();

// For each peer: index of highest log entry known to be replicated
private final Map<Integer, Long> matchIndex = new ConcurrentHashMap<>();
```

### Raft RPC Messages

#### RequestVote RPC

**Sent by**: Candidate during election  
**Received by**: All peers

```java
@Data
@Builder
public class RequestVoteRequest {
    private Integer term;           // Candidate's term
    private Integer candidateId;    // Candidate requesting vote
    private Long lastLogIndex;      // Index of candidate's last log entry
    private Long lastLogTerm;       // Term of candidate's last log entry
}

@Data
@Builder
public class RequestVoteResponse {
    private Integer term;           // Current term (for candidate to update itself)
    private Boolean voteGranted;    // True means candidate received vote
}
```

**Vote Granting Rules**:
1. Reject if `request.term < currentTerm`
2. Reject if already voted for different candidate in this term
3. Reject if candidate's log is less up-to-date than receiver's log
4. Otherwise: Grant vote

#### AppendEntries RPC

**Sent by**: Leader (heartbeat or log replication)  
**Received by**: Followers

```java
@Data
@Builder
public class AppendEntriesRequest {
    private Integer term;               // Leader's term
    private Integer leaderId;           // Leader's ID
    private Long prevLogIndex;          // Index of log entry immediately preceding new ones
    private Long prevLogTerm;           // Term of prevLogIndex entry
    private List<RaftLogEntry> entries; // Log entries to store (empty for heartbeat)
    private Long leaderCommit;          // Leader's commitIndex
}

@Data
@Builder
public class AppendEntriesResponse {
    private Integer term;               // Current term (for leader to update itself)
    private Boolean success;            // True if follower contained entry matching prevLogIndex/prevLogTerm
    private Long matchIndex;            // Highest log index follower has (for leader's matchIndex update)
}
```

**Consistency Check**:
```java
if (prevLogIndex > 0) {
    RaftLogEntry prevEntry = logPersistence.getLogEntry(prevLogIndex);
    
    // Reject if log doesn't contain entry at prevLogIndex with prevLogTerm
    if (prevEntry == null || !prevEntry.getTerm().equals(prevLogTerm)) {
        return AppendEntriesResponse.builder()
            .term(currentTerm)
            .success(false)
            .build();
    }
}
```

---

## Component Design

### 1. RaftController

**Package**: `com.distributedmq.metadata.coordination`

**Responsibilities**:
- Raft state machine implementation
- Leader election management
- Log replication coordination
- Heartbeat broadcasting
- Vote handling

#### Class Structure

```java
@Component
public class RaftController {
    
    // Dependencies
    private final RaftLogPersistence logPersistence;
    private final MetadataStateMachine stateMachine;
    private final RaftNetworkClient networkClient;
    private final RaftNodeConfig nodeConfig;
    
    // Persistent state
    private volatile Integer currentTerm = 0;
    private volatile Integer votedFor = null;
    
    // Volatile state
    private volatile RaftState state = RaftState.FOLLOWER;
    private volatile long commitIndex = 0;
    private volatile long lastApplied = 0;
    private volatile Long lastHeartbeatTime = System.currentTimeMillis();
    
    // Leader state
    private final Map<Integer, Long> nextIndex = new ConcurrentHashMap<>();
    private final Map<Integer, Long> matchIndex = new ConcurrentHashMap<>();
    
    // Election timeout (randomized)
    private volatile long electionTimeoutMs;
    
    // Key methods
    public void startElection() { /* ... */ }
    public void becomeLeader() { /* ... */ }
    public void becomeFollower(Integer term) { /* ... */ }
    public long appendEntry(Object command) { /* ... */ }
    public void advanceCommitIndex() { /* ... */ }
    public void applyCommittedEntries() { /* ... */ }
}
```

#### State Transitions

```java
// FOLLOWER → CANDIDATE
public void startElection() {
    synchronized (this) {
        currentTerm++;
        state = RaftState.CANDIDATE;
        votedFor = nodeConfig.getNodeId();
        resetElectionTimeout();
        
        log.info("🗳️ Node {} starting election for term {}", 
            nodeConfig.getNodeId(), currentTerm);
    }
    
    // Request votes from all peers
    requestVotes();
}

// CANDIDATE → LEADER
public void becomeLeader() {
    synchronized (this) {
        state = RaftState.LEADER;
        
        // Initialize leader state
        long lastLogIndex = logPersistence.getLastLogIndex();
        for (Integer peerId : nodeConfig.getPeerIds()) {
            nextIndex.put(peerId, lastLogIndex + 1);
            matchIndex.put(peerId, 0L);
        }
        
        log.info("🎉 Node {} elected as LEADER for term {}", 
            nodeConfig.getNodeId(), currentTerm);
    }
    
    // Start sending heartbeats
    startHeartbeats();
}

// ANY → FOLLOWER
public void becomeFollower(Integer term) {
    synchronized (this) {
        if (term > currentTerm) {
            currentTerm = term;
            votedFor = null;
        }
        
        state = RaftState.FOLLOWER;
        resetElectionTimeout();
        
        log.info("🔄 Node {} transitioned to FOLLOWER (term: {})", 
            nodeConfig.getNodeId(), currentTerm);
    }
}
```

---

### 2. RaftLogPersistence

**Package**: `com.distributedmq.metadata.coordination`

**Responsibilities**:
- Persistent log storage
- Log entry CRUD operations
- Log consistency maintenance

#### Database Schema

```sql
CREATE TABLE raft_log (
    log_index BIGINT PRIMARY KEY,
    term INTEGER NOT NULL,
    command_type VARCHAR(255) NOT NULL,
    command_data TEXT NOT NULL,
    timestamp BIGINT NOT NULL,
    INDEX idx_term (term),
    INDEX idx_timestamp (timestamp)
);
```

#### Key Operations

```java
@Component
public class RaftLogPersistence {
    
    @Autowired
    private RaftLogRepository repository;
    
    // Append entry to log
    public synchronized RaftLogEntry saveLog(RaftLogEntry entry) {
        return repository.save(entry);
    }
    
    // Get specific entry
    public RaftLogEntry getLogEntry(long index) {
        return repository.findById(index).orElse(null);
    }
    
    // Get range of entries
    public List<RaftLogEntry> getLogEntries(long startIndex, long endIndex) {
        return repository.findByLogIndexBetween(startIndex, endIndex);
    }
    
    // Get last log index
    public long getLastLogIndex() {
        return repository.findTopByOrderByLogIndexDesc()
            .map(RaftLogEntry::getLogIndex)
            .orElse(0L);
    }
    
    // Get last log term
    public Integer getLastLogTerm() {
        return repository.findTopByOrderByLogIndexDesc()
            .map(RaftLogEntry::getTerm)
            .orElse(0);
    }
    
    // Truncate log from index (conflict resolution)
    public synchronized void deleteLogsFrom(long index) {
        repository.deleteByLogIndexGreaterThanEqual(index);
    }
}
```

#### Log Entry Structure

```java
@Entity
@Table(name = "raft_log")
@Data
@Builder
public class RaftLogEntry {
    @Id
    private Long logIndex;          // Sequential log entry number
    
    private Integer term;           // Term when entry was created
    
    private String commandType;     // Type of command (e.g., "CreateTopicCommand")
    
    @Column(length = 10000)
    private String commandData;     // JSON-serialized command
    
    private Long timestamp;         // Creation timestamp
}
```

---

### 3. MetadataStateMachine

**Package**: `com.distributedmq.metadata.coordination`

**Responsibilities**:
- Apply committed Raft log entries
- Execute metadata commands
- Update database state

#### Command Processing

```java
@Component
public class MetadataStateMachine {
    
    @Autowired
    private BrokerRepository brokerRepository;
    
    @Autowired
    private TopicRepository topicRepository;
    
    @Autowired
    private PartitionRepository partitionRepository;
    
    public void apply(RaftLogEntry entry) {
        try {
            String commandType = entry.getCommandType();
            String commandData = entry.getCommandData();
            
            log.info("🎯 Applying command: {} (index: {})", commandType, entry.getLogIndex());
            
            switch (commandType) {
                case "RegisterBrokerCommand":
                    applyRegisterBroker(commandData);
                    break;
                    
                case "CreateTopicCommand":
                    applyCreateTopic(commandData);
                    break;
                    
                case "UpdateBrokerStatusCommand":
                    applyUpdateBrokerStatus(commandData);
                    break;
                    
                case "UpdatePartitionLeaderCommand":
                    applyUpdatePartitionLeader(commandData);
                    break;
                    
                default:
                    log.warn("⚠️ Unknown command type: {}", commandType);
            }
            
            log.info("✅ Command applied successfully");
            
        } catch (Exception e) {
            log.error("❌ Failed to apply command: {}", e.getMessage());
        }
    }
    
    private void applyRegisterBroker(String commandData) {
        RegisterBrokerCommand command = parseCommand(commandData, RegisterBrokerCommand.class);
        
        Broker broker = Broker.builder()
            .id(command.getBrokerId())
            .host(command.getHost())
            .port(command.getPort())
            .status(BrokerStatus.ONLINE)
            .registeredAt(System.currentTimeMillis())
            .lastHeartbeatTime(System.currentTimeMillis())
            .build();
        
        brokerRepository.save(broker);
        
        log.info("📝 Broker {} registered: {}:{}", 
            broker.getId(), broker.getHost(), broker.getPort());
    }
    
    private void applyCreateTopic(String commandData) {
        CreateTopicCommand command = parseCommand(commandData, CreateTopicCommand.class);
        
        // Create topic
        Topic topic = Topic.builder()
            .name(command.getTopicName())
            .numPartitions(command.getNumPartitions())
            .replicationFactor(command.getReplicationFactor())
            .createdAt(System.currentTimeMillis())
            .build();
        
        topicRepository.save(topic);
        
        // Assign partitions to brokers
        List<Broker> onlineBrokers = brokerRepository.findByStatus(BrokerStatus.ONLINE);
        assignPartitions(topic, onlineBrokers);
        
        log.info("📝 Topic '{}' created with {} partitions", 
            topic.getName(), topic.getNumPartitions());
    }
}
```

---

## State Machine Design

### State Lifecycle

```
Application Startup
       │
       ▼
Load Raft Log from Database
       │
       ├── lastLogIndex: 42
       ├── lastLogTerm: 3
       └── currentTerm: 3
       │
       ▼
Initialize State Machine
       │
       ├── commitIndex: 0
       ├── lastApplied: 0
       └── state: FOLLOWER
       │
       ▼
Start Election Timeout Timer
       │
       ▼
┌──────────────────────────────────────┐
│  Wait for Heartbeat or Timeout       │
├──────────────────────────────────────┤
│                                      │
│  ┌──── Heartbeat Received ──────┐   │
│  │  • Reset election timeout     │   │
│  │  • Update commitIndex         │   │
│  │  • Apply committed entries    │   │
│  └──────────────────────────────┘   │
│                                      │
│  ┌──── Election Timeout ────────┐   │
│  │  • Increment currentTerm      │   │
│  │  • Transition to CANDIDATE    │   │
│  │  • Request votes              │   │
│  └──────────────────────────────┘   │
│                                      │
└──────────────────────────────────────┘
```

### Election Timeout Handling

```java
@Scheduled(fixedDelay = 100) // Check every 100ms
public void checkElectionTimeout() {
    if (state == RaftState.LEADER) {
        return; // Leaders don't timeout
    }
    
    long timeSinceLastHeartbeat = System.currentTimeMillis() - lastHeartbeatTime;
    
    if (timeSinceLastHeartbeat > electionTimeoutMs) {
        log.info("⏱️ Election timeout ({} ms), starting election", electionTimeoutMs);
        startElection();
    }
}

private void resetElectionTimeout() {
    // Randomize timeout to prevent split votes
    long baseTimeout = nodeConfig.getElectionTimeoutMs();
    long jitter = (long) (Math.random() * nodeConfig.getElectionTimeoutJitterMs());
    this.electionTimeoutMs = baseTimeout + jitter;
    this.lastHeartbeatTime = System.currentTimeMillis();
    
    log.debug("⏱️ Election timeout set: {} ms", electionTimeoutMs);
}
```

---

## Log Replication Architecture

### Leader's Log Replication Flow

```
Client Request (e.g., Create Topic)
       │
       ▼
Leader: appendEntry(command)
       │
       ├── 1. Append to local log
       │   └── logIndex: 43, term: 3, command: CreateTopicCommand
       │
       ├── 2. Send AppendEntries RPC to all followers
       │   ├──► Follower 1
       │   └──► Follower 2
       │
       ├── 3. Wait for majority ACKs (2/3 nodes)
       │   ├── Follower 1: ACK ✅
       │   └── Follower 2: ACK ✅
       │
       ├── 4. Advance commitIndex
       │   └── commitIndex = 43
       │
       ├── 5. Apply to state machine
       │   └── stateMachine.apply(entry)
       │
       └── 6. Return response to client
```

### Implementation

```java
public long appendEntry(Object command) {
    if (state != RaftState.LEADER) {
        throw new NotLeaderException("Only leader can append entries");
    }
    
    // 1. Create log entry
    RaftLogEntry entry = RaftLogEntry.builder()
        .logIndex(logPersistence.getLastLogIndex() + 1)
        .term(currentTerm)
        .commandType(command.getClass().getSimpleName())
        .commandData(serializeCommand(command))
        .timestamp(System.currentTimeMillis())
        .build();
    
    // 2. Append to local log
    logPersistence.saveLog(entry);
    
    log.info("📝 Appended entry to log: index={}, term={}, command={}", 
        entry.getLogIndex(), entry.getTerm(), entry.getCommandType());
    
    // 3. Replicate to followers
    replicateToFollowers(entry);
    
    // 4. Wait for majority acknowledgment
    waitForMajorityAck(entry.getLogIndex());
    
    // 5. Commit and apply
    advanceCommitIndex();
    applyCommittedEntries();
    
    return entry.getLogIndex();
}

private void replicateToFollowers(RaftLogEntry entry) {
    List<CompletableFuture<Boolean>> futures = new ArrayList<>();
    
    for (Integer peerId : nodeConfig.getPeerIds()) {
        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
            try {
                // Get entries to send (may include previous entries if follower is behind)
                long peerNextIndex = nextIndex.get(peerId);
                List<RaftLogEntry> entriesToSend = getEntriesFrom(peerNextIndex);
                
                // Build AppendEntries request
                AppendEntriesRequest request = AppendEntriesRequest.builder()
                    .term(currentTerm)
                    .leaderId(nodeConfig.getNodeId())
                    .prevLogIndex(peerNextIndex - 1)
                    .prevLogTerm(getLogEntryTerm(peerNextIndex - 1))
                    .entries(entriesToSend)
                    .leaderCommit(commitIndex)
                    .build();
                
                // Send RPC
                String peerUrl = nodeConfig.getPeerUrl(peerId);
                AppendEntriesResponse response = networkClient.sendAppendEntries(peerUrl, request);
                
                // Process response
                if (response.isSuccess()) {
                    // Update leader's view of follower's log
                    nextIndex.put(peerId, entry.getLogIndex() + 1);
                    matchIndex.put(peerId, entry.getLogIndex());
                    return true;
                } else {
                    // Consistency check failed, decrement nextIndex and retry
                    nextIndex.put(peerId, Math.max(1, peerNextIndex - 1));
                    return false;
                }
                
            } catch (Exception e) {
                log.error("❌ Failed to replicate to peer {}: {}", peerId, e.getMessage());
                return false;
            }
        });
        
        futures.add(future);
    }
    
    // Wait for all RPCs to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
}
```

### Follower's Log Replication Handling

```java
public AppendEntriesResponse handleAppendEntries(AppendEntriesRequest request) {
    // 1. Check term
    if (request.getTerm() < currentTerm) {
        return AppendEntriesResponse.builder()
            .term(currentTerm)
            .success(false)
            .build();
    }
    
    // 2. Become follower if term is higher
    if (request.getTerm() > currentTerm) {
        becomeFollower(request.getTerm());
    }
    
    // 3. Reset election timeout (received heartbeat)
    resetElectionTimeout();
    
    // 4. Consistency check
    if (request.getPrevLogIndex() > 0) {
        RaftLogEntry prevEntry = logPersistence.getLogEntry(request.getPrevLogIndex());
        
        if (prevEntry == null || !prevEntry.getTerm().equals(request.getPrevLogTerm())) {
            log.warn("❌ Consistency check failed at index {}", request.getPrevLogIndex());
            return AppendEntriesResponse.builder()
                .term(currentTerm)
                .success(false)
                .build();
        }
    }
    
    // 5. Append entries
    if (!request.getEntries().isEmpty()) {
        for (RaftLogEntry entry : request.getEntries()) {
            // Delete conflicting entries
            RaftLogEntry existing = logPersistence.getLogEntry(entry.getLogIndex());
            if (existing != null && !existing.getTerm().equals(entry.getTerm())) {
                logPersistence.deleteLogsFrom(entry.getLogIndex());
            }
            
            // Append entry
            logPersistence.saveLog(entry);
        }
    }
    
    // 6. Update commitIndex
    if (request.getLeaderCommit() > commitIndex) {
        commitIndex = Math.min(request.getLeaderCommit(), logPersistence.getLastLogIndex());
        applyCommittedEntries();
    }
    
    // 7. Return success
    return AppendEntriesResponse.builder()
        .term(currentTerm)
        .success(true)
        .matchIndex(logPersistence.getLastLogIndex())
        .build();
}
```

---

## Leader Election Architecture

### Election Process

```
Follower (Election Timeout)
       │
       ▼
Increment currentTerm
       │
       ▼
Transition to CANDIDATE
       │
       ▼
Vote for Self
       │
       ▼
Send RequestVote RPC to All Peers
       │
       ├───► Peer 1
       ├───► Peer 2
       └───► Peer 3
       │
       ▼
Wait for Responses
       │
       ├── Response 1: VoteGranted ✅
       ├── Response 2: VoteGranted ✅
       └── Response 3: Timeout ❌
       │
       ▼
Count Votes (3/3 = self + 2 peers)
       │
       ├── Majority? (2/3) YES ✅
       │
       ▼
Become LEADER
       │
       ├── Initialize nextIndex[]
       ├── Initialize matchIndex[]
       └── Start heartbeat broadcast
```

### Vote Granting Logic

```java
public RequestVoteResponse handleRequestVote(RequestVoteRequest request) {
    // 1. Check term
    if (request.getTerm() < currentTerm) {
        return RequestVoteResponse.builder()
            .term(currentTerm)
            .voteGranted(false)
            .build();
    }
    
    // 2. Update term if candidate's term is higher
    if (request.getTerm() > currentTerm) {
        becomeFollower(request.getTerm());
    }
    
    // 3. Check if already voted
    if (votedFor != null && !votedFor.equals(request.getCandidateId())) {
        log.info("❌ Already voted for {} in term {}", votedFor, currentTerm);
        return RequestVoteResponse.builder()
            .term(currentTerm)
            .voteGranted(false)
            .build();
    }
    
    // 4. Check if candidate's log is at least as up-to-date
    long lastLogIndex = logPersistence.getLastLogIndex();
    Integer lastLogTerm = logPersistence.getLastLogTerm();
    
    boolean candidateLogUpToDate = 
        (request.getLastLogTerm() > lastLogTerm) ||
        (request.getLastLogTerm().equals(lastLogTerm) && request.getLastLogIndex() >= lastLogIndex);
    
    if (!candidateLogUpToDate) {
        log.info("❌ Candidate's log not up-to-date");
        return RequestVoteResponse.builder()
            .term(currentTerm)
            .voteGranted(false)
            .build();
    }
    
    // 5. Grant vote
    votedFor = request.getCandidateId();
    resetElectionTimeout();
    
    log.info("✅ Granted vote to candidate {} in term {}", votedFor, currentTerm);
    
    return RequestVoteResponse.builder()
        .term(currentTerm)
        .voteGranted(true)
        .build();
}
```

### Split Vote Prevention

**Randomized Election Timeout**:
```java
private void resetElectionTimeout() {
    long baseTimeout = 5000; // 5 seconds
    long jitter = (long) (Math.random() * 2000); // 0-2 seconds
    this.electionTimeoutMs = baseTimeout + jitter; // 5-7 seconds
}
```

**Why This Works**:
- Different nodes timeout at different times
- First node to timeout becomes candidate
- Others receive RequestVote before their timeout
- Reduces probability of multiple simultaneous candidates

---

## Heartbeat & Health Monitoring

### Heartbeat Broadcasting (Leader)

```java
@Scheduled(fixedDelayString = "${kraft.raft.heartbeat-interval-ms:1500}")
public void sendHeartbeats() {
    if (state != RaftState.LEADER) {
        return;
    }
    
    log.debug("💓 Sending heartbeats to followers");
    
    for (Integer peerId : nodeConfig.getPeerIds()) {
        try {
            // Empty AppendEntries = heartbeat
            AppendEntriesRequest request = AppendEntriesRequest.builder()
                .term(currentTerm)
                .leaderId(nodeConfig.getNodeId())
                .prevLogIndex(nextIndex.get(peerId) - 1)
                .prevLogTerm(getLogEntryTerm(nextIndex.get(peerId) - 1))
                .entries(Collections.emptyList()) // Empty!
                .leaderCommit(commitIndex)
                .build();
            
            String peerUrl = nodeConfig.getPeerUrl(peerId);
            networkClient.sendAppendEntries(peerUrl, request);
            
        } catch (Exception e) {
            log.warn("❌ Failed to send heartbeat to peer {}: {}", peerId, e.getMessage());
        }
    }
}
```

### Broker Health Monitoring

```java
@Service
public class HeartbeatService {
    
    private final Map<Integer, Long> lastHeartbeatTime = new ConcurrentHashMap<>();
    private static final long HEARTBEAT_TIMEOUT_MS = 30_000; // 30 seconds
    
    @Scheduled(fixedDelay = 5000) // Check every 5 seconds
    public void checkBrokerHealth() {
        if (!raftController.isControllerLeader()) {
            return; // Only leader monitors health
        }
        
        long currentTime = System.currentTimeMillis();
        
        for (Map.Entry<Integer, Long> entry : lastHeartbeatTime.entrySet()) {
            Integer brokerId = entry.getKey();
            Long lastHeartbeat = entry.getValue();
            long timeSinceLastHeartbeat = currentTime - lastHeartbeat;
            
            if (timeSinceLastHeartbeat > HEARTBEAT_TIMEOUT_MS) {
                log.warn("⚠️ Broker {} missed heartbeat ({} ms since last)", 
                    brokerId, timeSinceLastHeartbeat);
                
                markBrokerOffline(brokerId);
            }
        }
    }
    
    private void markBrokerOffline(Integer brokerId) {
        try {
            UpdateBrokerStatusCommand command = UpdateBrokerStatusCommand.builder()
                .brokerId(brokerId)
                .status(BrokerStatus.OFFLINE)
                .timestamp(System.currentTimeMillis())
                .build();
            
            raftController.appendEntry(command);
            
            log.info("❌ Marked Broker {} as OFFLINE", brokerId);
            
            // Remove from tracking
            lastHeartbeatTime.remove(brokerId);
            
        } catch (Exception e) {
            log.error("❌ Failed to mark broker {} offline: {}", brokerId, e.getMessage());
        }
    }
    
    public void updateHeartbeat(Integer brokerId, Long timestamp) {
        lastHeartbeatTime.put(brokerId, timestamp);
    }
    
    @PostConstruct
    public void initHeartbeatState() {
        if (raftController.isControllerLeader()) {
            // Rebuild state from database after leader election
            List<Broker> brokers = brokerRepository.findByStatus(BrokerStatus.ONLINE);
            for (Broker broker : brokers) {
                lastHeartbeatTime.put(broker.getId(), broker.getLastHeartbeatTime());
            }
            log.info("🔧 Rebuilt heartbeat state for {} brokers", brokers.size());
        }
    }
}
```

---

## Metadata State Machine

### Command Types

```java
// Broker Registration
@Data
@Builder
public class RegisterBrokerCommand {
    private Integer brokerId;
    private String host;
    private Integer port;
}

// Topic Creation
@Data
@Builder
public class CreateTopicCommand {
    private String topicName;
    private Integer numPartitions;
    private Integer replicationFactor;
}

// Broker Status Update
@Data
@Builder
public class UpdateBrokerStatusCommand {
    private Integer brokerId;
    private BrokerStatus status;
    private Long timestamp;
}

// Partition Leader Update
@Data
@Builder
public class UpdatePartitionLeaderCommand {
    private String topicName;
    private Integer partitionId;
    private Integer newLeader;
}
```

### State Application

```
Raft Log Entry Committed (commitIndex advanced)
       │
       ▼
applyCommittedEntries()
       │
       ├── For each entry from lastApplied+1 to commitIndex:
       │   │
       │   ├── Get entry from log
       │   │
       │   ├── stateMachine.apply(entry)
       │   │   │
       │   │   ├── Deserialize command
       │   │   ├── Execute command logic
       │   │   └── Update database
       │   │
       │   └── Update lastApplied
       │
       └── Log: ✅ Applied entries {lastApplied} to {commitIndex}
```

---

## Thread Safety & Concurrency

### Synchronization Strategy

**State Transitions**: `synchronized` blocks
```java
public synchronized void startElection() {
    currentTerm++;
    state = RaftState.CANDIDATE;
    votedFor = nodeId;
    // ...
}
```

**Volatile Fields**: Cross-thread visibility
```java
private volatile Integer currentTerm;
private volatile Integer votedFor;
private volatile RaftState state;
private volatile long commitIndex;
```

**Concurrent Collections**: Thread-safe maps
```java
private final Map<Integer, Long> nextIndex = new ConcurrentHashMap<>();
private final Map<Integer, Long> matchIndex = new ConcurrentHashMap<>();
```

### Race Condition Prevention

**Problem**: Multiple threads accessing Raft state

**Solution**:
1. Synchronize state transitions
2. Use volatile for frequently-read fields
3. Use concurrent collections for leader state
4. Single-threaded log persistence operations

---

## Failure Scenarios & Recovery

### Scenario 1: Leader Crash

**Timeline**:
1. Leader crashes (Node 2)
2. Followers detect missing heartbeats (5-7 seconds)
3. First follower times out, starts election
4. Majority votes, new leader elected (Node 3)
5. New leader rebuilds heartbeat state from database
6. Push CONTROLLER_CHANGED to all brokers
7. Brokers switch to new leader on next heartbeat

**Total Downtime**: 8-10 seconds

---

### Scenario 2: Network Partition

**Minority Partition** (1 node):
- Cannot elect leader (needs 2/3 votes)
- Stays in FOLLOWER state
- Cannot process writes
- Rejoins when partition heals

**Majority Partition** (2 nodes):
- Elects leader normally
- Processes writes
- Continues operations

---

### Scenario 3: Log Inconsistency

**Problem**: Follower has conflicting entries

**Recovery**:
1. Leader sends AppendEntries with prevLogIndex/prevLogTerm
2. Follower fails consistency check
3. Leader decrements nextIndex for follower
4. Leader retries with earlier entries
5. Follower deletes conflicting entries
6. Follower appends correct entries
7. Logs synchronized

---

## Summary

The DMQ Metadata Service architecture implements production-grade Raft consensus with:

✅ **Strong Consistency**: Majority-based commit ensures linearizability  
✅ **Fault Tolerance**: Tolerates F=1 failures with 3-node cluster  
✅ **Automatic Recovery**: Self-healing within 8-10 seconds  
✅ **Persistent Log**: Survives crashes with database-backed storage  
✅ **Thread Safety**: Synchronized state transitions, volatile fields  
✅ **Observability**: Comprehensive logging with emoji indicators  

**Key Innovations**:
1. Database-backed Raft log (persistent across restarts)
2. Heartbeat state rebuild after failover (no data loss)
3. Leader validation in heartbeat endpoint (prevents split-brain)
4. Push notifications for controller changes (faster broker awareness)

---

**Version**: 1.0.0  
**Last Updated**: November 2024  