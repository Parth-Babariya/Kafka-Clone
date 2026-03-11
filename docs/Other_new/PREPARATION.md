# DistributedMQ — Preparation

This single, in-depth document is designed to convey a deep, interview‑ready understanding of the DistributedMQ project (a Kafka-like system implemented with a custom Raft/KRaft metadata service). It synthesizes the current implementation, all major flows, the concepts used, design decisions, failure modes and mitigations, performance considerations, and a focused set of MAANG-interview level questions and answers.

Use this document to study the codebase, explain design choices, and answer deep technical questions during interviews.

---

## Table of Contents

1. Project summary (1 page)
2. Architecture & component breakdown
3. Key flows (detailed)
   - Leader election
   - Log replication (append / commit / apply)
   - Broker registration and heartbeat
   - Controller discovery
   - Failover and state rebuild
4. Data models and persistence
5. Concurrency, thread-safety, and correctness
6. Observability, testing, and performance
7. Production considerations and deployment
8. Trade-offs and alternatives (why these choices)
9. Failure scenarios and recovery recipes
10. MAANG-level interview questions & model answers

---

## 1. Project summary

DistributedMQ is a lightweight, educational Kafka-like system implemented in Java 11 + Spring Boot. The project focuses on metadata management using an in-house Raft implementation (KRaft-like) running across a 3-node metadata cluster. Storage/broker nodes register with the metadata cluster, send periodic heartbeats, and use controller discovery to find the current leader.

The functional emphasis of the implementation is fault tolerance: leader election, durable Raft log persistence, robust log replication, and automatic controller failover with state rebuild. The project intentionally focuses on metadata, coordination, and failure handling; full message ingestion/replication/consumer features. Consumer groups are out of scope for the current iteration.

Key properties:
- Strong consistency for metadata via Raft (majority commit)
- F=1 fault tolerance with 3 metadata nodes
- Database-backed (currently in memeory) Raft log for durability
- Heartbeat-based broker health detection (5s heartbeat, 30s timeout)
- Controller discovery using parallel queries and exponential backoff

---

## 2. Architecture & component breakdown

High-level modules (multi-module Maven project):
- `dmq-common` — shared DTOs, utility functions, command classes
- `dmq-metadata-service` — Raft controller: leader election, AppendEntries/RequestVote RPCs, persistent log, metadata state machine, heartbeat controller
- `dmq-storage-service` — Broker logic: controller discovery, heartbeat sender, metadata in-memory cache
- `dmq-client` — (optional) producer/consumer client

Logical components inside `dmq-metadata-service`:
- RaftController: main Raft state machine and orchestration
- RaftLogPersistence: database-backed log operations and conflict resolution
- RaftNetworkClient: HTTP client used to send RPCs to peer controllers
- MetadataStateMachine: interprets commands (RegisterBroker, CreateTopic, UpdateBrokerStatus) and applies them to the metadata DB
- HeartbeatService: tracks broker heartbeats in-memory and rebuilds state after leader election
- HeartbeatController: REST endpoint for brokers to send heartbeats (with leader validation and redirect when called on follower)

Storage/broker-side components:
- ControllerDiscoveryService: parallel queries to metadata nodes; returns first successful leader URL
- HeartbeatSender: periodic tasks to send /heartbeat to controller, include metadata version and broker info
- MetadataStore: local cache of cluster metadata (versioned)

Networking: simple HTTP/JSON APIs for RPCs and REST endpoints (AppendEntries, RequestVote, broker heartbeats, metadata queries).

Persistence: relational DB (Postgres or H2 for tests) with `raft_log` table for entries and metadata tables for brokers/topics/partitions.

---

## 3. Key flows (detailed)

This section walks through primary flows step-by-step, with rationale and corner cases.

### 3.1 Leader election (Raft)

Goal: elect a single leader to serialize metadata updates.

State machine:
- FOLLOWER: accepts RPCs and resets election timeout when AppendEntries is received
- CANDIDATE: increments term, votes for self, sends RequestVote to peers
- LEADER: sends periodic heartbeats (empty AppendEntries) and coordinates replication

Election timeout
- Randomized between base + jitter (defaults: 5s base + up to 2s jitter => 5-7s). Randomization reduces split-vote probability.

RequestVote handling rules
1. Reject if candidate term < currentTerm
2. Reject if already voted for different candidate this term
3. Reject if candidate's log is not at least as up-to-date as receiver's (compare lastLogTerm, lastLogIndex)
4. Else grant vote and reset election timer

Becoming leader
- When candidate obtains majority (2 of 3), it initializes leader state: nextIndex and matchIndex maps, and starts heartbeats.

Corner cases
- Higher-term RPC received: step down to follower and update currentTerm
- Split vote: timeouts differ so subsequent elections occur; randomized timeout reduces frequency

Why majority
- Majority ensures a single partition (no two distinct quorums) can commit safely. With 3 nodes, majority = 2.

### 3.2 Log replication: append → replicate → commit → apply

Client path (metadata command):
1. Client sends command to controller leader (e.g., CreateTopic)
2. Leader serializes command into `RaftLogEntry` and appends to local persistent log (RaftLogPersistence)
3. Leader sends AppendEntries RPCs to all followers (may include preceding entries if follower is behind)
4. Followers perform consistency check using prevLogIndex/prevLogTerm; if mismatch, follower rejects; leader will backtrack nextIndex and retry
5. Once a majority of nodes have acknowledged the new entry, leader advances commitIndex
6. Leader applies committed entries to `MetadataStateMachine` (applies to DB) and responds to client

Important details implemented
- Log stored in DB `raft_log` table to survive restarts
- AppendEntries response contains `matchIndex`, allowing leader to update matchIndex/nextIndex
- Leader waits for majority ACKs. Implementation includes a timeout for replication wait to avoid indefinite blocking (client receives timeout and can retry)
- Followers delete conflicting entries when leader's entries don't match their own term at given index

Consistency and safety
- The leader only considers entry committed when replicated on a majority; entries are applied in order and idempotent command semantics are preserved when practical.

### 3.3 Broker registration & heartbeat

Broker registration
- At startup, `dmq-storage-service` runs controller discovery to find leader
- Broker posts a registration command (or registration occurs via heartbeat flow) to the leader
- Leader appends RegisterBrokerCommand to Raft log; when committed, MetadataStateMachine writes broker row in DB

Heartbeat flow
- Brokers send POST /heartbeat/{brokerId} every 5s
- HeartbeatController validates whether the receiving metadata node is leader; if not, returns 503 and X-Controller-Leader header pointing to leader URL
- If leader, controller updates in-memory `lastHeartbeatTime` map and persists last heartbeat in DB (optionally)
- Heartbeat response includes metadata version; if broker sees its metadata version is stale, it pulls latest metadata

Health checks
- `HeartbeatService` runs every 5s and marks brokers offline if lastHeartbeatTime is older than 30s
- Offline marking is done via UpdateBrokerStatusCommand appended to Raft log and processed when committed

Offline → Online transition
- When a previously-offline broker sends a heartbeat, the leader appends UpdateBrokerStatusCommand to mark it ONLINE
- Leader validation prevents stale followers from accepting heartbeats and wrongly changing state

### 3.4 Controller discovery

Goal: brokers must reliably find the current controller leader even if some metadata nodes are down.

Algorithm implemented
1. Brokers query all configured metadata nodes in parallel (CompletableFuture for each)
2. Take the first successful response (fastest) that indicates the node is leader or that provides leader URL
3. If all attempts fail, retry with exponential backoff: 1s, 2s, 4s, 8s
4. Cache discovered controller to reduce load; invalidate on 3 heartbeat failures or on CONTROLLER_CHANGED push

Rationale
- Parallel queries drastically reduce discovery time vs sequential scanning
- Exponential backoff avoids overwhelming peers during outages

### 3.5 Failover & state rebuild

When leader dies (process crash or network partition), followers detect missing heartbeats and start election once their randomized election timeouts expire.

Failover timeline observed in tests
- Leader down → 1st follower timeout at ~6.5s → election → new leader elected in ~1.3s → state rebuild and push notifications complete around 8.5s total

State rebuild
- New leader rebuilds `lastHeartbeatTime` map from persisted broker rows (DB) to avoid treating all brokers as OFFLINE
- This prevents false negatives and ensures continuity for broker health monitoring

Controller change notification
- After leader election, a CONTROLLER_CHANGED push is sent to brokers allowing them to reconnect quickly and avoid discovery delays

---

## 4. Data models and persistence

Core DB tables and fields (simplified):

raft_log
- log_index (PK) BIGINT
- term INTEGER
- command_type VARCHAR
- command_data TEXT (JSON)
- timestamp BIGINT

brokers
- id INTEGER
- host VARCHAR
- port INTEGER
- status ENUM (ONLINE/OFFLINE)
- last_heartbeat_time BIGINT

topics
- id, name, num_partitions, replication_factor, created_at

partitions
- id, topic_id, partition_id, leader_broker_id, replicas

Persistence rationale
- Raft log in DB ensures durability and simple snapshotting path later
- Metadata state persisted to DB guarantees state rebuild after controller failover

Serialization
- Commands are JSON-serialized before storing in `command_data`. This makes log entries self-describing and easier to inspect.

---

## 5. Concurrency, thread-safety, and correctness

Key concurrency concerns
- RaftController state transitions (currentTerm, votedFor, state): need visibility and atomic updates
- nextIndex, matchIndex maps updated concurrently by replication threads
- RaftLogPersistence writes must be serialized to keep monotonically increasing logIndex

Implementation choices
- Volatile fields for frequently read/write state (currentTerm, state, commitIndex)
- Synchronized blocks for critical state transitions (startElection, becomeLeader, becomeFollower)
- ConcurrentHashMap for nextIndex and matchIndex to allow safe concurrent updates
- Synchronized DB writes in RaftLogPersistence (synchronized methods) to keep append semantics

Why these are sufficient (for this implementation)
- The design avoids heavy locks across the entire controller; synchronized methods are small and localized
- Volatile guarantees memory visibility for other threads (important in JVM)
- Concurrent collections avoid lock contention for leader state

Potential race conditions and mitigations
- Race: two threads attempt to change state concurrently → mitigated by synchronizing state-change methods
- Race: leader starts replication while election occurs → if higher term observed in RPC response, threads call becomeFollower which is synchronized

Correctness properties
- Safety (never two leaders for a given term): enforced by term checks and vote rules
- Log matching property: followers accept entries only if prevLogIndex/prevLogTerm matches
- Liveness: randomized election timeouts and heartbeats ensure progress under normal conditions

---

## 6. Observability, testing, and performance

Logging and observability
- Rich logging with clear markers (emoji and structured messages)
- Heartbeat and election logs enable quick triage
- No metrics currently; recommended Prometheus + Grafana integration to expose:
  - raft_current_term
  - raft_state (0/1/2)
  - raft_commit_index
  - raft_entries_replicated_total
  - heartbeat_last_seen

Testing
- Manual functional tests implemented covering:
  - Leader election
  - Log replication
  - Heartbeat/health checks
  - Failover scenarios
- Unit tests are minimal. Adding JUnit + Mockito integration tests is high-priority for productionization.

Performance observations (from manual runs)
- Election time: ~6-8 seconds (timeout + voting) with 5-7s randomized timeout
- Log replication latency: a few hundred milliseconds (DB write + RPC)
- Failover end-to-end: ~8.5 seconds in tests

Scaling notes
- Metadata cluster scales horizontally but consensus cost increases (quorum size grows)
- For metadata scale beyond a few nodes, add sharded Raft groups (multi-Raft) or increase cluster size considering write latency

---

## 7. Production considerations and deployment

Configuration
- Externalize timeouts (heartbeat interval, election base & jitter, replication timeout)
- Use separate DB instances for Raft log and metadata tables in large deployments

Snapshots & log compaction (not implemented yet)
- Without compaction, Raft log grows unbounded
- Implement periodic snapshots of state machine and truncate log up to last included index

Security
- Add TLS for inter-node RPC and REST APIs
- Add authentication for broker registration/heartbeats (mTLS or token-based)

Observability & SRE
- Add Prometheus metrics and alerts for election frequency, replication latency, leader changes
- Add tracing for RPCs (OpenTelemetry)

Deploying
- Containerize components with Docker and orchestrate via Kubernetes
- Use StatefulSets for metadata nodes to preserve stable network identities
- Back up DBs and implement automated restore tests

---

## 8. Trade-offs and alternatives (why these choices)

Why custom Raft implementation (vs off-the-shelf)
- Educational value: deep understanding of distributed consensus
- Control: adapt protocol to project-specific requirements or instrumentation
- Cost: implementation time vs dependency inclusion

Why DB-backed log
- Durability and ability to rebuild in simple way
- Simpler backup/inspection compared to custom file-based logs

Why HTTP/JSON RPCs (AppendEntries/RequestVote)
- Simpler implementation and debugging
- Slightly higher overhead vs binary protocols but acceptable for control plane metadata traffic

Alternatives & consequences
- Use a dedicated consensus library (e.g., Atomix) → faster to production but less learning
- Use persistent file-based log (WAL) → lower latency but more code for compaction and checkpointing
- Use gRPC instead of HTTP → lower latency and more compact messages; requires extra dependency

---

## 9. Failure scenarios & recovery recipes

This section is a practical troubleshooting guide for common failures and how the current implementation handles them.

Scenario A: Leader crash while entries are uncommitted
- Symptoms: client write returned error or timed out; followers have partial entries
- Recovery: election will produce new leader that backfills missing entries via AppendEntries; if majority never had entry, client must retry
- Remedy: monitor replication timeouts and alert on increasing rates

Scenario B: Split brain (two leaders)
- Symptoms: conflicting writes, inconsistent commitIndex
- Why it shouldn't happen: Raft prevents this by term-based voting and majority rule
- Recovery: higher-term discovery causes lower-term leader to step down; ensure leader validation in heartbeats

Scenario C: Follower permanently behind (slow disk or crash)
- Symptoms: frequent AppendEntries failures, leader backtracks nextIndex repeatedly
- Recovery: leader performs catch-up by sending prior entries; if follower cannot catch up, consider replacing node and resyncing
- Remedy: monitor matchIndex distribution across peers; set alerts for persistent low matchIndex

Scenario D: False offline marking after failover
- Symptoms: new leader marks many brokers offline because heartbeat map is empty
- Implementation mitigation: rebuild heartbeat state from DB on leader initialization

Scenario E: Network partition causing minority to elect leader
- Symptoms: minority cannot obtain majority; cannot process writes
- Recovery: when partition heals, majority resumes and resolves via log comparison

---

## 10. MAANG-level interview questions & model answers

Below are realistic interview questions that probe both breadth and depth. Each answer is tuned to the actual implementation, referencing trade-offs and concrete code-level behavior.

Q1 — Explain Raft's leader election and how your implementation prevents split votes.
A1 — Raft uses randomized election timeouts to minimize simultaneous candidate starts. In our implementation, each follower sets electionTimeout = base(5000ms) + random(0..2000ms). When the timeout expires, a node becomes a candidate, increments currentTerm, votes for itself, and sends RequestVote RPCs in parallel. A candidate wins when it collects majority votes (2 of 3). We prevent split votes by jittering timeouts; if a split vote occurs, subsequent randomized timeouts cause another election — eventually a single candidate wins. Additionally, RequestVote ensures candidate log up-to-dateness by comparing lastLogTerm and lastLogIndex.

Q2 — How does AppendEntries maintain log consistency across nodes? Describe conflict resolution.
A2 — AppendEntries uses prevLogIndex and prevLogTerm. A follower rejects the AppendEntries request if its log at prevLogIndex doesn't have prevLogTerm. Upon rejection, the leader decrements nextIndex for that follower and retries with earlier entries. If a follower has conflicting entries (same index but different term), it truncates from that index forward and appends the leader's entries. This ensures the leader's log becomes authoritative for that prefix. The leader updates matchIndex when followers acknowledge entries; commitIndex advances when majority replicate.

Q3 — Why store the Raft log in a database? What are pros/cons?
A3 — Pros: durability across restarts, simple backup/restore using DB tools, easy querying for debugging, and simple state rebuild after leader changes. Cons: DB latency may add to replication latency; DB transactions and indexing must be tuned; log compaction (snapshots) becomes necessary to prevent unbounded growth. For a control-plane metadata store, DB-backed log is pragmatic and simpler for educational/prototype systems.

Q4 — How do you ensure that only the leader processes state-changing requests? How do followers respond to such requests?
A4 — The HeartbeatController and other mutation endpoints check RaftController.isControllerLeader(). If a node is not leader, it returns HTTP 503 along with `X-Controller-Leader` header pointing to the current leader's URL (if known). Clients and brokers must then retry against the leader. This prevents split-brain and ensures the leader is the single authority for committing commands.

Q5 — Explain the commit process and how the system guarantees durability and linearizability.
A5 — A leader appends the command to its own log and replicates the entry to followers. When the leader observes that a majority has written the entry, it increments commitIndex and applies entries up to commitIndex to the state machine (DB). Durability: entries are persisted (DB) on majority nodes before considered committed. Linearizability: reads that must be linearizable are served by the leader or involve leader validation; writes are serialized by leader and committed only on majority.

Q6 — Describe how broker heartbeats are used to detect failures and how the system reacts.
A6 — Brokers send heartbeats every 5s. The leader maintains an in-memory map of lastHeartbeatTime per broker. A scheduled task checks every 5s and marks a broker OFFLINE if the last heartbeat is older than 30s. The OFFLINE transition is recorded by appending an UpdateBrokerStatusCommand to the Raft log so it is replicated and durable. On broker recovery, a heartbeat from the broker causes the leader to append an ONLINE update.

Q7 — What concurrency patterns do you use to keep Raft state safe and efficient?
A7 — Volatile variables for visibility (currentTerm, commitIndex, state). Synchronized methods for state transitions (startElection, becomeLeader, becomeFollower) to avoid races. ConcurrentHashMap for nextIndex/matchIndex to allow concurrent replication-related updates. Synchronized persistence methods ensure monotonic logIndex assignment. This balance reduces coarse-grained locks while preserving safety.

Q8 — How does the system handle log compaction / snapshots? If not implemented, what would you do next?
A8 — Currently log compaction isn't implemented. Next step: implement periodic snapshots of the state machine (serialize metadata to snapshot with lastIncludedIndex), store snapshot alongside term info, and truncate Raft log up to lastIncludedIndex. On follower sync, if follower's nextIndex <= lastIncludedIndex, transfer snapshot and have follower install snapshot and set nextIndex accordingly. Snapshots bound storage growth and speed restarts.

Q9 — Suppose the leader is overloaded and replication latency spikes. What mitigations would you add?
A9 — Add backpressure and batching: batch multiple commands into a single AppendEntries call. Use configurable replication concurrency and max inflight RPCs per follower. Add adaptive timeouts and circuit breakers to avoid repeated retries. Introduce flow control at client side (rate limiting) and prioritize heartbeat RPCs. Add separate threads for control plane and heavy background tasks.

Q10 — How would you improve reliability and observability for production?
A10 — Add Prometheus metrics, structured logs, distributed tracing (OpenTelemetry), and health endpoints. Implement alerting (leader flaps, replication lag, commit stalls). Add automated integration tests simulating partitions and restarts. Use StatefulSets in Kubernetes to retain identities and add readiness/liveness probes. Use mTLS for secure node-to-node communication.

Q11 — Explain CAP theorem trade-offs in your design.
A11 — The metadata service prioritizes consistency over availability for writes. Raft enforces strong consistency: writes require a majority to commit. During partitions where a node is isolated forming a minority, that partition cannot accept writes (reduced availability) to preserve consistency—this is a CA choice under partition (sacrificing availability during partitions to remain consistent).

Q12 — What are the security considerations for the current design?
A12 — Add TLS for RPCs and REST endpoints, authenticate brokers and clients (mTLS or tokens), implement RBAC for metadata changes, protect DB credentials, limit surface via network policies, and handle sensitive fields carefully. Also audit logs for admin operations.

Q13 — How do you test correctness of the Raft implementation?
A13 — Unit tests for term/vote logic, integration tests across 3-node clusters for leader election, log replication, and failover; fault injection tests (kill leader, delay network, disk errors), and model-checking approaches (e.g., use Jepsen-like patterns or property-based tests) for validating safety and liveness.

Q14 — Explain an optimization you implemented to speed follower catch-up.
A14 — When AppendEntries fails due to inconsistent prevLogIndex/prevLogTerm, instead of decrementing nextIndex by 1 in a linear manner, a binary search/backoff approach reduces the number of retries for long gaps. This reduces catch-up time when a follower is far behind.

Q15 — How would you extend this project to support large-scale production systems?
A15 — Implement log compaction and snapshots, metrics & alerting, secure transport (mTLS), separate DB for Raft log, multi-Raft groups for sharding metadata, support dynamic cluster membership with joint consensus, and add robust automated testing and continuous integration targeting failure cases.

---

## Appendix: Quick pointers to repository files
- `dmq-metadata-service/README.md` — operational guide for controllers
- `dmq-metadata-service/ARCHITECTURE.md` — full architecture (detailed Raft internals)
- `dmq-metadata-service/REPORT.md` — implementation report and test results
- `dmq-storage-service/ARCHITECTURE.md` — storage/broker architecture
- `dmq-storage-service/REPORT.md` — storage tests/results

---

## Final notes

This `PREPARATION.md` intentionally focuses on understanding the implementation, reasoning about design decisions, and preparing for interview questions that probe distributed systems fundamentals and engineering trade-offs. It reflects the current implementation and suggests pragmatic next steps to harden the system for production.

If you want, I can now:
- Add a condensed slide deck from this document for presentation/demo
- Generate targeted unit/integration test templates for critical Raft behaviors
- Create a Prometheus metrics integration PR skeleton

Tell me which follow-up you'd like and I'll proceed (I already updated the project todo list and created this file).