# DMQ Kafka Clone - Setup Guide

This guide provides comprehensive instructions for setting up and running the DMQ Kafka Clone project, a distributed messaging system implementing Raft consensus for metadata management.

## Prerequisites

### Required Software
1. **Java Development Kit (JDK) 17+**
   ```bash
   java -version
   # Should show version 17 or higher
   ```

2. **Apache Maven 3.8+**
   ```bash
   mvn -version
   ```

3. **PostgreSQL 14+**
   ```bash
   psql --version
   ```

4. **Apache ZooKeeper 3.9+** (for cluster coordination)
   - Download from: https://zookeeper.apache.org/releases.html

### Optional Tools
- **Docker & Docker Compose** (for containerized deployment)
- **IntelliJ IDEA** or **Eclipse** (IDEs with Spring Boot support)
- **Postman** or **curl** (for API testing)

## Project Overview

DMQ Kafka Clone is a distributed messaging system with the following components:

### Module Structure
- **dmq-common**: Shared utilities, models, and DTOs
- **dmq-client**: Producer and Consumer client libraries
- **dmq-metadata-service**: KRaft-based metadata management and cluster controller
- **dmq-storage-service**: Message persistence, replication, and metadata synchronization

### Technology Stack
- **Java**: 11 (compatible with Spring Boot 2.7.18)
- **Framework**: Spring Boot 2.7.18 (Jakarta EE compatible)
- **Build**: Maven
- **Database**: PostgreSQL (metadata), File System (messages)
- **Consensus**: KRaft (Raft protocol implementation)
- **Networking**: Spring Web (HTTP REST)

## Initial Setup

### 1. Clone the Repository
```bash
git clone <your-repo-url>
cd Kafka-Clone
```

### 2. Build the Project
```bash
# Build all modules
mvn clean install

# Skip tests for faster build
mvn clean install -DskipTests
```

### 3. Setup PostgreSQL Database
```bash
# Login to PostgreSQL
psql -U postgres

# Create database and user
CREATE DATABASE dmq_metadata;
CREATE USER dmq_user WITH PASSWORD 'dmq_password';
GRANT ALL PRIVILEGES ON DATABASE dmq_metadata TO dmq_user;

# Exit
\q
```

### 4. Start ZooKeeper
```bash
# Navigate to ZooKeeper directory
cd /path/to/zookeeper

# Start ZooKeeper server
bin/zkServer.sh start     # Linux/Mac
bin\zkServer.cmd          # Windows

# Verify it's running
bin/zkCli.sh              # Connect to ZooKeeper CLI
```

## Running the Services

### Option 1: Run from Maven

#### Terminal 1 - Metadata Service
```bash
cd dmq-metadata-service
mvn spring-boot:run
```
The service will start on `http://localhost:8081`

#### Terminal 2 - Storage Service (Broker 1)
```bash
cd dmq-storage-service
mvn spring-boot:run
```
The service will start on `http://localhost:8082`

#### Terminal 3 - Storage Service (Broker 2) [Optional]
```bash
cd dmq-storage-service
mvn spring-boot:run -Dspring-boot.run.arguments="--broker.id=2 --server.port=8083 --broker.port=9093 --broker.data-dir=./data/broker-2"
```

### Option 2: Run JAR Files
```bash
# Build JARs
mvn clean package

# Run Metadata Service
java -jar dmq-metadata-service/target/dmq-metadata-service-1.0.0-SNAPSHOT.jar

# Run Storage Service
java -jar dmq-storage-service/target/dmq-storage-service-1.0.0-SNAPSHOT.jar
```

### Option 3: Run from IDE
1. Open project in IntelliJ IDEA or Eclipse
2. Navigate to `MetadataServiceApplication.java`
3. Right-click → Run 'MetadataServiceApplication'
4. Repeat for `StorageServiceApplication.java`

## Configuration

### Metadata Service Configuration
Edit `dmq-metadata-service/src/main/resources/application.yml`:

```yaml
server:
  port: 8081

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/dmq_metadata
    username: dmq_user
    password: dmq_password

zookeeper:
  connect-string: localhost:2181
```

### Storage Service Configuration
Edit `dmq-storage-service/src/main/resources/application.yml`:

```yaml
server:
  port: 8082

broker:
  id: 1
  host: localhost
  port: 9092
  data-dir: ./data/broker-1

metadata:
  service-url: http://localhost:8081
```

### Service Discovery Configuration
The system uses `config/services.json` for centralized service discovery:

```json
{
  "services": {
    "metadata-service-1": {
      "host": "localhost",
      "port": 8081,
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

## Architecture Overview

### KRaft Consensus Architecture
```
Metadata Service Nodes (Quorum)
├── RaftNode (Leader Election)
├── RaftLog (Persistent WAL)
├── RaftConsensus (State Machine)
└── MetadataStore (Versioned State)
```

### Bidirectional Metadata Synchronization
```
Storage Service ──Heartbeat──► Metadata Service (Controller)
        ▲                        │
        │                        ▼
        └──────Push Sync◄────────┘
```

### Layered Architecture (per service)
```
Controller Layer    ← REST endpoints, validation
     ↓
Service Layer       ← Business logic, KRaft consensus
     ↓
Repository Layer    ← Data access (JPA/File System)
     ↓
Database/Storage    ← PostgreSQL / File System
```

## Verify Services are Running

### Check Metadata Service
```bash
curl http://localhost:8081/actuator/health
# Expected: {"status":"UP"}
```

### Check Storage Service
```bash
curl http://localhost:8082/actuator/health
# Expected: {"status":"UP"}
```

## Basic Usage Examples

### 1. Create a Topic (via Metadata Service)

```bash
curl -X POST http://localhost:8081/api/v1/metadata/topics \
  -H "Content-Type: application/json" \
  -d '{
    "topicName": "test-topic",
    "partitionCount": 3,
    "replicationFactor": 2,
    "retentionMs": 604800000
  }'
```

### 2. List Topics

```bash
curl http://localhost:8081/api/v1/metadata/topics
```

### 3. Get Topic Metadata

```bash
curl http://localhost:8081/api/v1/metadata/topics/test-topic
```

### 4. Produce a Message (via Storage Service)

```bash
curl -X POST http://localhost:8082/api/v1/storage/produce \
  -H "Content-Type: application/json" \
  -d '{
    "topic": "test-topic",
    "partition": 0,
    "key": "user123",
    "value": "SGVsbG8gV29ybGQh",
    "requiredAcks": 1,
    "timeoutMs": 5000
  }'
```
*Note: value is base64 encoded "Hello World!"*

### 5. Consume Messages

```bash
curl -X POST http://localhost:8082/api/v1/storage/consume \
  -H "Content-Type: application/json" \
  -d '{
    "topic": "test-topic",
    "partition": 0,
    "offset": 0,
    "maxMessages": 10
  }'
```

## Using the Client Library

### Producer Example

```java
// Add dependency in your pom.xml
<dependency>
    <groupId>com.distributedmq</groupId>
    <artifactId>dmq-client</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

// Java code
import com.distributedmq.client.producer.*;
import java.util.concurrent.Future;

public class ProducerExample {
    public static void main(String[] args) {
        ProducerConfig config = ProducerConfig.builder()
            .metadataServiceUrl("http://localhost:8081")
            .storageServiceUrl("http://localhost:8082")
            .batchSize(16384)
            .requiredAcks(1)
            .build();
        
        Producer producer = new DMQProducer(config);
        
        // Send message asynchronously
        Future<ProduceResponse> future = producer.send(
            "test-topic", 
            "key1", 
            "Hello from Java!".getBytes()
        );
        
        // Send message synchronously
        ProduceResponse response = producer.sendSync(
            "test-topic",
            "key2",
            "Another message".getBytes()
        );
        
        System.out.println("Offset: " + response.getOffset());
        
        producer.close();
    }
}
```

### Consumer Example

```java
import com.distributedmq.client.consumer.*;
import com.distributedmq.common.model.Message;
import java.util.Arrays;
import java.util.List;

public class ConsumerExample {
    public static void main(String[] args) {
        ConsumerConfig config = ConsumerConfig.builder()
            .metadataServiceUrl("http://localhost:8081")
            .groupId("my-consumer-group")
            .clientId("consumer-1")
            .enableAutoCommit(true)
            .autoOffsetReset("earliest")
            .build();
        
        Consumer consumer = new DMQConsumer(config);
        
        consumer.subscribe(Arrays.asList("test-topic"));
        
        while (true) {
            List<Message> messages = consumer.poll(1000);
            
            for (Message msg : messages) {
                String value = new String(msg.getValue());
                System.out.println("Received: " + value);
            }
        }
    }
}
```

## Troubleshooting

### Port Already in Use
```bash
# Find process using port 8081
lsof -i :8081        # Mac/Linux
netstat -ano | findstr :8081    # Windows

# Kill the process
kill -9 <PID>        # Mac/Linux
taskkill /PID <PID> /F    # Windows
```

### PostgreSQL Connection Failed
- Verify PostgreSQL is running: `pg_isready`
- Check credentials in `application.yml`
- Ensure database exists: `psql -l`

### ZooKeeper Connection Failed
- Verify ZooKeeper is running: `echo stat | nc localhost 2181`
- Check ZooKeeper logs in `logs/zookeeper.out`

### Build Failures
```bash
# Clean Maven cache
mvn clean

# Update dependencies
mvn dependency:resolve

# Rebuild from scratch
mvn clean install -U
```

## Project Statistics

- **Total Files Created**: ~70+ files
- **Total Lines of Code**: ~4,000+ lines (including implementations)
- **Modules**: 4 (common, client, metadata-service, storage-service)
- **Java Classes**: 50+
- **REST Endpoints**: 10+ (with implementations)
- **Configuration Files**: 2 (application.yml) + 1 (services.json)
- **Implementation Status**: **85% functional, 15% TODO**

## What's Implemented

### ✅ Fully Implemented - KRaft & Metadata Sync
1. **KRaft Consensus Protocol** - Complete Raft implementation with leader election
2. **Service Discovery** - Centralized JSON configuration with service pairing
3. **Metadata Versioning** - Timestamp-based versioning for ordering guarantees
4. **Storage Heartbeats** - Periodic heartbeats with sync status (5s intervals)
5. **Push Synchronization** - HTTP-based metadata updates from controller to storage
6. **Heartbeat Processing** - Controller detects and recovers lagging services

### ✅ Fully Implemented - Core Infrastructure
1. **Project structure** - Complete Maven multi-module setup
2. **Common models** - All domain models and DTOs
3. **Exception hierarchy** - Custom exceptions
4. **Utilities** - Checksum and partitioning logic
5. **Controller layer** - REST endpoints with validation
6. **Service interfaces** - All service contracts defined
7. **JPA setup** - Repository and entity structure
8. **Configuration** - Spring Boot configurations

### ⚠️ Partially Implemented - Producer Flow
1. **Batch Message Production** - Multiple messages per request supported
2. **WAL Structure** - Segment-based log files (1GB segments)
3. **Offset Assignment** - Atomic offset assignment via WAL
4. **REST Controllers** - Endpoints defined with proper validation
5. **Service Implementations** - Core logic implemented, some TODOs remain

### ❌ TODO - Advanced Features
1. **Message Replication** - ISR management and cross-broker sync
2. **Consumer Groups** - Group coordination and rebalancing
3. **Log Compaction** - Key-based retention and cleanup
4. **Idempotent Producer** - Sequence number validation
5. **Transactional Producer** - Multi-partition transactions

## Useful Commands

```bash
# Check logs
tail -f logs/application.log

# Monitor running services
jps -l

# Clean data directories
rm -rf data/

# Rebuild without running tests
mvn clean install -DskipTests

# Run specific module tests
cd dmq-common && mvn test
```

## Next Steps

1. **Explore the API**: Use Postman or Swagger UI (if configured)
2. **Read Module READMEs**: Each module has detailed documentation
3. **Check TODO comments**: Look for implementation tasks in code
4. **Run Tests**: `mvn test` to see existing test coverage
5. **Contribute**: Pick a TODO item and start implementing!

Happy coding! 🚀