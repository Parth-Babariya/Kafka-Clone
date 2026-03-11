# Project Structure - DMQ Kafka Clone

## Directory Tree

```
Kafka-Clone/
├── pom.xml                          # Parent POM
├── README.md                        # Main project documentation
├── .gitignore
│
├── dmq-common/                      # Shared utilities and models
│   ├── pom.xml
│   ├── README.md
│   └── src/main/java/com/distributedmq/common/
│       ├── model/                   # Domain models
│       │   ├── Message.java
│       │   ├── MessageHeaders.java
│       │   ├── TopicMetadata.java
│       │   ├── TopicConfig.java
│       │   ├── PartitionMetadata.java
│       │   ├── BrokerNode.java
│       │   ├── BrokerStatus.java
│       │   └── ConsumerOffset.java
│       ├── dto/                     # Data Transfer Objects
│       │   ├── ProduceRequest.java
│       │   ├── ProduceResponse.java
│       │   ├── ConsumeRequest.java
│       │   └── ConsumeResponse.java
│       ├── exception/               # Custom exceptions
│       │   ├── DMQException.java
│       │   ├── TopicNotFoundException.java
│       │   ├── PartitionNotAvailableException.java
│       │   └── LeaderNotAvailableException.java
│       └── util/                    # Utility classes
│           ├── ChecksumUtil.java
│           └── PartitionUtil.java
│
├── dmq-client/                      # Producer & Consumer clients
│   ├── pom.xml
│   ├── README.md
│   └── src/main/java/com/distributedmq/client/
│       ├── producer/
│       │   ├── Producer.java        # Producer interface
│       │   ├── ProducerConfig.java
│       │   └── DMQProducer.java     # Implementation
│       └── consumer/
│           ├── Consumer.java        # Consumer interface
│           ├── ConsumerConfig.java
│           ├── DMQConsumer.java     # Implementation
│           └── TopicPartition.java
│
├── dmq-metadata-service/            # Metadata & Controller service
│   ├── pom.xml
│   ├── README.md
│   ├── src/main/java/com/distributedmq/metadata/
│   │   ├── MetadataServiceApplication.java
│   │   ├── controller/              # REST API Layer
│   │   │   └── MetadataController.java
│   │   ├── service/                 # Business Logic Layer
│   │   │   ├── MetadataService.java
│   │   │   ├── MetadataServiceImpl.java
│   │   │   ├── ControllerService.java
│   │   │   └── ControllerServiceImpl.java
│   │   ├── entity/                  # JPA Entities
│   │   │   └── TopicEntity.java
│   │   ├── repository/              # Data Access Layer
│   │   │   └── TopicRepository.java
│   │   ├── dto/
│   │   │   ├── CreateTopicRequest.java
│   │   │   └── TopicMetadataResponse.java
│   │   └── coordination/            # ZooKeeper integration
│   │       └── ZooKeeperClient.java
│   └── src/main/resources/
│       └── application.yml          # Configuration
│
└── dmq-storage-service/             # Storage/Broker service
    ├── pom.xml
    ├── README.md
    ├── src/main/java/com/distributedmq/storage/
    │   ├── StorageServiceApplication.java
    │   ├── controller/              # REST API Layer
    │   │   └── StorageController.java
    │   ├── service/                 # Business Logic Layer
    │   │   ├── StorageService.java
    │   │   ├── StorageServiceImpl.java
    │   │   └── ReplicationManager.java
    │   └── wal/                     # Write-Ahead Log
    │       ├── WriteAheadLog.java
    │       └── LogSegment.java
    └── src/main/resources/
        └── application.yml          # Configuration
```

## Module Dependencies

```
dmq-client ──────┐
                 ├──> dmq-common
dmq-metadata ────┤
                 │
dmq-storage ─────┘
```

## Layered Architecture (per service)

### Layer Structure

```
┌─────────────────────────────────────────┐
│         Controller Layer                │  <- REST endpoints, validation
│    (@RestController, @RequestMapping)   │
└──────────────┬──────────────────────────┘
               │
┌──────────────▼──────────────────────────┐
│          Service Layer                  │  <- Business logic
│        (@Service, @Transactional)       │
└──────────────┬──────────────────────────┘
               │
┌──────────────▼──────────────────────────┐
│     Repository/DAO Layer                │  <- Data access
│    (@Repository, Spring Data JPA)       │
└──────────────┬──────────────────────────┘
               │
┌──────────────▼──────────────────────────┐
│       Database / External               │  <- PostgreSQL, ZooKeeper
└─────────────────────────────────────────┘
```

## Technology Stack

- **Java**: 17
- **Framework**: Spring Boot 3.1.5
- **Build Tool**: Maven
- **Database**: PostgreSQL (metadata), File System (message storage)
- **Coordination**: Apache Curator + ZooKeeper
- **Networking**: Netty (for replication)
- **Serialization**: Jackson (JSON)
- **Utilities**: Lombok, MapStruct

## Port Allocation

- **Metadata Service**: 8081 (HTTP), 2181 (ZooKeeper)
- **Storage Service (Broker 1)**: 8081 (HTTP), 9091 (Broker)
- **Storage Service (Broker 2)**: 8082 (HTTP), 9092 (Broker)
- **Storage Service (Broker 3)**: 8083 (HTTP), 9093 (Broker)

## Build & Run

### Build All Modules
```bash
cd Kafka-Clone
mvn clean install
```

### Run Metadata Service
```bash
cd dmq-metadata-service
mvn spring-boot:run
```

### Run Storage Service (Broker 1)
```bash
cd dmq-storage-service
mvn spring-boot:run -Dspring-boot.run.arguments="--broker.id=1 --server.port=8082 --broker.port=9092"
```

### Run Multiple Brokers
```bash
# Terminal 1 - Broker 1
mvn spring-boot:run -Dspring-boot.run.arguments="--broker.id=1 --server.port=8081 --broker.port=9091"

# Terminal 2 - Broker 2
mvn spring-boot:run -Dspring-boot.run.arguments="--broker.id=2 --server.port=8082 --broker.port=9092"

# Terminal 3 - Broker 3
mvn spring-boot:run -Dspring-boot.run.arguments="--broker.id=3 --server.port=8083 --broker.port=9093"
```

## Implementation Roadmap

### Phase 1: Core Infrastructure ✅
- [x] Maven multi-module setup
- [x] Common models and DTOs
- [x] Exception hierarchy
- [x] Utility classes

### Phase 2: Metadata Service
- [ ] Topic CRUD operations
- [ ] Partition assignment logic
- [ ] ZooKeeper integration
- [ ] Controller leader election
- [ ] Heartbeat monitoring
- [ ] Broker registry

### Phase 3: Storage Service
- [ ] WAL implementation
- [ ] Message persistence
- [ ] Segment management
- [ ] Message fetching
- [ ] Log retention
- [ ] Checksum validation

### Phase 4: Replication
- [ ] Leader/follower protocol
- [ ] ISR management
- [ ] Replication protocol
- [ ] Failure detection
- [ ] Leader election

### Phase 5: Client Library
- [ ] Producer implementation
- [ ] Metadata fetching
- [ ] Message batching
- [ ] Partitioner logic
- [ ] Consumer implementation
- [ ] Consumer group coordination
- [ ] Offset management
- [ ] Rebalancing

### Phase 6: Advanced Features
- [ ] Compression
- [ ] Transactions
- [ ] Exactly-once semantics
- [ ] Log compaction
- [ ] SSL/TLS
- [ ] Authentication/Authorization

## Key Design Decisions

1. **Layered Architecture**: Clear separation of concerns (Controller → Service → Repository)
2. **Spring Boot**: Rapid development, dependency injection, auto-configuration
3. **PostgreSQL**: Reliable metadata storage with ACID guarantees
4. **File-based WAL**: Sequential disk writes for high throughput
5. **Segment-based logs**: Efficient retention and compaction
6. **ZooKeeper**: Distributed coordination (leader election, configuration)
7. **Netty**: High-performance network I/O for replication

## Testing Strategy

- **Unit Tests**: Service layer logic with mocked dependencies
- **Integration Tests**: Repository layer with H2 in-memory database
- **End-to-End Tests**: Full flow from producer to consumer
- **Performance Tests**: Throughput and latency benchmarks

## Next Steps

1. **Complete WAL implementation** (read, retention, compaction)
2. **Implement replication protocol** (leader-follower sync)
3. **Add controller logic** (failure detection, leader election)
4. **Complete client library** (producer batching, consumer groups)
5. **Add monitoring** (metrics, health checks, logging)
6. **Write documentation** (API docs, deployment guide)

## Resources

- [Apache Kafka Documentation](https://kafka.apache.org/documentation/)
- [Spring Boot Reference](https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/)
- [Apache Curator](https://curator.apache.org/)
- [Netty Guide](https://netty.io/wiki/user-guide.html)
