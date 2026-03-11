package com.distributedmq.metadata.service;

import com.distributedmq.common.model.BrokerStatus;
import com.distributedmq.metadata.coordination.BrokerInfo;
import com.distributedmq.metadata.coordination.MetadataStateMachine;
import com.distributedmq.metadata.coordination.RaftController;
import com.distributedmq.metadata.coordination.UpdateBrokerStatusCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Service for handling broker heartbeat monitoring (Phase 5)
 * - Receives heartbeats from storage services
 * - Periodically checks for stale heartbeats
 * - Automatically detects broker failures
 * - Uses in-memory heartbeat tracking to avoid Raft overhead
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeartbeatService {

    private final RaftController raftController;
    private final MetadataStateMachine metadataStateMachine;
    private final ControllerService controllerService;

    // In-memory heartbeat tracking (not persisted in Raft)
    // Only tracked on controller leader - lost on failover but rebuilt within 30s
    private final ConcurrentHashMap<Integer, Long> inMemoryHeartbeats = new ConcurrentHashMap<>();

    // Heartbeat timeout configuration (broker considered dead after this time)
    @Value("${metadata.heartbeat.timeout-ms:30000}")  // Default: 30 seconds
    private long heartbeatTimeoutMs;

    // Heartbeat check interval (how often we check for stale heartbeats)
    @Value("${metadata.heartbeat.check-interval-ms:10000}")  // Default: 10 seconds
    private long heartbeatCheckIntervalMs;

    @PostConstruct
    public void init() {
        log.info("========================================");
        log.info("Heartbeat Service initialized");
        log.info("Heartbeat timeout: {} ms ({} seconds)", heartbeatTimeoutMs, heartbeatTimeoutMs / 1000);
        log.info("Heartbeat check interval: {} ms ({} seconds)", heartbeatCheckIntervalMs, heartbeatCheckIntervalMs / 1000);
        log.info("In-memory heartbeat tracking: ENABLED");
        log.info("========================================");
    }

    /**
     * Process a heartbeat from a broker
     * Updates in-memory timestamp first, only updates Raft state on status transitions
     */
    public void processHeartbeat(Integer brokerId) {
        log.debug("Processing heartbeat from broker: {}", brokerId);

        // Check if broker is registered
        BrokerInfo broker = metadataStateMachine.getAllBrokers().get(brokerId);
        if (broker == null) {
            log.warn("Received heartbeat from unregistered broker: {}", brokerId);
            throw new IllegalArgumentException("Broker " + brokerId + " is not registered");
        }

        // Only the leader processes heartbeats
        if (!raftController.isControllerLeader()) {
            log.debug("Not the leader, ignoring heartbeat from broker {}", brokerId);
            return;
        }

        long now = System.currentTimeMillis();
        
        // ALWAYS update in-memory heartbeat timestamp (no Raft overhead)
        inMemoryHeartbeats.put(brokerId, now);
        log.debug("Updated in-memory heartbeat for broker {} at {}", brokerId, now);

        // Only update Raft state if broker status needs to change (OFFLINE → ONLINE)
        if (broker.getStatus() == BrokerStatus.OFFLINE) {
            log.info("Broker {} transitioning from OFFLINE → ONLINE, updating Raft state", brokerId);
            
            // Create command to update broker status via Raft
            UpdateBrokerStatusCommand command = UpdateBrokerStatusCommand.builder()
                    .brokerId(brokerId)
                    .status(BrokerStatus.ONLINE)
                    .lastHeartbeatTime(now)
                    .timestamp(now)
                    .build();

            try {
                // Submit to Raft with timeout
                raftController.appendCommand(command).get(5, TimeUnit.SECONDS);
                log.info("✅ Broker {} status updated to ONLINE via Raft (version will increment)", brokerId);
            } catch (Exception e) {
                log.error("Failed to update broker {} status to ONLINE via Raft: {}", brokerId, e.getMessage());
                throw new RuntimeException("Failed to process heartbeat", e);
            }
        } else {
            // Broker already ONLINE - just update in-memory timestamp
            log.debug("Broker {} already ONLINE, heartbeat recorded in-memory only (no Raft, no version increment)", 
                    brokerId);
        }
    }

    /**
     * Scheduled task to check for stale heartbeats and detect broker failures
     * Runs every heartbeatCheckIntervalMs (default: 10 seconds)
     * Uses in-memory timestamps instead of Raft state
     */
    @Scheduled(fixedDelayString = "${metadata.heartbeat.check-interval-ms:10000}")
    public void checkHeartbeats() {
        // Only the leader checks heartbeats
        if (!raftController.isControllerLeader()) {
            log.trace("Not the leader, skipping heartbeat check");
            return;
        }

        long now = System.currentTimeMillis();
        Map<Integer, BrokerInfo> allBrokers = metadataStateMachine.getAllBrokers();

        log.debug("Checking heartbeats for {} brokers...", allBrokers.size());

        for (BrokerInfo broker : allBrokers.values()) {
            // Skip if already marked as offline in Raft state
            if (broker.getStatus() == BrokerStatus.OFFLINE) {
                log.trace("Broker {} already OFFLINE in Raft state, skipping", broker.getBrokerId());
                continue;
            }

            // Get last heartbeat from in-memory map (not from Raft state)
            Long lastHeartbeat = inMemoryHeartbeats.get(broker.getBrokerId());
            
            if (lastHeartbeat == null) {
                // No in-memory heartbeat yet - use Raft state as fallback (e.g., after controller failover)
                lastHeartbeat = broker.getLastHeartbeatTime();
                log.debug("No in-memory heartbeat for broker {}, using Raft state: {}", 
                        broker.getBrokerId(), lastHeartbeat);
            }

            long timeSinceLastHeartbeat = now - lastHeartbeat;

            // Check if heartbeat is stale (timeout exceeded)
            if (timeSinceLastHeartbeat > heartbeatTimeoutMs) {
                log.warn("========================================");
                log.warn("BROKER FAILURE DETECTED!");
                log.warn("Broker {} heartbeat timeout: last heartbeat {} ms ago (threshold: {} ms)",
                        broker.getBrokerId(), timeSinceLastHeartbeat, heartbeatTimeoutMs);
                log.warn("Last heartbeat time: {}", lastHeartbeat);
                log.warn("Current time: {}", now);
                log.warn("========================================");

                // Only update Raft state on status transition (ONLINE → OFFLINE)
                UpdateBrokerStatusCommand command = UpdateBrokerStatusCommand.builder()
                        .brokerId(broker.getBrokerId())
                        .status(BrokerStatus.OFFLINE)
                        .lastHeartbeatTime(lastHeartbeat)
                        .timestamp(now)
                        .build();

                try {
                    raftController.appendCommand(command).get(5, TimeUnit.SECONDS);
                    log.info("✅ Broker {} status updated to OFFLINE via Raft (version will increment)", 
                            broker.getBrokerId());
                    
                    // Remove from in-memory map since broker is offline
                    inMemoryHeartbeats.remove(broker.getBrokerId());
                } catch (Exception e) {
                    log.error("Failed to update broker {} status to OFFLINE via Raft: {}", 
                            broker.getBrokerId(), e.getMessage());
                }

                // Trigger broker failure handling
                try {
                    controllerService.handleBrokerFailure(broker.getBrokerId());
                    log.info("Successfully triggered failure handling for broker {}", broker.getBrokerId());
                } catch (Exception e) {
                    log.error("Failed to handle broker {} failure: {}", broker.getBrokerId(), e.getMessage(), e);
                }
            } else {
                log.trace("Broker {} heartbeat OK: {} ms ago", broker.getBrokerId(), timeSinceLastHeartbeat);
            }
        }
    }

    /**
     * Rebuild in-memory heartbeat state after controller failover
     * Called when this node becomes controller leader
     */
    public void rebuildHeartbeatState() {
        log.info("Rebuilding in-memory heartbeat state after controller failover");
        
        inMemoryHeartbeats.clear();
        
        Map<Integer, BrokerInfo> allBrokers = metadataStateMachine.getAllBrokers();
        for (BrokerInfo broker : allBrokers.values()) {
            if (broker.getStatus() == BrokerStatus.ONLINE && broker.getLastHeartbeatTime() > 0) {
                // Restore last known heartbeat time from Raft state
                // Broker will timeout in 30s if no new heartbeat arrives
                inMemoryHeartbeats.put(broker.getBrokerId(), broker.getLastHeartbeatTime());
                log.debug("Restored heartbeat state for broker {}: last seen at {}", 
                        broker.getBrokerId(), broker.getLastHeartbeatTime());
            }
        }
        
        log.info("Rebuilt in-memory heartbeat state for {} ONLINE brokers", inMemoryHeartbeats.size());
    }

    /**
     * Get heartbeat status for monitoring/debugging
     */
    public String getHeartbeatStatus() {
        StringBuilder status = new StringBuilder();
        status.append("Heartbeat Monitor Status:\n");
        status.append("=========================\n");
        status.append("Timeout: ").append(heartbeatTimeoutMs).append(" ms\n");
        status.append("Check Interval: ").append(heartbeatCheckIntervalMs).append(" ms\n");
        status.append("Is Leader: ").append(raftController.isControllerLeader()).append("\n");
        status.append("In-memory tracking: ENABLED\n");
        status.append("In-memory heartbeats tracked: ").append(inMemoryHeartbeats.size()).append("\n\n");

        long now = System.currentTimeMillis();
        status.append("Broker Heartbeat Status:\n");

        for (BrokerInfo broker : metadataStateMachine.getAllBrokers().values()) {
            Long inMemoryTime = inMemoryHeartbeats.get(broker.getBrokerId());
            long timeSinceHeartbeat;
            
            if (inMemoryTime != null) {
                timeSinceHeartbeat = now - inMemoryTime;
                status.append(String.format("  Broker %d: status=%s, in-memory_heartbeat=%d ms ago%s\n",
                        broker.getBrokerId(),
                        broker.getStatus(),
                        timeSinceHeartbeat,
                        timeSinceHeartbeat > heartbeatTimeoutMs ? " [STALE]" : ""));
            } else {
                timeSinceHeartbeat = now - broker.getLastHeartbeatTime();
                status.append(String.format("  Broker %d: status=%s, raft_heartbeat=%d ms ago (no in-memory)%s\n",
                        broker.getBrokerId(),
                        broker.getStatus(),
                        timeSinceHeartbeat,
                        timeSinceHeartbeat > heartbeatTimeoutMs ? " [STALE]" : ""));
            }
        }

        return status.toString();
    }
}
