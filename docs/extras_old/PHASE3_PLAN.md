# Phase 3: Persistence - Implementation Plan

## 📋 Overview

**Goal**: Implement PostgreSQL-based async persistence for metadata backup with leader-only writes.

**Current State**:
- ✅ H2 in-memory database (development only)
- ✅ TopicEntity and TopicRepository exist
- ✅ BrokerEntity and BrokerRepository exist
- ❌ No PartitionEntity or PartitionRepository
- ❌ No async configuration (@EnableAsync)
- ❌ H2 configured in application.yml (needs PostgreSQL)
- ❌ No startup logic to load from Raft log

**Target State**:
- ✅ PostgreSQL as central database
- ✅ PartitionEntity and PartitionRepository created
- ✅ Async database writes (leader-only, non-blocking)
- ✅ Startup: Load from Raft log first, DB is backup only
- ✅ Spring @Async configuration enabled

---

## 🎯 Phase 3 Scope

### 1️⃣ Database Migration: H2 → PostgreSQL

**Why**: 
- H2 is in-memory only (data lost on restart)
- PostgreSQL is production-ready with ACID guarantees
- Single central PostgreSQL instance shared by all metadata services

**Changes**:
- Update `application.yml` datasource config
- Keep H2 dependency for testing profile (optional)
- PostgreSQL dependency already in pom.xml ✅

**Configuration**:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/dmq_metadata
    username: dmq_user
    password: dmq_password
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: update  # Or validate for production
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
```

**Deployment Model**:
```
┌─────────────────────────────────────────────────────────────┐
│              PostgreSQL Database (Port 5432)                │
│         jdbc:postgresql://localhost:5432/dmq_metadata       │
│                                                             │
│  Tables: brokers, topics, partitions                        │
│  Purpose: Async backup only, not source of truth            │
└─────────────────────────────────────────────────────────────┘
                      ▲            ▲            ▲
                      │            │            │
                      │ Write      │ Write      │ Write
                      │ (Leader)   │ (Leader)   │ (Leader)
                      │            │            │
          ┌───────────┴────┐  ┌────┴────────┐  ┌─────┴──────────┐
          │ Metadata-1     │  │ Metadata-2  │  │ Metadata-3     │
          │ Port 9091      │  │ Port 9092   │  │ Port 9093      │
          │ (Leader)       │  │ (Follower)  │  │ (Follower)     │
          └────────────────┘  └─────────────┘  └────────────────┘
```

**Key Points**:
- ✅ All 3 metadata services know PostgreSQL connection details
- ✅ Only active controller (leader) writes to DB
- ✅ Followers skip DB writes (check `raftController.isControllerLeader()`)
- ✅ Writes are async and non-blocking

---

### 2️⃣ Create PartitionEntity & PartitionRepository

**PartitionEntity.java**:
```java
@Entity
@Table(name = "partitions", indexes = {
    @Index(name = "idx_topic_partition", columnList = "topicName,partitionId", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartitionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String topicName;
    
    @Column(nullable = false)
    private Integer partitionId;
    
    @Column(nullable = false)
    private Integer leaderId;
    
    @Column(columnDefinition = "TEXT")  // Store as JSON: [1,2,3]
    private String replicaIds;
    
    @Column(columnDefinition = "TEXT")  // Store as JSON: [1,2]
    private String isrIds;
    
    @Column(nullable = false)
    private Long startOffset = 0L;
    
    @Column(nullable = false)
    private Long endOffset = 0L;
    
    @Column(nullable = false)
    private Integer leaderEpoch = 0;
    
    @Column(nullable = false)
    private Long lastUpdatedAt;
    
    // Conversion methods: fromPartitionInfo(), toPartitionInfo()
}
```

**PartitionRepository.java**:
```java
@Repository
public interface PartitionRepository extends JpaRepository<PartitionEntity, Long> {
    List<PartitionEntity> findByTopicName(String topicName);
    Optional<PartitionEntity> findByTopicNameAndPartitionId(String topicName, Integer partitionId);
    void deleteByTopicName(String topicName);
}
```

**Why**:
- Raft log stores PartitionInfo, but DB needs persistent backup
- Allows recovery if Raft log is corrupted/lost (rare but possible)
- Supports manual inspection and debugging

---

### 3️⃣ Enable Spring @Async Configuration

**Create AsyncConfig.java**:
```java
@Configuration
@EnableAsync
public class AsyncConfig {
    
    @Bean(name = "dbPersistenceExecutor")
    public Executor dbPersistenceExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("db-persist-");
        executor.initialize();
        return executor;
    }
}
```

**Why**:
- Current `asyncPersistTopic()` and `asyncDeleteTopic()` use `@Async`
- But Spring @Async is not enabled yet (missing `@EnableAsync`)
- Custom executor for database operations prevents thread exhaustion

---

### 4️⃣ Update MetadataServiceImpl - Async Partition Persistence

**Add methods**:
```java
@Async("dbPersistenceExecutor")
private void asyncPersistPartitions(String topicName) {
    if (!raftController.isControllerLeader()) return;
    
    Map<Integer, PartitionInfo> partitions = metadataStateMachine.getPartitions(topicName);
    if (partitions == null) return;
    
    for (PartitionInfo partInfo : partitions.values()) {
        PartitionEntity entity = PartitionEntity.fromPartitionInfo(partInfo);
        partitionRepository.save(entity);
    }
}

@Async("dbPersistenceExecutor")
private void asyncDeletePartitions(String topicName) {
    if (!raftController.isControllerLeader()) return;
    
    partitionRepository.deleteByTopicName(topicName);
}
```

**Integration**:
- Call `asyncPersistPartitions()` after `assignPartitions()` in `createTopic()`
- Call `asyncDeletePartitions()` in `asyncDeleteTopic()`

---

### 5️⃣ Startup: Load from Raft Log First

**Current Issue**:
- State machine is empty on startup
- Needs to replay Raft log to rebuild state

**Solution - Update RaftController.init()**:
```java
@PostConstruct
public void init() {
    // ... existing initialization ...
    
    // Load persisted state
    loadPersistedState();
    
    // Replay Raft log to rebuild state machine
    replayRaftLog();
    
    // Start election timeout
    resetElectionTimeout();
}

private void replayRaftLog() {
    log.info("Replaying Raft log to rebuild state machine...");
    
    long lastIndex = logPersistence.getLastLogIndex();
    long startIndex = Math.max(1, lastApplied + 1);
    
    for (long index = startIndex; index <= lastIndex; index++) {
        RaftLogEntry entry = logPersistence.getEntry(index);
        if (entry != null) {
            stateMachine.apply(entry.getCommand());
            lastApplied = index;
        }
    }
    
    log.info("Replayed {} Raft log entries. State machine rebuilt.", lastIndex);
}
```

**Why**:
- **Raft log is source of truth**, not database
- State machine rebuilt from log on every startup
- Database is only used if Raft log is lost (disaster recovery)

**Database Fallback (Optional)**:
```java
private void loadFromDatabaseIfNeeded() {
    if (logPersistence.getLastLogIndex() == 0 && raftController.isControllerLeader()) {
        log.warn("No Raft log found. Attempting to load from database...");
        // Load topics, partitions, brokers from DB into state machine
    }
}
```

---

## 📊 Phase 3 Task Breakdown

### Task 1: Database Migration (H2 → PostgreSQL)
**Files**:
- `application.yml` - Update datasource configuration
- `pom.xml` - Ensure PostgreSQL dependency (already exists ✅)

**Testing**:
- Start PostgreSQL: `docker run -d -p 5432:5432 -e POSTGRES_DB=dmq_metadata -e POSTGRES_USER=dmq_user -e POSTGRES_PASSWORD=dmq_password postgres:15`
- Verify connection on startup
- Check tables created: `brokers`, `topics`

---

### Task 2: Create PartitionEntity and PartitionRepository
**Files**:
- `dmq-metadata-service/src/main/java/com/distributedmq/metadata/entity/PartitionEntity.java` (NEW)
- `dmq-metadata-service/src/main/java/com/distributedmq/metadata/repository/PartitionRepository.java` (NEW)

**Testing**:
- Verify `partitions` table created
- Check unique index on `(topicName, partitionId)`

---

### Task 3: Enable Spring @Async
**Files**:
- `dmq-metadata-service/src/main/java/com/distributedmq/metadata/config/AsyncConfig.java` (NEW)

**Testing**:
- Verify async methods run in `db-persist-*` threads
- Check logs: `[db-persist-1] Async persisted topic ...`

---

### Task 4: Add Async Partition Persistence
**Files**:
- `MetadataServiceImpl.java` - Add `asyncPersistPartitions()`, `asyncDeletePartitions()`
- Update `createTopic()` to call `asyncPersistPartitions()`
- Update `asyncDeleteTopic()` to call `asyncDeletePartitions()`

**Testing**:
- Create topic, verify partitions in DB
- Delete topic, verify partitions removed from DB
- Check only leader writes (followers skip)

---

### Task 5: Startup Raft Log Replay
**Files**:
- `RaftController.java` - Add `replayRaftLog()` method
- Call from `init()` after `loadPersistedState()`

**Testing**:
- Create topic, restart metadata service
- Verify state machine has topic (from Raft log, not DB)
- Check logs: `Replayed X Raft log entries`

---

### Task 6: Update Broker Persistence (Already Async ✅)
**Current State**:
- `RegisterBrokerCommand` exists
- Broker registration via Raft ✅
- Need to add `asyncPersistBroker()` similar to topic

**Files**:
- `MetadataServiceImpl.java` or create `BrokerServiceImpl.java`

**Implementation**:
```java
@Async("dbPersistenceExecutor")
private void asyncPersistBroker(int brokerId) {
    if (!raftController.isControllerLeader()) return;
    
    BrokerInfo brokerInfo = metadataStateMachine.getBroker(brokerId);
    if (brokerInfo == null) return;
    
    BrokerEntity entity = BrokerEntity.builder()
        .id(brokerInfo.getBrokerId())
        .host(brokerInfo.getHost())
        .port(brokerInfo.getPort())
        .status("ONLINE")
        .build();
    
    brokerRepository.save(entity);
}
```

---

## 🔄 Updated Flow Diagrams

### Create Topic Flow (With Partition Persistence)
```
1. RegisterTopicCommand → Raft Consensus → StateMachine.applyRegisterTopic()
2. AssignPartitionsCommand → Raft Consensus → StateMachine.applyAssignPartitions()
3. Async Persist Topic to PostgreSQL (Leader only)
4. Async Persist Partitions to PostgreSQL (Leader only)  ← NEW
5. Push to Storage Services
6. Return TopicMetadata
```

### Startup Flow (Raft Log First)
```
1. RaftController.init()
2. Load persisted Raft state (currentTerm, votedFor)
3. Replay Raft log → Rebuild state machine  ← NEW
4. State machine now has all brokers, topics, partitions
5. Start election timeout
6. (Optional) If no Raft log and leader, load from DB as fallback
```

---

## ⚠️ Important Principles

### 1. Raft Log = Source of Truth ✅
- State machine rebuilt from Raft log on startup
- Database is **backup only** for disaster recovery

### 2. Leader-Only Writes ✅
- All async DB methods check: `if (!raftController.isControllerLeader()) return;`
- Prevents DB inconsistency across nodes

### 3. Non-Blocking ✅
- All DB writes are `@Async` with separate thread pool
- Main Raft flow never waits for DB

### 4. PostgreSQL Shared, Not Replicated ✅
- Single PostgreSQL instance at `localhost:5432`
- All 3 metadata services know connection details
- Only leader writes, followers ignore

### 5. Focus on Kafka, Not DB Fault Tolerance ✅
- Simple PostgreSQL setup (no replication/failover)
- Raft log is primary, DB is backup
- If PostgreSQL fails, system continues (Raft log intact)

---

## 🧪 Testing Plan

### Test 1: PostgreSQL Connection
```bash
# Start PostgreSQL
docker run -d --name dmq-postgres -p 5432:5432 \
  -e POSTGRES_DB=dmq_metadata \
  -e POSTGRES_USER=dmq_user \
  -e POSTGRES_PASSWORD=dmq_password \
  postgres:15

# Verify connection
psql -h localhost -U dmq_user -d dmq_metadata -c "\dt"
```

### Test 2: Multi-Node Async Persistence
1. Start 3 metadata services (9091, 9092, 9093)
2. Create topic on leader (9091)
3. Check PostgreSQL - topic and partitions persisted
4. Check follower logs - should say "Skipping async persist on non-leader"
5. Kill leader, new leader elected
6. New leader should now persist to DB

### Test 3: Startup Raft Log Replay
1. Create topic with 3 partitions
2. Restart metadata service
3. Check logs: "Replayed 3 Raft log entries"
4. Verify state machine has topic and partitions (query API)
5. Verify same data in PostgreSQL (backup)

### Test 4: Async Thread Pool
1. Create 10 topics rapidly
2. Check logs - should show `[db-persist-1]`, `[db-persist-2]` threads
3. Verify all topics eventually persisted (async)

---

## 📁 Files to Create/Modify

### New Files (4)
1. `PartitionEntity.java` - JPA entity for partitions
2. `PartitionRepository.java` - JPA repository for partitions
3. `AsyncConfig.java` - Spring @Async configuration
4. `application-postgres.yml` - PostgreSQL profile (optional)

### Modified Files (3)
1. `application.yml` - Update datasource to PostgreSQL
2. `MetadataServiceImpl.java` - Add `asyncPersistPartitions()`, `asyncDeletePartitions()`
3. `RaftController.java` - Add `replayRaftLog()` in `init()`

### Optional Files (1)
1. `BrokerServiceImpl.java` - Async broker persistence (if needed)

---

## 🎯 Success Criteria

- ✅ PostgreSQL connection established
- ✅ Tables created: `brokers`, `topics`, `partitions`
- ✅ Leader-only writes verified (followers skip)
- ✅ Async persistence doesn't block Raft
- ✅ Startup replays Raft log and rebuilds state machine
- ✅ State machine is source of truth, DB is backup
- ✅ All changes compile and run successfully

---

## 📝 Summary

**Phase 3 Deliverables**:
1. PostgreSQL integration (replace H2)
2. PartitionEntity and PartitionRepository
3. Spring @Async configuration
4. Async partition persistence (leader-only)
5. Raft log replay on startup

**Effort Estimate**: ~2-3 hours

**Dependencies**: PostgreSQL running on `localhost:5432`

---

## 🚦 Ready to Proceed?

**Please review this plan and confirm**:
1. ✅ PostgreSQL configuration looks good?
2. ✅ PartitionEntity schema is correct?
3. ✅ Raft log replay approach is acceptable?
4. ✅ Leader-only async writes make sense?
5. ✅ Testing plan is sufficient?

**Once approved, I will implement Phase 3 step-by-step.**
