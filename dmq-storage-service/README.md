# DMQ Storage Service

[![Version](https://img.shields.io/badge/version-1.0.0-blue.svg)](https://github.com/yourusername/dmq-storage-service)
[![Java](https://img.shields.io/badge/Java-11-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.18-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Status](https://img.shields.io/badge/status-active-success.svg)]()

## Overview

**DMQ Storage Service** is the storage broker component of DistributedMQ, responsible for message storage, partition management, and metadata synchronization. It implements automatic controller discovery, heartbeat-based health monitoring, and seamless failover handling.

### Key Features

- 🔍 **Automatic Controller Discovery**: Parallel queries to all metadata nodes on startup
- 💓 **Heartbeat Management**: 5-second interval health reporting to controller
- 🔄 **Automatic Failover**: Seamless switch to new controller during failures
- 📊 **Metadata Synchronization**: Version-based staleness detection and refresh
- 📢 **Push Notifications**: Real-time CONTROLLER_CHANGED event handling
- 🎯 **Partition Management**: Leader and replica partition handling
- 📝 **Comprehensive Logging**: Emoji-based logging for easy debugging

---

## Table of Contents

1. [Architecture](#architecture)
2. [Core Components](#core-components)
3. [Configuration](#configuration)
4. [Running the Service](#running-the-service)
5. [API Endpoints](#api-endpoints)
6. [Heartbeat Flow](#heartbeat-flow)
7. [Controller Discovery](#controller-discovery)
8. [Failover Handling](#failover-handling)
9. [Metadata Synchronization](#metadata-synchronization)
10. [Testing](#testing)
11. [Troubleshooting](#troubleshooting)

---

## Architecture

### Component Overview

```
┌──────────────────────────────────────────────────────────┐
│              DMQ Storage Service (Broker)                │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  ┌────────────────────────────────────────────────────┐ │
│  │         HeartbeatSender (Scheduled)                │ │
│  │  • 5-second interval                               │ │
│  │  • Controller sync from MetadataStore             │ │
│  │  • Exponential backoff on failures                │ │
│  │  • Rediscovery after 3 consecutive failures       │ │
│  └────────────────┬───────────────────────────────────┘ │
│                   │                                     │
│  ┌────────────────▼───────────────────────────────────┐ │
│  │         ControllerDiscoveryService                 │ │
│  │  • Parallel queries to all metadata nodes         │ │
│  │  • First successful response strategy             │ │
│  │  • Retry with backoff (1s, 2s, 4s, 8s)          │ │
│  └────────────────┬───────────────────────────────────┘ │
│                   │                                     │
│  ┌────────────────▼───────────────────────────────────┐ │
│  │              MetadataStore                         │ │
│  │  • Controller info (volatile for thread safety)   │ │
│  │  • Topic/Partition metadata cache                 │ │
│  │  • Version tracking                               │ │
│  │  • CONTROLLER_CHANGED handler                     │ │
│  │  • Periodic refresh (2-minute interval)           │ │
│  └────────────────┬───────────────────────────────────┘ │
│                   │                                     │
│  ┌────────────────▼───────────────────────────────────┐ │
│  │           PartitionManager                         │ │
│  │  • Leader partition management                     │ │
│  │  • Replica partition management                    │ │
│  │  • Partition assignment tracking                   │ │
│  └────────────────────────────────────────────────────┘ │
│                                                          │
└──────────────────────────────────────────────────────────┘
                           │
                           │ HTTP REST
                           │
                  ┌────────▼────────┐
                  │   Metadata      │
                  │   Controller    │
                  │   (Leader)      │
                  └─────────────────┘
```

### Communication Patterns

1. **Startup Sequence**:
   - Controller discovery (parallel queries)
   - Broker registration
   - Initial metadata pull
   - Heartbeat start

2. **Heartbeat Cycle** (every 5 seconds):
   - Sync controller info from MetadataStore
   - Send heartbeat to current controller
   - Receive ACK with metadata version
   - Check version staleness
   - Refresh metadata if needed

3. **Failover Handling**:
   - Detect controller failure (heartbeat errors)
   - Receive CONTROLLER_CHANGED push notification
   - Update MetadataStore
   - Sync on next heartbeat
   - Switch to new controller

---

## Core Components

### 1. HeartbeatSender

**Purpose**: Manages periodic heartbeat transmission to the controller.

**Key Responsibilities**:
- Send heartbeat every 5 seconds
- Sync controller info before each heartbeat
- Handle heartbeat failures with exponential backoff
- Trigger rediscovery after 3 consecutive failures
- Process heartbeat responses (version check)

**Configuration**:
```yaml
dmq:
  storage:
    heartbeat:
      interval-ms: 5000          # Heartbeat interval
      max-consecutive-failures: 3 # Trigger rediscovery threshold
```

**Lifecycle**:
```
@PostConstruct (Initialization)
    ↓
Discover Controller
    ↓
Register with Controller
    ↓
Pull Initial Metadata
    ↓
@Scheduled (Every 5 seconds)
    ↓
Sync Controller Info → Send Heartbeat → Process Response
    ↓
[If 3 failures] → Rediscover Controller
```

**Code Example**:
```java
@Scheduled(fixedDelayString = "${dmq.storage.heartbeat.interval-ms:5000}")
public void sendHeartbeat() {
    // 1. Sync controller info from MetadataStore
    syncControllerInfoFromMetadataStore();
    
    // 2. Build heartbeat request
    HeartbeatRequest request = HeartbeatRequest.builder()
        .brokerId(brokerId)
        .timestamp(System.currentTimeMillis())
        .metadataVersion(metadataStore.getMetadataVersion())
        .build();
    
    // 3. Send to controller
    String endpoint = currentControllerUrl + "/api/v1/metadata/heartbeat/" + brokerId;
    HeartbeatResponse response = restTemplate.postForObject(endpoint, request, HeartbeatResponse.class);
    
    // 4. Process response
    if (response != null && response.isAck()) {
        consecutiveFailures = 0;
        metadataStore.checkAndRefreshMetadata(response.getCurrentVersion());
    }
}
```

---

### 2. ControllerDiscoveryService

**Purpose**: Discovers the current Raft leader (controller) from metadata cluster.

**Key Features**:
- **Parallel Queries**: Query all metadata nodes simultaneously
- **First Response**: Use first successful response
- **Retry Logic**: Exponential backoff (1s, 2s, 4s, 8s)
- **Fault Tolerance**: Any node can respond

**Discovery Algorithm**:
```java
public ControllerInfo discoverController() {
    // 1. Get metadata nodes from services.json
    List<MetadataServiceInfo> metadataNodes = getMetadataNodes();
    
    // 2. Create parallel futures
    List<CompletableFuture<ControllerInfo>> futures = metadataNodes.stream()
        .map(node -> CompletableFuture.supplyAsync(() -> {
            try {
                String url = node.getUrl() + "/api/v1/metadata/controller";
                return restTemplate.getForObject(url, ControllerInfo.class);
            } catch (Exception e) {
                log.error("❌ Failed to query node {}: {}", node.getId(), e.getMessage());
                return null;
            }
        }))
        .collect(Collectors.toList());
    
    // 3. Wait for all queries
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    
    // 4. Return first successful result
    return futures.stream()
        .map(f -> f.getNow(null))
        .filter(Objects::nonNull)
        .findFirst()
        .orElseThrow(() -> new RuntimeException("Controller discovery failed"));
}
```

**Performance**:
- **Sequential**: 15-20 seconds (5s timeout × 3 nodes)
- **Parallel**: 1-2 seconds (single query time)
- **Improvement**: 90% faster

---

### 3. MetadataStore

**Purpose**: Local cache for cluster metadata with version tracking.

**Stored Data**:
- **Controller Info**: URL, ID, term (volatile for thread safety)
- **Topic Metadata**: Topics, partitions, leaders, replicas
- **Version**: Metadata version for staleness detection

**Key Methods**:

#### handleMetadataUpdate()
```java
public void handleMetadataUpdate(MetadataUpdateRequest request) {
    switch (request.getUpdateType()) {
        case CONTROLLER_CHANGED:
            // Update controller info
            this.currentControllerId = request.getControllerId();
            this.currentControllerUrl = request.getControllerUrl();
            this.currentControllerTerm = request.getTerm();
            log.info("🔄 Controller changed to Node {} (Term: {})", 
                currentControllerId, currentControllerTerm);
            break;
            
        case BROKER_STATUS_CHANGED:
            // Handle broker status changes
            break;
            
        case TOPIC_CREATED:
            // Refresh metadata
            pullMetadataFromController();
            break;
    }
}
```

#### checkAndRefreshMetadata()
```java
public void checkAndRefreshMetadata(Long remoteVersion) {
    if (metadataVersion < remoteVersion) {
        log.info("🔄 Metadata stale (local: {}, remote: {}), refreshing...", 
            metadataVersion, remoteVersion);
        pullMetadataFromController();
    }
}
```

#### pullMetadataFromController()
```java
public void pullMetadataFromController() {
    try {
        String url = currentControllerUrl + "/api/v1/metadata/cluster";
        ClusterMetadata metadata = restTemplate.getForObject(url, ClusterMetadata.class);
        
        // Update local cache
        this.topicPartitions.clear();
        for (TopicMetadata topic : metadata.getTopics()) {
            // Cache topic and partition info
        }
        
        this.metadataVersion = metadata.getVersion();
        this.lastMetadataUpdateTimestamp = System.currentTimeMillis();
        
        log.info("✅ Metadata updated to version {}", metadataVersion);
    } catch (Exception e) {
        log.error("❌ Failed to pull metadata: {}", e.getMessage());
    }
}
```

**Thread Safety**:
- `volatile` fields for controller info (cross-thread visibility)
- `ConcurrentHashMap` for topic/partition metadata
- Atomic operations for version updates

---

### 4. PartitionManager

**Purpose**: Manages partition assignments and leadership.

**Responsibilities**:
- Track leader partitions for this broker
- Track replica partitions for this broker
- Update assignments from controller
- Handle partition reassignment

**Data Structures**:
```java
@Component
public class PartitionManager {
    // Partitions where this broker is leader
    private final Map<String, Set<Integer>> leaderPartitions = new ConcurrentHashMap<>();
    
    // Partitions where this broker is replica
    private final Map<String, Set<Integer>> replicaPartitions = new ConcurrentHashMap<>();
    
    public void updatePartitionAssignments(List<PartitionMetadata> partitions) {
        for (PartitionMetadata partition : partitions) {
            if (partition.getLeader().equals(brokerId)) {
                leaderPartitions.computeIfAbsent(partition.getTopic(), k -> new HashSet<>())
                    .add(partition.getId());
            }
            
            if (partition.getReplicas().contains(brokerId)) {
                replicaPartitions.computeIfAbsent(partition.getTopic(), k -> new HashSet<>())
                    .add(partition.getId());
            }
        }
    }
}
```

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
  }
}
```

**Location**: Place in project root or specify path via environment variable.

---

### application.yml

```yaml
server:
  port: 8081  # Storage broker port

dmq:
  storage:
    broker-id: 101  # Unique broker ID
    host: localhost
    port: 8081
    
    heartbeat:
      interval-ms: 5000           # Heartbeat interval
      max-consecutive-failures: 3 # Rediscovery trigger
      
    metadata:
      refresh-interval-ms: 120000 # Periodic refresh (2 minutes)
      
spring:
  application:
    name: dmq-storage-service
    
logging:
  level:
    com.distributedmq.storage: DEBUG
```

---

### Environment Variables

Override configuration via environment variables:

```bash
# Broker Configuration
export DMQ_BROKER_ID=101
export SERVER_PORT=8081

# Heartbeat Configuration
export DMQ_STORAGE_HEARTBEAT_INTERVAL_MS=5000

# Metadata Configuration
export DMQ_STORAGE_METADATA_REFRESH_INTERVAL_MS=120000
```

---

## Running the Service

### Prerequisites

- Java 11+
- Maven 3.6+
- services.json in project root
- Metadata cluster running

### Build

```bash
cd dmq-storage-service
mvn clean package
```

### Run Single Broker

```bash
java -jar target/dmq-storage-service-1.0.0.jar \
  --dmq.storage.broker-id=101 \
  --server.port=8081
```

### Run Multiple Brokers (Windows)

```powershell
# Terminal 1 - Broker 101
Start-Process powershell -ArgumentList "-NoExit", "-Command", "java -jar target/dmq-storage-service-1.0.0.jar --dmq.storage.broker-id=101 --server.port=8081"

# Terminal 2 - Broker 102
Start-Process powershell -ArgumentList "-NoExit", "-Command", "java -jar target/dmq-storage-service-1.0.0.jar --dmq.storage.broker-id=102 --server.port=8082"

# Terminal 3 - Broker 103
Start-Process powershell -ArgumentList "-NoExit", "-Command", "java -jar target/dmq-storage-service-1.0.0.jar --dmq.storage.broker-id=103 --server.port=8083"
```

### Expected Startup Logs

```bash
🚀 Starting DMQ Storage Service...
📋 Broker ID: 101
📋 Port: 8081

🔍 Starting controller discovery...
🔍 Querying metadata node 1: http://localhost:9091
🔍 Querying metadata node 2: http://localhost:9092
🔍 Querying metadata node 3: http://localhost:9093
✅ Response from Node 2: controllerId=2, term=3
✅ Controller discovered: http://localhost:9092

📝 Registering with controller...
✅ Broker 101 registered successfully

📥 Pulling initial metadata from controller...
✅ Metadata loaded: version=10, topics=5

💓 Starting heartbeat sender...
✅ Heartbeat sender initialized

🎉 DMQ Storage Service started successfully!
```

---

## API Endpoints

### 1. Metadata Update Webhook

**Endpoint**: `POST /api/v1/storage/metadata/update`

**Purpose**: Receive push notifications from controller.

**Request Body**:
```json
{
  "updateType": "CONTROLLER_CHANGED",
  "controllerId": 3,
  "controllerUrl": "http://localhost:9093",
  "term": 4,
  "timestamp": 1731345678000
}
```

**Response**:
```json
{
  "success": true,
  "message": "Metadata update processed"
}
```

**Update Types**:
- `CONTROLLER_CHANGED`: Controller failover
- `BROKER_STATUS_CHANGED`: Broker status update
- `TOPIC_CREATED`: New topic created
- `PARTITION_REASSIGNED`: Partition reassignment

---

### 2. Health Check

**Endpoint**: `GET /api/v1/storage/health`

**Response**:
```json
{
  "status": "UP",
  "brokerId": 101,
  "controllerConnected": true,
  "currentController": "http://localhost:9092",
  "metadataVersion": 15,
  "lastHeartbeatTime": 1731345678000
}
```

---

### 3. Broker Info

**Endpoint**: `GET /api/v1/storage/info`

**Response**:
```json
{
  "brokerId": 101,
  "host": "localhost",
  "port": 8081,
  "status": "ONLINE",
  "leaderPartitions": {
    "orders": [0, 2],
    "payments": [1]
  },
  "replicaPartitions": {
    "orders": [1],
    "payments": [0, 2]
  },
  "metadataVersion": 15
}
```

---

## Heartbeat Flow

### Sequence Diagram

```
Every 5 seconds:

Storage Broker                    Metadata Controller
       │                                   │
       ├──── 1. Sync Controller Info ─────┤
       │     (from MetadataStore)          │
       │     currentControllerUrl          │
       │                                   │
       ├──── 2. Build Heartbeat Request ──┤
       │     {brokerId: 101,               │
       │      timestamp: ...,              │
       │      metadataVersion: 12}         │
       │                                   │
       ├──── 3. Send Heartbeat ───────────►│
       │     POST /heartbeat/101           │
       │                                   │
       │     4. Leader Validation          │
       │        isControllerLeader()?      │
       │           ├── YES ───┐            │
       │           └── NO  ───┼── 503 ────►│
       │                      │   X-Controller-Leader: 3
       │                                   │
       │◄──── 5. Heartbeat Response ───────┤
       │     {ack: true,                   │
       │      currentVersion: 15}          │
       │                                   │
       ├──── 6. Version Check ─────────────┤
       │     local: 12, remote: 15         │
       │     → Stale! Trigger refresh      │
       │                                   │
       ├──── 7. Pull Metadata ────────────►│
       │     GET /cluster                  │
       │                                   │
       │◄──── 8. Cluster Metadata ─────────┤
       │     {version: 15, topics: [...]}  │
       │                                   │
       └──── 9. Update Local Cache ────────┘
```

---

## Controller Discovery

### Discovery Process

```
Startup
   │
   ├── Load metadata nodes from services.json
   │   [Node 1: 9091, Node 2: 9092, Node 3: 9093]
   │
   ├── Create parallel futures for each node
   │   ├── Future 1: Query Node 1
   │   ├── Future 2: Query Node 2
   │   └── Future 3: Query Node 3
   │
   ├── Wait for all futures to complete (1-2 seconds)
   │
   ├── Collect results
   │   ├── Node 1: Timeout (null)
   │   ├── Node 2: ControllerInfo{id: 2, term: 3} ✅
   │   └── Node 3: ControllerInfo{id: 2, term: 3} ✅
   │
   ├── Return first non-null result
   │   → Controller: Node 2 (http://localhost:9092)
   │
   └── Cache in MetadataStore
```

### Retry Logic

```
Attempt 1: Query all nodes → All timeout → Wait 1s
Attempt 2: Query all nodes → All timeout → Wait 2s
Attempt 3: Query all nodes → All timeout → Wait 4s
Attempt 4: Query all nodes → All timeout → Wait 8s
Attempt 5: Query all nodes → All timeout → Throw exception
```

---

## Failover Handling

### Failover Scenario

```
Timeline:

0s: Normal Operation
    Broker 101 → Controller Node 2 (http://localhost:9092)
    💓 Heartbeat sent successfully

5s: Controller Failure
    ❌ Node 2 crashed
    💓 Heartbeat failed: Connection refused
    consecutiveFailures = 1

10s: Raft Election in Progress
    💓 Heartbeat failed: Connection refused
    consecutiveFailures = 2

12s: New Leader Elected
    🎉 Node 3 elected as LEADER (term: 4)
    📢 Pushing CONTROLLER_CHANGED to all brokers

13s: CONTROLLER_CHANGED Received
    📥 Broker 101 receives notification
    🔄 MetadataStore updated:
       currentControllerId = 3
       currentControllerUrl = http://localhost:9093
       currentControllerTerm = 4

15s: Automatic Switch
    💓 HeartbeatSender syncs from MetadataStore
    🔄 Controller switched to Node 3
    💓 Heartbeat sent to http://localhost:9093
    ✅ Heartbeat ACK received
    consecutiveFailures = 0

Result: Total failover time = 15 seconds
```

### Failure Detection

HeartbeatSender tracks consecutive failures:

```java
private void handleHeartbeatFailure(Exception e) {
    consecutiveFailures++;
    log.warn("❌ Heartbeat failed (attempt {}/{}): {}", 
        consecutiveFailures, maxConsecutiveFailures, e.getMessage());
    
    if (consecutiveFailures >= maxConsecutiveFailures) {
        log.warn("⚠️ {} consecutive failures, triggering rediscovery", consecutiveFailures);
        rediscoverController();
    }
}

private void rediscoverController() {
    try {
        log.info("🔍 Rediscovering controller...");
        ControllerInfo controller = controllerDiscoveryService.discoverController();
        
        metadataStore.setControllerInfo(
            controller.getControllerId(),
            controller.getUrl(),
            controller.getTerm()
        );
        
        consecutiveFailures = 0;
        log.info("✅ Rediscovery successful: {}", controller.getUrl());
    } catch (Exception e) {
        log.error("❌ Rediscovery failed: {}", e.getMessage());
    }
}
```

---

## Metadata Synchronization

### Version Tracking

Every heartbeat response includes metadata version:

```json
{
  "ack": true,
  "currentVersion": 15,
  "controllerTerm": 3
}
```

Broker checks if local version is stale:

```java
if (localVersion < remoteVersion) {
    pullMetadataFromController();
}
```

### Periodic Refresh

Fallback mechanism (every 2 minutes):

```java
@Scheduled(fixedDelayString = "${dmq.storage.metadata.refresh-interval-ms:120000}")
public void periodicMetadataRefresh() {
    try {
        metadataStore.pullMetadataFromController();
        log.info("🔄 Periodic metadata refresh completed");
    } catch (Exception e) {
        log.error("❌ Periodic refresh failed: {}", e.getMessage());
    }
}
```

---

## Testing

### Manual Testing

#### Test 1: Startup & Discovery

```bash
# Start metadata cluster first
# Then start storage broker

# Expected logs:
🔍 Starting controller discovery...
✅ Controller discovered: http://localhost:9092
📝 Registered with controller
📥 Metadata loaded
💓 Heartbeat sender started
```

#### Test 2: Heartbeat Flow

```bash
# Monitor logs for 30 seconds

# Expected logs (every 5 seconds):
💓 [Broker 101] Sending heartbeat
✅ [Broker 101] Heartbeat ACK received
```

#### Test 3: Controller Failover

```bash
# 1. Note current controller (e.g., Node 2)
# 2. Kill controller (Ctrl+C)
# 3. Monitor broker logs

# Expected logs:
❌ Heartbeat failed (Connection refused)
📥 CONTROLLER_CHANGED received
🔄 Controller switched to Node 3
💓 Heartbeat sent to new controller
✅ Heartbeat ACK received
```

#### Test 4: Metadata Synchronization

```bash
# 1. Create topic while broker running
curl -X POST http://localhost:9092/api/v1/metadata/topics \
  -H "Content-Type: application/json" \
  -d '{
    "topicName": "test-topic",
    "numPartitions": 3,
    "replicationFactor": 2
  }'

# 2. Check broker logs

# Expected logs:
🔄 Version mismatch: local=10, remote=11
📥 Pulling latest metadata...
✅ Metadata updated to version 11
📊 Topics: test-topic (3 partitions)
```

---

## Troubleshooting

### Issue 1: Controller Discovery Fails

**Symptoms**:
```bash
❌ Failed to query all metadata nodes
❌ Controller discovery failed
```

**Causes**:
- Metadata cluster not running
- Incorrect services.json configuration
- Network connectivity issues

**Solutions**:
1. Verify metadata cluster is running:
   ```bash
   curl http://localhost:9091/api/v1/metadata/controller
   ```
2. Check services.json has correct URLs
3. Verify network connectivity

---

### Issue 2: Heartbeats Failing

**Symptoms**:
```bash
❌ Heartbeat failed (Connection refused)
❌ Heartbeat failed (attempt 2/3)
```

**Causes**:
- Controller not leader
- Controller crashed
- Network issues

**Solutions**:
1. Check controller logs for leader status
2. Verify controller is running
3. Wait for automatic rediscovery (after 3 failures)

---

### Issue 3: Metadata Not Syncing

**Symptoms**:
```bash
⚠️ Metadata version mismatch persists
❌ Failed to pull metadata
```

**Causes**:
- Controller endpoint incorrect
- Network timeout
- Controller not responding

**Solutions**:
1. Verify controller URL in MetadataStore
2. Increase timeout in RestTemplate configuration
3. Check controller logs for errors

---

### Issue 4: Broker Not Registering

**Symptoms**:
```bash
❌ Failed to register with controller
❌ Registration rejected
```

**Causes**:
- Duplicate broker ID
- Controller not leader
- Registration endpoint unavailable

**Solutions**:
1. Ensure unique broker ID
2. Verify controller is leader
3. Check controller logs for rejection reason

---

## Monitoring

### Key Metrics to Monitor

1. **Heartbeat Success Rate**: Should be ~100%
2. **Controller Switches**: Should be rare (only on failures)
3. **Metadata Refresh Count**: Low frequency expected
4. **Consecutive Failures**: Should never reach 3 (triggers rediscovery)

### Log Patterns

**Healthy Broker**:
```bash
💓 [Broker 101] Sending heartbeat
✅ [Broker 101] Heartbeat ACK received
# Repeat every 5 seconds
```

**Failover in Progress**:
```bash
❌ Heartbeat failed (Connection refused)
📥 CONTROLLER_CHANGED received
🔄 Controller switched to Node 3
✅ Heartbeat ACK received
```

**Metadata Refresh**:
```bash
🔄 Version mismatch: local=10, remote=15
📥 Pulling latest metadata...
✅ Metadata updated to version 15
```

---

## Performance Tuning

### Heartbeat Interval

```yaml
dmq:
  storage:
    heartbeat:
      interval-ms: 5000  # Default: 5 seconds
```

- **Lower** (e.g., 3000): Faster failure detection, more network traffic
- **Higher** (e.g., 10000): Less network traffic, slower failure detection

### Metadata Refresh Interval

```yaml
dmq:
  storage:
    metadata:
      refresh-interval-ms: 120000  # Default: 2 minutes
```

- **Lower** (e.g., 60000): More up-to-date metadata, more network traffic
- **Higher** (e.g., 300000): Less network traffic, potential staleness

### Discovery Timeout

```java
restTemplate.setConnectTimeout(2000);  // 2 seconds
restTemplate.setReadTimeout(5000);     // 5 seconds
```

---

## Best Practices

1. **Unique Broker IDs**: Always use unique IDs (101, 102, 103...)
2. **Monitor Heartbeats**: Set up alerts for consecutive failures
3. **services.json Accuracy**: Keep metadata node URLs updated
4. **Log Monitoring**: Watch for emoji indicators (❌, ⚠️)
5. **Graceful Shutdown**: Stop broker cleanly (not kill -9)
6. **Version Tracking**: Monitor metadata version in logs

---

## Related Documentation

- [Project README](../README.md) - Overall project documentation
- [Architecture Documentation](../ARCHITECTURE.md) - System architecture
- [Metadata Service README](../dmq-metadata-service/README.md) - Controller documentation
- [Testing Guide](../TESTING_GUIDE.md) - Comprehensive testing instructions

---

**Version**: 1.0.0  
**Last Updated**: November 2024  
**Status**: ✅ Operational