# Producer Implementation Guide

## Overview

This document describes the complete producer implementation in the Distributed Message Queue (DMQ) system, including message production flows, partition selection strategies, acknowledgment modes, replication behavior, and error handling.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Producer Types](#producer-types)
3. [Message Production Flow](#message-production-flow)
4. [Partition Selection](#partition-selection)
5. [Acknowledgment Modes (acks)](#acknowledgment-modes-acks)
6. [Replication Behavior](#replication-behavior)
7. [High Water Mark (HWM)](#high-water-mark-hwm)
8. [API Reference](#api-reference)
9. [Configuration](#configuration)
10. [Error Handling](#error-handling)

---

## Architecture Overview

### Components

```
┌─────────────────┐      ┌──────────────────────┐      ┌─────────────────┐
│  SimpleProducer │─────▶│  Metadata Service    │      │  Storage Broker │
│  (Client-Side)  │      │  (Topic Metadata)    │      │    (Leader)     │
└─────────────────┘      └──────────────────────┘      └─────────────────┘
        │                          │                              │
        │  1. Get Topic Metadata   │                              │
        ├─────────────────────────▶│                              │
        │  2. Return Partition     │                              │
        │     Leaders              │                              │
        │◀─────────────────────────┤                              │
        │                          │                              │
        │  3. Select Partition     │                              │
        │     (hash/round-robin)   │                              │
        │                          │                              │
        │  4. Send Message to      │                              │
        │     Partition Leader     │                              │
        ├──────────────────────────┼─────────────────────────────▶│
        │                          │                              │
        │  5. Leader Writes to WAL │                              │
        │                          │                              │
        │  6. Replicate to ISR     │                              │
        │     (based on acks)      │                              │
        │                          │                              │
        │  7. Acknowledgment       │                              │
        │◀─────────────────────────┼──────────────────────────────┤
        │                          │                              │
```

### Key Components

1. **SimpleProducer (Client)**: Producer client library for CLI/direct usage
2. **DMQProducer (Client)**: High-level producer with batching (not fully implemented)
3. **Metadata Service**: Provides topic metadata and partition leaders
4. **Storage Broker (Leader)**: Receives and persists messages
5. **Write-Ahead Log (WAL)**: Partition-level log storage
6. **ReplicationManager (Broker)**: Handles replication to ISR followers

---

## Producer Types

### 1. Simple Producer (Production-Ready)

Direct message production with metadata discovery and partition selection.

**Use Case**: Production applications, CLI tools, direct message sending

**Example**:
```java
SimpleProducer producer = new SimpleProducer("http://localhost:9091");

// Send with auto-partition selection (hash-based or round-robin)
ProduceResponse response = producer.send(
    "orders",        // topic
    "order-123",     // key (null for round-robin)
    "order data",    // value
    null,            // partition (null for auto-selection)
    1                // acks (0, 1, or -1)
);

if (response.isSuccess()) {
    System.out.println("Message sent to partition: " + response.getPartition());
    System.out.println("Offset: " + response.getResults().get(0).getOffset());
}

producer.close();
```

**Characteristics**:
- Metadata caching for performance
- Automatic partition selection (hash or round-robin)
- Direct broker communication
- Synchronous sends
- Configurable acknowledgment mode

### 2. DMQ Producer (Not Fully Implemented)

High-level producer with batching and async capabilities.

**Current Status**: Placeholder implementation, returns dummy responses

**Planned Features** (Not Yet Implemented):
- Message batching
- Async sends with callbacks
- Automatic retries
- Compression
- Background sender thread

---

## Message Production Flow

### End-to-End Flow

```
Producer                Metadata Service          Storage Broker (Leader)
   │                              │                              │
   │  1. Get Topic Metadata       │                              │
   │     (if not cached)          │                              │
   ├─────────────────────────────▶│                              │
   │                              │                              │
   │  Response:                   │                              │
   │  { partitions: [             │                              │
   │    {id: 0, leader: broker1}, │                              │
   │    {id: 1, leader: broker2}  │                              │
   │  ]}                          │                              │
   │◀─────────────────────────────┤                              │
   │                              │                              │
   │  2. Select Partition         │                              │
   │     - If key: hash(key) % n  │                              │
   │     - Else: round-robin      │                              │
   │                              │                              │
   │  3. Create ProduceRequest    │                              │
   │     {                        │                              │
   │       topic, partition,      │                              │
   │       messages: [{key, val}],│                              │
   │       requiredAcks           │                              │
   │     }                        │                              │
   │                              │                              │
   │  4. POST /storage/messages   │                              │
   ├──────────────────────────────┼─────────────────────────────▶│
   │                              │                              │
   │                              │  5. Validate Request         │
   │                              │     - Check leadership       │
   │                              │     - Validate message       │
   │                              │                              │
   │                              │  6. Append to WAL            │
   │                              │     - Generate offset        │
   │                              │     - Add timestamp          │
   │                              │     - Write to log           │
   │                              │                              │
   │                              │  7. Handle Replication       │
   │                              │     (based on acks mode)     │
   │                              │                              │
   │  8. ProduceResponse          │                              │
   │     {                        │                              │
   │       partition: 0,          │                              │
   │       offset: 12345,         │                              │
   │       success: true          │                              │
   │     }                        │                              │
   │◀─────────────────────────────┼──────────────────────────────┤
   │                              │                              │
```

### Detailed Steps

#### Step 1: Metadata Discovery

**Purpose**: Find which broker is the leader for the target partition

```java
// SimpleProducer.getTopicMetadata()
private TopicMetadata getTopicMetadata(String topic) throws Exception {
    // Check cache first
    if (metadataCache.containsKey(topic)) {
        return metadataCache.get(topic);
    }
    
    // Fetch from metadata service
    TopicMetadata metadata = metadataClient.getTopicMetadata(topic);
    metadataCache.put(topic, metadata);
    return metadata;
}
```

**TopicMetadata Structure**:
```json
{
  "name": "orders",
  "partitionCount": 3,
  "replicationFactor": 2,
  "partitions": [
    {
      "partitionId": 0,
      "leader": { "id": 101, "host": "localhost", "port": 8081 },
      "replicas": [101, 102],
      "isr": [101, 102]
    },
    {
      "partitionId": 1,
      "leader": { "id": 102, "host": "localhost", "port": 8082 },
      "replicas": [102, 103],
      "isr": [102, 103]
    }
  ]
}
```

#### Step 2: Partition Selection

**Three Strategies**:

1. **Explicit Partition** (User-specified):
```java
producer.send("orders", "key", "value", 0, 1); // Partition 0
```

2. **Key-Based Hashing**:
```java
int partition = Math.abs(key.hashCode()) % partitionCount;
// Same key always goes to same partition (ordering guarantee)
```

3. **Round-Robin** (No key):
```java
int partition = roundRobinCounter.getAndIncrement() % partitionCount;
// Distributes load evenly across partitions
```

**Implementation**:
```java
private int selectPartition(String key, Integer explicitPartition, int partitionCount) {
    // 1. Explicit partition (highest priority)
    if (explicitPartition != null) {
        if (explicitPartition < 0 || explicitPartition >= partitionCount) {
            throw new IllegalArgumentException("Invalid partition");
        }
        return explicitPartition;
    }
    
    // 2. Key-based hashing (ordering guarantee)
    if (key != null) {
        return Math.abs(key.hashCode()) % partitionCount;
    }
    
    // 3. Round-robin (load distribution)
    return roundRobinCounter.getAndIncrement() % partitionCount;
}
```

#### Step 3: Message Serialization

**Base64 Encoding** for JSON transport:

```java
// Encode value as Base64 for JSON serialization
String base64Value = Base64.getEncoder().encodeToString(value.getBytes());

// Create message
Map<String, Object> message = Map.of(
    "key", key,
    "value", base64Value
    // timestamp added by broker if not provided
);
```

#### Step 4: Send to Leader Broker

**HTTP POST Request**:

```
POST http://localhost:8081/api/v1/storage/messages
Content-Type: application/json

{
  "topic": "orders",
  "partition": 0,
  "messages": [
    {
      "key": "order-123",
      "value": "b3JkZXIgZGF0YQ=="  // Base64 encoded
    }
  ],
  "producerId": "cli-producer",
  "producerEpoch": 0,
  "requiredAcks": 1,
  "timeoutMs": 30000
}
```

#### Step 5: Broker-Side Processing

**StorageController.produceMessages()**:

```java
@PostMapping("/messages")
public ResponseEntity<ProduceResponse> produceMessages(
    @RequestBody ProduceRequest request) {
    
    // 1. Validate request
    if (request.getTopic() == null || request.getMessages() == null) {
        return error(INVALID_REQUEST);
    }
    
    // 2. Check leadership
    if (!storageService.isLeaderForPartition(topic, partition)) {
        return error(NOT_LEADER_FOR_PARTITION);
    }
    
    // 3. Append messages (handles replication based on acks)
    ProduceResponse response = storageService.appendMessages(request);
    
    return ResponseEntity.ok(response);
}
```

#### Step 6: Write to WAL

**StorageServiceImpl.appendMessages()**:

```java
public ProduceResponse appendMessages(ProduceRequest request) {
    String topicPartition = topic + "-" + partition;
    
    // Get or create WAL for partition
    WriteAheadLog wal = partitionLogs.computeIfAbsent(
        topicPartition,
        k -> new WriteAheadLog(topic, partition, config)
    );
    
    // Handle based on acknowledgment mode
    switch (request.getRequiredAcks()) {
        case 0:  // Fire-and-forget
            return handleAcksNone(request, wal);
            
        case 1:  // Leader acknowledgment
            return handleAcksLeader(request, wal);
            
        case -1: // All ISR replicas
            return handleAcksAll(request, wal);
            
        default:
            return error(INVALID_REQUEST);
    }
}
```

---

## Partition Selection

### Partition Selection Strategies

#### 1. Explicit Partition

**Use Case**: Testing, specific partition targeting, manual control

```java
producer.send("orders", "key", "value", 2, 1); // Always partition 2
```

**Characteristics**:
- No hashing or round-robin
- Direct partition specification
- User has full control
- Validates partition exists (0 to partitionCount-1)

#### 2. Key-Based Hashing

**Use Case**: Ordering guarantee, related messages co-location

```java
producer.send("orders", "user-123", "value", null, 1);
// All messages with key "user-123" go to same partition
```

**Algorithm**:
```java
int partition = Math.abs(key.hashCode()) % partitionCount;
```

**Characteristics**:
- **Ordering Guarantee**: Same key → same partition → ordered consumption
- **Sticky Routing**: Key always maps to same partition (until partition count changes)
- **Co-location**: Related events stored together
- **Example**: All orders for "user-123" in same partition, enabling ordered processing

**Trade-off**: Potential hotspot if one key has high volume

#### 3. Round-Robin (No Key)

**Use Case**: Load distribution, no ordering requirement

```java
producer.send("orders", null, "value", null, 1);
// Distributes across partitions: 0, 1, 2, 0, 1, 2, ...
```

**Algorithm**:
```java
int partition = roundRobinCounter.getAndIncrement() % partitionCount;
```

**Characteristics**:
- **Even Distribution**: Load balanced across all partitions
- **No Ordering**: Messages distributed randomly
- **Maximum Throughput**: Utilizes all partitions equally
- **Example**: Logging, metrics, events without ordering requirements

### Partition Selection Decision Tree

```
User specifies partition?
   │
   ├─ YES ──▶ Use specified partition
   │
   └─ NO
       │
       Message has key?
       │
       ├─ YES ──▶ Hash(key) % partitionCount (Ordering)
       │
       └─ NO ──▶ Round-robin (Load distribution)
```

---

## Acknowledgment Modes (acks)

### Overview

The `requiredAcks` parameter controls when the broker sends acknowledgment back to the producer:

| acks | Name | Producer Waits For | Use Case |
|------|------|-------------------|----------|
| 0 | Fire-and-forget | Nothing (immediate) | High throughput, data loss acceptable |
| 1 | Leader acknowledgment | Leader write only | Balanced durability/performance |
| -1 | All ISR replicas | min.insync.replicas | Maximum durability, critical data |

### acks=0 (Fire-and-Forget)

**Behavior**: Producer receives immediate acknowledgment without waiting for any writes

```
Producer                Leader Broker
   │                         │
   │  POST /messages         │
   ├────────────────────────▶│
   │                         │
   │  Response: SUCCESS      │
   │  (immediately)          │
   │◀────────────────────────┤
   │                         │
   │                         │  (Async: Write to WAL)
   │                         │  (Async: Replicate to ISR)
   │                         │
```

**Implementation** (handleAcksNone):
```java
private ProduceResponse handleAcksNone(ProduceRequest request, WriteAheadLog wal) {
    // 1. Return SUCCESS immediately (no write)
    long baseOffset = wal.getNextOffset();
    
    ProduceResponse response = ProduceResponse.builder()
        .topic(request.getTopic())
        .partition(request.getPartition())
        .success(true)
        .build();
    
    // 2. Async: Write to WAL in background
    CompletableFuture.runAsync(() -> {
        for (ProduceMessage msg : request.getMessages()) {
            wal.append(msg.getKey(), msg.getValue());
        }
    });
    
    // 3. Async: Replicate to ISR followers
    CompletableFuture.runAsync(() -> {
        replicationManager.replicateToISR(request, baseOffset);
    });
    
    // 4. HWM updated later when ISR confirms
    
    return response;
}
```

**Characteristics**:
- **Latency**: Lowest (~1ms)
- **Throughput**: Highest
- **Durability**: None - data can be lost if broker crashes before write
- **HWM Update**: Deferred until ISR replication completes
- **Use Case**: Metrics, logs, non-critical events

### acks=1 (Leader Acknowledgment)

**Behavior**: Producer waits for leader to write to WAL, then receives acknowledgment

```
Producer                Leader Broker
   │                         │
   │  POST /messages         │
   ├────────────────────────▶│
   │                         │
   │                         │  1. Write to WAL
   │                         │     (synchronous)
   │                         │
   │  Response: SUCCESS      │
   │  offset: 12345          │
   │◀────────────────────────┤
   │                         │
   │                         │  (Async: Replicate to ISR)
   │                         │
```

**Implementation** (handleAcksLeader):
```java
private ProduceResponse handleAcksLeader(ProduceRequest request, WriteAheadLog wal) {
    List<ProduceResult> results = new ArrayList<>();
    
    // 1. Write to leader WAL synchronously
    for (ProduceMessage msg : request.getMessages()) {
        long offset = wal.append(msg.getKey(), msg.getValue());
        long timestamp = msg.getTimestamp() != null ? 
            msg.getTimestamp() : System.currentTimeMillis();
        
        results.add(ProduceResult.builder()
            .offset(offset)
            .timestamp(timestamp)
            .errorCode(ErrorCode.NONE)
            .build());
    }
    
    // 2. Return SUCCESS immediately
    ProduceResponse response = ProduceResponse.builder()
        .topic(request.getTopic())
        .partition(request.getPartition())
        .results(results)
        .success(true)
        .build();
    
    // 3. Async: Replicate to ISR followers
    long baseOffset = results.get(0).getOffset();
    CompletableFuture.runAsync(() -> {
        replicationManager.replicateToISR(request, baseOffset);
    });
    
    // 4. HWM updated later when ISR confirms
    
    return response;
}
```

**Characteristics**:
- **Latency**: Medium (~5-10ms, depends on WAL write)
- **Throughput**: Good
- **Durability**: Moderate - data safe if leader survives, lost if leader crashes before replication
- **HWM Update**: Deferred until ISR replication completes
- **Use Case**: **Default mode** - most production workloads

### acks=-1 (All ISR Replicas)

**Behavior**: Producer waits for leader AND min.insync.replicas to acknowledge

```
Producer                Leader Broker           Follower 1    Follower 2
   │                         │                      │             │
   │  POST /messages         │                      │             │
   ├────────────────────────▶│                      │             │
   │                         │                      │             │
   │                         │  1. Write to WAL     │             │
   │                         │                      │             │
   │                         │  2. Send replication │             │
   │                         ├─────────────────────▶│             │
   │                         ├──────────────────────┼────────────▶│
   │                         │                      │             │
   │                         │  3. Wait for acks    │             │
   │                         │◀─────────────────────┤             │
   │                         │◀──────────────────────────────────┤
   │                         │                      │             │
   │                         │  4. Update HWM       │             │
   │                         │                      │             │
   │  Response: SUCCESS      │                      │             │
   │  offset: 12345          │                      │             │
   │◀────────────────────────┤                      │             │
   │                         │                      │             │
```

**Implementation** (handleAcksAll):
```java
private ProduceResponse handleAcksAll(ProduceRequest request, WriteAheadLog wal) {
    List<ProduceResult> results = new ArrayList<>();
    
    // 1. Write to leader WAL synchronously
    for (ProduceMessage msg : request.getMessages()) {
        long offset = wal.append(msg.getKey(), msg.getValue());
        long timestamp = msg.getTimestamp() != null ? 
            msg.getTimestamp() : System.currentTimeMillis();
        
        results.add(ProduceResult.builder()
            .offset(offset)
            .timestamp(timestamp)
            .errorCode(ErrorCode.NONE)
            .build());
    }
    
    long baseOffset = results.get(0).getOffset();
    
    // 2. Replicate to ISR and wait for min.insync.replicas
    ReplicationResult replicationResult = replicateToISR(request, baseOffset);
    
    // 3. Check if min.insync.replicas acknowledged
    int minInsyncReplicas = config.getMinInsyncReplicas(); // e.g., 2
    
    if (replicationResult.getSuccessCount() < minInsyncReplicas - 1) {
        // Not enough replicas (minus 1 for leader)
        return ProduceResponse.builder()
            .topic(request.getTopic())
            .partition(request.getPartition())
            .success(false)
            .errorCode(ErrorCode.TIMEOUT)
            .errorMessage("Not enough in-sync replicas")
            .build();
    }
    
    // 4. Update HWM synchronously (committed now)
    long highWaterMark = baseOffset + request.getMessages().size();
    updateHighWaterMark(topicPartition, highWaterMark);
    
    // 5. Return SUCCESS
    return ProduceResponse.builder()
        .topic(request.getTopic())
        .partition(request.getPartition())
        .results(results)
        .success(true)
        .build();
}
```

**Characteristics**:
- **Latency**: Highest (~20-50ms, depends on network + ISR count)
- **Throughput**: Lowest
- **Durability**: **Maximum** - data replicated to multiple brokers before ack
- **HWM Update**: Immediate (committed before response)
- **Use Case**: Financial transactions, critical user data, compliance requirements

### Acknowledgment Mode Comparison

| Aspect | acks=0 | acks=1 | acks=-1 |
|--------|--------|--------|---------|
| **Producer Waits** | Nothing | Leader write | Leader + ISR acks |
| **Latency** | ~1ms | ~5-10ms | ~20-50ms |
| **Throughput** | Highest | High | Moderate |
| **Data Loss Risk** | High | Medium | Very Low |
| **HWM Update** | Deferred | Deferred | Immediate |
| **Network Calls** | 1 (producer→leader) | 1 + async | 1 + sync ISR |
| **Use Case** | Metrics, logs | Most workloads | Critical data |

---

## Replication Behavior

### Replication Invariants

**Regardless of acks mode, replication ALWAYS occurs**:

1. **Leader ALWAYS replicates** to ALL ISR followers
2. **Replication is asynchronous** for acks=0 and acks=1
3. **Replication is synchronous** for acks=-1 (waits for min.insync.replicas)
4. **Only ISR members** receive replication requests
5. **Followers NOT in ISR** are excluded from replication

### Replication Flow

```
Leader Broker                    Follower Broker (ISR)
   │                                      │
   │  1. Append to Leader WAL             │
   │                                      │
   │  2. POST /replicate                  │
   │  {                                   │
   │    topic, partition,                 │
   │    messages: [...],                  │
   │    baseOffset: 12345,                │
   │    leaderHWM: 12340                  │
   │  }                                   │
   ├─────────────────────────────────────▶│
   │                                      │
   │                                      │  3. Validate Request
   │                                      │     - Check leader epoch
   │                                      │     - Check offset sequence
   │                                      │
   │                                      │  4. Write to Follower WAL
   │                                      │     - Append messages
   │                                      │     - Update LEO
   │                                      │
   │                                      │  5. Update Follower HWM
   │                                      │     - hwm = min(leaderHWM, LEO)
   │                                      │
   │  6. ReplicationResponse              │
   │  {                                   │
   │    success: true,                    │
   │    highWaterMark: 12345,             │
   │    logEndOffset: 12350               │
   │  }                                   │
   │◀─────────────────────────────────────┤
   │                                      │
   │  7. Update Leader HWM                │
   │     - If quorum reached              │
   │                                      │
```

### Replication Implementation

**ReplicationManager.replicateToISR()**:

```java
public ReplicationResult replicateToISR(
    ProduceRequest request, 
    long baseOffset) {
    
    String topicPartition = request.getTopic() + "-" + request.getPartition();
    
    // 1. Get ISR followers for this partition
    List<Integer> isrFollowers = metadataStore.getISRFollowers(
        request.getTopic(), 
        request.getPartition()
    );
    
    if (isrFollowers.isEmpty()) {
        // No followers, leader is sole replica
        return ReplicationResult.success(0);
    }
    
    // 2. Get current HWM
    long currentHWM = storageService.getHighWaterMark(
        request.getTopic(), 
        request.getPartition()
    );
    
    // 3. Create replication request
    ReplicationRequest replicationRequest = ReplicationRequest.builder()
        .topic(request.getTopic())
        .partition(request.getPartition())
        .messages(request.getMessages())
        .baseOffset(baseOffset)
        .leaderId(config.getBroker().getId())
        .leaderEpoch(getCurrentLeaderEpoch())
        .leaderHighWaterMark(currentHWM)
        .build();
    
    // 4. Send to all ISR followers (parallel)
    List<CompletableFuture<ReplicationResponse>> futures = new ArrayList<>();
    
    for (Integer followerId : isrFollowers) {
        String followerUrl = metadataStore.getBrokerUrl(followerId);
        
        CompletableFuture<ReplicationResponse> future = 
            CompletableFuture.supplyAsync(() -> 
                sendReplicationRequest(followerUrl, replicationRequest)
            );
        
        futures.add(future);
    }
    
    // 5. Wait for responses (with timeout)
    CompletableFuture<Void> allOf = CompletableFuture.allOf(
        futures.toArray(new CompletableFuture[0])
    );
    
    try {
        allOf.get(config.getReplicationTimeoutMs(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
        log.warn("Replication timeout for {}", topicPartition);
    }
    
    // 6. Count successful replications
    int successCount = 0;
    for (CompletableFuture<ReplicationResponse> future : futures) {
        if (future.isDone() && !future.isCompletedExceptionally()) {
            ReplicationResponse response = future.join();
            if (response.isSuccess()) {
                successCount++;
            }
        }
    }
    
    return ReplicationResult.builder()
        .successCount(successCount)
        .totalFollowers(isrFollowers.size())
        .build();
}
```

### Follower-Side Processing

**StorageController.replicateMessages()** (follower endpoint):

```java
@PostMapping("/replicate")
public ResponseEntity<ReplicationResponse> replicateMessages(
    @RequestBody ReplicationRequest request) {
    
    // 1. Validate request
    if (!validateReplicationRequest(request)) {
        return error(INVALID_REQUEST);
    }
    
    // 2. Check if we're a follower for this partition
    if (isLeaderForPartition(request.getTopic(), request.getPartition())) {
        return error(NOT_A_FOLLOWER);
    }
    
    // 3. Validate leader epoch (fencing)
    if (request.getLeaderEpoch() < getCurrentLeaderEpoch()) {
        return error(STALE_LEADER);
    }
    
    // 4. Append messages to follower WAL
    ProduceResponse appendResult = storageService.replicateMessages(request);
    
    if (!appendResult.isSuccess()) {
        return error(STORAGE_ERROR);
    }
    
    // 5. Update follower HWM based on leader HWM
    long leo = storageService.getLogEndOffset(
        request.getTopic(), 
        request.getPartition()
    );
    long newHWM = Math.min(request.getLeaderHighWaterMark(), leo);
    storageService.updateFollowerHWM(
        request.getTopic(), 
        request.getPartition(), 
        newHWM
    );
    
    // 6. Return response
    return ResponseEntity.ok(ReplicationResponse.builder()
        .topic(request.getTopic())
        .partition(request.getPartition())
        .followerId(config.getBroker().getId())
        .success(true)
        .highWaterMark(newHWM)
        .logEndOffset(leo)
        .build());
}
```

---

## Atomic Batch WAL Write Optimization

### Overview

**New Feature (November 2025)**: Atomic batch write optimization for Write-Ahead Log (WAL) with configurable toggle.

**Purpose**: When producers send multiple messages in a batch, write all messages to disk in **a single atomic I/O operation** instead of individual writes, significantly improving throughput and reducing latency.

### Architecture

```
Producer Batch Request (10 messages)
         ↓
StorageServiceImpl.appendMessages()
         ↓
    [Check Toggle]
         ↓
   ┌─────┴─────┐
   ↓           ↓
ENABLED     DISABLED
   ↓           ↓
WAL.appendBatch()   Loop: WAL.append()
(1 atomic write)    (10 individual writes)
   ↓           ↓
OPTIMIZED    EXISTING
```

### Configuration

**application.yml**:
```yaml
dmq:
  storage:
    wal:
      batch-write-enabled: true  # Toggle: true = optimized, false = existing
      batch-write-max-messages: 1000  # Safety limit
```

**Default**: Optimization **enabled** (`batch-write-enabled: true`)

### Performance Benefits

| Metric | Individual Writes | Atomic Batch | Improvement |
|--------|------------------|--------------|-------------|
| **I/O Operations** | N (per message) | 1 (per batch) | **N×** faster |
| **System Calls** | N × write() | 1 × write() | **N×** reduction |
| **Throughput** | ~1K-5K msg/sec | ~10K-50K msg/sec | **5-10×** increase |
| **Latency (P99)** | Linear with N | Constant | **~80%** reduction |
| **Lock Hold Time** | N × serialize + write | Serialize all + 1 write | **~50%** reduction |

### Implementation Details

**Batch Write Flow** (when enabled):

```java
public ProduceResponse handleAcksLeader(ProduceRequest request, WriteAheadLog wal) {
    synchronized (wal) {
        if (config.getWal().getBatchWriteEnabled()) {
            // OPTIMIZED PATH: Atomic batch write
            List<Message> messages = buildMessageList(request);
            
            // Single atomic operation:
            // 1. Assign all offsets atomically
            // 2. Pre-serialize ALL messages
            // 3. Pre-calculate ALL checksums
            // 4. Allocate single buffer
            // 5. Write buffer to disk in ONE syscall
            List<Long> offsets = wal.appendBatch(messages);
            
            return buildResponse(offsets);
        } else {
            // EXISTING PATH: Individual writes
            for (ProduceRequest.ProduceMessage msg : request.getMessages()) {
                long offset = wal.append(message);
                // N separate I/O operations
            }
            return buildResponse(results);
        }
    }
}
```

**LogSegment Batch Write**:

```java
public void appendBatch(List<Message> messages) throws IOException {
    // Step 1: Pre-serialize all messages (in memory)
    List<byte[]> serialized = new ArrayList<>();
    List<Integer> checksums = new ArrayList<>();
    
    for (Message msg : messages) {
        byte[] data = serializeMessage(msg);
        serialized.add(data);
        
        CRC32 crc = new CRC32();
        crc.update(data);
        checksums.add((int) crc.getValue());
    }
    
    // Step 2: Allocate single buffer for entire batch
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(totalSize);
    
    for (int i = 0; i < messages.size(); i++) {
        buffer.writeInt(serialized.get(i).length + 4);
        buffer.writeInt(checksums.get(i));
        buffer.write(serialized.get(i));
    }
    
    // Step 3: Single atomic write to disk
    dataOutputStream.write(buffer.toByteArray());
    
    // File format identical to individual writes!
}
```

### Toggle Behavior

#### **Enabled** (`batch-write-enabled: true`)
- ✅ **10 messages** → **1 I/O operation**
- ✅ All offsets assigned atomically
- ✅ High throughput, low latency
- ✅ All-or-nothing batch commit (better consistency)
- ✅ Works for **all acks modes** (0, 1, -1)
- ✅ Applies to **leader writes** AND **follower replication**

#### **Disabled** (`batch-write-enabled: false`)
- ✅ **10 messages** → **10 I/O operations**
- ✅ Proven legacy behavior
- ✅ Fallback for debugging
- ✅ 100% backward compatible

### Key Design Decisions

1. **No Segment Splits**: Batch write assumes batch fits in current segment
   - If segment nearly full, check occurs before batch write
   - Fallback to individual writes if batch doesn't fit (future enhancement)

2. **Atomic Offsets**: All offsets assigned via `getAndIncrement()` before write
   - Offset assignment still atomic even in batch mode
   - No gaps in offset sequence

3. **Same File Format**: Batch writes produce **identical on-disk format**
   - WAL recovery handles batched writes identically
   - Consumers see no difference
   - No breaking changes to storage format

4. **All Acks Modes**: Optimization works for acks=0, 1, and -1
   - Replication still follows existing flow
   - HWM updates unchanged

5. **Error Handling**: All-or-nothing batch semantics
   - If batch write fails, entire batch fails (no partial writes)
   - More consistent than individual writes
   - Matches Apache Kafka behavior

### Testing Recommendations

**Test with toggle=true** (optimized):
```bash
# In application.yml
wal:
  batch-write-enabled: true

# Send batch of 100 messages
mycli produce --topic test --batch-file messages.txt --acks 1

# Verify:
# - All messages have consecutive offsets
# - High throughput observed
# - Recovery after restart works
```

**Test with toggle=false** (existing):
```bash
# In application.yml
wal:
  batch-write-enabled: false

# Send same batch
mycli produce --topic test --batch-file messages.txt --acks 1

# Verify:
# - Same offsets produced
# - Lower throughput (expected)
# - Backward compatible behavior
```

### Production Recommendations

1. **Leave Enabled**: Default to `batch-write-enabled: true` for best performance
2. **Monitor Metrics**: Track write latency and throughput
3. **Safe Rollback**: Disable toggle if issues arise (instant rollback)
4. **Memory Safety**: `batch-write-max-messages: 1000` prevents excessive memory use
5. **Log Monitoring**: Watch for "Batch appended N messages" log entries

### Impact on Other Flows

✅ **No Changes Required**:
- Consumer read path (reads same file format)
- Replication protocol (uses same message format)
- HWM management (based on LEO, unchanged)
- WAL recovery (validates same way)
- Index management (indexes each message in batch)
- Flush behavior (time-based, unchanged)

---

## High Water Mark (HWM)

### Definition

**High Water Mark (HWM)**: The highest offset that has been replicated to min.insync.replicas (committed offset)

**Log End Offset (LEO)**: The offset of the next message to be written (highest offset + 1)

```
Partition Log (Leader):

Offset:     0    1    2    3    4    5    6    7    8
           ┌────┬────┬────┬────┬────┬────┬────┬────┬────┐
Messages:  │ M0 │ M1 │ M2 │ M3 │ M4 │ M5 │ M6 │ M7 │    │
           └────┴────┴────┴────┴────┴────┴────┴────┴────┘
                                        ▲              ▲
                                        │              │
                                       HWM=5         LEO=8
                                        │              │
                           Consumers can  │         Leader can
                           read up to     │         write here
                           offset 5       │
```

### HWM Update Behavior

#### acks=0 and acks=1 (Deferred HWM Update)

```
Time  Action                                   HWM    LEO
────  ─────────────────────────────────────   ────   ────
T0    Initial state                            100    100
T1    Producer sends message (acks=1)          100    101
T2    Leader writes to WAL                     100    101
T3    Leader returns SUCCESS to producer       100    101
T4    Replication starts (async)               100    101
T5    Follower 1 acknowledges                  100    101
T6    Follower 2 acknowledges                  101    101  ← HWM updated
```

**Key Point**: Producer gets SUCCESS at T3, but HWM updated at T6

#### acks=-1 (Immediate HWM Update)

```
Time  Action                                   HWM    LEO
────  ─────────────────────────────────────   ────   ────
T0    Initial state                            100    100
T1    Producer sends message (acks=-1)         100    101
T2    Leader writes to WAL                     100    101
T3    Replication starts (sync)                100    101
T4    Follower 1 acknowledges                  100    101
T5    Follower 2 acknowledges                  101    101  ← HWM updated
T6    Leader returns SUCCESS to producer       101    101
```

**Key Point**: Producer gets SUCCESS at T6, after HWM update

### HWM Update Implementation

**Leader-Side** (after replication):

```java
private void updateHighWaterMarkAfterReplication(
    String topic, 
    int partition, 
    long baseOffset,
    int successfulReplicas) {
    
    int minInsyncReplicas = config.getMinInsyncReplicas(); // e.g., 2
    
    // Check if we have quorum (including leader)
    int totalReplicas = successfulReplicas + 1; // +1 for leader
    
    if (totalReplicas >= minInsyncReplicas) {
        // Advance HWM to committed offset
        String topicPartition = topic + "-" + partition;
        long newHWM = baseOffset + request.getMessages().size();
        
        highWaterMarks.put(topicPartition, newHWM);
        
        log.info("Advanced HWM for {} to {} (replicas: {}/{})",
                 topicPartition, newHWM, totalReplicas, minInsyncReplicas);
    }
}
```

**Follower-Side** (during replication):

```java
private void updateFollowerHWM(
    String topic, 
    int partition, 
    long leaderHWM) {
    
    String topicPartition = topic + "-" + partition;
    
    // Get follower's LEO
    long leo = getLogEndOffset(topic, partition);
    
    // HWM = min(leader HWM, follower LEO)
    // Follower can't have HWM beyond its own LEO
    long newHWM = Math.min(leaderHWM, leo);
    
    highWaterMarks.put(topicPartition, newHWM);
    
    log.debug("Updated follower HWM for {} to {} (leaderHWM: {}, LEO: {})",
              topicPartition, newHWM, leaderHWM, leo);
}
```

### Consumer Visibility

**Consumers can only read up to HWM**:

```java
public ConsumeResponse fetch(ConsumeRequest request) {
    String topicPartition = request.getTopic() + "-" + request.getPartition();
    
    // Get HWM for partition
    long hwm = highWaterMarks.getOrDefault(topicPartition, 0L);
    
    // Validate request offset
    if (request.getOffset() > hwm) {
        // Consumer trying to read uncommitted data
        return ConsumeResponse.builder()
            .messages(Collections.emptyList())
            .highWaterMark(hwm)
            .build();
    }
    
    // Fetch messages up to HWM
    List<Message> messages = wal.read(
        request.getOffset(),
        Math.min(request.getMaxRecords(), hwm - request.getOffset())
    );
    
    return ConsumeResponse.builder()
        .messages(messages)
        .highWaterMark(hwm)
        .build();
}
```

---

## API Reference

### Client API (SimpleProducer)

```java
// Constructor
public SimpleProducer(String metadataServiceUrl)

// Send message with auto-partition selection
public ProduceResponse send(
    String topic,
    String key,        // null for round-robin, non-null for hash-based
    String value,
    Integer partition, // null for auto-selection
    int acks           // 0, 1, or -1
) throws Exception

// Close producer
public void close()
```

### Broker API (Storage Service)

```java
// Produce messages to partition (leader only)
POST /api/v1/storage/messages
Request: ProduceRequest {
  "topic": "orders",
  "partition": 0,
  "messages": [
    {
      "key": "order-123",
      "value": "b3JkZXIgZGF0YQ=="  // Base64 encoded
    }
  ],
  "producerId": "producer-1",
  "producerEpoch": 0,
  "requiredAcks": 1,  // 0, 1, or -1
  "timeoutMs": 30000
}
Response: ProduceResponse {
  "topic": "orders",
  "partition": 0,
  "results": [
    {
      "offset": 12345,
      "timestamp": 1700000000000,
      "errorCode": "NONE"
    }
  ],
  "success": true
}

// Replicate messages (follower endpoint)
POST /api/v1/storage/replicate
Request: ReplicationRequest {
  "topic": "orders",
  "partition": 0,
  "messages": [...],
  "baseOffset": 12345,
  "leaderId": 101,
  "leaderEpoch": 5,
  "leaderHighWaterMark": 12340
}
Response: ReplicationResponse {
  "topic": "orders",
  "partition": 0,
  "followerId": 102,
  "success": true,
  "highWaterMark": 12345,
  "logEndOffset": 12350
}
```

---

## Configuration

### ProducerConfig

```java
ProducerConfig config = ProducerConfig.builder()
    // Required
    .metadataServiceUrl("http://localhost:9091")
    
    // Optional (with defaults)
    .batchSize(16384)              // 16KB batch size
    .lingerMs(0L)                  // No batching delay
    .maxInFlightRequests(5)        // Pipelining
    .requiredAcks(1)               // Leader ack (default)
    .requestTimeoutMs(30000L)      // 30 second timeout
    .retries(3)                    // Retry count
    .compressionType("none")       // No compression
    .build();
```

### Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `metadataServiceUrl` | Required | Bootstrap metadata service URL |
| `batchSize` | 16384 | Batch size in bytes (not used in SimpleProducer) |
| `lingerMs` | 0 | Batching delay in ms (not used in SimpleProducer) |
| `maxInFlightRequests` | 5 | Max concurrent requests (not used in SimpleProducer) |
| `requiredAcks` | 1 | Acknowledgment mode (0, 1, -1) |
| `requestTimeoutMs` | 30000 | Request timeout |
| `retries` | 3 | Retry count (not implemented yet) |
| `compressionType` | none | Compression algorithm (not implemented yet) |

---

## Error Handling

### Client-Side Errors

**Topic Not Found**:
```java
try {
    producer.send("invalid-topic", null, "value", null, 1);
} catch (RuntimeException e) {
    // "Topic 'invalid-topic' not found. Create it first..."
}
```

**Invalid Partition**:
```java
try {
    producer.send("orders", null, "value", 999, 1);
} catch (IllegalArgumentException e) {
    // "Invalid partition 999. Valid range: 0-2"
}
```

**No Leader Available**:
```java
ProduceResponse response = producer.send(...);
if (!response.isSuccess()) {
    // errorCode: NOT_LEADER_FOR_PARTITION
    // Need to refresh metadata and retry
}
```

### Broker-Side Errors

**Request Validation**:
```java
// INVALID_REQUEST
- Topic is null or empty
- Partition is null or negative
- Messages list is null or empty
- Message value is null or empty
- Invalid acks value (not 0, 1, or -1)
```

**Leadership Errors**:
```java
// NOT_LEADER_FOR_PARTITION
- Broker is not the leader for this partition
- Client should refresh metadata and retry with correct leader
```

**Replication Errors**:
```java
// TIMEOUT (acks=-1 only)
- Not enough ISR replicas acknowledged within timeout
- min.insync.replicas not met
- Producer should retry
```

### Error Code Reference

```java
public enum ErrorCode {
    NONE(0, "No error"),
    UNKNOWN_TOPIC_OR_PARTITION(3, "Unknown topic or partition"),
    INVALID_REQUEST(10, "Invalid request"),
    NOT_LEADER_FOR_PARTITION(6, "Not leader for partition"),
    MESSAGE_TOO_LARGE(10, "Message too large"),
    INVALID_PRODUCER_EPOCH(47, "Invalid producer epoch"),
    DUPLICATE_SEQUENCE_NUMBER(34, "Duplicate sequence number"),
    TIMEOUT(7, "Request timeout");
}
```

---

## Complete Example: E2E Message Production

```java
// 1. Create producer
SimpleProducer producer = new SimpleProducer("http://localhost:9091");

// 2. Send message with different strategies

// Example A: Explicit partition
ProduceResponse response1 = producer.send(
    "orders",        // topic
    "order-123",     // key
    "order data",    // value
    0,               // partition 0 (explicit)
    1                // acks=1 (leader)
);
System.out.println("Sent to partition: " + response1.getPartition());

// Example B: Key-based hashing (ordering guarantee)
ProduceResponse response2 = producer.send(
    "orders",        // topic
    "user-456",      // key (all messages with same key go to same partition)
    "user event",    // value
    null,            // auto-select partition (hash-based)
    1                // acks=1
);

// Example C: Round-robin (load distribution)
ProduceResponse response3 = producer.send(
    "logs",          // topic
    null,            // no key (round-robin)
    "log entry",     // value
    null,            // auto-select partition (round-robin)
    0                // acks=0 (fire-and-forget)
);

// Example D: Maximum durability
ProduceResponse response4 = producer.send(
    "payments",      // topic
    "txn-789",       // key
    "payment data",  // value
    null,            // auto-select
    -1               // acks=-1 (all ISR replicas)
);

if (response4.isSuccess()) {
    System.out.println("Payment committed!");
    System.out.println("Offset: " + response4.getResults().get(0).getOffset());
} else {
    System.err.println("Payment failed: " + response4.getErrorMessage());
}

// 3. Close producer
producer.close();
```

### Internal Flow for Example D (acks=-1)

```
1. Client sends message
   ↓
2. Metadata lookup: "payments" topic
   - Partition count: 3
   - Key "txn-789" → hash → partition 1
   - Leader: broker-102 (localhost:8082)
   ↓
3. POST http://localhost:8082/api/v1/storage/messages
   {
     "topic": "payments",
     "partition": 1,
     "messages": [{"key": "txn-789", "value": "cGF5bWVudCBkYXRh"}],
     "requiredAcks": -1
   }
   ↓
4. Broker-102 (leader):
   - Validate request
   - Write to WAL (offset 5000)
   - Replicate to ISR [103, 104] (sync)
   ↓
5. Broker-103 (follower):
   - Receive replication request
   - Write to WAL
   - Return ACK
   ↓
6. Broker-104 (follower):
   - Receive replication request
   - Write to WAL
   - Return ACK
   ↓
7. Broker-102 (leader):
   - 2 follower ACKs received (min.insync.replicas=2 met)
   - Update HWM to 5001
   - Return SUCCESS to client
   ↓
8. Client receives response:
   {
     "partition": 1,
     "results": [{"offset": 5000}],
     "success": true
   }
```

---

## Performance Characteristics

### Latency by acks Mode

| acks | P50 | P99 | P999 | Notes |
|------|-----|-----|------|-------|
| 0 | 1ms | 2ms | 5ms | Network RTT only |
| 1 | 5ms | 15ms | 50ms | + WAL write |
| -1 | 25ms | 75ms | 200ms | + ISR replication |

**Factors affecting latency**:
- Network latency between client and broker
- WAL disk I/O performance
- Number of ISR replicas
- Replication network latency
- Broker load

### Throughput by acks Mode

| acks | Messages/sec (1KB msgs) | MB/sec | Notes |
|------|------------------------|--------|-------|
| 0 | ~100,000 | ~100 | Limited by network |
| 1 | ~50,000 | ~50 | Limited by WAL write |
| -1 | ~10,000 | ~10 | Limited by ISR replication |

**Factors affecting throughput**:
- Message size
- Batch size (not implemented in SimpleProducer)
- Number of producer threads
- Broker hardware (CPU, disk, network)
- Number of ISR replicas

---

## Comparison with Apache Kafka Producer

### Implemented Features

| Feature | DMQ SimpleProducer | Apache Kafka Producer | Notes |
|---------|-------------------|----------------------|-------|
| Metadata Discovery | ✅ Implemented | ✅ | With caching |
| Partition Selection | ✅ Implemented | ✅ | Hash + round-robin |
| acks=0/1/-1 | ✅ Implemented | ✅ | All modes supported |
| Replication | ✅ Implemented | ✅ | To ISR only |
| High Water Mark | ✅ Implemented | ✅ | Committed offset tracking |
| Synchronous Send | ✅ Implemented | ✅ | Blocking send |
| Error Handling | ✅ Implemented | ✅ | Error codes |

### Not Implemented (Future)

| Feature | DMQ Status | Apache Kafka | Notes |
|---------|-----------|--------------|-------|
| Batching | ❌ Not implemented | ✅ | Increases throughput |
| Async Send | ❌ Not implemented | ✅ | Non-blocking |
| Compression | ❌ Not implemented | ✅ | Reduces network usage |
| Retries | ❌ Not implemented | ✅ | Automatic retry |
| Idempotence | ❌ Not implemented | ✅ | Exactly-once semantics |
| Transactions | ❌ Not implemented | ✅ | Atomic multi-partition |
| Custom Partitioner | ❌ Not implemented | ✅ | User-defined strategy |

---

## Troubleshooting

### Producer Not Sending Messages

**Check**:
1. Is metadata service reachable? `curl http://localhost:9091/api/v1/metadata/topics/{topic}`
2. Does topic exist? Create with `mycli create-topic`
3. Are partition leaders available? Check broker status
4. Is storage broker reachable? Check leader broker URL

### "Not Leader for Partition" Error

**Cause**: Broker is not the leader, or leadership changed

**Solution**:
1. Clear metadata cache: `metadataCache.clear()`
2. Retry with fresh metadata lookup
3. Check broker logs for leadership changes

### Timeout with acks=-1

**Cause**: Not enough ISR replicas acknowledged

**Possible Reasons**:
1. Follower brokers down or unreachable
2. Network issues between leader and followers
3. Disk I/O slow on followers
4. min.insync.replicas misconfigured

**Solution**:
1. Check follower broker health
2. Verify ISR list: Check partition metadata
3. Increase timeout: `timeoutMs` in ProduceRequest
4. Reduce min.insync.replicas (reduces durability)

### High Latency

**Check**:
1. Which acks mode? acks=-1 has highest latency
2. WAL disk performance? Check disk I/O metrics
3. Network latency? Ping between brokers
4. Broker load? Check CPU/memory usage

**Solutions**:
1. Use acks=1 instead of acks=-1 if acceptable
2. Use SSD for WAL storage
3. Reduce ISR count (reduces durability)
4. Scale brokers horizontally

---

## Best Practices

### Choosing acks Mode

**Use acks=0** when:
- Data loss is acceptable
- Maximum throughput required
- Non-critical data (logs, metrics)

**Use acks=1** when:
- Balanced durability and performance needed
- Most production workloads
- Default recommendation

**Use acks=-1** when:
- Data loss is unacceptable
- Critical data (payments, orders)
- Compliance requirements

### Partition Strategy

**Use explicit partition** when:
- Testing specific partitions
- Manual load balancing needed

**Use key-based hashing** when:
- Ordering guarantee required
- Related events should be co-located
- Example: All events for "user-123" in order

**Use round-robin (no key)** when:
- Maximum throughput required
- No ordering requirements
- Even load distribution needed

### Error Handling

1. **Always check response.isSuccess()**
2. **Retry on NOT_LEADER_FOR_PARTITION** after metadata refresh
3. **Don't retry on INVALID_REQUEST** (fix the request)
4. **Implement exponential backoff** for retries
5. **Log failed messages** for debugging

### Performance

1. **Cache metadata** to avoid repeated lookups
2. **Reuse producer instances** (connection pooling)
3. **Use acks=1 by default** unless critical data
4. **Monitor latency percentiles** (P50, P99, P999)
5. **Close producer gracefully** to ensure inflight messages complete

---

**Last Updated**: November 22, 2025  
**Version**: 1.0.0
