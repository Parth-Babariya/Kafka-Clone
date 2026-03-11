package com.distributedmq.storage.service;

import com.distributedmq.common.dto.ConsumeRequest;
import com.distributedmq.common.dto.ConsumeResponse;
import com.distributedmq.common.dto.ProduceRequest;
import com.distributedmq.common.dto.ProduceResponse;
import com.distributedmq.common.dto.PartitionStatus;
import com.distributedmq.common.model.Message;
import com.distributedmq.storage.config.StorageConfig;
import com.distributedmq.storage.replication.MetadataStore;
import com.distributedmq.storage.replication.ReplicationManager;
import com.distributedmq.storage.wal.WriteAheadLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of StorageService
 * Manages WAL and message persistence
 */

@Slf4j       // Injects log for logging
@Service       // Marks as a Spring component
@RequiredArgsConstructor // Generates constructor for 'final' fields

public class StorageServiceImpl implements StorageService {

    // Map of topic-partition to WAL
    private final Map<String, WriteAheadLog> partitionLogs = new ConcurrentHashMap<>();
    
    // Track pending replication for HWM advancement (for acks=0 and acks=1)
    // topic-partition -> pending offset that needs ISR confirmation for HWM
    private final Map<String, Long> pendingHWMUpdates = new ConcurrentHashMap<>();
    
    // Track leader HWM per partition (received from replication requests)
    // topic-partition -> latest leader HWM
    private final Map<String, Long> leaderHighWaterMarks = new ConcurrentHashMap<>();
    
    private final ReplicationManager replicationManager;
    private final StorageConfig config;
    private final MetadataStore metadataStore;

    /**
     * Append messages to partition log with replication behavior based on acks:
     * 
     * REPLICATION BEHAVIOR (All ack types):
     * - Leader ALWAYS sends replication requests to ALL ISR followers asynchronously
     * - Only ISR (In-Sync Replicas) members receive replication requests
     * - Followers NOT in ISR are excluded from replication
     * 
     * RESPONSE TIMING (Producer acknowledgment):
     * - acks=0: Returns immediately after receiving request (before leader write)
     * - acks=1: Returns after leader write completes (doesn't wait for followers)
     * - acks=-1: Returns ONLY after min.insync.replicas acknowledge
     * 
     * HIGH WATERMARK UPDATES:
     * - HWM is advanced ONLY when min.insync.replicas successfully replicate
     * - For acks=0 and acks=1: HWM updated asynchronously after followers acknowledge
     * - For acks=-1: HWM updated synchronously before responding to producer
     * - Consumers can only read up to HWM (committed messages)
     */
    @Override
    public ProduceResponse appendMessages(ProduceRequest request) {
        String topicPartition = getTopicPartitionKey(request.getTopic(), request.getPartition());

        log.info("Attempting to append {} messages to {}", 
                request.getMessages().size(), topicPartition);
        
        // Step 1: Validate request and check leadership
        ProduceResponse.ErrorCode validationError = validateProduceRequest(request);
        if (validationError != ProduceResponse.ErrorCode.NONE) {
            return ProduceResponse.builder()
                    .topic(request.getTopic())
                    .partition(request.getPartition())
                    .success(false)
                    .errorCode(validationError)
                    .errorMessage(validationError.getMessage())
                    .build();
        }
        
        if (!isLeaderForPartition(request.getTopic(), request.getPartition())) {
            return ProduceResponse.builder()
                    .topic(request.getTopic())
                    .partition(request.getPartition())
                    .success(false)
                    .errorCode(ProduceResponse.ErrorCode.NOT_LEADER_FOR_PARTITION)
                    .errorMessage("Not leader for partition")
                    .build();
        }
        
        // Get or create WAL for partition
        WriteAheadLog wal = partitionLogs.computeIfAbsent(
                topicPartition,
                k -> new WriteAheadLog(request.getTopic(), request.getPartition(), config)
        );
        
        try {
            // Clean ACK logic based on required acks
            Integer acks = request.getRequiredAcks();
            if (acks == null) {
                acks = StorageConfig.ACKS_LEADER; // Default to acks=1
            }
            
            if (acks == StorageConfig.ACKS_NONE) {
                return handleAcksNone(request, wal);
            } else if (acks == StorageConfig.ACKS_LEADER) {
                return handleAcksLeader(request, wal);
            } else if (acks == StorageConfig.ACKS_ALL) {
                return handleAcksAll(request, wal);
            } else {
                return ProduceResponse.builder()
                        .topic(request.getTopic())
                        .partition(request.getPartition())
                        .success(false)
                        .errorCode(ProduceResponse.ErrorCode.INVALID_REQUEST)
                        .errorMessage("Invalid acks value: " + acks)
                        .build();
            }
            
        } catch (Exception e) {
            log.error("Error appending messages to {}", topicPartition, e);
            
            return ProduceResponse.builder()
                    .topic(request.getTopic())
                    .partition(request.getPartition())
                    .success(false)
                    .errorCode(ProduceResponse.ErrorCode.INVALID_REQUEST)
                    .errorMessage("Failed to append messages: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public ConsumeResponse fetch(ConsumeRequest request) {
        String topicPartition = getTopicPartitionKey(request.getTopic(), request.getPartition());
        
        log.debug("Fetching messages from {} starting at offset {}", topicPartition, request.getOffset());
        
        WriteAheadLog wal = partitionLogs.get(topicPartition);
        
        if (wal == null) {
            return ConsumeResponse.builder()
                    .success(false)
                    .errorMessage("Partition not found")
                    .messages(new ArrayList<>())
                    .build();
        }
        
        try {
            // Get max messages to fetch (use default if not specified)
            int maxMessages = request.getMaxMessages() != null ? 
                request.getMaxMessages() : config.getConsumer().getDefaultMaxMessages();
            
            // Get max wait time (use default if not specified)
            long maxWaitMs = request.getMaxWaitMs() != null ? 
                request.getMaxWaitMs() : StorageConfig.FETCH_MAX_WAIT_DEFAULT;
            
            // Try to fetch messages immediately
            List<Message> messages = wal.read(request.getOffset(), maxMessages);
            
            // If we have messages or maxWaitMs is 0, return immediately
            if (!messages.isEmpty() || maxWaitMs <= 0) {
                return createConsumeResponse(messages, wal.getHighWaterMark(), request);
            }
            
            // Implement long polling: wait for new messages up to maxWaitMs
            long startTime = System.currentTimeMillis();
            long remainingWaitMs = maxWaitMs;
            
            while (remainingWaitMs > 0) {
                // Wait for a short interval before checking again
                long pollIntervalMs = Math.min(100, remainingWaitMs); // Poll every 100ms or remaining time
                
                try {
                    Thread.sleep(pollIntervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Fetch interrupted for {}", topicPartition);
                    break;
                }
                
                // Check for new messages
                messages = wal.read(request.getOffset(), maxMessages);
                if (!messages.isEmpty()) {
                    log.debug("Found {} messages after waiting {}ms for {}", 
                             messages.size(), System.currentTimeMillis() - startTime, topicPartition);
                    break;
                }
                
                remainingWaitMs = maxWaitMs - (System.currentTimeMillis() - startTime);
            }
            
            return createConsumeResponse(messages, wal.getHighWaterMark(), request);
            
        } catch (Exception e) {
            log.error("Error fetching messages", e);
            
            return ConsumeResponse.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .messages(new ArrayList<>())
                    .build();
        }
    }
    
    /**
     * Create a standardized consume response
     */
    private ConsumeResponse createConsumeResponse(List<Message> messages, long highWaterMark, ConsumeRequest request) {
        // Set topic and partition on messages
        messages.forEach(message -> {
            message.setTopic(request.getTopic());
            message.setPartition(request.getPartition());
        });
        
        return ConsumeResponse.builder()
                .messages(messages)
                .highWaterMark(highWaterMark)
                .success(true)
                .build();
    }

    @Override
    public Long getHighWaterMark(String topic, Integer partition) {
        String topicPartition = getTopicPartitionKey(topic, partition);
        WriteAheadLog wal = partitionLogs.get(topicPartition);
        
        return wal != null ? wal.getHighWaterMark() : 0L;
    }

    @Override
    public void flush() {
        log.debug("Flushing all partition logs");
        
        partitionLogs.values().forEach(WriteAheadLog::flush);
    }

    @Override
    public boolean isLeaderForPartition(String topic, Integer partition) {
        // Use metadata store to check leadership
        return metadataStore.isLeaderForPartition(topic, partition);
    }

    @Override
    public Long getLogEndOffset(String topic, Integer partition) {
        String topicPartition = getTopicPartitionKey(topic, partition);
        WriteAheadLog wal = partitionLogs.get(topicPartition);
        
        return wal != null ? wal.getLogEndOffset() : 0L;
    }

    @Override
    public ProduceResponse replicateMessages(ProduceRequest request) {
        String topicPartition = getTopicPartitionKey(request.getTopic(), request.getPartition());

        log.info("Replicating {} messages to {} (follower)", 
                request.getMessages().size(), topicPartition);
        
        // Store leader HWM for lag calculation
        if (request.getLeaderHighWaterMark() != null) {
            leaderHighWaterMarks.put(topicPartition, request.getLeaderHighWaterMark());
            log.debug("Updated leader HWM for {}: {}", topicPartition, request.getLeaderHighWaterMark());
        }
        
        // Step 1: Validate request (skip leadership check for replication)
        ProduceResponse.ErrorCode validationError = validateProduceRequest(request);
        if (validationError != ProduceResponse.ErrorCode.NONE) {
            return ProduceResponse.builder()
                    .topic(request.getTopic())
                    .partition(request.getPartition())
                    .success(false)
                    .errorCode(validationError)
                    .errorMessage(validationError.getMessage())
                    .build();
        }
        
        // Get or create WAL for partition
        WriteAheadLog wal = partitionLogs.computeIfAbsent(
                topicPartition,
                k -> new WriteAheadLog(request.getTopic(), request.getPartition(), config)
        );
        
        try {
            // For replication, always use acks=0 (no further replication needed)
            List<ProduceResponse.ProduceResult> results = new ArrayList<>();
            long baseOffset = -1;
            
            synchronized (wal) {
                if (config.getWal().getBatchWriteEnabled()) {
                    // OPTIMIZED PATH: Atomic batch write
                    List<Message> messages = new ArrayList<>();
                    for (ProduceRequest.ProduceMessage produceMessage : request.getMessages()) {
                        Message message = Message.builder()
                                .key(produceMessage.getKey())
                                .value(produceMessage.getValue())
                                .topic(request.getTopic())
                                .partition(request.getPartition())
                                .timestamp(produceMessage.getTimestamp() != null ? 
                                         produceMessage.getTimestamp() : System.currentTimeMillis())
                                .build();
                        messages.add(message);
                    }
                    
                    // Write entire batch atomically
                    List<Long> offsets = wal.appendBatch(messages);
                    baseOffset = offsets.get(0);
                    
                    // Build results
                    for (int i = 0; i < messages.size(); i++) {
                        results.add(ProduceResponse.ProduceResult.builder()
                                .offset(offsets.get(i))
                                .timestamp(messages.get(i).getTimestamp())
                                .errorCode(ProduceResponse.ErrorCode.NONE)
                                .build());
                    }
                    
                    log.debug("Replicated batch of {} messages at offsets: {} to {} (optimized)", 
                             messages.size(), offsets.get(0), offsets.get(offsets.size() - 1));
                } else {
                    // EXISTING PATH: Individual writes
                    for (ProduceRequest.ProduceMessage produceMessage : request.getMessages()) {
                        Message message = Message.builder()
                                .key(produceMessage.getKey())
                                .value(produceMessage.getValue())
                                .topic(request.getTopic())
                                .partition(request.getPartition())
                                .timestamp(produceMessage.getTimestamp() != null ? 
                                         produceMessage.getTimestamp() : System.currentTimeMillis())
                                .build();
                        
                        long offset = wal.append(message);
                        message.setOffset(offset);
                        
                        if (baseOffset == -1) {
                            baseOffset = offset;
                        }
                        
                        results.add(ProduceResponse.ProduceResult.builder()
                                .offset(offset)
                                .timestamp(message.getTimestamp())
                                .errorCode(ProduceResponse.ErrorCode.NONE)
                                .build());
                        
                        log.debug("Replicated message at offset: {}", offset);
                    }
                }
            }
            
            // For followers, we don't update HWM or replicate further
            // HWM is managed by the leader
            
            return ProduceResponse.builder()
                    .topic(request.getTopic())
                    .partition(request.getPartition())
                    .results(results)
                    .success(true)
                    .errorCode(ProduceResponse.ErrorCode.NONE)
                    .build();
            
        } catch (Exception e) {
            log.error("Error replicating messages to {}", topicPartition, e);
            
            return ProduceResponse.builder()
                    .topic(request.getTopic())
                    .partition(request.getPartition())
                    .success(false)
                    .errorCode(ProduceResponse.ErrorCode.INVALID_REQUEST)
                    .errorMessage("Failed to replicate messages: " + e.getMessage())
                    .build();
        }
    }

    private String getTopicPartitionKey(String topic, Integer partition) {
        return topic + "-" + partition;
    }
    
    /**
     * Update HWM asynchronously after successful replication to min.insync.replicas
     * This is called from async replication tasks for acks=0 and acks=1
     */
    private void updateHighWaterMarkAsync(String topicPartition, WriteAheadLog wal) {
        try {
            long currentLeo = wal.getLogEndOffset();
            wal.updateHighWaterMark(currentLeo);
            log.debug("Async HWM update: {} advanced to {}", topicPartition, currentLeo);
        } catch (Exception e) {
            log.error("Failed to update HWM asynchronously for {}: {}", topicPartition, e.getMessage());
        }
    }
    
    /**
     * Validate produce request parameters
     */
    private ProduceResponse.ErrorCode validateProduceRequest(ProduceRequest request) {
        if (request.getTopic() == null || request.getTopic().isEmpty()) {
            return ProduceResponse.ErrorCode.INVALID_REQUEST;
        }
        if (request.getPartition() == null) {
            return ProduceResponse.ErrorCode.INVALID_REQUEST;
        }
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return ProduceResponse.ErrorCode.INVALID_REQUEST;
        }
        return ProduceResponse.ErrorCode.NONE;
    }
    
    /**
     * Result of replication operation with ISR details
     */
    private static class ReplicationResult {
        private final boolean successful;
        private final int successfulReplicas;
        private final int totalReplicas;
        
        public ReplicationResult(boolean successful, int successfulReplicas, int totalReplicas) {
            this.successful = successful;
            this.successfulReplicas = successfulReplicas;
            this.totalReplicas = totalReplicas;
        }
        
        public boolean isSuccessful() { return successful; }
        public int getSuccessfulReplicas() { return successfulReplicas; }
        public int getTotalReplicas() { return totalReplicas; }
    }
    
    /**
     * Replicate to ISR replicas and return detailed replication result
     */
    private ReplicationResult replicateToISR(ProduceRequest request, long baseOffset) {
        // Get current high water mark for this partition
        long currentHwm = getHighWaterMark(request.getTopic(), request.getPartition());
        
        // Phase 1: ISR Lag Monitoring - Get leader's LEO
        long leaderLEO = getLogEndOffset(request.getTopic(), request.getPartition());
        
        // For acks=-1, we need all ISR replicas to acknowledge
        boolean replicationSuccess = replicationManager.replicateBatch(
                request.getTopic(),
                request.getPartition(),
                request.getMessages(),
                baseOffset,
                StorageConfig.ACKS_ALL,
                currentHwm,
                leaderLEO // Phase 1: Pass LEO to followers
        );
        
        // Get ISR information for detailed result
        // Note: This is a simplified implementation. In a real Kafka implementation,
        // the ReplicationManager would return more detailed ISR information.
        // For now, we assume replication success means all ISR replicas acknowledged.
        int totalISR = getISRCount(request.getTopic(), request.getPartition());
        int successfulReplicas = replicationSuccess ? totalISR : 0;
        
        return new ReplicationResult(replicationSuccess, successfulReplicas, totalISR);
    }
    
    /**
     * Get the number of ISR replicas for a partition
     * This is a placeholder - in real implementation, this would come from MetadataStore
     */
    private int getISRCount(String topic, Integer partition) {
        // TODO: Implement proper ISR count from MetadataStore
        // For now, assume at least 1 (the leader)
        return 1;
    }
    
    /**
     * Handle acks=0: No acknowledgment required, just append to leader
     * But still replicate asynchronously to ISR followers (fire-and-forget)
     */
    private ProduceResponse handleAcksNone(ProduceRequest request, WriteAheadLog wal) {
        List<ProduceResponse.ProduceResult> results = new ArrayList<>();
        long baseOffset = -1;
        
        synchronized (wal) {
            if (config.getWal().getBatchWriteEnabled()) {
                // OPTIMIZED PATH: Atomic batch write
                List<Message> messages = new ArrayList<>();
                for (ProduceRequest.ProduceMessage produceMessage : request.getMessages()) {
                    Message message = Message.builder()
                            .key(produceMessage.getKey())
                            .value(produceMessage.getValue())
                            .topic(request.getTopic())
                            .partition(request.getPartition())
                            .timestamp(produceMessage.getTimestamp() != null ? 
                                     produceMessage.getTimestamp() : System.currentTimeMillis())
                            .build();
                    messages.add(message);
                }
                
                // Write entire batch atomically
                List<Long> offsets = wal.appendBatch(messages);
                baseOffset = offsets.get(0);
                
                // Build results
                for (int i = 0; i < messages.size(); i++) {
                    results.add(ProduceResponse.ProduceResult.builder()
                            .offset(offsets.get(i))
                            .timestamp(messages.get(i).getTimestamp())
                            .errorCode(ProduceResponse.ErrorCode.NONE)
                            .build());
                }
                
                log.debug("Batch appended {} messages at offsets: {} to {} (acks=0, optimized)", 
                         messages.size(), offsets.get(0), offsets.get(offsets.size() - 1));
            } else {
                // EXISTING PATH: Individual writes
                for (ProduceRequest.ProduceMessage produceMessage : request.getMessages()) {
                    Message message = Message.builder()
                            .key(produceMessage.getKey())
                            .value(produceMessage.getValue())
                            .topic(request.getTopic())
                            .partition(request.getPartition())
                            .timestamp(produceMessage.getTimestamp() != null ? 
                                     produceMessage.getTimestamp() : System.currentTimeMillis())
                            .build();
                    
                    long offset = wal.append(message);
                    message.setOffset(offset);
                    
                    if (baseOffset == -1) {
                        baseOffset = offset;
                    }
                    
                    results.add(ProduceResponse.ProduceResult.builder()
                            .offset(offset)
                            .timestamp(message.getTimestamp())
                            .errorCode(ProduceResponse.ErrorCode.NONE)
                            .build());
                    
                    log.debug("Message appended at offset: {} (acks=0)", offset);
                }
            }
        }
        
        // Replicate asynchronously to ISR followers (fire-and-forget)
        // Background task will update HWM when min.insync.replicas acknowledge
        long finalBaseOffset = baseOffset;
        String topicPartition = getTopicPartitionKey(request.getTopic(), request.getPartition());
        CompletableFuture.runAsync(() -> {
            try {
                long currentHwm = getHighWaterMark(request.getTopic(), request.getPartition());
                long leaderLEO = getLogEndOffset(request.getTopic(), request.getPartition()); // Phase 1: Get LEO
                boolean replicationSuccess = replicationManager.replicateBatch(
                    request.getTopic(),
                    request.getPartition(),
                    request.getMessages(),
                    finalBaseOffset,
                    StorageConfig.ACKS_NONE,
                    currentHwm,
                    leaderLEO // Phase 1: Pass LEO
                );
                log.debug("Async replication initiated for {}-{} at offset {} (acks=0), success: {}",
                         request.getTopic(), request.getPartition(), finalBaseOffset, replicationSuccess);
                
                // Update HWM if min.insync.replicas acknowledged
                if (replicationSuccess) {
                    updateHighWaterMarkAsync(topicPartition, wal);
                }
            } catch (Exception e) {
                log.warn("Async replication failed for {}-{}: {}",
                        request.getTopic(), request.getPartition(), e.getMessage());
            }
        });
        
        // Return immediately without waiting for replication
        return ProduceResponse.builder()
                .topic(request.getTopic())
                .partition(request.getPartition())
                .results(results)
                .success(true)
                .errorCode(ProduceResponse.ErrorCode.NONE)
                .build();
    }
    
    /**
     * Handle acks=1: Wait for leader acknowledgment only
     * But still replicate asynchronously to ISR followers (fire-and-forget)
     */
    private ProduceResponse handleAcksLeader(ProduceRequest request, WriteAheadLog wal) {
        List<ProduceResponse.ProduceResult> results = new ArrayList<>();
        long baseOffset = -1;
        
        synchronized (wal) {
            if (config.getWal().getBatchWriteEnabled()) {
                // OPTIMIZED PATH: Atomic batch write
                List<Message> messages = new ArrayList<>();
                for (ProduceRequest.ProduceMessage produceMessage : request.getMessages()) {
                    Message message = Message.builder()
                            .key(produceMessage.getKey())
                            .value(produceMessage.getValue())
                            .topic(request.getTopic())
                            .partition(request.getPartition())
                            .timestamp(produceMessage.getTimestamp() != null ? 
                                     produceMessage.getTimestamp() : System.currentTimeMillis())
                            .build();
                    messages.add(message);
                }
                
                // Write entire batch atomically
                List<Long> offsets = wal.appendBatch(messages);
                baseOffset = offsets.get(0);
                
                // Build results
                for (int i = 0; i < messages.size(); i++) {
                    results.add(ProduceResponse.ProduceResult.builder()
                            .offset(offsets.get(i))
                            .timestamp(messages.get(i).getTimestamp())
                            .errorCode(ProduceResponse.ErrorCode.NONE)
                            .build());
                }
                
                log.debug("Batch appended {} messages at offsets: {} to {} (acks=1, optimized)", 
                         messages.size(), offsets.get(0), offsets.get(offsets.size() - 1));
            } else {
                // EXISTING PATH: Individual writes
                for (ProduceRequest.ProduceMessage produceMessage : request.getMessages()) {
                    Message message = Message.builder()
                            .key(produceMessage.getKey())
                            .value(produceMessage.getValue())
                            .topic(request.getTopic())
                            .partition(request.getPartition())
                            .timestamp(produceMessage.getTimestamp() != null ? 
                                     produceMessage.getTimestamp() : System.currentTimeMillis())
                            .build();
                    
                    long offset = wal.append(message);
                    message.setOffset(offset);
                    
                    if (baseOffset == -1) {
                        baseOffset = offset;
                    }
                    
                    results.add(ProduceResponse.ProduceResult.builder()
                            .offset(offset)
                            .timestamp(message.getTimestamp())
                            .errorCode(ProduceResponse.ErrorCode.NONE)
                            .build());
                    
                    log.debug("Message appended at offset: {} (acks=1)", offset);
                }
            }
        }
        
        // For acks=1, update HWM immediately after leader acknowledgment
        // HWM should advance to the end of the batch we just wrote
        long newHwm = baseOffset + request.getMessages().size();
        wal.updateHighWaterMark(newHwm);
        log.debug("Updated High Watermark to {} for topic-partition: {} (acks=1)",
                 newHwm, getTopicPartitionKey(request.getTopic(), request.getPartition()));
        
        // Replicate asynchronously to ISR followers (fire-and-forget)
        // This doesn't affect the producer acknowledgment
        long finalBaseOffset = baseOffset;
        String topicPartition = getTopicPartitionKey(request.getTopic(), request.getPartition());
        CompletableFuture.runAsync(() -> {
            try {
                long currentHwm = getHighWaterMark(request.getTopic(), request.getPartition());
                long leaderLEO = getLogEndOffset(request.getTopic(), request.getPartition()); // Phase 1: Get LEO
                boolean replicationSuccess = replicationManager.replicateBatch(
                    request.getTopic(),
                    request.getPartition(),
                    request.getMessages(),
                    finalBaseOffset,
                    StorageConfig.ACKS_LEADER,
                    currentHwm,
                    leaderLEO // Phase 1: Pass LEO
                );
                log.debug("Async replication initiated for {}-{} at offset {} (acks=1), success: {}",
                         request.getTopic(), request.getPartition(), finalBaseOffset, replicationSuccess);
                
                // For acks=1, replication success/failure doesn't affect the response
                // The producer was already acknowledged when the leader wrote
            } catch (Exception e) {
                log.warn("Async replication failed for {}-{}: {}",
                        request.getTopic(), request.getPartition(), e.getMessage());
            }
        });
        
        // Return immediately after leader write without waiting for replication
        return ProduceResponse.builder()
                .topic(request.getTopic())
                .partition(request.getPartition())
                .results(results)
                .success(true)
                .errorCode(ProduceResponse.ErrorCode.NONE)
                .build();
    }
    
    /**
     * Handle acks=-1: Wait for all ISR replicas to acknowledge
     */
    private ProduceResponse handleAcksAll(ProduceRequest request, WriteAheadLog wal) {
        List<ProduceResponse.ProduceResult> results = new ArrayList<>();
        long baseOffset = -1;
        
        synchronized (wal) {
            if (config.getWal().getBatchWriteEnabled()) {
                // OPTIMIZED PATH: Atomic batch write
                List<Message> messages = new ArrayList<>();
                for (ProduceRequest.ProduceMessage produceMessage : request.getMessages()) {
                    Message message = Message.builder()
                            .key(produceMessage.getKey())
                            .value(produceMessage.getValue())
                            .topic(request.getTopic())
                            .partition(request.getPartition())
                            .timestamp(produceMessage.getTimestamp() != null ? 
                                     produceMessage.getTimestamp() : System.currentTimeMillis())
                            .build();
                    messages.add(message);
                }
                
                // Write entire batch atomically
                List<Long> offsets = wal.appendBatch(messages);
                baseOffset = offsets.get(0);
                
                // Build results
                for (int i = 0; i < messages.size(); i++) {
                    results.add(ProduceResponse.ProduceResult.builder()
                            .offset(offsets.get(i))
                            .timestamp(messages.get(i).getTimestamp())
                            .errorCode(ProduceResponse.ErrorCode.NONE)
                            .build());
                }
                
                log.debug("Batch appended {} messages at offsets: {} to {} (acks=-1, optimized)", 
                         messages.size(), offsets.get(0), offsets.get(offsets.size() - 1));
            } else {
                // EXISTING PATH: Individual writes
                for (ProduceRequest.ProduceMessage produceMessage : request.getMessages()) {
                    Message message = Message.builder()
                            .key(produceMessage.getKey())
                            .value(produceMessage.getValue())
                            .topic(request.getTopic())
                            .partition(request.getPartition())
                            .timestamp(produceMessage.getTimestamp() != null ? 
                                     produceMessage.getTimestamp() : System.currentTimeMillis())
                            .build();
                    
                    long offset = wal.append(message);
                    message.setOffset(offset);
                    
                    if (baseOffset == -1) {
                        baseOffset = offset;
                    }
                    
                    results.add(ProduceResponse.ProduceResult.builder()
                            .offset(offset)
                            .timestamp(message.getTimestamp())
                            .errorCode(ProduceResponse.ErrorCode.NONE)
                            .build());
                    
                    log.debug("Message appended at offset: {} (acks=-1)", offset);
                }
            }
        }
        
        // Step 2: Replicate to ISR replicas and check min ISR requirement
        ReplicationResult replicationResult = replicateToISR(request, baseOffset);
        
        if (!replicationResult.isSuccessful()) {
            log.error("Replication failed for topic-partition: {}, cannot acknowledge producer", 
                     getTopicPartitionKey(request.getTopic(), request.getPartition()));
            return ProduceResponse.builder()
                    .topic(request.getTopic())
                    .partition(request.getPartition())
                    .success(false)
                    .errorCode(ProduceResponse.ErrorCode.INVALID_REQUEST)
                    .errorMessage("Replication to ISR replicas failed")
                    .build();
        }
        
        // Step 3: Update High Watermark only if we have enough ISR replicas
        if (replicationResult.getSuccessfulReplicas() >= config.getReplication().getMinInsyncReplicas()) {
            // HWM should be the minimum LEO across all ISR replicas
            // Since we just successfully replicated to all ISR followers, they have caught up
            // to at least baseOffset + messageCount, so HWM can be set to that value
            long newHwm = baseOffset + request.getMessages().size();
            wal.updateHighWaterMark(newHwm);
            log.debug("Updated High Watermark to {} for topic-partition: {} (ISR replicas: {}/{})",
                     newHwm, getTopicPartitionKey(request.getTopic(), request.getPartition()),
                     replicationResult.getSuccessfulReplicas(), replicationResult.getTotalReplicas());
        } else {
            log.warn("Not enough ISR replicas for HWM advancement: {}/{} < minISR={}",
                    replicationResult.getSuccessfulReplicas(), replicationResult.getTotalReplicas(),
                    config.getReplication().getMinInsyncReplicas());
        }
        
        return ProduceResponse.builder()
                .topic(request.getTopic())
                .partition(request.getPartition())
                .results(results)
                .success(true)
                .errorCode(ProduceResponse.ErrorCode.NONE)
                .build();
    }

    /**
     * Collect partition status for all partitions this node manages
     * Used for status reporting and monitoring
     */
    public List<PartitionStatus> collectPartitionStatus() {
        List<PartitionStatus> partitionStatuses = new ArrayList<>();
        long currentTime = System.currentTimeMillis();

        for (Map.Entry<String, WriteAheadLog> entry : partitionLogs.entrySet()) {
            String topicPartitionKey = entry.getKey();
            WriteAheadLog wal = entry.getValue();

            // Parse topic and partition from key (format: "topic-partition")
            String[] parts = topicPartitionKey.split("-", 2);
            if (parts.length != 2) {
                log.warn("Invalid topic-partition key format: {}", topicPartitionKey);
                continue;
            }

            String topic = parts[0];
            Integer partition = Integer.parseInt(parts[1]);

            // Determine role
            PartitionStatus.Role role = metadataStore.isLeaderForPartition(topic, partition) ?
                    PartitionStatus.Role.LEADER : PartitionStatus.Role.FOLLOWER;

            // Get LEO and HWM
            Long leo = wal.getLogEndOffset();
            Long hwm = wal.getHighWaterMark();

            // Calculate lag (only for followers)
            Long lag = null;
            if (role == PartitionStatus.Role.FOLLOWER) {
                lag = calculateLag(topic, partition, leo);
            }

            PartitionStatus status = PartitionStatus.builder()
                    .topic(topic)
                    .partition(partition)
                    .role(role)
                    .leo(leo)
                    .lag(lag)
                    .hwm(role == PartitionStatus.Role.LEADER ? hwm : null)
                    .metadataVersion(metadataStore.getCurrentMetadataVersion())
                    .timestamp(currentTime)
                    .build();

            partitionStatuses.add(status);

            log.debug("Collected status for {}-{}: role={}, LEO={}, lag={}, HWM={}",
                    topic, partition, role, leo, lag, hwm);
        }

        return partitionStatuses;
    }

    /**
     * Calculate lag for a follower partition
     * Lag = leader's HWM - follower's LEO
     * Leader HWM is received from replication requests
     */
    private Long calculateLag(String topic, Integer partition, Long followerLeo) {
        try {
            String topicPartitionKey = getTopicPartitionKey(topic, partition);
            Long leaderHwm = leaderHighWaterMarks.get(topicPartitionKey);
            
            if (leaderHwm == null) {
                log.debug("No leader HWM available for {}-{}, cannot calculate lag", topic, partition);
                return null; // Unknown lag
            }
            
            if (followerLeo == null) {
                log.warn("Follower LEO is null for {}-{}", topic, partition);
                return null;
            }
            
            long lag = leaderHwm - followerLeo;
            
            // Lag should not be negative (follower should not be ahead of leader)
            if (lag < 0) {
                log.warn("Negative lag detected for {}-{}: leaderHWM={}, followerLEO={}, lag={}",
                        topic, partition, leaderHwm, followerLeo, lag);
                lag = 0; // Clamp to 0
            }
            
            log.debug("Calculated lag for {}-{}: leaderHWM={}, followerLEO={}, lag={}",
                    topic, partition, leaderHwm, followerLeo, lag);
            
            return lag;
            
        } catch (Exception e) {
            log.warn("Error calculating lag for {}-{}: {}", topic, partition, e.getMessage());
            return null; // Unknown lag
        }
    }
}
