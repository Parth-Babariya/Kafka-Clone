package com.distributedmq.storage.replication;

import com.distributedmq.common.dto.ConsumeRequest;
import com.distributedmq.common.dto.ConsumeResponse;
import com.distributedmq.common.dto.ProduceRequest;
import com.distributedmq.common.dto.ReplicationRequest;
import com.distributedmq.common.model.Message;
import com.distributedmq.storage.config.StorageConfig;
import com.distributedmq.storage.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Catch-Up Replicator - Leader-Driven Catch-Up Mechanism
 * 
 * Scans for lagging followers and proactively pushes missed messages to them.
 * Runs periodically to detect and resolve replication gaps caused by:
 * - Network failures
 * - Broker downtime
 * - Circuit breaker activations
 * 
 * Configuration:
 * - catchUpThreshold: Lag in messages to trigger catch-up (default: 50)
 * - catchUpBatchSize: Messages per catch-up batch (default: 250)
 * - scanIntervalMs: How often to scan for lagging followers (default: 10000ms)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatchUpReplicator {

    private final FollowerProgressTracker progressTracker;
    private final ReplicationManager replicationManager;
    private final MetadataStore metadataStore;
    private final StorageService storageService;
    private final StorageConfig config;

    // Configuration properties
    @Value("${dmq.storage.replication.catchup.threshold:50}")
    private long catchUpThreshold;

    @Value("${dmq.storage.replication.catchup.batch-size:250}")
    private int catchUpBatchSize;

    @Value("${dmq.storage.replication.catchup.enabled:true}")
    private boolean catchUpEnabled;

    @PostConstruct
    public void init() {
        log.info("========================================");
        log.info("Catch-Up Replicator initialized");
        log.info("Enabled: {}", catchUpEnabled);
        log.info("Catch-up threshold: {} messages", catchUpThreshold);
        log.info("Batch size: {} messages", catchUpBatchSize);
        log.info("Scan interval: 10 seconds (fixed delay)");
        log.info("========================================");
    }

    /**
     * Scheduled task to scan for lagging followers and initiate catch-up
     * Runs every 10 seconds (as per user configuration)
     */
    @Scheduled(fixedDelay = 10000) // 10 seconds
    public void scanForLaggingFollowers() {
        if (!catchUpEnabled) {
            log.trace("Catch-up replication is disabled");
            return;
        }

        try {
            // Get all partitions where this broker is the leader
            // We need to iterate through partitions and check which ones we lead
            List<MetadataStore.PartitionInfo> allFollowerPartitions = metadataStore.getPartitionsWhereFollower();
            
            // Build a list of partitions we lead by checking leadership
            List<PartitionLeadership> leaderPartitions = allFollowerPartitions.stream()
                .map(p -> {
                    String key = p.getTopic() + "-" + p.getPartition();
                    if (metadataStore.isLeaderForPartition(p.getTopic(), p.getPartition())) {
                        return new PartitionLeadership(p.getTopic(), p.getPartition(), 
                            getFollowersForPartition(p));
                    }
                    return null;
                })
                .filter(p -> p != null)
                .collect(Collectors.toList());

            if (leaderPartitions.isEmpty()) {
                log.trace("No leader partitions, skipping catch-up scan");
                return;
            }

            log.debug("Scanning {} leader partitions for lagging followers", leaderPartitions.size());

            int lagDetected = 0;
            int catchUpAttempts = 0;
            int catchUpSuccess = 0;

            for (PartitionLeadership partition : leaderPartitions) {
                String topic = partition.topic;
                Integer partitionId = partition.partition;

                // Get leader's current LEO
                Long leaderLEO = storageService.getLogEndOffset(topic, partitionId);
                if (leaderLEO == null) {
                    log.warn("Could not get LEO for leader partition {}-{}, skipping", topic, partitionId);
                    continue;
                }

                // Check each follower in the replica set
                for (Integer replicaId : partition.followers) {
                    // Skip self (leader)
                    if (replicaId.equals(config.getBroker().getId())) {
                        continue;
                    }

                    // Check if follower is lagging
                    if (progressTracker.isFollowerLagging(topic, partitionId, replicaId, leaderLEO, catchUpThreshold)) {
                        lagDetected++;
                        
                        Long followerLEO = progressTracker.getFollowerLEO(topic, partitionId, replicaId);
                        long lag = followerLEO != null ? (leaderLEO - followerLEO) : leaderLEO;
                        
                        log.info("Detected lagging follower: broker {} for {}-{} (lag: {} messages, threshold: {})",
                                replicaId, topic, partitionId, lag, catchUpThreshold);

                        // Initiate catch-up
                        catchUpAttempts++;
                        boolean success = catchUpFollower(topic, partitionId, replicaId, followerLEO, leaderLEO);
                        
                        if (success) {
                            catchUpSuccess++;
                        }
                    }
                }
            }

            if (lagDetected > 0) {
                log.info("Catch-up scan complete: detected {} lagging followers, attempted {} catch-ups, {} successful",
                        lagDetected, catchUpAttempts, catchUpSuccess);
            } else {
                log.debug("Catch-up scan complete: all followers in sync");
            }

        } catch (Exception e) {
            log.error("Error during catch-up scan: {}", e.getMessage(), e);
        }
    }

    /**
     * Catch up a lagging follower by pushing missed messages
     * 
     * @param topic Topic name
     * @param partition Partition ID
     * @param followerId Follower broker ID
     * @param followerLEO Follower's current LEO (null if unknown)
     * @param leaderLEO Leader's current LEO
     * @return true if catch-up completed successfully, false otherwise
     */
    private boolean catchUpFollower(String topic, Integer partition, Integer followerId, 
                                    Long followerLEO, Long leaderLEO) {
        try {
            // Determine starting offset for catch-up
            long startOffset = followerLEO != null ? followerLEO : 0L;
            
            if (startOffset >= leaderLEO) {
                log.debug("Follower {} already caught up for {}-{} (follower: {}, leader: {})",
                        followerId, topic, partition, startOffset, leaderLEO);
                return true;
            }

            long totalLag = leaderLEO - startOffset;
            log.info("Starting catch-up replication: follower {} for {}-{}, offset {} -> {}, gap: {} messages",
                    followerId, topic, partition, startOffset, leaderLEO, totalLag);

            long currentOffset = startOffset;
            int batchesSent = 0;
            long messagesSent = 0;

            // Send messages in batches until caught up
            while (currentOffset < leaderLEO) {
                // Read batch from WAL using consume API
                ConsumeRequest consumeRequest = ConsumeRequest.builder()
                    .topic(topic)
                    .partition(partition)
                    .offset(currentOffset)
                    .maxMessages(catchUpBatchSize)
                    .build();
                
                ConsumeResponse consumeResponse = storageService.fetch(consumeRequest);
                
                if (consumeResponse == null || consumeResponse.getMessages() == null || 
                    consumeResponse.getMessages().isEmpty()) {
                    log.warn("No messages found at offset {} for {}-{}, catch-up incomplete",
                            currentOffset, topic, partition);
                    return false;
                }
                
                List<Message> messages = consumeResponse.getMessages();

                // Convert to ProduceMessages for replication
                List<ProduceRequest.ProduceMessage> produceMessages = messages.stream()
                        .map(msg -> ProduceRequest.ProduceMessage.builder()
                                .key(msg.getKey())
                                .value(msg.getValue())
                                .timestamp(msg.getTimestamp())
                                .build())
                        .collect(Collectors.toList());

                // Get partition metadata for leader info
                Long leaderEpoch = metadataStore.getLeaderEpoch(topic, partition);
                if (leaderEpoch == null) {
                    log.warn("Leader epoch not found for {}-{}, using default 0", topic, partition);
                    leaderEpoch = 0L;
                }

                // Build catch-up replication request
                ReplicationRequest request = ReplicationRequest.builder()
                        .topic(topic)
                        .partition(partition)
                        .messages(produceMessages)
                        .baseOffset(currentOffset)
                        .leaderId(config.getBroker().getId())
                        .leaderEpoch(leaderEpoch)
                        .leaderLogEndOffset(leaderLEO)
                        .leaderHighWaterMark(storageService.getHighWaterMark(topic, partition))
                        .requiredAcks(1)
                        .timeoutMs(5000L)
                        .build();

                // Get follower broker info
                BrokerInfo followerBroker = metadataStore.getBroker(followerId);
                if (followerBroker == null) {
                    log.error("Follower broker {} not found in metadata, aborting catch-up", followerId);
                    return false;
                }

                // Send replication request directly
                boolean success = replicationManager.sendCatchUpReplication(followerBroker, request);

                if (!success) {
                    log.warn("Catch-up batch failed for follower {} at offset {}, will retry next cycle",
                            followerId, currentOffset);
                    return false;
                }

                // Update progress
                currentOffset += messages.size();
                batchesSent++;
                messagesSent += messages.size();

                log.debug("Catch-up batch sent: follower {} for {}-{}, batch {}, offset {} -> {}, {} messages",
                        followerId, topic, partition, batchesSent, (currentOffset - messages.size()), currentOffset, messages.size());

                // Update tracker with new follower LEO
                progressTracker.updateFollowerProgress(topic, partition, followerId, currentOffset);
            }

            log.info("✓ Catch-up complete: follower {} for {}-{}, sent {} batches ({} messages), {} -> {}",
                    followerId, topic, partition, batchesSent, messagesSent, startOffset, currentOffset);

            return true;

        } catch (Exception e) {
            log.error("Catch-up failed for follower {} on {}-{}: {}", 
                    followerId, topic, partition, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Get catch-up statistics for monitoring
     */
    public String getCatchUpStats() {
        return String.format("Catch-Up Replicator Stats:\n" +
                           "  Enabled: %s\n" +
                           "  Threshold: %d messages\n" +
                           "  Batch Size: %d messages\n" +
                           "  Scan Interval: 10 seconds\n",
                catchUpEnabled, catchUpThreshold, catchUpBatchSize);
    }
    
    /**
     * Get followers for a partition from metadata
     */
    private List<Integer> getFollowersForPartition(MetadataStore.PartitionInfo partitionInfo) {
        // The PartitionInfo contains ISR, we need to get all replicas except the leader
        // Since we don't have direct access to replicas, we'll use ISR as followers
        return partitionInfo.getIsrIds().stream()
            .filter(id -> !id.equals(config.getBroker().getId()))
            .collect(Collectors.toList());
    }
    
    /**
     * Helper class to hold partition leadership info
     */
    private static class PartitionLeadership {
        final String topic;
        final Integer partition;
        final List<Integer> followers;
        
        PartitionLeadership(String topic, Integer partition, List<Integer> followers) {
            this.topic = topic;
            this.partition = partition;
            this.followers = followers;
        }
    }
}
