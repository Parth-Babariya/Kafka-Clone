package com.distributedmq.metadata.service;

import com.distributedmq.common.model.BrokerNode;
import com.distributedmq.common.model.BrokerStatus;
import com.distributedmq.common.model.PartitionMetadata;
import com.distributedmq.metadata.config.ClusterTopologyConfig;
import com.distributedmq.metadata.coordination.RaftController;
import com.distributedmq.metadata.coordination.RegisterBrokerCommand;
import com.distributedmq.metadata.coordination.AssignPartitionsCommand;
import com.distributedmq.metadata.coordination.PartitionAssignment;
import com.distributedmq.metadata.coordination.BrokerInfo;
import com.distributedmq.metadata.coordination.PartitionInfo;
import com.distributedmq.metadata.coordination.UpdatePartitionLeaderCommand;
import com.distributedmq.metadata.coordination.UpdateISRCommand;
import com.distributedmq.metadata.coordination.UpdateBrokerStatusCommand;
import com.distributedmq.metadata.coordination.ConsumerGroupInfo;
import com.distributedmq.metadata.coordination.UpdateConsumerGroupLeaderCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Implementation of ControllerService
 * Handles cluster coordination and leader election
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ControllerServiceImpl implements ControllerService {

    private final RaftController raftController;
    private final MetadataPushService metadataPushService;
    private final com.distributedmq.metadata.coordination.MetadataStateMachine metadataStateMachine;
    private final ClusterTopologyConfig clusterTopologyConfig;

    // In-memory broker registry (should be persisted in production)
    private final ConcurrentMap<Integer, BrokerNode> brokerRegistry = new ConcurrentHashMap<>();

    // REMOVED: partitionRegistry - Using Raft state machine (MetadataStateMachine) as single source of truth

    private final ScheduledExecutorService leadershipChecker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r);
        t.setName("leadership-checker");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    public void init() {
        log.info("ControllerServiceImpl initialized, waiting for Raft leadership...");

        // Start checking for leadership periodically
        leadershipChecker.scheduleWithFixedDelay(this::checkAndInitializeLeadership,
                1, 2, TimeUnit.SECONDS); // Check every 2 seconds
    }

    /**
     * Check if we have become the leader and initialize broker registration
     */
    private void checkAndInitializeLeadership() {
        if (raftController.isControllerLeader()) {
            log.info("Detected controller leadership, initializing brokers from config...");
            initializeBrokersFromConfig();
            leadershipChecker.shutdown(); // Stop checking once we're the leader
        }
    }

    /**
     * Initialize brokers from centralized config file - only called after becoming Raft leader
     * Loads storage services from config/services.json
     * 
     * NOTE: Brokers are registered but marked OFFLINE. They become ONLINE when they send first heartbeat.
     */
    private void initializeBrokersFromConfig() {
        log.info("========================================");
        log.info("SKIPPING auto-registration of brokers from config/services.json");
        log.info("Brokers will register themselves when they start up");
        log.info("========================================");
        
        // REMOVED: Auto-registration of brokers from config
        // Reason: Brokers should only be registered when they actually start up and send heartbeat
        // The old approach registered all brokers as ONLINE immediately, which was incorrect
        // Now: Storage services register themselves via /api/v1/metadata/brokers POST endpoint
    }

    @Override
    public List<PartitionMetadata> assignPartitions(String topicName, int partitionCount, int replicationFactor) {
        log.info("Assigning {} partitions with replication factor {} for topic: {}",
                partitionCount, replicationFactor, topicName);

        // Get available ONLINE brokers from state machine (registered via Raft consensus)
        List<BrokerInfo> availableBrokers = metadataStateMachine.getAllBrokers().values().stream()
                .filter(broker -> broker.getStatus() == BrokerStatus.ONLINE)
                .collect(Collectors.toList());

        if (availableBrokers.isEmpty()) {
            throw new IllegalStateException("No ONLINE brokers available for partition assignment");
        }

        if (availableBrokers.size() < replicationFactor) {
            throw new IllegalStateException(
                String.format("Not enough ONLINE brokers for replication factor %d. Available: %d",
                    replicationFactor, availableBrokers.size()));
        }

        List<PartitionMetadata> partitions = new ArrayList<>();
        List<PartitionAssignment> assignments = new ArrayList<>();

        for (int partitionId = 0; partitionId < partitionCount; partitionId++) {
            // Round-robin assignment across available brokers
            int startIndex = partitionId % availableBrokers.size();

            // Select replicas based on replication factor
            List<BrokerNode> replicas = new ArrayList<>();
            List<Integer> replicaIds = new ArrayList<>();
            for (int i = 0; i < replicationFactor; i++) {
                int brokerIndex = (startIndex + i) % availableBrokers.size();
                BrokerInfo brokerInfo = availableBrokers.get(brokerIndex);

                // Create broker node from broker info
                BrokerNode brokerNode = BrokerNode.builder()
                        .brokerId(brokerInfo.getBrokerId())
                        .host(brokerInfo.getHost())
                        .port(brokerInfo.getPort())
                        .status(BrokerStatus.ONLINE)
                        .build();

                replicas.add(brokerNode);
                replicaIds.add(brokerInfo.getBrokerId());
            }

            // Assign first replica as leader
            BrokerNode leader = replicas.get(0);
            int leaderId = replicaIds.get(0);

            // Initially all replicas are in ISR
            List<BrokerNode> isr = new ArrayList<>(replicas);
            List<Integer> isrIds = new ArrayList<>(replicaIds);

            // Create partition metadata for return value
            PartitionMetadata partition = PartitionMetadata.builder()
                    .topicName(topicName)
                    .partitionId(partitionId)
                    .leader(leader)
                    .replicas(replicas)
                    .isr(isr)
                    .startOffset(0L)
                    .endOffset(0L)
                    .build();

            partitions.add(partition);

            // Create partition assignment for Raft command
            PartitionAssignment assignment = new PartitionAssignment(
                    partitionId,
                    leaderId,
                    replicaIds,
                    isrIds
            );
            assignments.add(assignment);
        }

        // Submit partition assignments to Raft for consensus
        // No local registry - Raft state machine is the single source of truth
        AssignPartitionsCommand command = new AssignPartitionsCommand(
                topicName,
                assignments,
                System.currentTimeMillis()
        );

        try {
            // Submit to Raft and wait for commit (blocks until consensus achieved)
            CompletableFuture<Void> future = raftController.appendCommand(command);
            future.get(10, TimeUnit.SECONDS); // Wait with timeout
            
            log.info("Successfully assigned {} partitions for topic: {} via Raft consensus using {} brokers",
                    partitions.size(), topicName, availableBrokers.size());
        } catch (Exception e) {
            log.error("Failed to commit partition assignments via Raft for topic: {}", topicName, e);
            throw new IllegalStateException("Failed to assign partitions via Raft consensus", e);
        }

        return partitions;
    }

    @Override
    public void cleanupTopicPartitions(String topicName) {
        log.info("Cleaning up partitions for topic: {}", topicName);
        
        // TODO: Notify storage nodes to delete partition data
        // This will be implemented when storage service metadata sync is added
        
        log.info("Partition cleanup completed for topic: {}", topicName);
    }

    @Override
    public void handleBrokerFailure(Integer brokerId) {
        log.warn("========================================");
        log.warn("Handling broker failure: {}", brokerId);
        log.warn("========================================");
        
        // Step 1: Mark broker as OFFLINE via Raft consensus (Phase 4)
        try {
            UpdateBrokerStatusCommand statusCommand = UpdateBrokerStatusCommand.builder()
                .brokerId(brokerId)
                .status(BrokerStatus.OFFLINE)
                .lastHeartbeatTime(System.currentTimeMillis())
                .timestamp(System.currentTimeMillis())
                .build();
            
            raftController.appendCommand(statusCommand).get(10, TimeUnit.SECONDS);
            log.info("Marked broker {} as OFFLINE in Raft state", brokerId);
        } catch (Exception e) {
            log.error("Failed to update broker {} status to OFFLINE in Raft: {}", brokerId, e.getMessage());
            // Continue with failure handling even if status update fails
        }
        
        // Also update local registry for immediate effect
        BrokerNode broker = brokerRegistry.get(brokerId);
        if (broker != null) {
            broker.setStatus(BrokerStatus.OFFLINE);
            log.info("Marked broker {} as OFFLINE in local registry", brokerId);
        }
        
        // Step 2: Find all partitions affected by this broker failure
        Map<String, Map<Integer, PartitionInfo>> allPartitions = metadataStateMachine.getAllPartitions();
        List<PartitionInfo> partitionsToReelect = new ArrayList<>();
        List<PartitionInfo> partitionsToUpdateISR = new ArrayList<>();
        
        log.info("Scanning partitions for broker {} failure impact...", brokerId);
        
        for (Map.Entry<String, Map<Integer, PartitionInfo>> topicEntry : allPartitions.entrySet()) {
            String topicName = topicEntry.getKey();
            for (Map.Entry<Integer, PartitionInfo> partEntry : topicEntry.getValue().entrySet()) {
                PartitionInfo partition = partEntry.getValue();
                
                // Check if failed broker is the leader
                if (partition.getLeaderId() == brokerId) {
                    log.warn("Found partition {}-{} where failed broker {} is LEADER", 
                        topicName, partition.getPartitionId(), brokerId);
                    partitionsToReelect.add(partition);
                }
                // Check if failed broker is in ISR (but not leader)
                else if (partition.getIsrIds() != null && partition.getIsrIds().contains(brokerId)) {
                    log.warn("Found partition {}-{} where failed broker {} is in ISR (not leader)", 
                        topicName, partition.getPartitionId(), brokerId);
                    partitionsToUpdateISR.add(partition);
                }
            }
        }
        
        log.info("Broker failure impact analysis:");
        log.info("  - Partitions requiring leader re-election: {}", partitionsToReelect.size());
        log.info("  - Partitions requiring ISR update: {}", partitionsToUpdateISR.size());
        
        // Step 3: Re-elect leaders for partitions where failed broker was leader
        int reelectionSuccess = 0;
        int reelectionFailed = 0;
        
        for (PartitionInfo partition : partitionsToReelect) {
            try {
                log.info("Re-electing leader for partition {}-{} (current leader {} failed)", 
                    partition.getTopicName(), partition.getPartitionId(), brokerId);
                
                // First, remove failed broker from ISR
                List<Integer> newISR = new ArrayList<>(partition.getIsrIds());
                boolean removed = newISR.remove(Integer.valueOf(brokerId));
                
                if (removed) {
                    log.info("Removed failed broker {} from ISR for partition {}-{}", 
                        brokerId, partition.getTopicName(), partition.getPartitionId());
                }
                
                // Check if ISR is empty after removal
                if (newISR.isEmpty()) {
                    log.error("CRITICAL: Partition {}-{} has NO remaining ISR after broker {} failure! Partition is OFFLINE", 
                        partition.getTopicName(), partition.getPartitionId(), brokerId);
                    log.error("This partition will remain unavailable until a replica catches up and joins ISR");
                    reelectionFailed++;
                    // TODO: Mark partition as offline in metadata, implement recovery mechanism
                    continue;
                }
                
                // Update ISR (remove failed broker) via Raft consensus
                UpdateISRCommand isrCommand = UpdateISRCommand.builder()
                    .topicName(partition.getTopicName())
                    .partitionId(partition.getPartitionId())
                    .newISR(newISR)
                    .timestamp(System.currentTimeMillis())
                    .build();
                
                log.info("Submitting ISR update to Raft for partition {}-{}: removing broker {}", 
                    partition.getTopicName(), partition.getPartitionId(), brokerId);
                raftController.appendCommand(isrCommand).get(10, TimeUnit.SECONDS);
                log.info("ISR update committed to Raft for partition {}-{}", 
                    partition.getTopicName(), partition.getPartitionId());
                
                // Now elect new leader from remaining ISR
                log.info("Electing new leader for partition {}-{} from ISR: {}", 
                    partition.getTopicName(), partition.getPartitionId(), newISR);
                electPartitionLeader(partition.getTopicName(), partition.getPartitionId());
                
                reelectionSuccess++;
                log.info("✓ Successfully re-elected leader for partition {}-{}", 
                    partition.getTopicName(), partition.getPartitionId());
                    
            } catch (Exception e) {
                reelectionFailed++;
                log.error("✗ Failed to re-elect leader for partition {}-{}: {}", 
                    partition.getTopicName(), partition.getPartitionId(), e.getMessage(), e);
            }
        }
        
        // Step 4: Update ISR for partitions where failed broker was follower (not leader)
        int isrUpdateSuccess = 0;
        int isrUpdateFailed = 0;
        
        for (PartitionInfo partition : partitionsToUpdateISR) {
            try {
                log.info("Updating ISR for partition {}-{} to remove failed follower {}", 
                    partition.getTopicName(), partition.getPartitionId(), brokerId);
                
                List<Integer> newISR = new ArrayList<>(partition.getIsrIds());
                boolean removed = newISR.remove(Integer.valueOf(brokerId));
                
                if (!removed) {
                    log.warn("Broker {} was not in ISR for partition {}-{}, skipping", 
                        brokerId, partition.getTopicName(), partition.getPartitionId());
                    continue;
                }
                
                // Check if ISR shrinks below minimum (warning only, don't block)
                if (newISR.size() < 2) {
                    log.warn("WARNING: ISR for partition {}-{} shrunk to {} replicas after removing broker {}", 
                        partition.getTopicName(), partition.getPartitionId(), newISR.size(), brokerId);
                }
                
                UpdateISRCommand command = UpdateISRCommand.builder()
                    .topicName(partition.getTopicName())
                    .partitionId(partition.getPartitionId())
                    .newISR(newISR)
                    .timestamp(System.currentTimeMillis())
                    .build();
                
                raftController.appendCommand(command).get(10, TimeUnit.SECONDS);
                isrUpdateSuccess++;
                
                log.info("✓ Updated ISR for partition {}-{}: removed broker {}, new ISR: {}", 
                    partition.getTopicName(), partition.getPartitionId(), brokerId, newISR);
                    
            } catch (Exception e) {
                isrUpdateFailed++;
                log.error("✗ Failed to update ISR for partition {}-{}: {}", 
                    partition.getTopicName(), partition.getPartitionId(), e.getMessage(), e);
            }
        }
        
        // Step 5: Reassign consumer groups coordinated by failed broker
        try {
            log.info("Checking consumer groups coordinated by failed broker {}...", brokerId);
            List<ConsumerGroupInfo> affectedGroups = metadataStateMachine.getConsumerGroupsByLeader(brokerId);
            
            if (!affectedGroups.isEmpty()) {
                log.warn("Found {} consumer groups coordinated by failed broker {}", affectedGroups.size(), brokerId);
                
                int groupReassignSuccess = 0;
                int groupReassignFailed = 0;
                
                for (ConsumerGroupInfo group : affectedGroups) {
                    try {
                        // Select new group leader from available online brokers
                        List<BrokerInfo> onlineBrokers = metadataStateMachine.getAllBrokers().values().stream()
                                .filter(b -> b.getStatus() == BrokerStatus.ONLINE)
                                .collect(Collectors.toList());
                        
                        if (onlineBrokers.isEmpty()) {
                            log.error("No online brokers available to reassign consumer group {}", group.getGroupId());
                            groupReassignFailed++;
                            continue;
                        }
                        
                        // Simple selection: first available broker
                        // TODO: Implement load-based selection
                        Integer newLeader = onlineBrokers.get(0).getBrokerId();
                        
                        UpdateConsumerGroupLeaderCommand cmd = UpdateConsumerGroupLeaderCommand.builder()
                                .groupId(group.getGroupId())
                                .newGroupLeaderBrokerId(newLeader)
                                .timestamp(System.currentTimeMillis())
                                .build();
                        
                        raftController.appendCommand(cmd).get(10, TimeUnit.SECONDS);
                        groupReassignSuccess++;
                        
                        log.info("✓ Reassigned consumer group {} from broker-{} to broker-{}", 
                                 group.getGroupId(), brokerId, newLeader);
                        
                    } catch (Exception e) {
                        groupReassignFailed++;
                        log.error("✗ Failed to reassign consumer group {}: {}", 
                                 group.getGroupId(), e.getMessage(), e);
                    }
                }
                
                log.info("Consumer group reassignment: {} succeeded, {} failed", 
                         groupReassignSuccess, groupReassignFailed);
            } else {
                log.info("No consumer groups coordinated by broker {}", brokerId);
            }
        } catch (Exception e) {
            log.error("Error during consumer group reassignment for broker {}: {}", brokerId, e.getMessage(), e);
            // Don't fail overall broker failure handling
        }
        
        // Step 6: Push broker status change and metadata updates to all storage nodes
        try {
            log.info("Pushing broker failure updates to all storage nodes...");
            metadataPushService.pushFullClusterMetadata(getActiveBrokers());
            log.info("Successfully pushed broker failure update for broker {} to storage nodes", brokerId);
        } catch (Exception e) {
            log.error("Failed to push broker failure update for broker {} to storage nodes: {}", 
                brokerId, e.getMessage());
            // Don't fail failure handling if push fails - storage nodes will eventually sync
        }
        
        // Step 7: Summary logging
        log.warn("========================================");
        log.warn("Broker failure handling completed for broker {}", brokerId);
        log.warn("Summary:");
        log.warn("  - Leader re-elections: {} succeeded, {} failed", reelectionSuccess, reelectionFailed);
        log.warn("  - ISR updates: {} succeeded, {} failed", isrUpdateSuccess, isrUpdateFailed);
        if (reelectionFailed > 0) {
            log.error("  - {} partitions are OFFLINE due to no available ISR", reelectionFailed);
        }
        log.warn("========================================");
    }

    @Override
    public BrokerNode electPartitionLeader(String topicName, int partition) {
        log.info("Electing leader for partition: {}-{}", topicName, partition);
        
        // Step 1: Get partition info from Raft state machine (source of truth)
        PartitionInfo partitionInfo = metadataStateMachine.getPartition(topicName, partition);
        if (partitionInfo == null) {
            String errorMsg = String.format("Partition not found in state machine: %s-%d", topicName, partition);
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }
        
        // Step 2: Get current ISR (in-sync replicas)
        List<Integer> isr = partitionInfo.getIsrIds();
        if (isr == null || isr.isEmpty()) {
            String errorMsg = String.format("No ISR available for partition %s-%d - cannot elect leader", 
                topicName, partition);
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }
        
        log.info("Current ISR for {}-{}: {}", topicName, partition, isr);
        
        // Step 3: Pick first replica in ISR as new leader (preferred leader election)
        Integer newLeaderId = isr.get(0);
        log.info("Selected broker {} as new leader for {}-{} (first in ISR)", 
            newLeaderId, topicName, partition);
        
        // Step 4: Increment leader epoch (prevents split-brain)
        long newEpoch = partitionInfo.getLeaderEpoch() + 1;
        log.info("Incrementing leader epoch: {} -> {} for partition {}-{}", 
            partitionInfo.getLeaderEpoch(), newEpoch, topicName, partition);
        
        // Step 5: Submit UpdatePartitionLeaderCommand to Raft for consensus
        UpdatePartitionLeaderCommand command = UpdatePartitionLeaderCommand.builder()
            .topicName(topicName)
            .partitionId(partition)
            .newLeaderId(newLeaderId)
            .leaderEpoch(newEpoch)
            .timestamp(System.currentTimeMillis())
            .build();
        
        try {
            log.info("Submitting UpdatePartitionLeaderCommand to Raft for {}-{}", topicName, partition);
            raftController.appendCommand(command).get(30, TimeUnit.SECONDS);
            log.info("Successfully committed leader election to Raft log for {}-{}", topicName, partition);
        } catch (Exception e) {
            log.error("Failed to commit leader election to Raft for {}-{}: {}", 
                topicName, partition, e.getMessage(), e);
            throw new RuntimeException("Failed to elect partition leader through Raft consensus", e);
        }
        
        // Step 6: Get broker node for return value
        BrokerInfo brokerInfo = metadataStateMachine.getBroker(newLeaderId);
        if (brokerInfo == null) {
            String errorMsg = String.format("Broker %d not found in state machine", newLeaderId);
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }
        
        BrokerNode newLeader = BrokerNode.builder()
            .brokerId(brokerInfo.getBrokerId())
            .host(brokerInfo.getHost())
            .port(brokerInfo.getPort())
            .status(BrokerStatus.ONLINE)
            .build();
        
        // Step 7: Calculate followers (all replicas except leader)
        List<Integer> followerIds = partitionInfo.getReplicaIds().stream()
            .filter(id -> !id.equals(newLeaderId))
            .collect(Collectors.toList());
        
        // Step 8: Push leadership change to storage nodes
        try {
            log.info("Pushing leadership change to storage nodes for {}-{}: leader={}, followers={}, isr={}", 
                topicName, partition, newLeaderId, followerIds, isr);
            metadataPushService.pushPartitionLeadershipUpdate(
                topicName, partition, newLeaderId, followerIds, isr);
            log.info("Successfully pushed leadership change for partition {}-{} to storage nodes", 
                topicName, partition);
        } catch (Exception e) {
            log.error("Failed to push leadership change for partition {}-{}: {}", 
                topicName, partition, e.getMessage());
            // Don't fail election if push fails - Raft state is already updated
            // Storage nodes will eventually sync via metadata pull
        }
        
        log.info("Successfully elected broker {} as leader for partition {}-{} with epoch {}", 
            newLeaderId, topicName, partition, newEpoch);
        
        return newLeader;
    }

    @Override
    public List<BrokerNode> getActiveBrokers() {
        // Get brokers from Raft state machine (Phase 4: Use actual status from Raft)
        return metadataStateMachine.getAllBrokers().values().stream()
                .filter(brokerInfo -> brokerInfo.getStatus() == BrokerStatus.ONLINE) // Only ONLINE brokers
                .map(brokerInfo -> BrokerNode.builder()
                        .brokerId(brokerInfo.getBrokerId())
                        .host(brokerInfo.getHost())
                        .port(brokerInfo.getPort())
                        .status(brokerInfo.getStatus()) // Use actual status from Raft state
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * Get all brokers (including offline brokers) from Raft state
     * Phase 4: Returns complete broker list with actual status
     */
    public List<BrokerNode> getAllBrokers() {
        return metadataStateMachine.getAllBrokers().values().stream()
                .map(brokerInfo -> BrokerNode.builder()
                        .brokerId(brokerInfo.getBrokerId())
                        .host(brokerInfo.getHost())
                        .port(brokerInfo.getPort())
                        .status(brokerInfo.getStatus())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public void registerBroker(BrokerNode broker) {
        log.info("Registering broker via Raft consensus: {}", broker.getBrokerId());

        // Only the leader can register brokers
        if (!raftController.isControllerLeader()) {
            throw new IllegalStateException("Only the Raft leader can register brokers");
        }

        // Create Raft command for broker registration
        RegisterBrokerCommand command = RegisterBrokerCommand.builder()
                .brokerId(broker.getBrokerId())
                .host(broker.getHost())
                .port(broker.getPort())
                .timestamp(System.currentTimeMillis())
                .build();

        // Append to Raft log and wait for completion
        try {
            raftController.appendCommand(command).get(30, TimeUnit.SECONDS);
            log.info("Broker registration command committed to Raft log: {}", broker.getBrokerId());
        } catch (Exception e) {
            log.error("Failed to commit broker registration for {}: {}", broker.getBrokerId(), e.getMessage());
            throw new RuntimeException("Failed to register broker through Raft consensus", e);
        }
    }

    @Override
    public void unregisterBroker(Integer brokerId) {
        log.info("Unregistering broker: {}", brokerId);
        
        brokerRegistry.remove(brokerId);
        
        // Handle as broker failure
        handleBrokerFailure(brokerId);
        
        log.info("Successfully unregistered broker: {}", brokerId);

        // Push broker status change to storage nodes
        try {
            metadataPushService.pushFullClusterMetadata(getActiveBrokers());
            log.info("Successfully pushed broker unregistration update for broker {}", brokerId);
        } catch (Exception e) {
            log.error("Failed to push broker unregistration update for broker {}: {}", brokerId, e.getMessage());
            // Don't fail unregistration if push fails
        }
    }

    @Override
    public void updatePartitionLeadership(String topicName, int partitionId, Integer leaderId, List<Integer> followers, List<Integer> isr) {
        log.info("Updating partition leadership for {}-{}: leader={}, followers={}, isr={}",
                topicName, partitionId, leaderId, followers, isr);

        // Query current partition state from Raft
        PartitionInfo partition = metadataStateMachine.getPartition(topicName, partitionId);
        
        if (partition == null) {
            log.warn("Partition {}-{} not found in Raft state, cannot update leadership", topicName, partitionId);
            return;
        }

        // Prepare updated partition info
        Integer newLeaderId = (leaderId != null) ? leaderId : partition.getLeaderId();
        List<Integer> newISR = (isr != null) ? isr : partition.getIsrIds();
        
        // Update leader via Raft consensus
        if (leaderId != null && !leaderId.equals(partition.getLeaderId())) {
            try {
                log.info("Updating leader for {}-{} from {} to {} via Raft", 
                    topicName, partitionId, partition.getLeaderId(), leaderId);
                
                UpdatePartitionLeaderCommand command = UpdatePartitionLeaderCommand.builder()
                    .topicName(topicName)
                    .partitionId(partitionId)
                    .newLeaderId(leaderId)
                    .leaderEpoch(partition.getLeaderEpoch() + 1)
                    .timestamp(System.currentTimeMillis())
                    .build();
                
                raftController.appendCommand(command).get(10, TimeUnit.SECONDS);
                log.info("Leader update committed to Raft for {}-{}", topicName, partitionId);
            } catch (Exception e) {
                log.error("Failed to update leader for {}-{} via Raft: {}", topicName, partitionId, e.getMessage());
            }
        }

        // Update ISR via Raft consensus
        if (isr != null && !isr.equals(partition.getIsrIds())) {
            try {
                log.info("Updating ISR for {}-{} from {} to {} via Raft", 
                    topicName, partitionId, partition.getIsrIds(), isr);
                
                UpdateISRCommand command = UpdateISRCommand.builder()
                    .topicName(topicName)
                    .partitionId(partitionId)
                    .newISR(isr)
                    .timestamp(System.currentTimeMillis())
                    .build();
                
                raftController.appendCommand(command).get(10, TimeUnit.SECONDS);
                log.info("ISR update committed to Raft for {}-{}", topicName, partitionId);
            } catch (Exception e) {
                log.error("Failed to update ISR for {}-{} via Raft: {}", topicName, partitionId, e.getMessage());
            }
        }

        log.info("Partition leadership update completed for {}-{}", topicName, partitionId);
    }

    @Override
    public void removeFromISR(String topicName, int partitionId, Integer brokerId) {
        log.info("Removing broker {} from ISR for partition {}-{}", brokerId, topicName, partitionId);

        // Query partition from Raft state
        PartitionInfo partition = metadataStateMachine.getPartition(topicName, partitionId);
        if (partition == null) {
            log.warn("Partition {}-{} not found in Raft state when removing from ISR", topicName, partitionId);
            return;
        }

        List<Integer> currentISR = partition.getIsrIds();
        if (currentISR == null || !currentISR.contains(brokerId)) {
            log.debug("Broker {} is not in ISR for {}-{}, nothing to remove", brokerId, topicName, partitionId);
            return;
        }

        // Create new ISR without the broker
        List<Integer> newISR = new ArrayList<>(currentISR);
        newISR.remove(Integer.valueOf(brokerId));
        
        // Update via Raft consensus
        try {
            UpdateISRCommand command = UpdateISRCommand.builder()
                .topicName(topicName)
                .partitionId(partitionId)
                .newISR(newISR)
                .timestamp(System.currentTimeMillis())
                .build();
            
            raftController.appendCommand(command).get(10, TimeUnit.SECONDS);
            log.info("Successfully removed broker {} from ISR for {}-{} via Raft", brokerId, topicName, partitionId);
        } catch (Exception e) {
            log.error("Failed to remove broker {} from ISR for {}-{} via Raft: {}", 
                brokerId, topicName, partitionId, e.getMessage());
        }
    }

    @Override
    public void addToISR(String topicName, int partitionId, Integer brokerId) {
        log.info("Adding broker {} to ISR for partition {}-{}", brokerId, topicName, partitionId);

        // Query partition from Raft state
        PartitionInfo partition = metadataStateMachine.getPartition(topicName, partitionId);
        if (partition == null) {
            log.warn("Partition {}-{} not found in Raft state when adding to ISR", topicName, partitionId);
            return;
        }

        // Check if broker is a replica
        if (!partition.getReplicaIds().contains(brokerId)) {
            log.warn("Broker {} is not a replica for {}-{}, cannot add to ISR", brokerId, topicName, partitionId);
            return;
        }

        List<Integer> currentISR = partition.getIsrIds();
        if (currentISR != null && currentISR.contains(brokerId)) {
            log.debug("Broker {} is already in ISR for {}-{}", brokerId, topicName, partitionId);
            return;
        }

        // Create new ISR with the broker
        List<Integer> newISR = new ArrayList<>(currentISR != null ? currentISR : new ArrayList<>());
        newISR.add(brokerId);
        
        // Update via Raft consensus
        try {
            UpdateISRCommand command = UpdateISRCommand.builder()
                .topicName(topicName)
                .partitionId(partitionId)
                .newISR(newISR)
                .timestamp(System.currentTimeMillis())
                .build();
            
            raftController.appendCommand(command).get(10, TimeUnit.SECONDS);
            log.info("Successfully added broker {} to ISR for {}-{} via Raft", brokerId, topicName, partitionId);
        } catch (Exception e) {
            log.error("Failed to add broker {} to ISR for {}-{} via Raft: {}", 
                brokerId, topicName, partitionId, e.getMessage());
        }
    }

    @Override
    public List<Integer> getISR(String topicName, int partitionId) {
        // Query partition from Raft state
        PartitionInfo partition = metadataStateMachine.getPartition(topicName, partitionId);
        if (partition == null || partition.getIsrIds() == null) {
            return new ArrayList<>();
        }
        
        return new ArrayList<>(partition.getIsrIds());
    }

    @Override
    public Integer getPartitionLeader(String topicName, int partitionId) {
        // Query partition from Raft state
        PartitionInfo partition = metadataStateMachine.getPartition(topicName, partitionId);
        if (partition == null) {
            return null;
        }
        
        return partition.getLeaderId();
    }

    @Override
    public List<Integer> getPartitionFollowers(String topicName, int partitionId) {
        // Query partition from Raft state
        PartitionInfo partition = metadataStateMachine.getPartition(topicName, partitionId);
        if (partition == null || partition.getReplicaIds() == null) {
            return new ArrayList<>();
        }

        Integer leaderId = partition.getLeaderId();
        return partition.getReplicaIds().stream()
                .filter(id -> !id.equals(leaderId))
                .collect(Collectors.toList());
    }
}
