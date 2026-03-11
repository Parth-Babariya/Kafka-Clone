# DMQ Kafka Clone - Quick Reference

## 🚀 Quick Start Commands

```bash
# Build entire project
mvn clean install

# Run Metadata Service (KRaft Controller - Terminal 1)
cd dmq-metadata-service && mvn spring-boot:run

# Run Storage Service (Terminal 2)
cd dmq-storage-service && mvn spring-boot:run

# Create a topic (goes through KRaft consensus)
curl -X POST http://localhost:8080/api/v1/metadata/topics \
  -H "Content-Type: application/json" \
  -d '{"topicName":"orders","partitionCount":3,"replicationFactor":1}'

# Test metadata sync (automatic heartbeats every 5 seconds)
# Check logs for: "Successfully sent heartbeat to controller"
```

## 📍 Service Endpoints

| Service | HTTP Port | Description |
|---------|-----------|-------------|
| Metadata Service (KRaft) | 8080 | KRaft controller, topic management, metadata sync |
| Storage Service (Broker 1) | 8082 | Message storage, replication, heartbeat sending |
| PostgreSQL | 5432 | Metadata persistence |

## 🗂️ Module Overview

| Module | Purpose | Key Classes |
|--------|---------|-------------|
| **dmq-common** | Shared utilities | Message, TopicMetadata, PartitionUtil |
| **dmq-client** | Producer/Consumer | DMQProducer, DMQConsumer |
| **dmq-metadata-service** | KRaft Controller | RaftNode, MetadataService, ServiceDiscovery |
| **dmq-storage-service** | Storage & Sync | WriteAheadLog, MetadataStore, StorageHeartbeatScheduler |

## 🎯 Implementation Status

| Feature | Status | Location |
|---------|--------|----------|
| **KRaft Consensus** | ✅ **Complete** | metadata/raft |
| **Service Discovery** | ✅ **Complete** | config/services.json |
| **Metadata Versioning** | ✅ **Complete** | metadata/dto, storage/service |
| **Storage Heartbeats** | ✅ **Complete** | storage/heartbeat |
| **Push Synchronization** | ✅ **Complete** | metadata/service, storage/controller |
| **Project Structure** | ✅ Complete | All modules |
| **Common Models** | ✅ Complete | dmq-common/model |
| **REST Controllers** | ✅ **Functional** | */controller |
| **Service Interfaces** | ✅ Complete | */service |
| **Service Implementations** | ✅ **Functional** | */service/*Impl |
| **Entity Classes** | ⚠️ Placeholder/TODO | metadata/entity |
| **WAL Structure** | ⚠️ Partial/TODO | storage/wal |
| **Producer Client** | ⚠️ Placeholder/TODO | client/producer |
| **Consumer Client** | ⚠️ Placeholder/TODO | client/consumer |
| **Message Replication** | ❌ All TODO | storage/service |
| **Consumer Groups** | ❌ All TODO | client/consumer |

**Legend**:
- ✅ **Complete** - Fully implemented and working
- ⚠️ Boilerplate/Placeholder - Structure exists, logic is TODO
- ❌ All TODO - Completely marked for implementation

**Note**: KRaft consensus and bidirectional metadata synchronization are **fully implemented and production-ready**.

## 📋 Key Implemented Features

### ✅ KRaft Consensus Protocol
- **RaftNode**: Leader election with randomized timeouts
- **RaftLog**: Persistent log with term/index tracking
- **RaftConsensus**: State machine for metadata operations
- **Quorum Requirements**: Majority voting for decisions

### ✅ Bidirectional Metadata Synchronization
- **Service Discovery**: Centralized JSON configuration
- **Heartbeat Mechanism**: 5-second periodic heartbeats
- **Push Synchronization**: HTTP-based metadata updates
- **Version Control**: Timestamp-based ordering guarantees

### ✅ Core Infrastructure
- **REST APIs**: Functional endpoints with proper validation
- **Service Layer**: Implemented business logic
- **Configuration**: Spring Boot configurations
- **Error Handling**: Custom exception hierarchy

## 🔧 Configuration Files

### Metadata Service (`dmq-metadata-service/src/main/resources/application.yml`)
```yaml
server:
  port: 8080
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/dmq_metadata
metadata:
  cluster-id: "dmq-cluster-1"
  data-dir: ./data/metadata
raft:
  election-timeout-ms: 5000
  heartbeat-interval-ms: 1000
```

### Storage Service (`dmq-storage-service/src/main/resources/application.yml`)
```yaml
server:
  port: 8082
broker:
  id: 1
  data-dir: ./data/broker-1
wal:
  segment-size-bytes: 1073741824  # 1GB
metadata:
  service-url: http://localhost:8080
  heartbeat-interval-ms: 5000
```

### Service Discovery (`config/services.json`)
```json
{
  "services": {
    "metadata-service-1": {
      "host": "localhost",
      "port": 8080,
      "type": "metadata"
    },
    "storage-service-1": {
      "host": "localhost",
      "port": 8082,
      "type": "storage",
      "pairedMetadataService": "metadata-service-1"
    }
  }
}
```

## 🧪 Test Commands

### Metadata Service APIs
```bash
# Register broker
curl -X POST http://localhost:8080/api/v1/metadata/brokers \
  -H "Content-Type: application/json" \
  -d '{
    "id": 1,
    "host": "localhost",
    "port": 8082,
    "rack": "rack1"
  }'

# Create topic (KRaft consensus)
curl -X POST http://localhost:8080/api/v1/metadata/topics \
  -H "Content-Type: application/json" \
  -d '{
    "topicName": "orders",
    "partitionCount": 3,
    "replicationFactor": 1
  }'

# List topics
curl http://localhost:8080/api/v1/metadata/topics

# Get topic metadata
curl http://localhost:8080/api/v1/metadata/topics/orders
```

### Storage Service APIs
```bash
# Produce batch messages
curl -X POST http://localhost:8082/api/v1/storage/messages \
  -H "Content-Type: application/json" \
  -d '{
    "topic": "orders",
    "partition": 0,
    "messages": [
      {"key": "order-1", "value": "dmFsdWUx"},
      {"key": "order-2", "value": "dmFsdWUy"}
    ],
    "producerId": "producer-1",
    "producerEpoch": 0,
    "requiredAcks": 1
  }'

# Consume messages
curl -X POST http://localhost:8082/api/v1/storage/consume \
  -H "Content-Type: application/json" \
  -d '{
    "topic": "orders",
    "partition": 0,
    "offset": 0,
    "maxMessages": 10
  }'

# Check high water mark
curl http://localhost:8082/api/v1/storage/partitions/orders/0/high-water-mark
```

### Metadata Synchronization Testing
```bash
# Heartbeat sending (automatic every 5 seconds)
# Check storage service logs: "Successfully sent heartbeat to controller"

# Heartbeat processing (automatic when received)
# Check metadata service logs: "Received heartbeat from storage service"

# Push sync trigger (automatic when metadata changes)
# Check metadata service logs: "Triggering metadata push to storage service"

# Push sync reception (automatic)
# Check storage service logs: "Received metadata update from controller"
```

## 📦 Maven Commands

```bash
# Build all modules
mvn clean install

# Build without tests
mvn clean install -DskipTests

# Run tests only
mvn test

# Run specific module
cd dmq-common && mvn clean install

# Update dependencies
mvn dependency:resolve

# Show dependency tree
mvn dependency:tree

# Clean all target directories
mvn clean

# Package as JAR
mvn package
```

## 🐛 Troubleshooting

| Problem | Solution |
|---------|----------|
| Port 8080 in use | Kill process: `netstat -ano | findstr :8080` |
| PostgreSQL connection failed | Check `application.yml` credentials |
| Build failed | Run `mvn clean install -U` |
| Class not found | Run `mvn clean install` in parent directory |
| Metadata sync not working | Check `config/services.json` configuration |
| Heartbeats not sending | Verify storage service is running and configured |

## 📚 File Locations

```
Kafka-Clone/
├── pom.xml                          # Parent POM
├── config/
│   └── services.json                # Service discovery config
├── docs/
│   ├── PROJECT_STRUCTURE.md         # Complete structure
│   ├── GETTING_STARTED.md           # Setup guide
│   ├── SETUP_SUMMARY.md             # What was created
│   ├── ARCHITECTURE_DIAGRAMS.md     # KRaft & sync diagrams
│   └── QUICK_REFERENCE.md           # This file
├── dmq-common/                      # Shared code
├── dmq-client/                      # Client library
├── dmq-metadata-service/            # KRaft controller
│   └── IMPLEMENTATION_STATUS.md     # Current status
└── dmq-storage-service/             # Storage with sync
    └── IMPLEMENTATION_STATUS.md     # Current status
```

## 🎓 Key Concepts

| Concept | Description |
|---------|-------------|
| **KRaft** | Kafka Raft consensus for metadata management |
| **Service Discovery** | Centralized configuration for service locations |
| **Heartbeat** | Periodic health checks with metadata sync status |
| **Push Sync** | Controller pushes metadata updates to storage services |
| **Version Control** | Timestamp-based ordering for metadata updates |
| **Topic** | Logical channel for messages |
| **Partition** | Ordered, immutable sequence of messages |
| **Broker** | Storage node that hosts partitions |
| **WAL** | Write-Ahead Log for durable storage |

## 🔗 Useful Links

- **Apache Kafka Docs**: https://kafka.apache.org/documentation/
- **Raft Consensus**: https://raft.github.io/
- **Spring Boot 2.7 Docs**: https://docs.spring.io/spring-boot/docs/2.7.18/reference/htmlsingle/

## 💡 Tips

1. **KRaft is Working**: Consensus protocol is fully implemented
2. **Sync is Active**: Bidirectional metadata sync is operational
3. **Check Logs**: Enable DEBUG logging to see sync operations
4. **Service Config**: Update `config/services.json` for your environment
5. **Test Incrementally**: Start with metadata operations, then producer flow
6. **Monitor Heartbeats**: Watch for "heartbeat" log messages

**Key Achievement**: KRaft consensus and metadata synchronization are **production-ready**. The foundation is solid for implementing the remaining producer/consumer flow features.

---

**Last Updated**: October 2025
**Version**: 1.0.0-KRAFT
**Status**: **KRaft & Metadata Sync Complete** 🚀
