# DMQ Kafka Clone - Architecture Diagrams

## System Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         CLIENT APPLICATIONS                              │
│                                                                          │
│  ┌──────────────────────┐              ┌──────────────────────┐         │
│  │  Producer App        │              │  Consumer App        │         │
│  │  (Your Application)  │              │  (Your Application)  │         │
│  └──────────┬───────────┘              └──────────┬───────────┘         │
│             │                                     │                      │
│             │  (uses dmq-client)                  │  (uses dmq-client)   │
│             │                                     │                      │
│  ┌──────────▼───────────┐              ┌─────────▼────────────┐         │
│  │   DMQProducer        │              │   DMQConsumer        │         │
│  │   (Client Library)   │              │   (Client Library)   │         │
│  └──────────┬───────────┘              └─────────┬────────────┘         │
└─────────────┼────────────────────────────────────┼─────────────────────┘
              │                                     │
              │ HTTP/REST                           │ HTTP/REST
              │                                     │
┌─────────────▼─────────────────────────────────────▼─────────────────────┐
│                        DMQ CLUSTER SERVICES                              │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────┐     │
│  │             Metadata Service (port 8081)                       │     │
│  │  ┌──────────────────────────────────────────────────────────┐  │     │
│  │  │  REST API:                                               │  │     │
│  │  │  - POST /topics (create)                                 │  │     │
│  │  │  - GET /topics/{name} (get metadata)                     │  │     │
│  │  │  - GET /topics (list all)                                │  │     │
│  │  └──────────────┬───────────────────────────────────────────┘  │     │
│  │                 │                                               │     │
│  │  ┌──────────────▼───────────────────────────────────────────┐  │     │
│  │  │  MetadataService         ControllerService              │  │     │
│  │  │  - Topic CRUD            - Partition Assignment         │  │     │
│  │  │  - Partition Metadata    - Leader Election              │  │     │
│  │  │  - Consumer Offsets      - Failure Detection            │  │     │
│  │  └──────────────┬───────────────────┬───────────────────────┘  │     │
│  │                 │                   │                           │     │
│  │  ┌──────────────▼─────────┐  ┌──────▼──────────────────────┐   │     │
│  │  │  PostgreSQL            │  │  ZooKeeper                  │   │     │
│  │  │  (Topic/Partition DB)  │  │  (Cluster Coordination)     │   │     │
│  │  └────────────────────────┘  └─────────────────────────────┘   │     │
│  └────────────────────────────────────────────────────────────────┘     │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────┐     │
│  │          Storage Service / Broker 1 (port 8081:9091)           │     │
│  │  ┌──────────────────────────────────────────────────────────┐  │     │
│  │  │  REST API:                                               │  │     │
│  │  │  - POST /produce (write messages)                        │  │     │
│  │  │  - POST /consume (read messages)                         │  │     │
│  │  └──────────────┬───────────────────────────────────────────┘  │     │
│  │                 │                                               │     │
│  │  ┌──────────────▼───────────────────────────────────────────┐  │     │
│  │  │  StorageService          ReplicationManager              │  │     │
│  │  │  - Message Append        - Leader-Follower Sync          │  │     │
│  │  │  - Message Fetch         - ISR Management                │  │     │
│  │  └──────────────┬───────────────────────────────────────────┘  │     │
│  │                 │                                               │     │
│  │  ┌──────────────▼───────────────────────────────────────────┐  │     │
│  │  │  Write-Ahead Log (WAL)                                   │  │     │
│  │  │  - Segment-based files                                   │  │     │
│  │  │  - Sequential writes                                     │  │     │
│  │  │  - Indexed reads                                         │  │     │
│  │  └──────────────┬───────────────────────────────────────────┘  │     │
│  │                 │                                               │     │
│  │  ┌──────────────▼───────────────────────────────────────────┐  │     │
│  │  │  File System:                                            │  │     │
│  │  │  data/logs/topic-0/partition-0/0000000000.log            │  │     │
│  │  │  data/logs/topic-0/partition-1/0000000000.log            │  │     │
│  │  └──────────────────────────────────────────────────────────┘  │     │
│  └────────────────────────────────────────────────────────────────┘     │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────┐     │
│  │          Storage Service / Broker 2 (port 8082:9092)           │     │
│  │                      (Same structure)                           │     │
│  └────────────────────────────────────────────────────────────────┘     │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────┐     │
│  │          Storage Service / Broker 3 (port 8083:9093)           │     │
│  │                      (Same structure)                           │     │
│  └────────────────────────────────────────────────────────────────┘     │
└──────────────────────────────────────────────────────────────────────────┘
```

## Message Flow: Producer to Consumer

```
┌──────────┐
│ Producer │
│   App    │
└─────┬────┘
      │ 1. send("orders", key, value)
      ▼
┌─────────────┐
│ DMQProducer │
│  (Client)   │
└─────┬───────┘
      │ 2. GET /api/v1/metadata/topics/orders
      ▼
┌──────────────┐
│  Metadata    │
│   Service    │ Returns: {topic: "orders", partitions: [
└─────┬────────┘           {id: 0, leader: "broker-1:9092"},
      │                    {id: 1, leader: "broker-2:9093"} ]}
      │ 3. Partition metadata response
      ▼
┌─────────────┐
│ DMQProducer │
│  (Client)   │  4. Calculate partition using key hash
└─────┬───────┘     partition = hash(key) % 2 = 1
      │             leader = "broker-2:9093"
      │
      │ 5. POST /api/v1/storage/produce
      │    {topic: "orders", partition: 1, key, value}
      ▼
┌──────────────┐
│   Storage    │
│ Service (B2) │
└─────┬────────┘
      │ 6. Append to WAL
      ▼
┌──────────────┐
│ WriteAheadLog│  7. Write to segment file
│   (B2-P1)    │     data/logs/orders/1/0000000000.log
└─────┬────────┘
      │
      │ 8. If acks > 0, replicate to followers
      ▼
┌──────────────┐
│ Replication  │  9. Send to follower brokers
│   Manager    │     (Broker 1, Broker 3)
└─────┬────────┘
      │
      │ 10. Response: {offset: 42, success: true}
      ▼
┌─────────────┐
│ DMQProducer │
│  (Client)   │
└─────┬───────┘
      │ 11. Return Future/Response to app
      ▼
┌──────────┐
│ Producer │
│   App    │
└──────────┘

─────────────────────────────────────────────────────────────────

┌──────────┐
│ Consumer │
│   App    │
└─────┬────┘
      │ 1. poll(timeout)
      ▼
┌─────────────┐
│ DMQConsumer │
│  (Client)   │
└─────┬───────┘
      │ 2. GET /api/v1/metadata/topics/orders
      ▼
┌──────────────┐
│  Metadata    │  3. Return partition metadata
│   Service    │
└─────┬────────┘
      │
      ▼
┌─────────────┐
│ DMQConsumer │  4. Determine partitions to fetch from
│  (Client)   │     (based on consumer group assignment)
└─────┬───────┘
      │
      │ 5. POST /api/v1/storage/consume
      │    {topic: "orders", partition: 1, offset: 40}
      ▼
┌──────────────┐
│   Storage    │
│ Service (B2) │
└─────┬────────┘
      │ 6. Read from WAL
      ▼
┌──────────────┐
│ WriteAheadLog│  7. Read from segment file
│   (B2-P1)    │     starting at offset 40
└─────┬────────┘
      │
      │ 8. Response: {messages: [...], highWaterMark: 50}
      ▼
┌─────────────┐
│ DMQConsumer │  9. Update offset position
│  (Client)   │
└─────┬───────┘
      │ 10. Return messages to app
      ▼
┌──────────┐
│ Consumer │
│   App    │
└──────────┘
```

## Data Flow: Topic Creation

```
1. Client Request
   POST /api/v1/metadata/topics
   {topicName: "orders", partitionCount: 3, replicationFactor: 2}
        │
        ▼
2. MetadataController
   - Validates request
   - Calls MetadataService
        │
        ▼
3. MetadataService
   - Checks if topic exists
   - Creates TopicConfig
   - Calls ControllerService
        │
        ▼
4. ControllerService
   - Gets active brokers
   - Assigns partitions to brokers
   - Round-robin assignment:
     * Partition 0: Leader=B1, Replicas=[B1, B2]
     * Partition 1: Leader=B2, Replicas=[B2, B3]
     * Partition 2: Leader=B3, Replicas=[B3, B1]
        │
        ▼
5. MetadataService
   - Creates TopicEntity
   - Saves to PostgreSQL
        │
        ▼
6. [Future] Notify Storage Nodes
   - Send partition assignments
   - Storage nodes create WAL directories
        │
        ▼
7. Response
   {topicName: "orders", partitionCount: 3, ...}
```

## Replication Flow (Future Implementation)

```
┌──────────────────────┐
│   Leader (Broker 1)  │
│   Partition 0        │
└──────────┬───────────┘
           │
           │ 1. Producer writes to leader
           │
           ▼
     ┌─────────┐
     │   WAL   │
     └─────┬───┘
           │
           │ 2. Append to log, assign offset
           │
           ├────────────┬────────────┐
           │            │            │
           ▼            ▼            ▼
    ┌──────────┐  ┌──────────┐  ┌──────────┐
    │ Follower │  │ Follower │  │ Follower │
    │ (Broker2)│  │ (Broker3)│  │ (Broker4)│
    └────┬─────┘  └────┬─────┘  └────┬─────┘
         │             │             │
         │ 3. Fetch request (offset: X)
         │             │             │
         ◄─────────────┼─────────────┘
         │             │
         │ 4. Return messages
         ├─────────────►
         │             │
         │ 5. Append to local WAL
         ▼             ▼
    ┌─────────┐  ┌─────────┐
    │   WAL   │  │   WAL   │
    └─────────┘  └─────────┘
         │             │
         │ 6. Send ACK
         └─────────────►
                       │
                       │ 7. Update ISR
                       │    Update HWM
                       ▼
              ┌─────────────────┐
              │ Metadata Service│
              │  ISR: [1,2,3]   │
              │  HWM: X+1       │
              └─────────────────┘
```

## Layered Architecture (per service)

```
┌─────────────────────────────────────────────────────────────┐
│                    CONTROLLER LAYER                         │
│  @RestController, @RequestMapping                           │
│                                                              │
│  Responsibilities:                                           │
│  - HTTP request/response handling                            │
│  - Input validation (@Valid, @Validated)                     │
│  - Request routing                                           │
│  - Response formatting                                       │
│                                                              │
│  Example: MetadataController.java                            │
│  @PostMapping("/topics")                                     │
│  public ResponseEntity<TopicMetadataResponse> createTopic(   │
│      @Validated @RequestBody CreateTopicRequest request)     │
└──────────────────────────┬──────────────────────────────────┘
                           │ Delegates to
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                     SERVICE LAYER                           │
│  @Service, @Transactional                                   │
│                                                              │
│  Responsibilities:                                           │
│  - Business logic                                            │
│  - Transaction management                                    │
│  - Orchestration of operations                               │
│  - Domain model manipulation                                 │
│                                                              │
│  Example: MetadataServiceImpl.java                           │
│  public TopicMetadata createTopic(CreateTopicRequest req) {  │
│      // Business logic                                       │
│      // Call repository                                      │
│  }                                                           │
└──────────────────────────┬──────────────────────────────────┘
                           │ Uses
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                  REPOSITORY/DAO LAYER                       │
│  @Repository, Spring Data JPA                               │
│                                                              │
│  Responsibilities:                                           │
│  - Data access                                               │
│  - CRUD operations                                           │
│  - Query execution                                           │
│  - Entity mapping                                            │
│                                                              │
│  Example: TopicRepository.java                               │
│  public interface TopicRepository                            │
│      extends JpaRepository<TopicEntity, Long>                │
└──────────────────────────┬──────────────────────────────────┘
                           │ Accesses
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    DATABASE LAYER                           │
│  PostgreSQL, File System                                    │
│                                                              │
│  - Persistent storage                                        │
│  - ACID transactions                                         │
│  - Data integrity                                            │
└─────────────────────────────────────────────────────────────┘
```

## Package Structure Pattern

```
com.distributedmq.<service>/
├── <Service>Application.java     # Spring Boot main class
├── controller/                    # REST endpoints
│   └── *Controller.java
├── service/                       # Business logic
│   ├── *Service.java              # Interface
│   └── *ServiceImpl.java          # Implementation
├── repository/                    # Data access (for metadata service)
│   └── *Repository.java
├── entity/                        # JPA entities (for metadata service)
│   └── *Entity.java
├── dto/                           # Request/Response DTOs
│   ├── *Request.java
│   └── *Response.java
├── config/                        # Configuration classes
│   └── *Config.java
└── util/                          # Utilities
    └── *Util.java
```

## Sequence Diagram: Full Message Lifecycle

```
Producer    DMQProducer    Metadata    Storage(L)   Storage(F1)  Storage(F2)
   │             │            │            │             │            │
   │─send()────► │            │            │             │            │
   │             │            │            │             │            │
   │             │─getMetadata()─────────► │             │            │
   │             │            │            │             │            │
   │             │◄─metadata──┘            │             │            │
   │             │                         │             │            │
   │             │─append()───────────────►│             │            │
   │             │                         │             │            │
   │             │                         │─replicate()─►│            │
   │             │                         │             │            │
   │             │                         │─replicate()───────────────►│
   │             │                         │             │            │
   │             │                         │◄─ack────────┘            │
   │             │                         │             │            │
   │             │                         │◄─ack─────────────────────┘│
   │             │                         │             │            │
   │             │◄─response───────────────┘             │            │
   │             │                         │             │            │
   │◄─Future─────┘                         │             │            │
   │                                       │             │            │

─────────────────────────────────────────────────────────────────────────

Consumer    DMQConsumer    Metadata    Storage     MetadataService
   │             │            │            │             │
   │─poll()────► │            │            │             │
   │             │            │            │             │
   │             │─getMetadata()─────────► │             │
   │             │            │            │             │
   │             │◄─metadata──┘            │             │
   │             │                         │             │
   │             │─getOffset()─────────────────────────► │
   │             │                         │             │
   │             │◄─offset─────────────────────────────┘ │
   │             │                         │             │
   │             │─fetch()────────────────►│             │
   │             │                         │             │
   │             │◄─messages───────────────┘             │
   │             │                         │             │
   │             │─commitOffset()──────────────────────► │
   │             │                         │             │
   │◄─messages───┘                         │             │
   │                                       │             │
```

---

**Legend**:
- `────►` : Synchronous call
- `- - - ►` : Asynchronous call
- `◄────` : Response
- (L) : Leader
- (F) : Follower
