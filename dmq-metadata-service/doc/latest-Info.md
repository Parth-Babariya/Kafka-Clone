# 📋 **Metadata Service Responsibilities & Flows**

Based on the current implementation, here's a comprehensive breakdown of all responsibilities and flows handled by the **Metadata Service** (KRaft-based controller and metadata management):

---

## 🏗️ **Core Architecture & Responsibilities**

### **1. KRaft Controller Coordination (Raft-based)**
**Components**: `RaftController.java`, `RaftState.java`, `MetadataStateMachine.java`
- **Leader Election**: Raft consensus algorithm for controller leader selection
- **Metadata Consensus**: Replicated state machine for metadata consistency
- **Term Management**: Raft terms for leader legitimacy and epoch handling
- **Log Replication**: Raft log entries for metadata operations

### **2. Metadata Management & Persistence**
**Components**: `MetadataService.java`, `MetadataServiceImpl.java`, `TopicRepository.java`, `BrokerRepository.java`
- **Topic CRUD Operations**: Create, read, update, delete topics with configuration
- **Broker Registration**: Dynamic broker registration and status tracking
- **Metadata Versioning**: Timestamp-based metadata versioning for consistency
- **JPA Persistence**: Database-backed metadata storage with JSON config serialization

### **3. REST API Endpoints**
**Component**: `MetadataController.java`
- **Topic Management**: `POST/GET/DELETE /api/v1/metadata/topics`
- **Broker Management**: `POST/GET /api/v1/metadata/brokers`
- **Controller Info**: `GET /api/v1/metadata/controller`
- **Metadata Sync**: `POST/GET /api/v1/metadata/sync` (push/pull synchronization)
- **Heartbeat Processing**: Multiple heartbeat endpoints for different service types
- **ISR Management**: `POST /api/v1/metadata/isr-status-batch` (ISR status updates)

---

## 🔄 **Topic Creation Flow**

### **Controller Leader-Side Topic Creation**

1. **Request Validation**
   - Controller leader verification (only leader can create topics)
   - Topic name uniqueness validation
   - Partition count and replication factor validation

2. **Partition Assignment** [check leader election algorithms]
   - Round-robin broker assignment across available storage nodes
   - Replication factor consideration for replica placement
   - Leader election for each partition (first replica becomes leader)

3. **Metadata Persistence**
   - Topic entity creation with JPA persistence
   - Partition metadata generation and storage
   - Configuration serialization to JSON

4. **Push Synchronization**
   - Metadata push to all storage nodes via `MetadataPushService`
   - Broker list inclusion for storage node awareness
   - Asynchronous push with response collection

5. **Response Construction**
   - Complete topic metadata return with partition assignments
   - Creation timestamp and configuration details

---

## 🔄 **Broker Registration Flow**

### **Dynamic Broker Registration**

1. **Registration Request Processing**
   - Broker ID and endpoint validation
   - Duplicate broker detection and conflict resolution
   - Controller leader verification for write operations

2. **Broker Entity Creation**
   - Broker information persistence to database
   - Status initialization (ONLINE/OFFLINE)
   - Service pairing configuration

3. **Cluster State Update**
   - Active broker list updates
   - Partition reassignment triggers (if needed)
   - Metadata version increment

4. **Push Notifications**
   - Metadata updates pushed to all storage nodes
   - Broker list synchronization across cluster

---

## 🔄 **Metadata Synchronization Flows**

### **Push Synchronization (Controller → Storage Nodes)**
**Component**: `MetadataPushService.java`

1. **Change Detection**
   - Topic creation/update triggers push synchronization
   - Broker registration triggers cluster metadata updates
   - ISR changes trigger selective metadata pushes

2. **Metadata Packaging**
   - Complete topic and broker information aggregation
   - Partition assignments and leadership information
   - Configuration and timestamp metadata

3. **Push Execution**
   - HTTP POST to all storage node `/api/v1/storage/metadata` endpoints
   - Parallel push operations with response collection
   - Error handling and retry logic for failed pushes

4. **Consistency Verification**
   - Response validation from storage nodes
   - Metadata version synchronization
   - Push success/failure logging and monitoring

### **Pull Synchronization (Storage → Metadata Services)**

1. **Sync Request Processing**
   - Metadata service URL validation
   - Controller leader verification for sync operations
   - Request authentication and authorization

2. **Full Metadata Retrieval**
   - Complete topic and broker list aggregation
   - Current partition assignments and leadership
   - Configuration and metadata versions

3. **HTTP Response Delivery**
   - JSON-formatted metadata payload
   - Sync timestamp and version information
   - Incremental sync support (TODO: delta-only sync)

---

## 💓 **Heartbeat Processing Flows**

### **Storage Service Heartbeats**
**Endpoints**: `/heartbeat`, `/storage-heartbeat`, `/storage-controller-heartbeat`

1. **Heartbeat Reception**
   - Multiple heartbeat format support (simple, detailed, controller-specific)
   - Broker ID extraction and validation
   - Timestamp and status information processing

2. **Broker Status Updates**
   - ONLINE/OFFLINE status tracking
   - Last heartbeat timestamp updates
   - Broker registry maintenance

3. **ISR Status Processing** (Controller Heartbeats)
   - Per-partition status analysis (LEO, HWM, lag, role)
   - Lag threshold monitoring and ISR management triggers
   - Leadership verification and partition health assessment

4. **Metadata Sync Validation**
   - Storage node metadata version comparison
   - Outdated metadata detection and sync triggers
   - Controller instructions for metadata refresh

### **ISR Status Batch Updates**
**Endpoint**: `/isr-status-batch`

1. **Batch ISR Processing**
   - Multiple ISR status updates in single request
   - Lag threshold breach detection
   - ISR membership change processing

2. **ISR Management Actions**
   - **Lag Threshold Breached**: Remove broker from ISR, trigger reassignment
   - **Lag Threshold Recovered**: Add broker back to ISR, expand replication
   - **Membership Changes**: ISR composition updates and notifications

3. **Controller Response Generation**
   - Acknowledgment of ISR updates
   - Controller instructions for storage nodes
   - Metadata version synchronization

---

## 🎯 **Controller Coordination Flows**

### **Partition Assignment & Leadership**
**Component**: `ControllerServiceImpl.java`

1. **Assignment Algorithm**
   - Round-robin distribution across available brokers
   - Replication factor consideration for replica placement
   - Rack awareness and fault domain separation (TODO)

2. **Leadership Election**
   - First replica designated as partition leader
   - ISR initialization with all replicas
   - Leadership metadata updates

3. **Rebalancing Triggers**
   - Broker failure detection and reassignment
   - New broker registration triggers
   - Partition count changes (TODO)

### **ISR Management**

1. **ISR Shrink Operations**
   - Lag threshold exceeded detection
   - Broker removal from ISR lists
   - Replication factor impact assessment

2. **ISR Expansion Operations**
   - Lag recovery detection
   - Broker re-addition to ISR
   - Replication restoration

3. **ISR State Persistence**
   - ISR membership tracking per partition
   - Metadata updates and synchronization
   - Storage node notifications

---

## 📊 **Background Tasks & Monitoring**

### **Heartbeat Schedulers**
**Component**: `MetadataHeartbeatScheduler.java`
- **Inter-Service Heartbeats**: 30-second intervals between metadata services
- **Controller Sync Validation**: Metadata version and sync status checks
- **Failure Detection**: Automated detection of out-of-sync services

### **ISR Monitoring**
- **Lag Threshold Monitoring**: Continuous ISR health assessment
- **Membership Tracking**: ISR composition changes and notifications
- **Rebalancing Triggers**: Automated partition reassignment on failures

---

## 🔄 **Data Flow Context**

### **Topic Creation Path**
```
Client Request → Metadata Controller → Controller Leader Check → Partition Assignment → Metadata Persistence → Push to Storage Nodes → Topic Metadata Response
```

### **Broker Registration Path**
```
Broker Registration → Metadata Controller → Validation → Entity Creation → Metadata Push → Broker Response
```

### **Heartbeat Processing Path**
```
Storage Heartbeat → Metadata Controller → Status Update → ISR Analysis → Controller Instructions → Heartbeat Response
```

### **ISR Management Path**
```
ISR Status Batch → Lag Analysis → ISR Updates → Controller Actions → Storage Notifications → ISR Response
```

### **Metadata Sync Path**
```
Sync Request → Metadata Aggregation → HTTP Response → Storage Node Updates → Consistency Verification
```

---

## 🛡️ **Fault Tolerance & Consistency**

### **Controller Leader Election**
- **Raft Consensus**: Distributed leader election with term management
- **Failover Handling**: Automatic leadership transfer on failures
- **Split-Brain Prevention**: Term-based legitimacy validation

### **Metadata Consistency**
- **Version-based Sync**: Timestamp and version tracking for consistency
- **Push/Pull Mechanisms**: Multiple synchronization strategies
- **Conflict Resolution**: Leader-based conflict resolution for metadata updates

### **Broker Failure Handling**
- **Heartbeat-based Detection**: Automated failure detection via missed heartbeats
- **ISR Management**: Automatic ISR shrinking and rebalancing
- **Partition Reassignment**: Leader election for affected partitions

---

## 🎯 **Current Implementation Status**

- ✅ **Topic Management**: Full CRUD operations with persistence
- ✅ **Broker Registration**: Dynamic broker management and status tracking
- ✅ **Metadata Sync**: Push/pull synchronization mechanisms
- ✅ **Heartbeat Processing**: Multiple heartbeat types with ISR management
- ✅ **Controller Coordination**: Basic Raft controller with leadership
- ✅ **ISR Management**: Lag-based ISR expansion/contraction
- ⚠️ **Raft Implementation**: Basic structure (needs full consensus implementation)
- ⚠️ **Incremental Sync**: Full sync only (delta sync TODO)
- ❌ **Consumer Group Management**: Not implemented
- ❌ **Offset Management**: Not implemented
- ❌ **Rebalancing Logic**: Basic implementation (needs enhancement)

The metadata service serves as the **control plane** of the Kafka clone, managing cluster coordination, metadata consistency, and dynamic configuration updates across all storage nodes through robust synchronization mechanisms.