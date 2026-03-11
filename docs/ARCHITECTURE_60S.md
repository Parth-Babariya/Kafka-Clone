Architecture — What to draw in 60 seconds

Below are two representations you can use during interviews:
1) Quick instructions: what to draw in 60 seconds (short bullets you can narrate while drawing)
2) ASCII diagram (copy/paste-friendly) — the diagram is annotated with texts so you can visualize immediately

---

Quick drawing steps (60s):

1. Draw three boxes in a row and label them:
   - `Metadata Node 1 (9091)`  `Metadata Node 2 (9092)`  `Metadata Node 3 (9093)`
   - Mark the current leader (e.g., shade or add text "LEADER") on one of them.

2. Between the three metadata nodes draw bidirectional arrows and label them:
   - `AppendEntries` (heartbeat/log replication)
   - `RequestVote` (elections)

3. Below the three metadata nodes draw a single DB box and label it:
   - `DB: raft_log + metadata tables` (persistent Raft log + brokers/topics/partitions)
   - Draw vertical arrows from each metadata node to the DB (persist and read).

4. Below the DB draw 3–5 storage broker boxes (e.g., `Broker 101 (8081)`, `Broker 102 (8082)`, `Broker 103 (8083)`)
   - Draw upward arrows from brokers to the leader metadata node and label them: `discover / register / heartbeat`.
   - Also draw small arrows from leader to brokers for push notifications: `CONTROLLER_CHANGED`.

5. Add a client icon (producer / consumer) to the right; draw arrow to a broker for `produce / consume` (data-plane — mention TODO for full implementation).

6. Add short text notes to the side:
   - "Raft: leader election → log replication → majority commit"
   - "Heartbeats: broker → controller every 5s; leader validates and updates lastHeartbeatTime"
   - "Failover: election timeout 5–7s, new leader rebuilds state from DB (~8–10s total in tests)"

---

ASCII Diagram (annotated)

```
                          +-------------------------------------------+
                          |          Metadata Cluster (Raft)         |
                          |  AppendEntries / RequestVote RPCs (HTTP) |
                          +-------------------------------------------+

   [Metadata Node 1]
    (9091)    <------->   [Metadata Node 2] (LEADER)
     FOLLOWER              (9092)  <------->   [Metadata Node 3]
                           (RAFT)                (9093)

    ^  ^  ^
    |  |  |                    AppendEntries
    |  |  |                    RequestVote
    |  |  |                     (heartbeats, replication)
    |  |  |
    |  |  +--------------------------------------------+
    |  |                                               |
    |  +----------------------------------------------- +
    |                                                   |
    +---------------------------------------------------+
                         | persist / read |

                      +-------------------------------+
                      | DB: raft_log + metadata tables|
                      +-------------------------------+


    Broker 101 (8081)    Broker 102 (8082)    Broker 103 (8083)
    +-------------+      +-------------+      +-------------+
    |             | ---> |             | ---> |             |
    |  Storage    |      |  Storage    |      |  Storage    |
    |  Broker     |      |  Broker     |      |  Broker     |
    +-------------+      +-------------+      +-------------+
          |  ^                 |  ^               |  ^
          |  | discover /       |  | discover /    |  | discover /
          |  | register /       |  | register /     |  | register /
          |  +--- heartbeat --->+  +--- heartbeat ->+  +--- heartbeat -> [Leader]


Client (Producer) --> Broker 101: produce (data-plane TODO)
Client (Consumer) <-- Broker 101: consume (data-plane TODO)


Notes:
- Leader handles all state-changing requests; followers redirect with 503 + X-Controller-Leader header.
- Heartbeat interval: 5s (broker→controller). Leader heartbeat to followers: ~1.5s (empty AppendEntries).
- Election timeout: 5–7s (randomized) → prevents split votes. Failover measured ~8–10s.
```

---

Short narrative you can say while drawing (20–30s):

"I draw a 3-node Raft metadata cluster at the top — nodes talk to each other using AppendEntries and RequestVote. Metadata is stored persistently in a database (raft_log + metadata tables). Below are storage brokers that discover and register with the leader, then send heartbeats every 5 seconds; the leader validates and keeps an in-memory heartbeat map and persists status changes via Raft commands. Clients talk to brokers for data-plane operations (produce/consume) — the data-plane is planned but out-of-scope for this iteration. On leader failure, followers detect missed heartbeats and start an election; the new leader rebuilds heartbeat state from DB and notifies brokers (CONTROLLER_CHANGED)." 

---

File created: `ARCHITECTURE_60S.md` — open it and copy the ASCII diagram into your whiteboard or slide. If you want, I also created a simple SVG file with the same labeled layout for quick viewing.

Next: I created an SVG version of this diagram (`ARCHITECTURE_60S.svg`) in the repo so you can open it in a browser for a quick visual. (If you'd like a PNG instead, I can produce one but it requires converting the SVG.)
