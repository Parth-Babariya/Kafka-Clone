package com.distributedmq.storage.replication;

import com.distributedmq.common.dto.ProduceRequest;
import com.distributedmq.common.dto.ProduceResponse;
import com.distributedmq.common.dto.ReplicationAck;
import com.distributedmq.common.dto.ReplicationRequest;
import com.distributedmq.common.dto.ReplicationResponse;
import com.distributedmq.storage.config.StorageConfig;
import com.distributedmq.storage.service.StorageService;
import com.distributedmq.storage.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import com.distributedmq.storage.service.StorageService;

/**
 * Manages replication of messages to follower brokers
 * Handles network communication and acknowledgment collection
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReplicationManager {

    private final RestTemplate restTemplate;
    private final StorageConfig config;
    private final MetadataStore metadataStore;
    private final ReplicaLagTracker lagTracker; // Phase 1: ISR Lag Monitoring
    private final FollowerProgressTracker progressTracker; // Catch-up mechanism: track follower progress

    // Circuit breaker state per follower broker
    private final ConcurrentHashMap<Integer, CircuitBreakerState> circuitBreakers = new ConcurrentHashMap<>();

    // Circuit breaker configuration
    private static final int CIRCUIT_BREAKER_FAILURE_THRESHOLD = 3;
    private static final long CIRCUIT_BREAKER_TIMEOUT_MS = 30000; // 30 seconds
    private static final int MAX_RETRY_ATTEMPTS = 2;
    private static final long RETRY_BACKOFF_MS = 1000; // 1 second

    /**
     * Circuit breaker state for each broker
     */
    private static class CircuitBreakerState {
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final AtomicLong lastFailureTime = new AtomicLong(0);
        private volatile boolean isOpen = false;

        public boolean isOpen() {
            if (isOpen) {
                // Check if timeout has passed
                if (System.currentTimeMillis() - lastFailureTime.get() > CIRCUIT_BREAKER_TIMEOUT_MS) {
                    isOpen = false;
                    failureCount.set(0);
                    log.info("Circuit breaker reset after timeout");
                }
            }
            return isOpen;
        }

        public void recordSuccess() {
            failureCount.set(0);
            isOpen = false;
        }

        public void recordFailure() {
            lastFailureTime.set(System.currentTimeMillis());
            if (failureCount.incrementAndGet() >= CIRCUIT_BREAKER_FAILURE_THRESHOLD) {
                isOpen = true;
                log.warn("Circuit breaker opened after {} failures", failureCount.get());
            }
        }
    }

    /**
     * Replicate a batch of messages to all ISR (in-sync replicas) for the partition
     * @return true if replication successful based on requiredAcks, false otherwise
     */
    public boolean replicateBatch(String topic, Integer partition,
                                  List<ProduceRequest.ProduceMessage> messages,
                                  Long baseOffset, Integer requiredAcks, Long leaderHighWaterMark,
                                  Long leaderLogEndOffset) { // Phase 1: Added LEO parameter

        log.info("Starting replication for topic-partition {}-{} with {} messages, baseOffset: {}, requiredAcks: {}, leaderLEO: {}",
                topic, partition, messages.size(), baseOffset, requiredAcks, leaderLogEndOffset);

        // Step 1: Comprehensive request validation
        if (!validateReplicationRequest(topic, partition, messages, baseOffset, requiredAcks)) {
            log.error("Replication request validation failed for {}-{}", topic, partition);
            return false;
        }

        // Step 2: Leadership validation - only leaders can initiate replication
        if (!metadataStore.isLeaderForPartition(topic, partition)) {
            log.error("Cannot replicate {}-{}: not the partition leader", topic, partition);
            return false;
        }

        // Get ISR (in-sync replicas) for this partition to send replication requests
        // ISR contains followers that are caught up and can reliably replicate messages
        List<BrokerInfo> isrBrokers = metadataStore.getISRForPartition(topic, partition);
        if (isrBrokers.isEmpty()) {
            log.warn("No ISR found for partition {}-{}", topic, partition);
            // If no ISR and acks > 0, we need at least the leader ack
            return requiredAcks == StorageConfig.ACKS_NONE; // Only succeed if acks=0 (no replication needed)
        }

        // Filter out the current broker (leader) from ISR - we don't replicate to ourselves
        List<BrokerInfo> followers = isrBrokers.stream()
                .filter(broker -> !broker.getId().equals(config.getBroker().getId()))
                .collect(Collectors.toList());

        if (followers.isEmpty()) {
            log.warn("No followers found in ISR for partition {}-{} (ISR size: {})", topic, partition, isrBrokers.size());
            // If no followers but acks > 0, we need at least the leader ack
            return requiredAcks == StorageConfig.ACKS_NONE; // Only succeed if acks=0 (no replication needed)
        }

        // Create replication request
        ReplicationRequest request = ReplicationRequest.builder()
                .topic(topic)
                .partition(partition)
                .messages(messages)
                .baseOffset(baseOffset)
                .leaderId(config.getBroker().getId())
                .leaderEpoch(metadataStore.getLeaderEpoch(topic, partition))
                .leaderHighWaterMark(leaderHighWaterMark)
                .leaderLogEndOffset(leaderLogEndOffset) // Phase 1: ISR Lag Monitoring - Include LEO
                .timeoutMs((long) config.getReplication().getFetchMaxWaitMs())
                .requiredAcks(requiredAcks)
                .build();

        // Send replication requests to all ISR asynchronously
        List<CompletableFuture<ReplicationAck>> replicationFutures = followers.stream()
                .map(follower -> CompletableFuture.supplyAsync(() ->
                    replicateToFollower(follower, request)))
                .collect(Collectors.toList());

        // Wait for acknowledgments based on requiredAcks
        try {
            long timeoutMs = config.getReplication().getFetchMaxWaitMs();
            List<ReplicationAck> acks = replicationFutures.stream()
                    .map(future -> {
                        try {
                            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
                        } catch (Exception e) {
                            log.error("Failed to get replication result: {}", e.getMessage());
                            // Return a failed ack
                            return ReplicationAck.builder()
                                    .topic(topic)
                                    .partition(partition)
                                    .success(false)
                                    .errorCode(ReplicationAck.ErrorCode.REPLICATION_FAILED)
                                    .errorMessage("Replication timeout or error: " + e.getMessage())
                                    .build();
                        }
                    })
                    .collect(Collectors.toList());

            // Count successful acknowledgments
            long successfulAcks = acks.stream()
                    .filter(ReplicationAck::isSuccess)
                    .count();

            // For acks=1: leader already succeeded, replication is fire-and-forget (always successful)
            // For acks=-1: need all followers to acknowledge
            boolean replicationSuccessful;
            if (requiredAcks == StorageConfig.ACKS_ALL) {
                replicationSuccessful = successfulAcks == followers.size();
            } else if (requiredAcks == StorageConfig.ACKS_LEADER) {
                replicationSuccessful = true; // Fire-and-forget for acks=1
            } else {
                replicationSuccessful = false; // Invalid acks value
            }

            log.info("Replication completed for {}-{}: {}/{} followers acknowledged successfully",
                    topic, partition, successfulAcks, followers.size());

            return replicationSuccessful;

        } catch (Exception e) {
            log.error("Replication failed for topic-partition {}-{}", topic, partition, e);
            return false;
        }
    }

    /**
     * Send replication request to a specific follower with circuit breaker and retry logic
     */
    private ReplicationAck replicateToFollower(BrokerInfo follower, ReplicationRequest request) {
        // Check circuit breaker
        CircuitBreakerState circuitBreaker = circuitBreakers.computeIfAbsent(follower.getId(),
            id -> new CircuitBreakerState());

        if (circuitBreaker.isOpen()) {
            log.warn("Circuit breaker is open for follower {}, skipping replication", follower.getId());
            return ReplicationAck.builder()
                    .topic(request.getTopic())
                    .partition(request.getPartition())
                    .followerId(follower.getId())
                    .success(false)
                    .errorCode(ReplicationAck.ErrorCode.REPLICATION_FAILED)
                    .errorMessage("Circuit breaker is open")
                    .build();
        }

        // Retry logic
        Exception lastException = null;
        for (int attempt = 0; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                if (attempt > 0) {
                    // Wait before retry
                    Thread.sleep(RETRY_BACKOFF_MS * attempt);
                    log.debug("Retrying replication to follower {} (attempt {})", follower.getId(), attempt + 1);
                }

                ReplicationAck result = sendReplicationRequest(follower, request);

                if (result.isSuccess()) {
                    circuitBreaker.recordSuccess();
                    return result;
                } else {
                    // Non-network error, don't retry
                    circuitBreaker.recordFailure();
                    return result;
                }

            } catch (Exception e) {
                lastException = e;
                log.warn("Replication attempt {} failed for follower {}: {}", attempt + 1, follower.getId(), e.getMessage());

                if (attempt == MAX_RETRY_ATTEMPTS) {
                    // All retries exhausted
                    circuitBreaker.recordFailure();
                    break;
                }
            }
        }

        // All attempts failed
        log.error("All replication attempts failed for follower {}: {}", follower.getId(),
                 lastException != null ? lastException.getMessage() : "Unknown error");

        return ReplicationAck.builder()
                .topic(request.getTopic())
                .partition(request.getPartition())
                .followerId(follower.getId())
                .success(false)
                .errorCode(ReplicationAck.ErrorCode.REPLICATION_FAILED)
                .errorMessage("All retry attempts failed: " + (lastException != null ? lastException.getMessage() : "Unknown error"))
                .build();
    }

    /**
     * Send replication request to follower (extracted for retry logic)
     */
    private ReplicationAck sendReplicationRequest(BrokerInfo follower, ReplicationRequest request) {
        String url = String.format("http://%s:%d/api/v1/storage/replicate",
                follower.getHost(), follower.getPort());

        log.debug("Sending replication request to follower {}: {}", follower.getId(), url);

        HttpEntity<ReplicationRequest> entity = new HttpEntity<>(request);
        ResponseEntity<ReplicationResponse> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, ReplicationResponse.class);

        ReplicationResponse replicationResponse = response.getBody();

        if (replicationResponse != null && replicationResponse.isSuccess()) {
            // Calculate follower's new LEO
            Long followerLEO = replicationResponse.getBaseOffset() + replicationResponse.getMessageCount();
            
            // Update follower progress tracker (for catch-up mechanism)
            progressTracker.updateFollowerProgress(
                request.getTopic(), 
                request.getPartition(), 
                follower.getId(), 
                followerLEO
            );
            
            // Convert to ack
            return ReplicationAck.builder()
                    .topic(request.getTopic())
                    .partition(request.getPartition())
                    .followerId(follower.getId())
                    .baseOffset(request.getBaseOffset())
                    .messageCount(request.getMessages().size())
                    .logEndOffset(followerLEO)
                    .highWaterMark(replicationResponse.getBaseOffset() + replicationResponse.getMessageCount())
                    .success(true)
                    .errorCode(ReplicationAck.ErrorCode.NONE)
                    .build();
        } else {
            log.warn("Replication failed for follower {}: {}",
                    follower.getId(), replicationResponse != null ? replicationResponse.getErrorMessage() : "null response");
            return ReplicationAck.builder()
                    .topic(request.getTopic())
                    .partition(request.getPartition())
                    .followerId(follower.getId())
                    .success(false)
                    .errorCode(ReplicationAck.ErrorCode.REPLICATION_FAILED)
                    .errorMessage(replicationResponse != null ? replicationResponse.getErrorMessage() : "Unknown error")
                    .build();
        }
    }

    /**
     * Process replication request as a follower
     * This is called when this broker receives a replication request from a leader
     */
    public ReplicationResponse processReplicationRequest(ReplicationRequest request, StorageService storageService) {
        log.info("Processing replication request from leader {} for topic-partition {}-{} with {} messages",
                request.getLeaderId(), request.getTopic(), request.getPartition(), request.getMessages().size());

        // Validate that this broker is a follower for this partition
        if (!metadataStore.isFollowerForPartition(request.getTopic(), request.getPartition())) {
            return ReplicationResponse.builder()
                    .topic(request.getTopic())
                    .partition(request.getPartition())
                    .followerId(config.getBroker().getId())
                    .success(false)
                    .errorCode(ReplicationResponse.ErrorCode.NOT_FOLLOWER_FOR_PARTITION)
                    .errorMessage("This broker is not a follower for partition " + request.getTopic() + "-" + request.getPartition())
                    .build();
        }

        // TODO: Validate leader epoch to prevent stale leader requests

        try {
            // Create a ProduceRequest from the replication request
            ProduceRequest produceRequest = ProduceRequest.builder()
                    .topic(request.getTopic())
                    .partition(request.getPartition())
                    .messages(request.getMessages())
                    .requiredAcks(StorageConfig.FOLLOWER_ACKS) // Followers don't need to replicate further
                    .leaderHighWaterMark(request.getLeaderHighWaterMark()) // Pass leader HWM for lag calculation
                    .build();

            // Process the messages through StorageService (use replicateMessages for followers)
            ProduceResponse produceResponse = storageService.replicateMessages(produceRequest);

            if (produceResponse.isSuccess()) {
                log.info("Successfully replicated {} messages for topic-partition {}-{}",
                        request.getMessages().size(), request.getTopic(), request.getPartition());

                // Phase 1: ISR Lag Monitoring - Calculate follower LEO after successful replication
                // Follower LEO = baseOffset + number of messages replicated
                long followerLEO = request.getBaseOffset() + request.getMessages().size();
                long leaderLEO = request.getLeaderLogEndOffset() != null ? request.getLeaderLogEndOffset() : followerLEO;
                
                // Record replication in lag tracker
                lagTracker.recordReplication(
                    request.getTopic(),
                    request.getPartition(),
                    leaderLEO,
                    followerLEO
                );
                
                // Get lag info to include in response
                ReplicaLagTracker.LagInfo lagInfo = lagTracker.getLagInfo(
                    request.getTopic(), request.getPartition());

                return ReplicationResponse.builder()
                        .topic(request.getTopic())
                        .partition(request.getPartition())
                        .followerId(config.getBroker().getId())
                        .baseOffset(request.getBaseOffset())
                        .messageCount(request.getMessages().size())
                        .success(true)
                        .errorCode(ReplicationResponse.ErrorCode.NONE)
                        .replicationTimeMs(System.currentTimeMillis())
                        // Phase 1: ISR Lag Monitoring - Include lag metrics
                        .followerLogEndOffset(followerLEO)
                        .leaderLogEndOffset(leaderLEO)
                        .offsetLag(lagInfo != null ? lagInfo.getOffsetLag() : 0L)
                        .lastCaughtUpTimestamp(lagInfo != null ? lagInfo.getLastCaughtUpTimestamp() : System.currentTimeMillis())
                        .timeSinceLastFetch(lagInfo != null ? lagInfo.getTimeSinceLastFetch() : 0L)
                        .build();
            } else {
                log.error("Replication failed: {}", produceResponse.getErrorMessage());
                return ReplicationResponse.builder()
                        .topic(request.getTopic())
                        .partition(request.getPartition())
                        .followerId(config.getBroker().getId())
                        .success(false)
                        .errorCode(ReplicationResponse.ErrorCode.STORAGE_ERROR)
                        .errorMessage(produceResponse.getErrorMessage())
                        .build();
            }

        } catch (Exception e) {
            log.error("Error processing replication request: {}", e.getMessage());
            return ReplicationResponse.builder()
                    .topic(request.getTopic())
                    .partition(request.getPartition())
                    .followerId(config.getBroker().getId())
                    .success(false)
                    .errorCode(ReplicationResponse.ErrorCode.STORAGE_ERROR)
                    .errorMessage("Storage error: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Validate replication request parameters
     */
    private boolean validateReplicationRequest(String topic, Integer partition,
                                             List<ProduceRequest.ProduceMessage> messages,
                                             Long baseOffset, Integer requiredAcks) {
        // Validate topic and partition
        if (topic == null || topic.isEmpty()) {
            log.error("Replication request validation failed: topic is null or empty");
            return false;
        }
        if (partition == null || partition < 0) {
            log.error("Replication request validation failed: invalid partition {}", partition);
            return false;
        }

        // Validate messages
        if (messages == null || messages.isEmpty()) {
            log.error("Replication request validation failed: messages is null or empty");
            return false;
        }

        // Validate base offset
        if (baseOffset == null || baseOffset < 0) {
            log.error("Replication request validation failed: invalid baseOffset {}", baseOffset);
            return false;
        }

        // Validate required acks
        if (requiredAcks == null ||
            (requiredAcks != StorageConfig.ACKS_NONE &&
             requiredAcks != StorageConfig.ACKS_LEADER &&
             requiredAcks != StorageConfig.ACKS_ALL)) {
            log.error("Replication request validation failed: invalid requiredAcks {}", requiredAcks);
            return false;
        }

        // Validate message contents
        for (ProduceRequest.ProduceMessage message : messages) {
            if (message.getValue() == null || message.getValue().length == 0) {
                log.error("Replication request validation failed: message has null or empty value");
                return false;
            }
        }

        return true;
    }

    /**
     * Send catch-up replication to a follower (called by CatchUpReplicator)
     * This is a synchronous method used specifically for catch-up scenarios.
     * 
     * @param follower The follower broker to send to
     * @param request The replication request with catch-up data
     * @return true if replication succeeded, false otherwise
     */
    public boolean sendCatchUpReplication(BrokerInfo follower, ReplicationRequest request) {
        log.debug("Sending catch-up replication to follower {}: topic={}, partition={}, baseOffset={}, messageCount={}",
                follower.getId(), request.getTopic(), request.getPartition(), 
                request.getBaseOffset(), request.getMessages().size());

        try {
            // Use existing replicateToFollower method which has retry logic and circuit breaker
            ReplicationAck ack = replicateToFollower(follower, request);

            if (ack.isSuccess()) {
                log.debug("✓ Catch-up replication successful to follower {}: offset {} -> {}",
                        follower.getId(), request.getBaseOffset(), 
                        request.getBaseOffset() + request.getMessages().size());
                return true;
            } else {
                log.warn("✗ Catch-up replication failed to follower {}: {}",
                        follower.getId(), ack.getErrorMessage());
                return false;
            }

        } catch (Exception e) {
            log.error("Exception during catch-up replication to follower {}: {}",
                    follower.getId(), e.getMessage(), e);
            return false;
        }
    }
}