# MAANG Interview Preparation — DistributedMQ (Kafka-Inspired)

Purpose: A single, compact, interview-focused document that answers the enhanced checklist you provided. Each section states what the current implementation supports (truthful, not boastful) and either gives concise interview-ready explanations or marks the topic as TODO when not implemented.

---

## Quick project summary (1 paragraph)
DistributedMQ is a Kafka-inspired messaging system implemented in Java 11 + Spring Boot. The project focuses on metadata management using a custom Raft (KRaft-like) implementation across a 3-node metadata cluster and a set of storage/broker nodes. The current implementation emphasizes fault tolerance: persistent Raft log (DB-backed), leader election, log replication, heartbeat-based broker health, controller discovery (parallel queries + exponential backoff), and automatic controller failover. Message production/replication, consumer groups, and advanced delivery semantics are out of scope and marked TODO where applicable.

---

## 1. Pub-Sub Architecture & Microservices
For interview answers, describe architecture, component responsibilities, and common flows. Below is concise, code-aligned content you can speak to and draw.

- System decomposition (current implementation):
  - `dmq-metadata-service` — Raft-based controllers (3 nodes). Responsible for metadata (brokers, topics, partitions), leader election, log replication, applying metadata commands.
  - `dmq-storage-service` — Broker nodes. Responsible for controller discovery, registering, sending heartbeats, maintaining a metadata cache.
  - `dmq-common` — DTOs, commands, shared types.
  - `dmq-client` — (partially present/experimental) client library.

- Communication patterns implemented:
  - Control-plane: HTTP/JSON RPCs (AppendEntries, RequestVote), REST endpoints for heartbeats and metadata queries.
  - Data-plane message APIs (producer/consumer) are TODO (not implemented).

- Topics & partitions:
  - Current: Topic creation and partition assignment logic implemented in `MetadataStateMachine` (creates topic rows and assigns partitions to brokers). Partition replication (replicas, ISR) is partial—replication of messages for partitions is TODO.

- Message routing and producers/consumers:
  - TODO: full producer partition selection, client libs, consumer group orchestration. (Mark as TODO for interview.)

- Multiple consumers & consumer groups:
  - TODO: not implemented. In interview, state you would adopt Kafka-style consumer groups with offsets per group, and partition assignment strategies (range/round-robin/sticky) when implemented.

- Message ordering:
  - Current guarantee: metadata ensures partition-level ordering semantics conceptually; message-level ordering end-to-end is TODO (requires concrete broker commit log implementation).

- Backpressure & offset tracking:
  - TODO: broker-side backpressure and consumer offset persistence are not implemented.

Interview-ready summary: explain that metadata (controller) is responsible for topic/partition metadata and brokers are responsible for storing and serving messages; state the parts implemented (metadata) vs TODO (data-plane). Keep concise.

---

## 2. Raft-Based Consensus & Coordination (implemented)
Be ready to explain Raft from both theory and how this code implements it.

- Raft basics (what to say): leader election, log replication (AppendEntries), consistency via majority commit, terms, persistence of important state (currentTerm, votedFor, log).

- Implementation specifics (current repo):
  - Election timeout: randomized (base 5s + jitter up to ~2s) to reduce split votes.
  - RequestVote RPC: checks term and candidate log up-to-dateness (lastLogTerm & lastLogIndex) before granting vote.
  - AppendEntries RPC: includes prevLogIndex/prevLogTerm; follower verifies and rejects if mismatch; entries are stored in DB table `raft_log`.
  - Persistent state: `RaftLogPersistence` writes to DB; `currentTerm` and `votedFor` persisted logically (in memory & persisted where appropriate).
  - Leader state: `nextIndex` and `matchIndex` maintained per follower in ConcurrentHashMap.
  - Commit: leader advances `commitIndex` when a majority of followers have stored entry; then applies entries to `MetadataStateMachine` which updates DB tables (brokers, topics, partitions).
  - Heartbeats: leader sends empty AppendEntries every ~1.5s.

- Fault tolerance: with a 3-node cluster, F=1 tolerated; majority = 2. If a node fails, cluster can still elect leader and accept writes as long as majority is available.

- Edge-case behavior (what to memorize for interviews):
  - If a follower receives RPC with higher term, it steps down to follower and updates currentTerm.
  - If AppendEntries fails due to conflict, leader decrements `nextIndex` (implementation optimized with an improved backtrack approach) and retries.
  - New leader rebuilds in-memory heartbeat state from DB to avoid marking all brokers offline after failover.

- Raft vs Paxos: succinctly explain Raft chosen for clarity and implementability — deterministic leader-oriented protocol, simpler to explain and implement than Paxos.

---

## 3. Partition Replication & High Availability
(Partially implemented in metadata; message-level replication: TODO)

- Partition rationale: partitions allow parallelism and ordering per partition. Current metadata stores partition assignment and leader broker per partition. Assignments are applied via `MetadataStateMachine`.

- Replication factor & leader-follower (message replication):
  - TODO: storage-level replica implementation is not completed. The metadata service supports assigning replicas/leader but the data-path replication protocol (writing messages to multiple replicas, ISR tracking) is TODO.

- Failover mechanism (metadata-level): when a broker that is leader for partitions goes offline, `HeartbeatService` triggers `UpdateBrokerStatusCommand` which is committed via Raft and the `MetadataStateMachine` may reassign partition leaders (assignment logic is present but may be basic). The actual data sync for replicas is TODO.

Interview note: be explicit — metadata failover & partition leader reassignment is implemented at the control plane; the heavy lifting for data-plane replication is future work.

---

## 4. Message Persistence, Delivery Semantics & Retries
(Mostly TODO — metadata makes decisions but message storage pipeline is not implemented)

- Storage design: current project uses DB for metadata and DB-backed Raft log. There is no full commit-log-backed message storage for topics in this iteration — TODO.

- Message retention, delivery semantics, retries, idempotency: all are TODO. In interviews, explain how you'd implement each:
  - At-least-once: persist to local partition log and replicate to followers; acknowledgment to producer only after majority commit.
  - Exactly-once: require idempotent producers with unique message IDs + transactional commit across endpoints (complex feature)
  - Retries: exponential backoff + dead-letter queue for poison messages

---

## 5. Security — JWT-Based Producer/Consumer Auth (TODO / recommended plan)

- Current: no JWT-based auth implemented in repo.

- Interview answer to show knowledge (explain the plan):
  - Use an authentication service or simple JWT issuance at login. Producers/consumers include JWT in Authorization header.
  - Broker/controller validate signature and claims (audience, scopes: publish/subscribe, topic ACLs).
  - Token lifecycle: short-lived access tokens + refresh tokens. Revoke via blacklist or token introspection service.
  - Transport: enforce TLS (HTTPS) for all RPCs and REST endpoints.

Mark as TODO in repo: add middleware (Spring Security filter) for JWT verification and tests to cover missing/invalid tokens.

---

## 6. CI/CD — GitHub Actions (TODO / recommended minimal setup)

- Current: repository contains Maven modules. No explicit GitHub Actions workflows in repo (if present, treat as partial).

- Interview-ready plan to describe:
  - Build job: `mvn -T1C -DskipTests=false clean verify` on PRs
  - Integration job: start H2 or PostgreSQL via service containers and run integration tests (3-node controller simulation)
  - Docker build & push on main branch with tags
  - CD (optional): deploy to a staging cluster

Mark as TODO: add `/.github/workflows/ci.yml` that builds modules, runs unit & integration tests, and publishes artifacts on success.

---

## 7. System Design & Interview Discussion (how to answer concisely)

- Architecture diagram to draw in 60s (see quick-draw below).  Explain components: controllers (Raft), brokers, clients; show flows: discovery -> register -> heartbeats -> commit.

- Scalability: metadata cluster scales for control plane but consensus overhead grows with node count; for many topics/brokers use sharding (multi-Raft groups) for metadata.

- Reliability & availability: with Raft, consistency prioritized; system remains available for writes only when majority present (sacrifices availability during minority partitions).

- Bottlenecks: leader becomes coordinator for writes—can be mitigated with batching, leader stickiness, or partitioning metadata across Raft groups.

- Comparison with Kafka: functionally similar control-plane goals; Kafka uses ZooKeeper (older) or KRaft (newer). Our implementation is KRaft-like: controller is Raft-based; data-plane message replication is not fully built here.

---

## 8. Coding / Practical Prep (concise checklist with current state)
For interview coding demo preparation, these tasks are relevant; mark repo state:

- Minimal pub-sub demo: TODO (not fully implemented)
- Retry logic: discovery/backoff present for controller discovery; broker heartbeat retry/backoff is implemented. (Partial)
- Persistence layer: DB-backed metadata & Raft log implemented. (Done for metadata)
- Raft simulation: implemented in `dmq-metadata-service` (Done)
- JWT validation: TODO
- Consumer group demo: TODO
- Failover demo: manual testing scripts exist and were documented in REPORT (Done - manual tests recorded)

---

## 9. Pub-Sub Specific Deep Dive (answers you can deliver in interviews)

- Publish semantics: our current focus is control-plane (metadata). The project does not implement the full publish/consume flow; explain how you would map the metadata to the data-plane: producers send to broker leader for partition; broker writes to local partition log and replicates to replicas (TODO for data-plane).

- Delivery guarantees: default plan is at-least-once by acknowledging after persistent local write + replication to majority; exactly-once requires transactional support (future work).

- Ordering: order guaranteed per partition if producers and brokers behave correctly; this requires broker-local append-only logs and single-writer per partition.

- Offset management: TODO (design: store offsets per consumer group in metadata DB or dedicated offset store; commit semantics similar to Kafka).

---

## 10. Bonus / Advanced Topics (short answers you can memorize)

- CAP Theorem: metadata service is CP in partition scenarios: consistency prioritized; availability sacrificed if majority not reachable.

- Leader stickiness: keep leader for some time to reduce leadership churn; use randomized election timeouts and stabilize leader by avoiding immediate retries.

- Write amplification & compaction: Raft log requires compaction (snapshots) to bound storage; not implemented — marked TODO.

- Message batching: implement batched AppendEntries (already used conceptually for entries replication); apply same to producer batching once data-plane is built.

- Observability: TODO: add Prometheus metrics, structured logs, and tracing (OpenTelemetry recommended). Current repo has rich logging but lacks metrics.

---

## Quick interview-ready snippets / one-liners (bottom of document as requested)

1) Draw your architecture in 60 seconds (what to draw):
   - Three metadata nodes (Raft) in a row; label Ports 9091/9092/9093. Mark one as leader.
   - Storage brokers below (8081-8085). Arrows from brokers to controllers: discovery, register, heartbeat.
   - Show DB below controllers for `raft_log` and metadata tables. Indicate `AppendEntries` and `RequestVote` RPC arrows among metadata nodes.

2) Raft vs Kafka replication — 2–3 lines:
   - Raft: leader-based consensus for metadata; ensures majority commit and linearizability for control-plane operations. Kafka (data-plane) replicates partition logs across brokers; KRaft replaces ZooKeeper with Raft for metadata, while Kafka's data replication remains broker-to-broker.

3) Why chose Raft (2 lines):
   - Simplicity and clarity compared to Paxos; leader-oriented design fits a controller service that serializes metadata writes and provides strong consistency with straightforward correctness proofs.

4) Describe producer → broker → consumer flow (secure & reliable) — concise:
   - Producer authenticates (JWT + TLS) → finds partition leader via metadata → sends message; broker persists to local partition log and replicates to followers; on majority commit the broker acknowledges to producer; consumer fetches from leader, commits offset to metadata (or offset store); retries/backoff used for transient failures.
   - (Note: full data-plane is TODO; above is plan aligned with implemented metadata control-plane.)

5) "What happens when leader dies" scenario (concise timeline):
   - Leader crash → followers miss heartbeats → randomized election timeouts expire → candidate increments term & RequestVote in parallel → candidate wins majority → new leader initializes nextIndex/matchIndex and rebuilds in-memory heartbeat state from DB → CONTROLLER_CHANGED pushed to brokers → brokers reconnect to new leader. End-to-end ~8-10s in current tests.

6) Trade-offs (latency vs consistency) — short:
   - We prioritize consistency (majority commit) for metadata. This increases write latency because a majority must persist entries before commit, but prevents divergent metadata and ensures correctness. For higher throughput with relaxed consistency, you could weaken acknowledgment requirements (risky for metadata).

---

## Final notes and repo alignment

- Marked TODO where features are not implemented (data-plane storage, JWT, CI/CD workflows, log compaction, consumer groups).
- The document is strictly aligned with current implementation: Raft, DB-backed log, heartbeat, controller discovery, metadata state machine, and failover are implemented and described; data-plane specifics are left as TODO.

If you'd like, next I can:
- (A) produce a one-page visual "Project Readiness Map" (diagram + notes) for last-minute revision, or
- (B) create CI skeleton (`.github/workflows/ci.yml`) for building and running module tests, or
- (C) scaffold a small Prometheus metrics exporter integrated into the metadata service.

Choose A, B or C to continue, or ask for any refinements to this document.
