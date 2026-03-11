# 📋 **Storage Service Responsibilities & Flows**

Based on the current implementation, here's a comprehensive breakdown of all responsibilities and flows handled by the **Storage Service**:

---

## 🏗️ **Core Architecture & Responsibilities**

### **1. Message Persistence & WAL Management**
**Components**: `WriteAheadLog.java`, `LogSegment.java`
- **Segment-based durable storage** with atomic offset management
- **Recovery from disk** during startup with checksum validation
- **Concurrent read/write access** with read-write locks
- **High Water Mark (HWM) tracking** for committed messages
- **Log End Offset (LEO) management** for latest written messages

### **2. REST API Endpoints**
**Component**: `StorageController.java`
- **Produce Messages**: `POST /api/v1/storage/messages` (leader validation, replication coordination)
- **Consume Messages**: `POST /api/v1/storage/consume` (with long polling support)
- **Replication**: `POST /api/v1/storage/replicate` (follower endpoint for receiving replicated messages)
- **Metadata Updates**: `POST /api/v1/storage/metadata` (push-based metadata synchronization)
- **Health Checks**: `GET /api/v1/storage/health`
- **Partition Status**: `GET /api/v1/storage/partitions/{topic}/{partition}/high-water-mark`

---

## 🔄 **Message Production Flow**

### **Leader-Side Production (acks=0, acks=1, acks=-1)**

1. **Request Validation**
   - Topic/partition validation
   - Message size limits
   - Producer ID/epoch validation (TODO)
   - Leadership verification

2. **Message Append to WAL**
   - Synchronous write to leader's log
   - Offset assignment and timestamp setting
   - Segment management and rollover

3. **Replication Coordination** (ISR-based)
   - **acks=0**: Fire-and-forget async replication to ISR followers
   - **acks=1**: Fire-and-forget async replication to ISR followers
   - **acks=-1**: Synchronous wait for ALL ISR followers to acknowledge

4. **High Water Mark Updates**
   - **acks=0/1**: Async HWM advancement after ISR acknowledgment
   - **acks=-1**: Sync HWM advancement before producer response

5. **Response Timing**
   - **acks=0**: Immediate response (no waiting)
   - **acks=1**: Response after leader write (no replication wait)
   - **acks=-1**: Response only after min.insync.replicas acknowledge

---

## 📖 **Message Consumption Flow**

### **Consumer Fetch Logic**

1. **Request Processing**
   - Offset validation and bounds checking
   - Partition existence verification
   - Max messages and timeout parameter handling

2. **Message Retrieval**
   - Direct read from WAL segments if messages available
   - **Long polling implementation**: Busy-wait loop (100ms intervals) up to maxWaitMs
   - CRC validation during reads

3. **Response Construction**
   - Message enrichment with topic/partition metadata
   - High water mark inclusion for consumer progress tracking

---

## 🔄 **Replication Flow**

### **Leader-to-Follower Replication**
**Component**: `ReplicationManager.java`

1. **ISR Determination**
   - Query MetadataStore for current ISR members
   - Filter out leader (don't replicate to self)
   - Handle empty ISR scenarios

2. **Replication Request Creation**
   - Batch message packaging with metadata
   - Leader epoch and HWM inclusion
   - Timeout and acknowledgment requirements

3. **Network Communication**
   - **Circuit Breaker Pattern**: Automatic failure detection and recovery
   - **Retry Logic**: Exponential backoff (up to 2 retries)
   - Async HTTP requests to follower endpoints

4. **Acknowledgment Collection**
   - Parallel wait for follower responses
   - Success counting based on requiredAcks
   - Timeout handling with configurable limits

### **Follower-Side Replication Processing**

1. **Request Validation**
   - Follower role verification for partition
   - Leader epoch validation (TODO)
   - Message integrity checks

2. **Message Storage**
   - Append to follower's WAL (acks=0 behavior)
   - Leader HWM tracking for lag calculation
   - No further replication (followers don't replicate)

---

## 📊 **Monitoring & Health Reporting**

### **ISR Status Monitoring** [Verify if any changes needed]
**Component**: `ISRStatusBatchScheduler.java`
- **Lag Threshold Detection**: Monitor follower lag vs configurable thresholds
- **Batch Status Updates**: Send ISR health reports to controller every 30 seconds
- **Membership Change Tracking**: Detect ISR composition changes (TODO)

### **Partition Status Collection**
**Component**: `StorageServiceImpl.collectPartitionStatus()`
- **Role Determination**: Leader vs Follower classification
- **Offset Reporting**: LEO, HWM, and lag metrics
- **Heartbeat Integration**: Controller communication for cluster health

---

## 🔧 **Configuration & Metadata Management**

### **Configuration Management**
**Component**: `StorageConfig.java`
- **Broker Identity**: ID, host, port configuration
- **Replication Settings**: ISR requirements, timeouts, retry policies
- **Consumer Settings**: Fetch limits, polling intervals
- **Storage Settings**: Log directories, segment sizes, retention policies

### **Metadata Synchronization**
**Component**: `MetadataStore.java`
- **Push-based Updates**: Receive metadata changes from controller
- **Version Tracking**: Metadata version management for consistency
- **Leadership Caching**: Local partition leadership state
- **ISR Membership**: Current in-sync replica information

---

## 🛡️ **Fault Tolerance & Error Handling**

### **Circuit Breaker Implementation**
- **Failure Threshold**: 3 consecutive failures trigger circuit open
- **Recovery Timeout**: 30-second recovery period
- **Per-Follower State**: Individual circuit breakers for each broker

### **Retry Mechanisms**
- **Exponential Backoff**: 1-second base delay with attempt multiplication
- **Max Retry Attempts**: 2 additional attempts beyond initial
- **Selective Retry**: Only retry network-related failures

### **Data Integrity**
- **CRC32 Checksums**: Message integrity validation during reads
- **Segment Validation**: Consistency checks during recovery
- **Atomic Operations**: Thread-safe offset and HWM management

---

## ⏰ **Background Tasks & Scheduling**

### **ISR Monitoring** (30-second intervals)
- Lag threshold breach detection
- Batch status reporting to controller
- Membership change monitoring (TODO)

### **Async Operations**
- Replication tasks for acks=0 and acks=1
- HWM advancement after ISR acknowledgment
- Background follower communication

---

## 🔄 **Data Flow Context**

### **Write Path (Producer → Storage Service)**
```
Producer Request → REST Controller → Validation → WAL Append → Replication Coordination → ISR Followers → Acknowledgments → HWM Update → Producer Response
```

### **Read Path (Consumer → Storage Service)**
```
Consumer Request → REST Controller → WAL Read → Long Polling (if needed) → Message Batch → Consumer Response
```

### **Replication Path (Leader → Followers)**
```
Leader WAL Append → Replication Manager → ISR Selection → HTTP Requests → Follower Processing → Acknowledgments → HWM Advancement
```

### **Metadata Path (Controller → Storage Nodes)**
```
Controller Changes → Push Notifications → Metadata Store Update → Local State Refresh → Leadership Updates
```

---

## 🎯 **Current Implementation Status**

- ✅ **Message Production**: Full implementation with all ack semantics
- ✅ **Message Consumption**: Basic implementation with long polling
- ✅ **Replication**: ISR-based with circuit breakers and retries
- ✅ **Persistence**: WAL with segment management and recovery
- ✅ **Monitoring**: ISR status reporting and lag calculation
- ⚠️ **Long Polling**: Basic busy-wait (needs optimization)
- ⚠️ **Lag Calculation**: Implemented but needs consistency improvements
- ❌ **Service Discovery**: Static config (needs dynamic implementation)
- ❌ **Dynamic Metadata Push URLs**: Hardcoded (needs configurability)

The storage service serves as the **core data plane** of the Kafka clone, handling all message persistence, replication coordination, and consumer serving with robust fault tolerance mechanisms.