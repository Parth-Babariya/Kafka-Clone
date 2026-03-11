# Raft in DistributedMQ — End‑to‑End Detailed Guide

Audience: Beginner → Advanced. Goal: make you fluent in both the Raft algorithm and how it is implemented in this repository (KRaft‑like metadata plane), including discovery, registration, heartbeat, and failover/state rebuild. Topics not implemented are clearly marked as TODO.

---

## 0. High‑Level Summary (What/Why)

- What problem Raft solves: Agree on an ordered log of commands across unreliable nodes. If most nodes (a majority) are up and able to communicate, the cluster can elect a leader and make progress safely.
- Why we use it here: The metadata controller cluster (3 nodes) needs strong consistency for operations like broker registration, topic creation, and partition leader changes. Raft provides a simple, leader‑based approach with provable safety.
- Our deployment: 3 metadata nodes on ports 9091/9092/9093. One is leader at a time. Brokers (8081‑8085) talk to the leader for state changes. All metadata changes are replicated via Raft before being applied.

---

## 1. Core Concepts (Beginner Friendly)

- Term: Logical epoch number. Increments when elections happen. A higher term supersedes lower terms.
- Roles:
  - FOLLOWER: Passive, responds to leader/candidate RPCs, resets election timeout on heartbeats.
  - CANDIDATE: Starts election by incrementing term and requesting votes.
  - LEADER: Handles client writes, replicates log, sends heartbeats.
- Majority/Quorum: With 3 nodes, majority is 2. Any decision requires ≥2 nodes.
- Log: Ordered list of entries (command + term + index). Entries are persisted before acknowledgment.
- Commit: An entry is committed when stored on a majority of nodes. Only then it can be applied to the state machine (DB update here).
- Safety properties:
  - Election safety: At most one leader per term.
  - Log matching: If two logs have the same index and term, the log entries up to that index are identical.
  - Leader completeness: Leader’s log contains all committed entries.
- CAP position (for metadata): CP under partition: consistency over availability for writes.

---

## 2. Raft RPCs and State (Theory → Implementation)

### 2.1 Node State (in our code)

- Persistent (survives restart): currentTerm, votedFor, RaftLog (table `raft_log`).
- Volatile (in memory): role/state, commitIndex, lastApplied, lastHeartbeatTime, and (leader‑only) nextIndex[peer], matchIndex[peer].

### 2.2 RequestVote (Candidate → Peers)

- Request includes: candidate term, candidateId, candidate lastLogIndex, lastLogTerm.
- Receiver grants vote if:
  1) request.term ≥ currentTerm (updates term & steps down if needed),
  2) hasn’t voted for someone else this term (or votes for the same candidate), and
  3) candidate log is at least as up‑to‑date as receiver’s (compare lastLogTerm, then lastLogIndex).
- Our behavior: Reject if term is stale, already voted, or candidate log behind. On grant, reset election timer.

### 2.3 AppendEntries (Leader → Followers)

- Used both for heartbeats (entries empty) and replication (entries present).
- Request includes: leader term, leaderId, prevLogIndex, prevLogTerm, entries[], leaderCommit.
- Follower checks consistency: if it doesn’t have prevLogIndex with prevLogTerm, it rejects. On success, it appends entries, truncating conflicting ones.
- Follower updates commitIndex = min(leaderCommit, follower.lastLogIndex) and applies committed entries in order.

---

## 3. Leader Election (Implemented)

Goal: elect a single leader to serialize metadata updates.

Role behavior:
- FOLLOWER: accepts RPCs and resets election timeout on every valid AppendEntries from leader.
- CANDIDATE: increments currentTerm, votes for self, sends RequestVote to all peers.
- LEADER: sends periodic heartbeats (empty AppendEntries) and replicates new entries.

Election timeout:
- Randomized: base + jitter. Defaults: base 5s + up to 2s jitter → 5‑7s. Randomization reduces split votes.

RequestVote rules (receiver side):
- Reject if candidate term < currentTerm.
- Reject if already voted for a different candidate in current term.
- Reject if candidate’s log is not at least as up‑to‑date (compare lastLogTerm, then lastLogIndex).
- Else grant vote and reset election timer.

Becoming leader:
- A candidate becomes leader after obtaining a majority (2/3). It initializes `nextIndex` and `matchIndex` maps for each follower, then starts sending heartbeats (~1.5s interval).

Corner cases:
- Higher‑term RPC received (RequestVote/AppendEntries): step down to FOLLOWER, update currentTerm.
- Split vote: no majority in first round; timers are randomized; another election round will eventually elect a leader.

Why majority:
- Prevents two separate quorums making conflicting decisions; ensures there’s always overlap across terms.

Implementation highlights (where to look):
- Role transitions and timers in `dmq-metadata-service` RaftController.
- Election timeout logic with jitter; timer checks every ~100ms.
- RequestVote REST endpoint accepts/denies votes per rules.

Observability cues:
- Log lines with emojis: 🗳️ election start, 🎉 leader elected, ⏱️ timeout resets, 💓 heartbeats.

---

## 4. Log Replication (Append → Replicate → Commit → Apply)

Client path (metadata change):
1) Client sends command (e.g., CreateTopic) to the controller leader.
2) Leader serializes to RaftLogEntry: {index, term, commandType, commandData JSON, timestamp} and persists to DB table `raft_log`.
3) Leader sends AppendEntries to all followers. If a follower is behind, entries can include preceding entries from that follower’s nextIndex.
4) Followers perform consistency checks using prevLogIndex/prevLogTerm. On mismatch, follower rejects; leader backtracks nextIndex for that follower and retries (optimized backtrack over simple ‑1 decrements).
5) When a majority ack the new entry, leader advances commitIndex.
6) Leader applies committed entries in order to `MetadataStateMachine` (updates broker/topic/partition tables), then replies success to the client.

Important implementation details:
- DB‑backed log (`raft_log`) survives crashes/restarts.
- AppendEntriesResponse includes `matchIndex` so leader updates `matchIndex[peer]` and `nextIndex[peer]` accurately.
- Replication wait has a timeout to avoid deadlock if a follower is down; client may receive a timeout (retry is acceptable).
- Followers delete conflicting entries before appending leader’s entries to restore the log prefix.

Consistency & safety:
- Only entries stored on a majority are considered committed; they are applied in order. Commands are designed to be idempotent where practical.

Where to look in code:
- RaftLogPersistence (save/get/truncate), MetadataStateMachine (apply commands), network client for AppendEntries.

---

## 5. Broker Registration & Heartbeat (Implemented on control‑plane)

Registration:
- On startup, `dmq-storage-service` runs controller discovery, then registers (or first heartbeat implies registration).
- Leader appends `RegisterBrokerCommand` to Raft log. When committed, `MetadataStateMachine` writes/updates the broker row in DB.

Heartbeat flow (every 5s from broker):
- Endpoint: `POST /heartbeat/{brokerId}` on metadata.
- Leader validation: if receiver is not leader, return HTTP 503 with `X-Controller-Leader: <leader-url>`. Broker switches.
- If leader: update in‑memory `lastHeartbeatTime` (and optionally persist last seen to DB). Response includes metadata version. If broker detects stale version, it pulls latest cluster metadata.

Health checks:
- `HeartbeatService` scans every 5s. If `now - lastHeartbeatTime > 30s`, broker is marked OFFLINE by appending `UpdateBrokerStatusCommand` to the Raft log. When committed, DB reflects OFFLINE and partitions may be reassigned.

OFFLINE → ONLINE transition:
- When an OFFLINE broker sends a new heartbeat to the leader, leader appends `UpdateBrokerStatusCommand` with ONLINE to Raft log and, when committed, DB updates to ONLINE.

Safety guard:
- Leader validation prevents a stale follower from processing heartbeats and making state changes (avoids split‑brain for broker status).

Where to look in code:
- HeartbeatController (503 redirect + leader processing), HeartbeatService (timed checks + OFFLINE marking), MetadataStateMachine (apply broker status updates).

---

## 6. Controller Discovery (Implemented on brokers)

Goal: Brokers must reliably find the current leader even if some metadata nodes are down.

Algorithm implemented (ControllerDiscoveryService):
1) Query all configured metadata nodes in parallel (CompletableFutures).
2) Take the first successful response indicating leader status or leader redirect URL.
3) If all fail, retry with exponential backoff: 1s → 2s → 4s → 8s.
4) Cache discovered leader info; invalidate after 3 consecutive heartbeat failures or on receiving `CONTROLLER_CHANGED` push.

Rationale:
- Parallel queries reduce discovery latency significantly vs sequential.
- Exponential backoff avoids thundering herds during outages.

Where to look:
- Storage/broker module `dmq-storage-service`: discovery service, metadata store cache, and heartbeat sender’s rediscovery on failure.

---

## 7. Failover & State Rebuild (Implemented)

Trigger:
- Leader process crashes or is partitioned away. Followers stop receiving heartbeats (AppendEntries) and reach their randomized election timeouts.

Observed timeline in tests:
- Leader down → first follower timeout around ~6.5s → candidate issues RequestVote in parallel → majority formed in ~1.3s → new leader elected → rebuild state → push notifications. End‑to‑end ~8.5–10s.

State rebuild (critical to avoid false OFFLINE):
- New leader reconstructs in‑memory `lastHeartbeatTime` map from DB’s broker rows (and last stored timestamps). This avoids immediately marking all brokers OFFLINE after a leadership change.

Controller change notification:
- New leader pushes `CONTROLLER_CHANGED` to brokers to shorten the time brokers operate against a non‑leader and to trigger fast reconnection.

Post‑failover steady state:
- Brokers switch to new leader on next heartbeat, continue normal operation.

Where to look:
- Leader transition code (becomeLeader), HeartbeatService init (rebuild from DB), notification mechanism (push to brokers).

---

## 8. Database Schema & Serialization

- `raft_log` table (simplified columns):
  - `log_index` (PK, BIGINT): monotonically increasing index.
  - `term` (INT): creator’s term.
  - `command_type` (VARCHAR): e.g., RegisterBrokerCommand, CreateTopicCommand, UpdateBrokerStatusCommand.
  - `command_data` (TEXT): JSON payload of the command.
  - `timestamp` (BIGINT): creation time.

- Metadata tables:
  - brokers: id, host, port, status, last_heartbeat_time, registered_at, etc.
  - topics: name, num_partitions, replication_factor, created_at (basic).
  - partitions: topic, partition_id, leader, replicas (basic, data‑plane replication is TODO).

- Serialization: Jackson JSON for command_data; DTOs in `dmq-common` define schemas.

---

## 9. Concurrency, Timers, and Safety in Code

- Volatile fields for visibility: currentTerm, role/state, commitIndex, lastApplied, lastHeartbeatTime.
- Synchronized transitions: startElection, becomeLeader, becomeFollower—avoid role races.
- ConcurrentHashMap for leader’s `nextIndex`/`matchIndex` to allow concurrent replication tasks.
- Timers:
  - Election timeout check ~every 100ms.
  - Leader heartbeats ~every 1.5s (empty AppendEntries).
  - Broker health scan every 5s; timeout threshold 30s.
- Replication wait timeout prevents deadlocks when followers are down.

---

## 10. Configuration & Tunables (Examples)

- Election timeout (ms): base + jitter
  - `kraft.raft.election-timeout-ms=5000`
  - `kraft.raft.election-timeout-jitter-ms=2000`
- Heartbeat interval (leader → followers):
  - `kraft.raft.heartbeat-interval-ms=1500`
- Broker heartbeat:
  - interval fixed at 5s; timeout threshold 30s (mark OFFLINE)
- Discovery backoff: 1s, 2s, 4s, 8s (built into algorithm)

Note: Property names shown here reflect typical usage described in docs; align with your actual application.yml if present.

---

## 11. End‑to‑End Sequences (Compact ASCII)

### 11.1 Election
```
Follower            Candidate                 Peers
  |  (timeout 5–7s)   |  startElection()        |
  |------------------>|  term++ , vote self     |
  |                   |-- RequestVote ------->  | (2 peers)
  |                   |<-- Votes (>=1) -------  |
  |                   |  majority? yes → LEADER |
  |                   |  start heartbeats       |
```

### 11.2 Append/Commit/Apply
```
Client -> Leader: CreateTopic
Leader: append to DB raft_log(index=N, term=t)
Leader -> Followers: AppendEntries(prev, entries=[N])
Followers: check prev match; append; ack
Leader: majority ack? yes → commitIndex=N → apply to DB → 200 OK
```

### 11.3 Broker heartbeat & status
```
Broker -> /heartbeat/{id} (5s)
Follower? 503 + X-Controller-Leader → retry leader
Leader? update lastHeartbeatTime[id]
HealthScan(5s): now - last[id] > 30s ? append UpdateBrokerStatus(OFFLINE)
```

### 11.4 Discovery (broker)
```
Parallel GET /who-is-leader? to nodes: 9091, 9092, 9093
First success → use; else retry with 1s,2s,4s,8s backoff
Invalidate cache after 3 heartbeat failures or CONTROLLER_CHANGED
```

### 11.5 Failover
```
Leader crash → followers miss heartbeats
~6.5s timeout → election → new leader ~1.3s later
New leader: rebuild heartbeat state from DB; push CONTROLLER_CHANGED
Brokers reconnect; steady state restored (8–10s total)
```

---

## 12. Troubleshooting & Observability

- Logs to look for:
  - 🗳️ Starting election, 🎉 Elected leader, 💓 Sending heartbeats
  - ❌ AppendEntries consistency check failed (index mismatch)
  - ⚠️ Broker missed heartbeat (>30s)
- Leader redirects:
  - 503 with `X-Controller-Leader` header → client/broker should switch
- Common symptoms:
  - Frequent elections: tune jitter, check network latency
  - Slow catch‑up: ensure fast backtrack of nextIndex; consider batching
  - Brokers flapping ONLINE/OFFLINE: verify heartbeat time sync

Metrics (TODO):
- raft_current_term, raft_state, raft_commit_index, raft_replication_latency_seconds
- brokers_online, heartbeat_lag_ms

---

## 13. Limitations & TODOs (Honest, Interview‑Friendly)

- Data‑plane message replication: TODO (only control‑plane/metadata via Raft implemented).
- Log compaction/snapshots: TODO (raft_log grows; implement snapshots + delete up to lastIncludedIndex).
- Dynamic membership (joint consensus): TODO.
- Pre‑Vote optimization: TODO (reduce disruptive elections).
- Linearizable reads (read‑index / lease‑based): TODO.
- JWT auth + TLS for brokers/clients: TODO.
- CI/CD workflows, metrics, and chaos tests: TODO.

---

## 14. Pointers to Code & Docs in This Repo

- `dmq-metadata-service/ARCHITECTURE.md` — Full controller design and Raft internals.
- `dmq-metadata-service/REPORT.md` — Implementation status, tests, and performance.
- `dmq-metadata-service/README.md` — APIs and operations.
- `dmq-storage-service/ARCHITECTURE.md` — Broker discovery/heartbeat internals.
- `MAANG_PREPARATION.md` — Interview checklist answers.
- `ARCHITECTURE_60S.md` / `.svg` — Quick draw diagrams.

---

## 15. One‑Screen TL;DR (say this if time‑boxed)

“DistributedMQ runs a 3‑node Raft metadata cluster. We use randomized 5–7s election timeouts, 1.5s heartbeats, DB‑backed raft_log, and majority commit before applying commands to a metadata state machine. Brokers discover the leader via parallel queries, register, and send 5s heartbeats; the leader marks them OFFLINE after 30s of silence by replicating a status update via Raft. On leader failure, a new leader is elected in ~8–10s, rebuilds heartbeat state from DB, and pushes CONTROLLER_CHANGED so brokers reconnect quickly. Data‑plane message replication is future work; control‑plane is strongly consistent and operational.”
