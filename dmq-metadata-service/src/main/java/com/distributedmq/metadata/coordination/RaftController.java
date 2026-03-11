package com.distributedmq.metadata.coordination;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Raft-based Controller Coordinator (KRaft mode)
 * Implements the Raft consensus algorithm for leader election and log replication
 */
@Slf4j
@Component
public class RaftController {

    @Value("${kraft.node-id}")
    private Integer nodeId;

    @Value("${kraft.raft.election-timeout-ms}")
    private long electionTimeoutMs;

    @Value("${kraft.raft.heartbeat-interval-ms}")
    private long heartbeatIntervalMs;

    @Value("${kraft.raft.log-dir}")
    private String logDir;

    @Autowired
    private RaftNodeConfig raftConfig;

    @Autowired
    private RaftLogPersistence logPersistence;

    @Autowired
    private MetadataStateMachine stateMachine;

    @Autowired
    private RaftNetworkClient networkClient;

    @Autowired(required = false)
    private com.distributedmq.metadata.service.MetadataPushService metadataPushService;
    
    @Lazy
    @Autowired(required = false)
    private com.distributedmq.metadata.service.HeartbeatService heartbeatService;

    // Raft state
    private volatile RaftState state = RaftState.FOLLOWER;
    private volatile long currentTerm = 0;
    private volatile Integer votedFor = null;
    private volatile Integer currentLeaderId = null;

    // Log state
    private volatile long commitIndex = 0;
    private volatile long lastApplied = 0;

    // Leader state (only used when leader)
    private final Map<Integer, Long> nextIndex = new ConcurrentHashMap<>();
    private final Map<Integer, Long> matchIndex = new ConcurrentHashMap<>();

    // Track pending commands waiting for commit
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> pendingCommands = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> commandTimestamps = new ConcurrentHashMap<>();

    // Timers
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> electionTimeoutFuture;
    private ScheduledFuture<?> heartbeatFuture;

    // Election timeout tracking
    private final AtomicLong lastHeartbeatTime = new AtomicLong(System.currentTimeMillis());

    @PostConstruct
    public void init() {
        log.info("Initializing Raft Controller for node: {}", nodeId);

        // Initialize scheduler
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r);
            t.setName("raft-scheduler-" + nodeId);
            t.setDaemon(true);
            return t;
        });

        // Load persisted state and replay Raft log
        loadPersistedState();
        replayRaftLog();

        // Start election timeout
        resetElectionTimeout();

        // Start command timeout cleanup
        scheduler.scheduleWithFixedDelay(this::cleanupTimedOutCommands, 10, 10, TimeUnit.SECONDS);

        log.info("Raft Controller initialized: node={}, term={}, state={}",
                nodeId, currentTerm, state);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Raft Controller for node: {}", nodeId);

        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (networkClient != null) {
            networkClient.shutdown();
        }
    }

    /**
     * Load persisted Raft state from disk
     */
    private void loadPersistedState() {
        try {
            RaftPersistentState persistedState = logPersistence.loadPersistedState();
            if (persistedState != null) {
                this.currentTerm = persistedState.getCurrentTerm();
                this.votedFor = persistedState.getVotedFor();
                this.commitIndex = persistedState.getCommitIndex();
                log.info("Loaded persisted state: term={}, votedFor={}, commitIndex={}",
                        currentTerm, votedFor, commitIndex);
            } else {
                log.info("No persisted state found, starting fresh");
            }
        } catch (Exception e) {
            log.error("Failed to load persisted state, starting fresh", e);
            // Start with clean state
            this.currentTerm = 0;
            this.votedFor = null;
            this.commitIndex = 0;
            this.lastApplied = 0;
        }
    }

    /**
     * Replay Raft log to rebuild state machine
     * This is the critical startup step - Raft log is source of truth
     */
    private void replayRaftLog() {
        try {
            long lastLogIndex = logPersistence.getLastLogIndex();
            long startIndex = Math.max(1, lastApplied + 1);
            
            if (startIndex > lastLogIndex) {
                log.info("No log entries to replay (lastApplied={}, lastLogIndex={})", lastApplied, lastLogIndex);
                return;
            }

            log.info("Replaying Raft log from index {} to {} to rebuild state machine...", startIndex, lastLogIndex);
            
            int replayedCount = 0;
            for (long index = startIndex; index <= lastLogIndex; index++) {
                RaftLogEntry entry = logPersistence.getEntry(index);
                if (entry != null) {
                    // Apply command to state machine
                    stateMachine.apply(entry.getCommand());
                    lastApplied = index;
                    replayedCount++;
                    
                    if (replayedCount % 100 == 0) {
                        log.debug("Replayed {} log entries...", replayedCount);
                    }
                }
            }
            
            log.info("Successfully replayed {} Raft log entries. State machine rebuilt from Raft log.", replayedCount);
            log.info("State machine statistics: {} brokers, {} topics, {} total partitions",
                    stateMachine.getBrokerCount(), 
                    stateMachine.getTopicCount(),
                    stateMachine.getAllPartitions().size());

        } catch (Exception e) {
            log.error("Failed to replay Raft log, state machine may be incomplete", e);
            // Continue anyway - follower will catch up from leader
        }
    }

    /**
     * Reset election timeout with randomized delay
     */
    private void resetElectionTimeout() {
        if (electionTimeoutFuture != null) {
            electionTimeoutFuture.cancel(false);
        }

        long randomizedTimeout = electionTimeoutMs + ThreadLocalRandom.current().nextLong(electionTimeoutMs);
        electionTimeoutFuture = scheduler.schedule(this::startElection, randomizedTimeout, TimeUnit.MILLISECONDS);
        lastHeartbeatTime.set(System.currentTimeMillis());
    }

    /**
     * Start leader election
     */
    private void startElection() {
        synchronized (this) {
            if (state == RaftState.LEADER) {
                return; // Already leader
            }

            state = RaftState.CANDIDATE;
            currentTerm++;
            votedFor = nodeId;
            currentLeaderId = null;

            // Persist state change
            persistState();

            log.info("Starting election for term {}", currentTerm);

            // Request votes from all peers
            requestVotesFromPeers();

            // Reset election timeout
            resetElectionTimeout();
        }
    }

    /**
     * Request votes from all peer nodes
     */
    private void requestVotesFromPeers() {
        long lastLogIndex = logPersistence.getLastLogIndex();
        long lastLogTerm = logPersistence.getLastLogTerm();

        RequestVoteRequest request = RequestVoteRequest.builder()
                .term(currentTerm)
                .candidateId(nodeId)
                .lastLogIndex(lastLogIndex)
                .lastLogTerm(lastLogTerm)
                .build();

        List<CompletableFuture<RequestVoteResponse>> voteFutures =
                networkClient.sendRequestVoteToAll(raftConfig.getPeers(), request);

        // Count votes asynchronously
        CompletableFuture.allOf(voteFutures.toArray(new CompletableFuture[0]))
                .thenRun(() -> countVotes(voteFutures));
    }

    /**
     * Count votes and become leader if majority achieved
     */
    private void countVotes(List<CompletableFuture<RequestVoteResponse>> voteFutures) {
        int votesGranted = 1; // Vote for self
        int totalPeers = raftConfig.getPeers().size();

        for (CompletableFuture<RequestVoteResponse> future : voteFutures) {
            try {
                RequestVoteResponse response = future.get();
                if (response != null && response.isVoteGranted() && response.getTerm() == currentTerm) {
                    votesGranted++;
                } else if (response != null && response.getTerm() > currentTerm) {
                    // Step down if we see a higher term
                    stepDown(response.getTerm());
                    return;
                }
            } catch (Exception e) {
                log.warn("Failed to get vote response", e);
            }
        }

        int majority = (totalPeers + 1) / 2 + 1; // Include self
        if (votesGranted >= majority && state == RaftState.CANDIDATE) {
            becomeLeader();
        } else {
            log.info("Election failed: got {}/{} votes needed", votesGranted, majority);
        }
    }

    /**
     * Become the leader
     */
    private void becomeLeader() {
        synchronized (this) {
            if (state != RaftState.CANDIDATE) {
                return;
            }

            state = RaftState.LEADER;
            currentLeaderId = nodeId;

            // Initialize leader state
            long nextLogIndex = logPersistence.getLastLogIndex() + 1;
            for (RaftNodeConfig.NodeInfo peer : raftConfig.getPeers()) {
                nextIndex.put(peer.getNodeId(), nextLogIndex);
                matchIndex.put(peer.getNodeId(), 0L);
            }

            log.info("🎖️ Node {} became leader for term {}", nodeId, currentTerm);

            // Rebuild in-memory heartbeat state after controller failover
            if (heartbeatService != null) {
                try {
                    heartbeatService.rebuildHeartbeatState();
                    log.info("✅ Rebuilt in-memory heartbeat state for new controller");
                } catch (Exception e) {
                    log.error("❌ Failed to rebuild heartbeat state: {}", e.getMessage(), e);
                }
            } else {
                log.warn("⚠️ HeartbeatService not available, heartbeat state not rebuilt");
            }

            // Start sending heartbeats
            startHeartbeatTimer();
            
            // Notify all storage nodes about new controller (async to avoid blocking election)
            if (metadataPushService != null) {
                CompletableFuture.runAsync(() -> {
                    try {
                        String controllerUrl = com.distributedmq.common.config.ServiceDiscovery.getMetadataServiceUrl(nodeId);
                        log.info("📢 Pushing CONTROLLER_CHANGED notification: controllerId={}, url={}, term={}", 
                                nodeId, controllerUrl, currentTerm);
                        metadataPushService.pushControllerChanged(nodeId, controllerUrl, currentTerm);
                    } catch (Exception e) {
                        log.error("Failed to push CONTROLLER_CHANGED notification: {}", e.getMessage(), e);
                    }
                });
            } else {
                log.warn("MetadataPushService not available, cannot push CONTROLLER_CHANGED notification");
            }
        }
    }

    /**
     * Step down to follower
     */
    private void stepDown(long newTerm) {
        synchronized (this) {
            if (newTerm > currentTerm) {
                currentTerm = newTerm;
                votedFor = null;
                state = RaftState.FOLLOWER;
                currentLeaderId = null;

                persistState();

                // Stop leader activities
                if (heartbeatFuture != null) {
                    heartbeatFuture.cancel(false);
                    heartbeatFuture = null;
                }

                // Fail all pending commands since we're no longer leader
                for (CompletableFuture<Void> future : pendingCommands.values()) {
                    if (!future.isDone()) {
                        future.completeExceptionally(new IllegalStateException("Lost leadership"));
                    }
                }
                pendingCommands.clear();

                resetElectionTimeout();

                log.info("Stepped down to follower for term {}", currentTerm);
            }
        }
    }

    /**
     * Start heartbeat timer for leader
     */
    private void startHeartbeatTimer() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
        }

        heartbeatFuture = scheduler.scheduleWithFixedDelay(
                this::sendHeartbeats, 0, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Send heartbeats to all followers
     */
    private void sendHeartbeats() {
        if (state != RaftState.LEADER) {
            return;
        }

        for (RaftNodeConfig.NodeInfo peer : raftConfig.getPeers()) {
            sendAppendEntriesToPeer(peer);
        }
    }

    /**
     * Send AppendEntries to a specific peer
     */
    private void sendAppendEntriesToPeer(RaftNodeConfig.NodeInfo peer) {
        long prevLogIndex = nextIndex.get(peer.getNodeId()) - 1;
        long prevLogTerm = logPersistence.getTermForIndex(prevLogIndex);

        List<RaftLogEntry> entries = logPersistence.getEntries(
                nextIndex.get(peer.getNodeId()), logPersistence.getLastLogIndex());

        AppendEntriesRequest request = AppendEntriesRequest.builder()
                .term(currentTerm)
                .leaderId(nodeId)
                .prevLogIndex(prevLogIndex)
                .prevLogTerm(prevLogTerm)
                .entries(entries)
                .leaderCommit(commitIndex)
                .build();

        networkClient.sendAppendEntries(peer, request)
                .thenAccept(response -> handleAppendEntriesResponse(peer, request, response));
    }

    /**
     * Handle AppendEntries response from peer
     */
    private void handleAppendEntriesResponse(RaftNodeConfig.NodeInfo peer, AppendEntriesRequest request,
                                           AppendEntriesResponse response) {
        if (response.getTerm() > currentTerm) {
            stepDown(response.getTerm());
            return;
        }

        if (state != RaftState.LEADER || response.getTerm() != currentTerm) {
            return;
        }

        if (response.isSuccess()) {
            // Update match and next index
            long matchIdx = request.getPrevLogIndex() + (request.getEntries() != null ? request.getEntries().size() : 0);
            matchIndex.put(peer.getNodeId(), Math.max(matchIndex.get(peer.getNodeId()), matchIdx));
            nextIndex.put(peer.getNodeId(), matchIdx + 1);

            // Check if we can commit more entries
            updateCommitIndex();
        } else {
            // Decrement next index and retry
            long currentNextIndex = nextIndex.get(peer.getNodeId());
            nextIndex.put(peer.getNodeId(), Math.max(1, currentNextIndex - 1));
            // Will retry on next heartbeat
        }
    }

    /**
     * Update commit index based on majority replication
     */
    private void updateCommitIndex() {
        long newCommitIndex = commitIndex;
        int totalNodes = raftConfig.getPeers().size() + 1; // Include self

        for (long index = commitIndex + 1; index <= logPersistence.getLastLogIndex(); index++) {
            int replicatedCount = 1; // Self has it

            for (RaftNodeConfig.NodeInfo peer : raftConfig.getPeers()) {
                if (matchIndex.get(peer.getNodeId()) >= index) {
                    replicatedCount++;
                }
            }

            if (replicatedCount > totalNodes / 2) {
                newCommitIndex = index;
            } else {
                break; // Can't commit further
            }
        }

        if (newCommitIndex > commitIndex) {
            commitIndex = newCommitIndex;
            persistState();

            // Apply committed entries to state machine
            applyCommittedEntries();
        }
    }

    /**
     * Apply committed entries to state machine
     */
    private void applyCommittedEntries() {
        log.debug("Applying committed entries: lastApplied={}, commitIndex={}", lastApplied, commitIndex);

        while (lastApplied < commitIndex) {
            lastApplied++;
            RaftLogEntry entry = logPersistence.getEntry(lastApplied);
            if (entry != null) {
                log.debug("Applying log entry {}: term={}, commandType={}",
                    lastApplied, entry.getTerm(), entry.getCommand().getClass().getSimpleName());

                try {
                    stateMachine.apply(entry.getCommand());
                    log.debug("Successfully applied command at index {}", lastApplied);
                } catch (Exception e) {
                    log.error("Failed to apply command at index {}: {}", lastApplied, e.getMessage(), e);
                }

                // Complete any pending futures for this entry
                CompletableFuture<Void> pendingFuture = pendingCommands.remove(lastApplied);
                if (pendingFuture != null && !pendingFuture.isDone()) {
                    pendingFuture.complete(null);
                }
                commandTimestamps.remove(lastApplied);
            } else {
                log.warn("Log entry {} is null, skipping application", lastApplied);
            }
        }

        log.debug("Finished applying committed entries: lastApplied={}", lastApplied);
    }

    /**
     * Handle incoming RequestVote RPC
     */
    public RequestVoteResponse handleRequestVote(RequestVoteRequest request) {
        synchronized (this) {
            if (request.getTerm() > currentTerm) {
                stepDown(request.getTerm());
            }

            boolean grantVote = false;
            if (request.getTerm() == currentTerm &&
                (votedFor == null || votedFor.equals(request.getCandidateId())) &&
                isLogUpToDate(request.getLastLogIndex(), request.getLastLogTerm())) {

                votedFor = request.getCandidateId();
                grantVote = true;
                persistState();
                resetElectionTimeout();
            }

            return RequestVoteResponse.builder()
                    .term(currentTerm)
                    .voteGranted(grantVote)
                    .build();
        }
    }

    /**
     * Handle incoming AppendEntries RPC
     */
    public AppendEntriesResponse handleAppendEntries(AppendEntriesRequest request) {
        synchronized (this) {
            if (request.getTerm() > currentTerm) {
                stepDown(request.getTerm());
            }

            if (request.getTerm() < currentTerm) {
                return AppendEntriesResponse.builder()
                        .term(currentTerm)
                        .success(false)
                        .build();
            }

            // Valid leader for this term
            state = RaftState.FOLLOWER;
            currentLeaderId = request.getLeaderId();
            resetElectionTimeout();

            // Check log consistency
            if (request.getPrevLogIndex() > 0) {
                RaftLogEntry prevEntry = logPersistence.getEntry(request.getPrevLogIndex());
                if (prevEntry == null || prevEntry.getTerm() != request.getPrevLogTerm()) {
                    return AppendEntriesResponse.builder()
                            .term(currentTerm)
                            .success(false)
                            .build();
                }
            }

            // Append new entries
            if (request.getEntries() != null && !request.getEntries().isEmpty()) {
                log.debug("Follower {} received {} log entries from leader {}", nodeId, request.getEntries().size(), request.getLeaderId());
                for (RaftLogEntry entry : request.getEntries()) {
                    log.debug("Received entry: index={}, term={}, commandType={}",
                        entry.getIndex(), entry.getTerm(), entry.getCommand().getClass().getSimpleName());
                }
                logPersistence.appendEntries(request.getPrevLogIndex() + 1, request.getEntries());
            }

            // Update commit index
            if (request.getLeaderCommit() > commitIndex) {
                long oldCommitIndex = commitIndex;
                commitIndex = Math.min(request.getLeaderCommit(), logPersistence.getLastLogIndex());
                persistState();
                log.debug("Updated commitIndex from {} to {} (leaderCommit={}, lastLogIndex={})",
                    oldCommitIndex, commitIndex, request.getLeaderCommit(), logPersistence.getLastLogIndex());
                applyCommittedEntries();
            }

            return AppendEntriesResponse.builder()
                    .term(currentTerm)
                    .success(true)
                    .build();
        }
    }

    /**
     * Check if candidate's log is at least as up-to-date as ours
     */
    private boolean isLogUpToDate(long candidateLastLogIndex, long candidateLastLogTerm) {
        long lastLogIndex = logPersistence.getLastLogIndex();
        long lastLogTerm = logPersistence.getLastLogTerm();

        return candidateLastLogTerm > lastLogTerm ||
               (candidateLastLogTerm == lastLogTerm && candidateLastLogIndex >= lastLogIndex);
    }

    /**
     * Persist current state
     */
    private void persistState() {
        try {
            RaftPersistentState state = RaftPersistentState.builder()
                    .currentTerm(currentTerm)
                    .votedFor(votedFor)
                    .commitIndex(commitIndex)
                    .build();
            logPersistence.persistMetadata(state);
        } catch (Exception e) {
            log.error("Failed to persist Raft state", e);
        }
    }

    /**
     * Append a new command to the log (only leader can do this)
     */
    public CompletableFuture<Void> appendCommand(Object command) {
        if (state != RaftState.LEADER) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Not the leader"));
            return future;
        }

        RaftLogEntry entry = RaftLogEntry.builder()
                .term(currentTerm)
                .index(logPersistence.getLastLogIndex() + 1)
                .command(command)
                .build();

        logPersistence.appendEntry(entry);

        // Create future for this command and store it
        CompletableFuture<Void> commandFuture = new CompletableFuture<>();
        pendingCommands.put(entry.getIndex(), commandFuture);
        commandTimestamps.put(entry.getIndex(), System.currentTimeMillis());

        // Single-node optimization: if no peers, commit immediately
        if (raftConfig.getPeers().isEmpty()) {
            log.debug("Single-node cluster detected, committing entry {} immediately", entry.getIndex());
            commitIndex = entry.getIndex();
            persistState();
            applyCommittedEntries();
        } else {
            // Trigger replication to followers
            sendHeartbeats();
        }

        return commandFuture;
    }

    // Public API methods

    public boolean isControllerLeader() {
        return state == RaftState.LEADER;
    }

    public Integer getControllerLeaderId() {
        return currentLeaderId;
    }

    public long getCurrentTerm() {
        return currentTerm;
    }

    public RaftState getState() {
        return state;
    }

    public long getCommitIndex() {
        return commitIndex;
    }

    public long getLastApplied() {
        return lastApplied;
    }

    public Integer getNodeId() {
        return nodeId;
    }
    
    public MetadataStateMachine getStateMachine() {
        return stateMachine;
    }

    /**
     * Clean up timed-out pending commands
     */
    private void cleanupTimedOutCommands() {
        long now = System.currentTimeMillis();
        long timeoutMs = 30000; // 30 seconds

        List<Long> timedOutIndices = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : commandTimestamps.entrySet()) {
            if (now - entry.getValue() > timeoutMs) {
                timedOutIndices.add(entry.getKey());
            }
        }

        for (Long index : timedOutIndices) {
            CompletableFuture<Void> future = pendingCommands.remove(index);
            commandTimestamps.remove(index);
            if (future != null && !future.isDone()) {
                future.completeExceptionally(new TimeoutException("Command commit timed out"));
                log.warn("Command at index {} timed out waiting for commit", index);
            }
        }
    }
}