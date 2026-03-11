# Catch-Up Mechanism - Implementation Proposal

## Problem Statement

### Current Behavior (PUSH-only Model)
Currently, the replication system uses a **PUSH-based model**:
- ✅ Leader pushes new messages to followers via `POST /api/v1/storage/replicate`
- ✅ Circuit breaker protects against network failures
- ✅ ISRLagProcessor monitors lag and manages ISR membership

### Critical Gap
❌ **When a follower goes down and comes back, it has NO way to catch up on missed messages:**

```
Timeline:
T0: Broker 102 is follower, receiving messages (offset 0-1000)
T1: Broker 102 goes OFFLINE (network failure, crash, etc.)
T2: Leader writes messages at offsets 1001-2000 
    → Push to 102 fails (circuit breaker opens)
T3: Leader writes messages at offsets 2001-3000
    → Push to 102 still fails
T4: Broker 102 comes back ONLINE
T5: Leader pushes NEW messages (3001-4000) to 102
    ❌ Gap: Broker 102 never receives messages 1001-3000
```

**Result:**
- Follower has data gaps: [0-1000] + [3001-4000], missing [1001-3000]
- Follower remains out-of-sync forever (or until topic is deleted)
- Under-replication persists even though broker is healthy
- Data loss if leader fails before follower catches up

---

## Root Cause Analysis

### 1. **No Active Fetch Mechanism**
Followers are **passive receivers** - they only accept what the leader pushes:
```java
// Current: Follower receives pushes
@PostMapping("/replicate")
public ResponseEntity<ReplicationResponse> replicateMessages(ReplicationRequest request) {
    // Just writes what leader sends, no awareness of gaps
    storageService.appendMessages(request.getTopic(), request.getPartition(), request.getMessages());
}
```

### 2. **Leader Doesn't Track Follower Offsets**
Leader only knows:
- Which brokers are in ISR (from metadata)
- Whether current push succeeded/failed (circuit breaker)

Leader does NOT know:
- ❌ What offset each follower is at
- ❌ If follower has gaps in its log
- ❌ Whether follower needs historical data

### 3. **WAL Can Read Historical Data But Isn't Used for Replication**
The `WriteAheadLog.read(startOffset, maxMessages)` exists but is only used for consumer reads:
```java
// WAL has the capability to read from any offset
public List<Message> read(long startOffset, int maxMessages) {
    // Can read historical messages from segments
}
```

**But this is NEVER used for follower catch-up!**

---

## Proposed Solutions

I propose **3 options** (from simplest to most complete):

---

## ✅ **Option 1: Leader-Driven Catch-Up (Simpler, Recommended)**

### Overview
Leader actively detects lagging followers and pushes missing data to them.

### Components

#### 1.1. Follower Reports Current Offset
Modify lag reporting to include current log position:

**File:** `dmq-common/src/main/java/com/distributedmq/common/dto/ISRLagReport.java`
```java
@Data
public static class PartitionLag {
    private String topic;
    private Integer partition;
    
    // NEW: Follower's current position
    private Long followerLogEndOffset;  // What offset follower is at
    
    // Existing fields
    private Long offsetLag;
    private Long timeSinceCaughtUp;
    private Boolean isOutOfSync;
}
```

#### 1.2. Leader Tracks Follower Progress
**File:** `dmq-storage-service/src/main/java/com/distributedmq/storage/replication/FollowerProgressTracker.java` (NEW)
```java
@Component
public class FollowerProgressTracker {
    // Track each follower's LEO per partition
    private Map<String, Map<Integer, Long>> followerOffsets; // brokerId -> (partition -> LEO)
    
    public void updateFollowerProgress(Integer brokerId, String topic, Integer partition, Long leo) {
        // Update tracking
    }
    
    public boolean isFollowerLagging(Integer brokerId, String topic, Integer partition, Long leaderLEO) {
        Long followerLEO = getFollowerLEO(brokerId, topic, partition);
        return (leaderLEO - followerLEO) > CATCH_UP_THRESHOLD; // e.g., 100 messages
    }
    
    public long getFollowerLEO(Integer brokerId, String topic, Integer partition) {
        // Return follower's last known offset
    }
}
```

#### 1.3. Leader Initiates Catch-Up Replication
**File:** `dmq-storage-service/src/main/java/com/distributedmq/storage/replication/CatchUpReplicator.java` (NEW)
```java
@Component
@Slf4j
public class CatchUpReplicator {
    
    @Scheduled(fixedDelay = 5000) // Check every 5 seconds
    public void scanForLaggingFollowers() {
        if (!isLeader()) return;
        
        for (Partition partition : myLeaderPartitions) {
            for (Integer followerId : partition.getReplicas()) {
                if (progressTracker.isFollowerLagging(followerId, topic, partition, leaderLEO)) {
                    log.info("Follower {} is lagging for {}-{}, initiating catch-up", 
                        followerId, topic, partition);
                    
                    catchUpFollower(followerId, topic, partition);
                }
            }
        }
    }
    
    private void catchUpFollower(Integer followerId, String topic, Integer partition) {
        long followerLEO = progressTracker.getFollowerLEO(followerId, topic, partition);
        long leaderLEO = wal.getLogEndOffset();
        
        log.info("Catch-up replication: follower {} at offset {}, leader at {}, gap: {}", 
            followerId, followerLEO, leaderLEO, (leaderLEO - followerLEO));
        
        long currentOffset = followerLEO;
        while (currentOffset < leaderLEO) {
            // Read batch from WAL
            List<Message> messages = wal.read(currentOffset, BATCH_SIZE); // e.g., 1000 messages
            
            if (messages.isEmpty()) break;
            
            // Push to follower
            ReplicationRequest request = buildCatchUpRequest(topic, partition, messages, currentOffset);
            boolean success = replicationManager.replicateToFollower(followerId, request);
            
            if (!success) {
                log.warn("Catch-up failed for follower {}, will retry next cycle", followerId);
                break;
            }
            
            currentOffset += messages.size();
            progressTracker.updateFollowerProgress(followerId, topic, partition, currentOffset);
        }
        
        log.info("Catch-up complete for follower {}: {} -> {}", followerId, followerLEO, currentOffset);
    }
}
```

#### 1.4. Update ISRLagProcessor
**File:** `dmq-metadata-service/src/main/java/com/distributedmq/metadata/service/ISRLagProcessor.java`
```java
private void processPartitionLag(ISRLagReport.PartitionLag lag, Integer brokerId) {
    // ... existing code ...
    
    // NEW: Send follower's LEO to storage leader
    metadataService.updateFollowerProgress(brokerId, topic, partition, lag.getFollowerLogEndOffset());
}
```

### Pros & Cons
✅ **Pros:**
- Leader-controlled (simpler coordination)
- No new endpoints needed
- Works with existing PUSH model
- Leader has visibility into follower progress

❌ **Cons:**
- Leader does extra work (reading from WAL for catch-up)
- Polling-based (5-second intervals)
- Leader needs to track all follower offsets

---

## ✅ **Option 2: Follower-Driven PULL (More like Kafka)**

### Overview
Followers actively fetch missing data from the leader when they detect gaps.

### Components

#### 2.1. New Fetch Endpoint on Leader
**File:** `dmq-storage-service/src/main/java/com/distributedmq/storage/controller/StorageController.java`
```java
/**
 * NEW: Follower pulls historical messages from leader
 * Endpoint: POST /api/v1/storage/fetch-replication
 */
@PostMapping("/fetch-replication")
public ResponseEntity<FetchReplicationResponse> fetchReplicationData(
        @Validated @RequestBody FetchReplicationRequest request) {
    
    log.info("Follower {} requesting catch-up data for {}-{} from offset {}", 
        request.getFollowerId(), request.getTopic(), request.getPartition(), request.getStartOffset());
    
    // Validate requester is a replica
    if (!isReplica(request.getFollowerId(), request.getTopic(), request.getPartition())) {
        return ResponseEntity.status(403).body(
            FetchReplicationResponse.error("Not a replica for this partition"));
    }
    
    // Read from WAL
    List<Message> messages = storageService.readMessagesForReplication(
        request.getTopic(), 
        request.getPartition(), 
        request.getStartOffset(), 
        request.getMaxMessages()
    );
    
    return ResponseEntity.ok(FetchReplicationResponse.builder()
        .topic(request.getTopic())
        .partition(request.getPartition())
        .messages(messages)
        .startOffset(request.getStartOffset())
        .endOffset(request.getStartOffset() + messages.size())
        .leaderLEO(storageService.getLogEndOffset(request.getTopic(), request.getPartition()))
        .success(true)
        .build());
}
```

#### 2.2. New DTOs
**File:** `dmq-common/src/main/java/com/distributedmq/common/dto/FetchReplicationRequest.java` (NEW)
```java
@Data
@Builder
public class FetchReplicationRequest {
    private String topic;
    private Integer partition;
    private Integer followerId;
    private Long startOffset;      // Offset to start fetching from
    private Integer maxMessages;   // Max messages to fetch (default 1000)
}
```

**File:** `dmq-common/src/main/java/com/distributedmq/common/dto/FetchReplicationResponse.java` (NEW)
```java
@Data
@Builder
public class FetchReplicationResponse {
    private String topic;
    private Integer partition;
    private List<ProduceRequest.ProduceMessage> messages;
    private Long startOffset;
    private Long endOffset;
    private Long leaderLEO;
    private Boolean success;
    private String errorMessage;
}
```

#### 2.3. Follower Catch-Up Service
**File:** `dmq-storage-service/src/main/java/com/distributedmq/storage/replication/FollowerCatchUpService.java` (NEW)
```java
@Component
@Slf4j
public class FollowerCatchUpService {
    
    @Scheduled(fixedDelay = 10000) // Check every 10 seconds
    public void checkAndCatchUp() {
        // For each partition where I'm a follower
        for (Partition partition : myFollowerPartitions) {
            Long myLEO = wal.getLogEndOffset(partition.getTopic(), partition.getPartition());
            Long leaderLEO = metadataStore.getLeaderLEO(partition.getTopic(), partition.getPartition());
            
            if (leaderLEO > myLEO + CATCH_UP_THRESHOLD) {
                log.info("I'm lagging for {}-{}: my LEO {}, leader LEO {}, catching up...", 
                    partition.getTopic(), partition.getPartition(), myLEO, leaderLEO);
                
                catchUpFromLeader(partition, myLEO, leaderLEO);
            }
        }
    }
    
    private void catchUpFromLeader(Partition partition, long myLEO, long leaderLEO) {
        BrokerInfo leader = metadataStore.getLeaderBroker(partition.getTopic(), partition.getPartition());
        
        long currentOffset = myLEO;
        while (currentOffset < leaderLEO) {
            // Fetch batch from leader
            FetchReplicationRequest request = FetchReplicationRequest.builder()
                .topic(partition.getTopic())
                .partition(partition.getPartition())
                .followerId(config.getBroker().getId())
                .startOffset(currentOffset)
                .maxMessages(1000)
                .build();
            
            FetchReplicationResponse response = fetchFromLeader(leader, request);
            
            if (!response.getSuccess() || response.getMessages().isEmpty()) {
                log.warn("Failed to fetch from leader, will retry later");
                break;
            }
            
            // Write to local WAL
            storageService.appendMessagesAsFollower(
                partition.getTopic(), 
                partition.getPartition(), 
                response.getMessages()
            );
            
            currentOffset = response.getEndOffset();
            
            log.info("Caught up batch: {} -> {}", response.getStartOffset(), response.getEndOffset());
        }
        
        log.info("Catch-up complete for {}-{}: {} -> {}", 
            partition.getTopic(), partition.getPartition(), myLEO, currentOffset);
    }
    
    private FetchReplicationResponse fetchFromLeader(BrokerInfo leader, FetchReplicationRequest request) {
        String url = String.format("http://%s:%d/api/v1/storage/fetch-replication", 
            leader.getHost(), leader.getPort());
        
        return restTemplate.postForObject(url, request, FetchReplicationResponse.class);
    }
}
```

### Pros & Cons
✅ **Pros:**
- More like Kafka's pull-based replication
- Follower self-heals without leader intervention
- Leader doesn't need to track follower offsets
- Scalable (followers pull on-demand)

❌ **Cons:**
- More complex (new endpoints, DTOs, coordination)
- Requires metadata about leader's LEO
- Potential thundering herd if many followers lag

---

## ✅ **Option 3: Hybrid (Leader PUSH + Follower PULL)**

### Overview
Combine both approaches:
- **Normal operation:** Leader PUSH (existing)
- **Catch-up:** Follower PULL (when gap detected)
- **Monitoring:** Leader tracks progress and can trigger catch-up

### Best of Both Worlds
- Leader maintains awareness (for ISR management)
- Follower can self-heal (resilience)
- Graceful degradation (if one mechanism fails, other works)

---

## 📊 Comparison Matrix

| Feature | Option 1 (Leader PUSH) | Option 2 (Follower PULL) | Option 3 (Hybrid) |
|---------|----------------------|-------------------------|-------------------|
| **Complexity** | Low ⭐ | Medium | High |
| **Leader Load** | High | Low ⭐ | Medium |
| **Scalability** | Medium | High ⭐ | High ⭐ |
| **Kafka-like** | No | Yes ⭐ | Yes ⭐ |
| **Self-Healing** | No | Yes ⭐ | Yes ⭐ |
| **Code Changes** | ~300 lines | ~500 lines | ~700 lines |
| **New Endpoints** | 0 ⭐ | 1 | 1 |

---

## 🎯 Recommendation: **Option 1 (Leader-Driven PUSH)**

### Why?
1. **Minimal Changes:** Leverages existing PUSH infrastructure
2. **Simpler Coordination:** Leader already manages ISR
3. **Quick Win:** Can implement in 1-2 days
4. **Safe:** Leader has full visibility into replication state

### Later Migration Path
If needed, can evolve to Option 3 (Hybrid):
1. ✅ Start with Option 1 (get catch-up working)
2. ✅ Add Option 2 endpoints (for follower self-heal)
3. ✅ Use both mechanisms (redundancy)

---

## 📝 Implementation Checklist (Option 1)

### Phase 1: Follower Progress Tracking
- [ ] Add `followerLogEndOffset` to `ISRLagReport.PartitionLag`
- [ ] Create `FollowerProgressTracker` component
- [ ] Update `ISRLagProcessor` to track follower offsets
- [ ] Add REST endpoint to receive follower progress from metadata service

### Phase 2: Leader Catch-Up Logic
- [ ] Create `CatchUpReplicator` scheduled service
- [ ] Implement `scanForLaggingFollowers()` method
- [ ] Implement `catchUpFollower()` method using WAL reads
- [ ] Add configuration for catch-up thresholds

### Phase 3: Testing & Monitoring
- [ ] Unit tests for `FollowerProgressTracker`
- [ ] Integration test: broker goes down and comes back
- [ ] Metrics: catch-up operations, bytes replicated, time to catch up
- [ ] Logging: detailed catch-up progress

### Phase 4: Edge Cases
- [ ] Handle: Follower never reports progress (use heartbeat as fallback)
- [ ] Handle: WAL segment deleted before catch-up (fail, mark out-of-sync)
- [ ] Handle: Leader changes during catch-up (abort, retry with new leader)
- [ ] Rate limiting: Don't overload leader with too many catch-ups

---

## 🔥 Quick Start (Option 1 Implementation)

### Step 1: Modify DTOs
```bash
# File: dmq-common/src/main/java/com/distributedmq/common/dto/ISRLagReport.java
# Add followerLogEndOffset field to PartitionLag
```

### Step 2: Create New Components
```bash
# File: dmq-storage-service/.../replication/FollowerProgressTracker.java
# File: dmq-storage-service/.../replication/CatchUpReplicator.java
```

### Step 3: Wire Up Services
```bash
# Update: ISRLagProcessor to send follower offsets
# Update: ReplicationManager to expose follower tracking
```

### Step 4: Test
```bash
# 1. Start 3 brokers (101, 102, 103)
# 2. Create topic with RF=3
# 3. Produce 1000 messages
# 4. Stop broker 102
# 5. Produce 1000 more messages (102 misses these)
# 6. Start broker 102
# 7. Verify: 102 catches up and rejoins ISR
```

---

## ❓ Questions for Approval

1. **Which option do you prefer?** (1, 2, or 3)
2. **Catch-up threshold:** How many messages behind triggers catch-up? (Recommend: 100)
3. **Catch-up batch size:** How many messages per batch? (Recommend: 1000)
4. **Catch-up interval:** How often to scan? (Recommend: 5 seconds)
5. **Priority:** Should I implement this now or after other features?

---

## 🚨 Impact on Current System

### What Changes:
- ✅ Followers can now recover from downtime
- ✅ Under-replication automatically resolves
- ✅ ISR expansion works properly

### What Stays the Same:
- ✅ Normal operation (PUSH replication)
- ✅ ISR shrink logic
- ✅ Leader election
- ✅ Heartbeat monitoring

### No Breaking Changes:
- ✅ Backward compatible
- ✅ No config changes required
- ✅ Existing topics/data unaffected

---

**Ready to proceed?** Please approve your preferred option and I'll implement it! 🚀
