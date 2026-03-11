# Consumer & Consumer Group Implementation Guide

## Overview

This document describes the complete consumer and consumer group implementation in the Distributed Message Queue (DMQ) system, including detailed flows, heartbeat mechanisms, offset management, and rebalancing strategies.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Consumer Types](#consumer-types)
3. [Consumer Group Flow](#consumer-group-flow)
4. [Subscription & Assignment](#subscription--assignment)
5. [Message Consumption (Poll)](#message-consumption-poll)
6. [Offset Management](#offset-management)
7. [Heartbeat Mechanism](#heartbeat-mechanism)
8. [Rebalancing Process](#rebalancing-process)
9. [Consumer Group Coordination](#consumer-group-coordination)
10. [API Reference](#api-reference)
11. [Configuration](#configuration)
12. [Error Handling](#error-handling)

---

## Architecture Overview

### Components

```
┌─────────────────┐      ┌──────────────────────┐      ┌─────────────────┐
│   DMQConsumer   │─────▶│  Metadata Service    │      │  Storage Broker │
│  (Client-Side)  │      │  (Controller Raft)   │      │  (Group Leader) │
└─────────────────┘      └──────────────────────┘      └─────────────────┘
        │                          │                              │
        │  1. Find/Create Group    │                              │
        ├─────────────────────────▶│                              │
        │  2. Get Group Leader     │                              │
        │◀─────────────────────────┤                              │
        │                          │                              │
        │  3. Join Group           │                              │
        ├──────────────────────────┼─────────────────────────────▶│
        │  4. Get Partition        │                              │
        │     Assignment           │                              │
        │◀─────────────────────────┼──────────────────────────────┤
        │                          │                              │
        │  5. Periodic Heartbeat   │                              │
        ├──────────────────────────┼─────────────────────────────▶│
        │                          │                              │
        │  6. Fetch Messages       │                              │
        ├──────────────────────────┼─────────────────────────────▶│
        │                          │                              │
        │  7. Commit Offsets       │                              │
        ├──────────────────────────┼─────────────────────────────▶│
        │                          │                              │
```

### Key Components

1. **DMQConsumer (Client)**: Consumer client library
2. **SimpleConsumer (Client)**: Low-level partition consumer
3. **Metadata Service**: Consumer group registry and controller
4. **Storage Broker (Group Leader)**: Consumer group coordinator
5. **ConsumerGroupManager (Broker)**: Manages group membership
6. **ConsumerHeartbeatMonitor (Broker)**: Tracks consumer liveness
7. **RebalanceScheduler (Broker)**: Triggers rebalancing

---

## Consumer Types

### 1. Simple Consumer (Partition-Level)

Direct partition consumption without consumer groups.

**Use Case**: Testing, debugging, specific partition reading

**Example**:
```java
SimpleConsumer consumer = new SimpleConsumer(metadataServiceUrl);
List<Message> messages = consumer.consume(topic, partition, offset, maxRecords);
consumer.close();
```

**Characteristics**:
- Direct partition access
- Manual offset management
- No automatic rebalancing
- No group coordination overhead

### 2. Consumer Group Consumer (DMQConsumer)

High-level consumer with automatic partition assignment and rebalancing.

**Use Case**: Production applications, parallel processing, fault tolerance

**Example**:
```java
ConsumerConfig config = ConsumerConfig.builder()
    .metadataServiceUrl("http://localhost:9091")
    .groupId("order-processor")
    .heartbeatIntervalMs(3000)
    .sessionTimeoutMs(10000)
    .enableAutoCommit(true)
    .build();

DMQConsumer consumer = new DMQConsumer(config);
consumer.subscribe("orders");

while (running) {
    ConsumerRecords records = consumer.poll(Duration.ofSeconds(5));
    for (ConsumerRecord record : records) {
        process(record);
    }
    consumer.commitSync(); // Manual commit
}

consumer.close();
```

**Characteristics**:
- Automatic partition assignment
- Consumer group coordination
- Automatic rebalancing
- Background heartbeat
- Offset management (manual or auto-commit)

---

## Consumer Group Flow

### Phase 1: Group Discovery & Registration

```
Consumer                Metadata Service (Controller)
   │                              │
   │  POST /find-or-create        │
   │  { topic, appId }           │
   ├────────────────────────────▶│
   │                              │
   │  Check if group exists       │
   │  (topic, appId) -> groupId   │
   │                              │
   │  If NOT exists:              │
   │    - Generate groupId        │
   │    - Select group leader     │
   │      broker (random)         │
   │    - Submit to Raft          │
   │    - Persist to DB           │
   │                              │
   │  Response:                   │
   │  { groupId,                  │
   │    groupLeaderBrokerId,      │
   │    groupLeaderUrl }          │
   │◀────────────────────────────┤
   │                              │
```

**Details**:
- **Group ID Format**: `G_<topic>_<appId>`
- **Example**: `G_orders_order-processor`
- **Group Leader Selection**: Random online broker (future: co-location with partition leaders)
- **Raft Consensus**: Group registration via `RegisterConsumerGroupCommand`

### Phase 2: Consumer Registration (Join Group)

```
Consumer                Group Leader Broker
   │                              │
   │  POST /join-group            │
   │  { groupId, consumerId }     │
   ├────────────────────────────▶│
   │                              │
   │  ConsumerGroupManager:       │
   │  - Register consumer         │
   │  - Trigger rebalance if      │
   │    new member               │
   │  - Generate memberId         │
   │                              │
   │  Response:                   │
   │  { memberId,                 │
   │    rebalanceRequired }       │
   │◀────────────────────────────┤
   │                              │
   │  GET /assignment/{memberId}  │
   ├────────────────────────────▶│
   │                              │
   │  Wait for rebalance          │
   │  to complete...              │
   │                              │
   │  Response:                   │
   │  { assignedPartitions: [0,2] }│
   │◀────────────────────────────┤
   │                              │
```

**Details**:
- **Consumer ID**: Generated client-side (UUID)
- **Member ID**: Assigned by group leader broker
- **Rebalance Trigger**: New member, member failure, member leave
- **Assignment Strategy**: Range-based partition assignment

### Phase 3: Background Heartbeat

```
DMQConsumer (Background Thread)       Group Leader Broker
   │                                          │
   │  Every 3 seconds:                        │
   │  POST /heartbeat                         │
   │  { groupId, memberId }                   │
   ├─────────────────────────────────────────▶│
   │                                          │
   │  ConsumerHeartbeatMonitor:               │
   │  - Update lastHeartbeat timestamp        │
   │  - Return current generation             │
   │                                          │
   │  Response: { generation: 6, success }    │
   │◀─────────────────────────────────────────┤
   │                                          │
   │  Consumer checks generation:             │
   │  if (responseGeneration > currentGen)    │
   │      then REBALANCE DETECTED             │
   │                                          │
```

**Details**:
- **Heartbeat Interval**: 3000ms (configurable via `heartbeatIntervalMs`)
- **Session Timeout**: 10000ms (configurable via `sessionTimeoutMs`)
- **Failure Detection**: If no heartbeat for > sessionTimeout, member considered dead
- **Background Thread**: Daemon thread handles heartbeats automatically
- **Generation ID**: Increments on each rebalance, used to detect stale consumers

**Generation-Based Rebalance Detection**:

The generation ID is a monotonically increasing counter maintained by the group leader broker. Every time a rebalance occurs (member joins, leaves, or fails), the generation increments:

```
Initial State:
  - Consumer 1, Consumer 2, Consumer 3 are stable
  - Current Generation: 5
  - All consumers know generation = 5

Event: Consumer 2 fails (heartbeat timeout)
  - Broker increments generation: 5 → 6
  - Broker triggers rebalance
  - New partition assignment calculated

Consumer 1 sends heartbeat:
  Request:  { groupId, memberId, generation: 5 }
  Response: { generation: 6, success: true }
  
Consumer 1 detects mismatch:
  if (6 > 5) {
      // Rebalance happened!
      fetchNewAssignment();
      currentGeneration = 6;
  }
```

**Why Generation-Based Detection?**

1. **Passive Notification**: Consumers don't need a separate rebalance notification API
2. **Piggyback on Heartbeat**: Uses existing heartbeat mechanism
3. **Eventually Consistent**: All consumers discover rebalance within one heartbeat interval (3s)
4. **Stale Request Protection**: Prevents commits/fetches with old partition assignments

### Consumer Response to Generation Change**:

```java
private void sendHeartbeat() {
    HeartbeatRequest request = HeartbeatRequest.builder()
        .groupId(groupId)
        .memberId(memberId)
        .build();
    
    HeartbeatResponse response = httpClient.post(
        groupLeaderUrl + "/api/v1/consumer-group/heartbeat",
        request
    );
    
    // Generation mismatch = rebalance occurred
    if (response.getGeneration() > currentGeneration) {
        log.info("Rebalance detected! Old gen: {}, New gen: {}", 
                 currentGeneration, response.getGeneration());
        
        // 1. Commit current offsets (save progress)
        commitSync();
        
        // 2. Fetch new partition assignment
        AssignmentResponse assignment = fetchAssignment(memberId);
        
        // 3. Update local state
        assignedPartitions.clear();
        assignedPartitions.addAll(assignment.getPartitions());
        currentGeneration = response.getGeneration();
        
        // 4. Initialize offsets for new partitions
        for (Integer partition : assignedPartitions) {
            if (!currentOffsets.containsKey(partition)) {
                Long committedOffset = fetchCommittedOffset(partition);
                currentOffsets.put(partition, committedOffset != null ? committedOffset : 0L);
            }
        }
        
        log.info("Rebalance complete. New assignment: {}, Generation: {}", 
                 assignedPartitions, currentGeneration);
    }
}
```

**Broker-Side Generation Management**:

```java
// ConsumerGroupManager
public synchronized void rebalance(String groupId) {
    ConsumerGroup group = groups.get(groupId);
    
    // Increment generation (marks all consumers as stale)
    int newGeneration = group.getGeneration() + 1;
    group.setGeneration(newGeneration);
    
    log.info("Group {} rebalancing: generation {} → {}", 
             groupId, group.getGeneration() - 1, newGeneration);
    
    // Recalculate partition assignments
    Map<String, List<Integer>> newAssignments = calculateAssignments(
        group.getMembers(), 
        group.getTopic()
    );
    
    group.setAssignments(newAssignments);
    group.setState(ConsumerGroupState.STABLE);
    
    log.info("Rebalance complete. Generation: {}, Members: {}", 
             newGeneration, group.getMembers().size());
}
```

---

## Subscription & Assignment

### Subscribe Flow

```java
// Client Code
consumer.subscribe("orders");
```

**Internal Flow**:

```
1. Consumer discovers group:
   - POST /find-or-create → Get group leader broker

2. Consumer joins group:
   - POST /join-group → Get memberId
   
3. Consumer gets assignment:
   - GET /assignment/{memberId} → Get assigned partitions
   
4. Consumer stores assignment:
   - assignedPartitions = [0, 2, 4]
   
5. Background heartbeat starts:
   - Every 3s → POST /heartbeat
```

### Partition Assignment Strategy

**Range-Based Assignment** (ConsumerGroupManager):

```java
// Example: Topic with 10 partitions, 3 consumers

Consumer 1: [0, 1, 2, 3]  // 4 partitions
Consumer 2: [4, 5, 6]     // 3 partitions  
Consumer 3: [7, 8, 9]     // 3 partitions

// Algorithm:
int partitionsPerConsumer = totalPartitions / totalConsumers;
int extraPartitions = totalPartitions % totalConsumers;

for (int i = 0; i < consumers.size(); i++) {
    int start = i * partitionsPerConsumer + Math.min(i, extraPartitions);
    int numPartitions = partitionsPerConsumer + (i < extraPartitions ? 1 : 0);
    assign(consumer[i], range(start, start + numPartitions));
}
```

---

## Message Consumption (Poll)

### Poll Flow

```java
ConsumerRecords records = consumer.poll(Duration.ofSeconds(5));
```

**Internal Flow**:

```
1. Check assigned partitions:
   if (assignedPartitions.isEmpty()) {
       return empty records;
   }

2. For each assigned partition:
   - Get current offset from tracking map
   - Fetch messages using SimpleConsumer
   - Aggregate results
   
3. Update offset tracking:
   - currentOffsets[partition] = lastFetchedOffset + 1
   
4. Return ConsumerRecords
```

**Implementation**:

```java
public ConsumerRecords poll(Duration timeout) {
    if (assignedPartitions.isEmpty()) {
        return new ConsumerRecords(Collections.emptyList());
    }

    List<ConsumerRecord> allRecords = new ArrayList<>();
    
    for (Integer partition : assignedPartitions) {
        Long currentOffset = currentOffsets.getOrDefault(partition, 0L);
        
        // Fetch from storage broker via SimpleConsumer
        List<Message> messages = simpleConsumer.consume(
            subscribedTopic, 
            partition, 
            currentOffset, 
            maxPollRecords
        );
        
        // Convert to ConsumerRecords
        for (Message msg : messages) {
            allRecords.add(new ConsumerRecord(
                subscribedTopic, partition, msg.getOffset(),
                msg.getKey(), msg.getValue(), msg.getTimestamp()
            ));
            
            // Track highest offset
            currentOffsets.put(partition, msg.getOffset() + 1);
        }
    }
    
    return new ConsumerRecords(allRecords);
}
```

### Impact of Atomic Batch WAL Writes

**New Optimization (November 2025)**: Producer writes are now atomic batch operations when `batch-write-enabled: true` in broker configuration.

**Consumer Impact**: ✅ **NONE** - Consumers are completely unaffected

**Why No Changes?**
1. **Same File Format**: Batch writes produce identical on-disk format
2. **Sequential Offsets**: Messages still have consecutive offsets
3. **HWM Semantics**: High Water Mark behavior unchanged
4. **Read Path**: `WAL.read()` handles batched writes identically

**Consumer Reads Batch-Written Messages**:
```
Producer writes batch of 10 messages atomically:
  - Single I/O operation on broker
  - Messages get offsets 100-109
  - Stored with same format as individual writes

Consumer reads messages:
  - consumer.poll() reads offsets 100-109
  - No awareness of how they were written
  - Same sequential iteration
  - No performance difference for reads
```

**Performance**: Consumers actually benefit from faster producer writes (higher message availability)

---

## Offset Management

### Offset Tracking

**Client-Side Tracking**:
```java
// Map: partition -> next offset to fetch
private final Map<Integer, Long> currentOffsets = new ConcurrentHashMap<>();

// Map: partition -> last committed offset
private final Map<Integer, Long> committedOffsets = new ConcurrentHashMap<>();
```

### Manual Commit (Sync)

```java
consumer.commitSync();
```

**Flow**:
```
1. Prepare commit request:
   - Collect (partition, offset) pairs from currentOffsets
   
2. Send to group leader broker:
   POST /commit-offsets
   {
     "groupId": "G_orders_order-processor",
     "memberId": "member-123",
     "offsets": {
       "0": 100,
       "2": 250,
       "4": 175
     }
   }
   
3. Broker persists offsets:
   - Update in-memory map
   - Persist to storage (file/db)
   
4. Update committedOffsets locally:
   - committedOffsets.putAll(currentOffsets)
```

### Manual Commit (Async)

```java
consumer.commitAsync();
```

**Flow**:
```
1. Same as sync but non-blocking:
   - Uses CompletableFuture
   - Returns immediately
   
2. Callback on completion:
   - Success: Update committedOffsets
   - Failure: Log error, retry on next commit
```

### Auto-Commit

**Configuration**:
```java
ConsumerConfig.builder()
    .enableAutoCommit(true)  // Default: true
    .build();
```

**Behavior**:
- Auto-commit happens **before each poll()**
- Commits offsets from previous poll()
- No explicit commitSync()/commitAsync() needed

**Implementation**:
```java
public ConsumerRecords poll(Duration timeout) {
    // Auto-commit previous poll's offsets
    if (enableAutoCommit && !currentOffsets.isEmpty()) {
        commitAsync();
    }
    
    // Fetch new messages
    return fetchMessages();
}
```

---

## Heartbeat Mechanism

### Heartbeat Thread

**Startup**:
```java
private void startHeartbeatThread() {
    heartbeatThread = new Thread(() -> {
        while (!closed && !Thread.currentThread().isInterrupted()) {
            try {
                sendHeartbeat();
                Thread.sleep(heartbeatIntervalMs); // Default: 3000ms
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                log.error("Heartbeat failed", e);
            }
        }
    }, "consumer-heartbeat-" + consumerId);
    
    heartbeatThread.setDaemon(true);
    heartbeatThread.start();
}
```

### Heartbeat Request

```java
private void sendHeartbeat() {
    HeartbeatRequest request = HeartbeatRequest.builder()
        .groupId(groupId)
        .memberId(memberId)
        .generation(currentGeneration)
        .build();
    
    HeartbeatResponse response = httpClient.post(
        groupLeaderUrl + "/api/v1/consumer-group/heartbeat",
        request
    );
    
    if (response.getGeneration() > currentGeneration) {
        // Rebalance detected, fetch new assignment
        fetchNewAssignment();
    }
}
```

### Broker-Side Heartbeat Processing

**ConsumerHeartbeatMonitor**:

```java
public synchronized HeartbeatResponse processHeartbeat(
    String groupId, 
    String memberId
) {
    ConsumerGroup group = groups.get(groupId);
    ConsumerMember member = group.getMember(memberId);
    
    // Update last heartbeat timestamp
    member.setLastHeartbeat(System.currentTimeMillis());
    
    return HeartbeatResponse.builder()
        .success(true)
        .generation(group.getGeneration())
        .build();
}
```

**Periodic Failure Detection**:

```java
@Scheduled(fixedRate = 5000) // Every 5 seconds
public void detectFailedConsumers() {
    long now = System.currentTimeMillis();
    
    for (ConsumerGroup group : groups.values()) {
        List<String> failedMembers = new ArrayList<>();
        
        for (ConsumerMember member : group.getMembers()) {
            long timeSinceHeartbeat = now - member.getLastHeartbeat();
            
            if (timeSinceHeartbeat > sessionTimeoutMs) {
                failedMembers.add(member.getMemberId());
            }
        }
        
        if (!failedMembers.isEmpty()) {
            // Remove failed members
            for (String memberId : failedMembers) {
                group.removeMember(memberId);
            }
            
            // Trigger rebalance
            rebalanceScheduler.scheduleRebalance(group.getGroupId());
        }
    }
}
```

---

## Rebalancing Process

### Rebalance Triggers

1. **New Consumer Joins**: POST /join-group with new consumerId
2. **Consumer Leaves**: POST /leave-group or close()
3. **Consumer Fails**: Heartbeat timeout detected
4. **Partition Changes**: Topic partition count modified (future)

### Rebalance Flow

```
1. Rebalance Triggered
   │
   ├─▶ RebalanceScheduler.scheduleRebalance(groupId)
   │
   │
2. Increment Generation
   │
   ├─▶ generation++
   │   (all consumers become "stale" until they fetch new assignment)
   │
   │
3. Recalculate Partitions
   │
   ├─▶ ConsumerGroupManager.rebalance()
   │   - Get active members (recent heartbeat)
   │   - Get topic partition count
   │   - Apply range assignment strategy
   │   - Store new assignments
   │
   │
4. Notify Consumers (Passive)
   │
   ├─▶ Consumers detect via heartbeat response:
   │   - if (response.generation > currentGeneration)
   │   - Fetch new assignment
   │   - Update assignedPartitions
   │   - Continue polling
   │
   │
5. Cleanup Old Assignments
   │
   └─▶ Clear assignments for removed members
```

### Rebalance Example

**Scenario**: 3 consumers, 10 partitions, Consumer 2 fails

```
Initial State:
  Consumer 1 (member-1): [0, 1, 2, 3]
  Consumer 2 (member-2): [4, 5, 6]     ← FAILS
  Consumer 3 (member-3): [7, 8, 9]
  Generation: 5

After Rebalance:
  Consumer 1 (member-1): [0, 1, 2, 3, 4]
  Consumer 3 (member-3): [5, 6, 7, 8, 9]
  Generation: 6
  
Consumer 2 partitions [4, 5, 6] reassigned to surviving members
```

### Consumer-Side Rebalance Handling

```java
// Heartbeat response indicates rebalance
if (response.getGeneration() > currentGeneration) {
    log.info("Rebalance detected. Fetching new assignment...");
    
    // Commit current offsets before rebalance
    commitSync();
    
    // Fetch new assignment
    AssignmentResponse assignment = fetchAssignment(memberId);
    
    // Update local state
    assignedPartitions.clear();
    assignedPartitions.addAll(assignment.getPartitions());
    currentGeneration = assignment.getGeneration();
    
    // Reset offsets for new partitions
    for (Integer partition : assignedPartitions) {
        if (!currentOffsets.containsKey(partition)) {
            // Start from last committed or beginning
            Long committed = fetchCommittedOffset(partition);
            currentOffsets.put(partition, committed != null ? committed : 0L);
        }
    }
    
    log.info("Rebalance complete. New assignment: {}", assignedPartitions);
}
```

---

## Consumer Group Coordination

### Group States

```java
public enum ConsumerGroupState {
    EMPTY,              // No members
    STABLE,             // Members assigned, no rebalance
    PREPARING_REBALANCE, // Rebalance scheduled
    REBALANCING         // Actively rebalancing
}
```

### Group Lifecycle

```
1. EMPTY
   │
   │  First consumer joins
   │
   ├─▶ PREPARING_REBALANCE
   │
   │  Rebalance triggered (after 500ms delay)
   │
   ├─▶ REBALANCING
   │
   │  Assignments calculated and stored
   │
   ├─▶ STABLE
   │
   │  Consumer fails or new consumer joins
   │
   ├─▶ PREPARING_REBALANCE
   │
   │  ...cycle continues...
   │
   │  All consumers leave
   │
   └─▶ EMPTY
```

### RebalanceScheduler

**Delayed Rebalance** (avoids rapid rebalances):

```java
public synchronized void scheduleRebalance(String groupId) {
    // Cancel existing rebalance task
    ScheduledFuture<?> existing = scheduledRebalances.get(groupId);
    if (existing != null && !existing.isDone()) {
        existing.cancel(false);
    }
    
    // Schedule new rebalance after delay
    ScheduledFuture<?> task = scheduler.schedule(
        () -> executeRebalance(groupId),
        500, // 500ms delay
        TimeUnit.MILLISECONDS
    );
    
    scheduledRebalances.put(groupId, task);
}

private void executeRebalance(String groupId) {
    consumerGroupManager.rebalance(groupId);
}
```

---

## API Reference

### Consumer Client API (DMQConsumer)

```java
// Constructor
public DMQConsumer(ConsumerConfig config)

// Subscribe to topic (triggers group join)
public void subscribe(String topic)

// Poll for messages
public ConsumerRecords poll(Duration timeout)

// Commit offsets synchronously
public void commitSync()

// Commit offsets asynchronously
public CompletableFuture<Void> commitAsync()

// Close consumer (leaves group)
public void close()
```

### Metadata Service API

```java
// Find or create consumer group
POST /api/v1/metadata/consumer-groups/find-or-create
Request: { "topic": "orders", "appId": "order-processor" }
Response: { 
  "groupId": "G_orders_order-processor",
  "groupLeaderBrokerId": 101,
  "groupLeaderUrl": "localhost:8081"
}

// List all groups
GET /api/v1/metadata/consumer-groups
Response: [
  { "groupId": "G_orders_order-processor", "topic": "orders", ... },
  ...
]

// Describe group
GET /api/v1/metadata/consumer-groups/{groupId}
Response: {
  "groupId": "G_orders_order-processor",
  "topic": "orders",
  "appId": "order-processor",
  "groupLeaderBrokerId": 101,
  "groupLeaderUrl": "localhost:8081",
  "createdAt": 1700000000000,
  "lastModifiedAt": 1700000000000
}

// Delete group (called by broker when last member leaves)
DELETE /api/v1/metadata/consumer-groups/{groupId}?brokerId=101
```

### Storage Broker API (Group Leader)

```java
// Join consumer group
POST /api/v1/consumer-group/join
Request: { "groupId": "G_orders_order-processor", "consumerId": "uuid" }
Response: { 
  "memberId": "member-1", 
  "rebalanceRequired": true 
}

// Get partition assignment
GET /api/v1/consumer-group/assignment/{memberId}
Response: { 
  "partitions": [0, 2, 4], 
  "generation": 5 
}

// Send heartbeat
POST /api/v1/consumer-group/heartbeat
Request: { "groupId": "G_orders_order-processor", "memberId": "member-1" }
Response: { 
  "success": true, 
  "generation": 5 
}

// Commit offsets
POST /api/v1/consumer-group/commit-offsets
Request: {
  "groupId": "G_orders_order-processor",
  "memberId": "member-1",
  "offsets": { "0": 100, "2": 250 }
}
Response: { "success": true }

// Leave group
POST /api/v1/consumer-group/leave
Request: { "groupId": "G_orders_order-processor", "memberId": "member-1" }
Response: { "success": true }
```

---

## Configuration

### ConsumerConfig

```java
ConsumerConfig config = ConsumerConfig.builder()
    // Required
    .metadataServiceUrl("http://localhost:9091")
    .groupId("order-processor")
    
    // Optional (with defaults)
    .heartbeatIntervalMs(3000)      // Heartbeat frequency
    .sessionTimeoutMs(10000)         // Failure detection timeout
    .maxPollRecords(500)             // Max records per poll
    .enableAutoCommit(true)          // Auto-commit offsets
    .build();
```

### Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `metadataServiceUrl` | Required | Bootstrap metadata service URL |
| `groupId` | Required | Consumer group ID (equivalent to Kafka's group.id) |
| `heartbeatIntervalMs` | 3000 | Heartbeat frequency in milliseconds |
| `sessionTimeoutMs` | 10000 | Session timeout for failure detection |
| `maxPollRecords` | 500 | Maximum records returned by single poll() |
| `enableAutoCommit` | true | Enable automatic offset commit |

---

## Error Handling

### Consumer-Side Errors

**Heartbeat Failure**:
```java
// Logged but non-fatal (will retry on next interval)
catch (Exception e) {
    log.error("Heartbeat failed: {}", e.getMessage());
    // Continue, broker will detect failure via timeout
}
```

**Poll Failure**:
```java
// Returns empty records on error
catch (Exception e) {
    log.error("Poll failed for partition {}: {}", partition, e.getMessage());
    return new ConsumerRecords(Collections.emptyList());
}
```

**Commit Failure**:
```java
// Sync: Throws exception
// Async: Logged, will retry on next commit
```

**Group Join Failure**:
```java
// Throws exception, consumer unusable
throw new RuntimeException("Failed to join consumer group", e);
```

### Broker-Side Errors

**Member Not Found**:
```http
POST /heartbeat
Response: 404 Not Found
→ Consumer must rejoin group
```

**Group Not Found**:
```http
POST /commit-offsets
Response: 404 Not Found
→ Group was deleted, consumer must recreate
```

**Invalid Generation**:
```http
POST /commit-offsets with old generation
Response: 409 Conflict
→ Consumer must fetch new assignment
```

---

## Complete Example: E2E Consumer Group Flow

```java
// 1. Create consumer configuration
ConsumerConfig config = ConsumerConfig.builder()
    .metadataServiceUrl("http://localhost:9091")
    .groupId("order-processor")
    .heartbeatIntervalMs(3000)
    .sessionTimeoutMs(10000)
    .maxPollRecords(500)
    .enableAutoCommit(true)
    .build();

// 2. Create consumer instance
DMQConsumer consumer = new DMQConsumer(config);
//    Internal: Generates consumerId (UUID)

// 3. Subscribe to topic
consumer.subscribe("orders");
//    Internal Flow:
//    a. POST /find-or-create → Get group leader broker
//    b. POST /join-group → Get memberId
//    c. GET /assignment/{memberId} → Get assigned partitions [0, 2]
//    d. Start heartbeat thread (every 3s)

// 4. Poll and process messages
try {
    while (running) {
        // Poll for messages
        ConsumerRecords records = consumer.poll(Duration.ofSeconds(5));
        //    Internal Flow:
        //    a. Auto-commit previous offsets (if enabled)
        //    b. For each partition [0, 2]:
        //       - Fetch from current offset
        //       - Track new offset
        //    c. Return aggregated records
        
        // Process messages
        for (ConsumerRecord record : records) {
            System.out.printf("Partition: %d, Offset: %d, Key: %s, Value: %s%n",
                record.getPartition(), record.getOffset(), 
                record.getKey(), record.getValue());
            
            // Business logic
            processOrder(record.getValue());
        }
        
        // Manual commit (if auto-commit disabled)
        // consumer.commitSync();
        //    Internal Flow:
        //    a. Collect (partition, offset) from currentOffsets
        //    b. POST /commit-offsets
        //    c. Update committedOffsets
    }
} finally {
    // 5. Close consumer
    consumer.close();
    //    Internal Flow:
    //    a. POST /leave-group
    //    b. Stop heartbeat thread
    //    c. Close HTTP clients
}
```

---

## Comparison with Apache Kafka

### Implemented Features

| Feature | DMQ Implementation | Apache Kafka | Notes |
|---------|-------------------|--------------|-------|
| Consumer Groups | ✅ Implemented | ✅ | Fully functional |
| Auto Partition Assignment | ✅ Range-based | ✅ Multiple strategies | Range strategy only |
| Background Heartbeat | ✅ Implemented | ✅ | 3-second interval |
| Offset Commit (Sync) | ✅ Implemented | ✅ | Blocking commit |
| Offset Commit (Async) | ✅ Implemented | ✅ | Non-blocking commit |
| Auto-commit | ✅ Implemented | ✅ | Enabled by default |
| Automatic Rebalancing | ✅ Implemented | ✅ | On join/leave/failure |
| Generation ID | ✅ Implemented | ✅ | Rebalance detection |
| Consumer Failure Detection | ✅ Implemented | ✅ | Via heartbeat timeout |
| Simple Consumer (Direct) | ✅ Implemented | ✅ | Partition-level access |

---

## Troubleshooting

### Consumer Not Receiving Messages

**Check**:
1. Is consumer subscribed? `consumer.subscribe(topic)`
2. Are partitions assigned? Check logs for "New assignment: [...]"
3. Is heartbeat working? Check logs for heartbeat errors
4. Are messages in the topic? Use simple consumer to verify

### Consumer Stuck in Rebalance

**Check**:
1. Are all consumers sending heartbeats?
2. Is session timeout too short? Increase `sessionTimeoutMs`
3. Are there rapid member joins/leaves?
4. Check broker logs for rebalance scheduler activity

### Offset Commit Failures

**Check**:
1. Is consumer still a member of the group?
2. Is the group generation current?
3. Is the group leader broker online?
4. Check network connectivity to broker

### Duplicate Message Processing

**Cause**: Consumer failed after processing but before committing offset

**Solutions**:
1. Use `commitSync()` immediately after processing
2. Implement idempotent processing
3. Store offsets externally with processed results

---

## Performance Tuning

### Throughput Optimization

```java
// Increase max records per poll
.maxPollRecords(1000)  // Default: 500

// Reduce heartbeat frequency (if stable environment)
.heartbeatIntervalMs(5000)  // Default: 3000

// Use async commit
consumer.commitAsync();  // Instead of commitSync()
```

### Latency Optimization

```java
// Reduce poll timeout
consumer.poll(Duration.ofMillis(100));  // Faster iteration

// Increase heartbeat frequency (faster failure detection)
.heartbeatIntervalMs(2000)  // Default: 3000

// Reduce session timeout
.sessionTimeoutMs(6000)  // Default: 10000
```

---

## Summary

### Key Takeaways

1. **Two Consumer Types**: SimpleConsumer (low-level) and DMQConsumer (high-level with groups)
2. **Automatic Coordination**: Consumer groups handle partition assignment automatically
3. **Background Heartbeat**: Daemon thread sends heartbeats every 3 seconds
4. **Rebalancing**: Automatic on member join/leave/failure
5. **Offset Management**: Manual or auto-commit supported
6. **Fault Tolerance**: Failed consumers detected via heartbeat timeout and partitions reassigned
7. **Scalability**: Add more consumers to parallelize processing

### Best Practices

1. **Always close consumers**: Use try-finally or try-with-resources
2. **Handle rebalance gracefully**: Commit offsets before assignment change
3. **Monitor consumer lag**: Track processing delay
4. **Use appropriate timeouts**: Balance failure detection speed vs stability
5. **Idempotent processing**: Handle duplicate messages gracefully
6. **Error handling**: Don't let one bad message break the consumer
7. **Graceful shutdown**: Close consumer on SIGTERM/SIGINT

---

**Last Updated**: November 22, 2025  
**Version**: 1.0.0
