package com.distributedmq.client.consumer;

import com.distributedmq.client.cli.utils.TokenManager;
import com.distributedmq.common.config.ServiceDiscovery;
import com.distributedmq.common.dto.*;
import com.distributedmq.common.model.BrokerNode;
import com.distributedmq.common.model.Message;
import com.distributedmq.common.model.TopicMetadata;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Default implementation of Consumer with consumer group support
 */
@Slf4j
public class DMQConsumer implements Consumer {

    private final ConsumerConfig config;
    private volatile boolean closed = false;
    
    // HTTP communication
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final TokenManager tokenManager;
    
    // Consumer group state
    private String groupId;
    private String groupLeaderUrl;
    private Integer groupLeaderBrokerId;
    private String consumerId;
    private String subscribedTopic;
    private volatile ConsumerGroupState currentState = ConsumerGroupState.NOT_JOINED;
    
    // Partition assignment
    private final Set<Integer> assignedPartitions = ConcurrentHashMap.newKeySet();
    private volatile int currentGeneration = 0;
    
    // Offset tracking (client-side)
    private final Map<Integer, Long> currentOffsets = new ConcurrentHashMap<>();
    
    // Background heartbeat thread
    private ScheduledExecutorService heartbeatExecutor;
    
    // Auto-commit tracking
    private long lastAutoCommitTime = 0;
    
    // Metadata cache
    private TopicMetadata topicMetadata;
    private long metadataLastFetch = 0;
    private static final long METADATA_CACHE_MS = 60000; // 1 minute

    public DMQConsumer(ConsumerConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
        this.tokenManager = TokenManager.getInstance();
        log.info("DMQConsumer initialized with config: {}", config);
    }
    
    /**
     * Consumer group state enum
     */
    private enum ConsumerGroupState {
        NOT_JOINED,
        JOINING,
        STABLE,
        REBALANCING,
        LEAVING
    }

    @Override
    public void subscribe(Collection<String> topics) {
        validateNotClosed();
        
        if (topics == null || topics.isEmpty()) {
            throw new IllegalArgumentException("Topics cannot be null or empty");
        }
        
        if (topics.size() != 1) {
            throw new IllegalArgumentException("Currently only single topic subscription supported");
        }
        
        String topic = topics.iterator().next();
        this.subscribedTopic = topic;
        
        log.info("Subscribing to topic: {}", topic);
        
        try {
            // Step 1: Discover or create consumer group via metadata service
            ConsumerGroupResponse groupInfo = discoverConsumerGroup(topic);
            
            // Step 2: Store group info
            this.groupId = groupInfo.getGroupId();
            this.groupLeaderUrl = groupInfo.getGroupLeaderUrl();
            this.groupLeaderBrokerId = groupInfo.getGroupLeaderBrokerId();
            
            log.info("Found consumer group: groupId={}, leader=broker-{}", groupId, groupLeaderBrokerId);
            
            // Step 3: Generate unique consumer ID
            this.consumerId = generateConsumerId();
            
            // Step 4: Join consumer group
            joinConsumerGroup(topic);
            
            // Step 5: Start background heartbeat thread
            startHeartbeatThread();
            
            // Step 6: Wait for partition assignment
            waitForPartitionAssignment();
            
            log.info("Successfully subscribed to topic: {}, assigned partitions: {}, generation: {}", 
                     topic, assignedPartitions, currentGeneration);
            
        } catch (Exception e) {
            log.error("Failed to subscribe to topic: {}", topic, e);
            cleanup();
            throw new RuntimeException("Subscription failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Discover or create consumer group via metadata service
     */
    private ConsumerGroupResponse discoverConsumerGroup(String topic) throws Exception {
        String metadataUrl = config.getMetadataServiceUrl();
        
        if (metadataUrl == null || metadataUrl.isEmpty()) {
            // Auto-discover from config/services.json
            ServiceDiscovery.ServiceInfo metadataService = ServiceDiscovery.getAllMetadataServices().get(0);
            metadataUrl = metadataService.getUrl();
        }
        
        FindGroupRequest request = FindGroupRequest.builder()
                .topic(topic)
                .appId(config.getGroupId())
                .build();
        
        String url = metadataUrl + "/api/v1/metadata/consumer-groups/find-or-create";
        
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .timeout(Duration.ofSeconds(10));
        
        // Add JWT token if available
        String authHeader = tokenManager.getAuthorizationHeader();
        if (authHeader != null) {
            requestBuilder.header("Authorization", authHeader);
        }
        
        HttpRequest httpRequest = requestBuilder.build();
        
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to discover consumer group: HTTP " + response.statusCode() + " - " + response.body());
        }
        
        return objectMapper.readValue(response.body(), ConsumerGroupResponse.class);
    }
    
    /**
     * Generate unique consumer ID
     */
    private String generateConsumerId() {
        return config.getClientId() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    /**
     * Join consumer group
     */
    private void joinConsumerGroup(String topic) throws Exception {
        currentState = ConsumerGroupState.JOINING;
        
        ConsumerJoinRequest request = ConsumerJoinRequest.builder()
                .consumerId(consumerId)
                .groupId(groupId)
                .topic(topic)
                .appId(config.getGroupId())
                .offsetState(new HashMap<>(currentOffsets))
                .build();
        
        String joinUrl = "http://" + groupLeaderUrl + "/api/v1/consumer-groups/join";
        
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(joinUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .timeout(Duration.ofSeconds(10));
        
        // Add JWT token if available
        String authHeader = tokenManager.getAuthorizationHeader();
        if (authHeader != null) {
            requestBuilder.header("Authorization", authHeader);
        }
        
        HttpRequest httpRequest = requestBuilder.build();
        
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to join consumer group: HTTP " + response.statusCode() + " - " + response.body());
        }
        
        ConsumerGroupOperationResponse joinResponse = objectMapper.readValue(
                response.body(), ConsumerGroupOperationResponse.class);
        
        if (!joinResponse.isSuccess()) {
            throw new RuntimeException("Join failed: " + joinResponse.getErrorMessage());
        }
        
        log.info("Joined consumer group: {}, state: {}", groupId, joinResponse.getRebalanceState());
    }
    
    /**
     * Wait for partition assignment (poll heartbeat until STABLE)
     */
    private void waitForPartitionAssignment() throws Exception {
        long timeout = System.currentTimeMillis() + config.getSessionTimeoutMs();
        
        log.info("Waiting for partition assignment...");
        
        while (System.currentTimeMillis() < timeout) {
            ConsumerGroupOperationResponse response = sendHeartbeat();
            
            if (response.getRebalanceState() == RebalanceState.STABLE && 
                response.getAssignedPartitions() != null && 
                !response.getAssignedPartitions().isEmpty()) {
                
                // Got assignment!
                assignedPartitions.clear();
                assignedPartitions.addAll(response.getAssignedPartitions());
                currentGeneration = response.getGeneration();
                currentState = ConsumerGroupState.STABLE;
                
                log.info("Partition assignment received: {}", assignedPartitions);
                return;
            }
            
            log.debug("Waiting for assignment, current state: {}", response.getRebalanceState());
            Thread.sleep(1000);
        }
        
        throw new TimeoutException("Timeout waiting for partition assignment");
    }

    @Override
    public void assign(Collection<TopicPartition> partitions) {
        validateNotClosed();
        
        // TODO: Implement manual partition assignment
        log.info("Assigning partitions: {}", partitions);
    }
    
    /**
     * Start background heartbeat thread
     */
    private void startHeartbeatThread() {
        if (heartbeatExecutor != null) {
            log.warn("Heartbeat thread already running");
            return;
        }
        
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("dmq-consumer-heartbeat-" + consumerId);
            t.setDaemon(true);
            return t;
        });
        
        long intervalMs = config.getHeartbeatIntervalMs();
        
        heartbeatExecutor.scheduleAtFixedRate(
                this::sendHeartbeatSafe,
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS
        );
        
        log.info("Started heartbeat thread with interval: {}ms", intervalMs);
    }
    
    /**
     * Send heartbeat (safe wrapper for background thread)
     */
    private void sendHeartbeatSafe() {
        try {
            ConsumerGroupOperationResponse response = sendHeartbeat();
            handleHeartbeatResponse(response);
        } catch (Exception e) {
            log.error("Heartbeat failed: {}", e.getMessage());
            // TODO: Implement retry logic or rejoin on repeated failures
        }
    }
    
    /**
     * Send heartbeat with current offsets
     */
    private ConsumerGroupOperationResponse sendHeartbeat() throws Exception {
        ConsumerHeartbeatRequest request = ConsumerHeartbeatRequest.builder()
                .consumerId(consumerId)
                .groupId(groupId)
                .offsets(new HashMap<>(currentOffsets))
                .build();
        
        String heartbeatUrl = "http://" + groupLeaderUrl + "/api/v1/consumer-groups/heartbeat";
        
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(heartbeatUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .timeout(Duration.ofSeconds(5));
        
        // Add JWT token if available
        String authHeader = tokenManager.getAuthorizationHeader();
        if (authHeader != null) {
            requestBuilder.header("Authorization", authHeader);
        }
        
        HttpRequest httpRequest = requestBuilder.build();
        
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new RuntimeException("Heartbeat failed: HTTP " + response.statusCode() + " - " + response.body());
        }
        
        return objectMapper.readValue(response.body(), ConsumerGroupOperationResponse.class);
    }
    
    /**
     * Handle heartbeat response (detect rebalancing)
     */
    private void handleHeartbeatResponse(ConsumerGroupOperationResponse response) {
        if (!response.isSuccess()) {
            log.warn("Heartbeat error: {} - {}", response.getErrorCode(), response.getErrorMessage());
            
            if ("UNKNOWN_MEMBER_ID".equals(response.getErrorCode())) {
                log.warn("Consumer kicked out of group, need to rejoin");
                currentState = ConsumerGroupState.NOT_JOINED;
                // TODO: Trigger rejoin
            }
            return;
        }
        
        RebalanceState newState = response.getRebalanceState();
        
        // Detect rebalance start
        if ((newState == RebalanceState.PREPARING_REBALANCE || newState == RebalanceState.IN_REBALANCE) 
                && currentState == ConsumerGroupState.STABLE) {
            log.info("Rebalance triggered, state: {}", newState);
            currentState = ConsumerGroupState.REBALANCING;
            // TODO: Call rebalance listener onPartitionsRevoked()
        }
        
        // Detect rebalance completion
        if (newState == RebalanceState.STABLE && currentState == ConsumerGroupState.REBALANCING) {
            Set<Integer> newPartitions = response.getAssignedPartitions();
            if (newPartitions != null && !newPartitions.isEmpty()) {
                log.info("Rebalance completed. Old partitions: {}, New partitions: {}", 
                         assignedPartitions, newPartitions);
                
                assignedPartitions.clear();
                assignedPartitions.addAll(newPartitions);
                currentGeneration = response.getGeneration();
                currentState = ConsumerGroupState.STABLE;
                
                // TODO: Call rebalance listener onPartitionsAssigned()
            }
        }
    }

    @Override
    public List<Message> poll(long timeoutMs) {
        validateNotClosed();
        
        if (currentState != ConsumerGroupState.STABLE) {
            log.debug("Not in STABLE state (current: {}), returning empty result", currentState);
            return Collections.emptyList();
        }
        
        if (assignedPartitions.isEmpty()) {
            log.debug("No partitions assigned, returning empty result");
            return Collections.emptyList();
        }
        
        List<Message> allMessages = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutMs;
        
        try {
            // Refresh topic metadata if needed
            refreshTopicMetadataIfNeeded();
            
            // Fetch from each assigned partition
            for (Integer partition : assignedPartitions) {
                if (currentState != ConsumerGroupState.STABLE) {
                    // Rebalance started during poll
                    log.info("Rebalancing during poll, stopping fetch");
                    break;
                }
                
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                
                try {
                    List<Message> messages = fetchFromPartition(partition, remaining);
                    allMessages.addAll(messages);
                    
                    // Update offsets
                    if (!messages.isEmpty()) {
                        long lastOffset = messages.get(messages.size() - 1).getOffset();
                        currentOffsets.put(partition, lastOffset + 1);
                        log.debug("Updated offset for partition {}: {}", partition, lastOffset + 1);
                    }
                    
                    if (allMessages.size() >= config.getMaxPollRecords()) {
                        break;
                    }
                } catch (Exception e) {
                    log.error("Error fetching from partition {}: {}", partition, e.getMessage());
                }
            }
            
            // Auto-commit if enabled
            if (config.getEnableAutoCommit() && !allMessages.isEmpty()) {
                autoCommitIfNeeded();
            }
            
            log.debug("Polled {} messages from {} partitions", allMessages.size(), assignedPartitions.size());
            
        } catch (Exception e) {
            log.error("Error during poll", e);
        }
        
        return allMessages;
    }
    
    /**
     * Fetch messages from a specific partition
     */
    private List<Message> fetchFromPartition(int partition, long timeoutMs) throws Exception {
        // Get current offset for partition (default to 0 if not set)
        long offset = currentOffsets.getOrDefault(partition, 0L);
        
        // Find partition leader from metadata
        BrokerNode leader = findPartitionLeader(partition);
        if (leader == null) {
            log.warn("No leader found for partition {}", partition);
            return Collections.emptyList();
        }
        
        // Build consume request
        ConsumeRequest request = new ConsumeRequest();
        request.setTopic(subscribedTopic);
        request.setPartition(partition);
        request.setOffset(offset);
        request.setMaxMessages(config.getMaxPollRecords());
        
        String consumeUrl = "http://" + leader.getAddress() + "/api/v1/storage/consume";
        
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(consumeUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .timeout(Duration.ofMillis(Math.max(timeoutMs, 1000)));
        
        // Add JWT token if available
        String authHeader = tokenManager.getAuthorizationHeader();
        if (authHeader != null) {
            requestBuilder.header("Authorization", authHeader);
        }
        
        HttpRequest httpRequest = requestBuilder.build();
        
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            log.error("Failed to consume from partition {}: HTTP {} - {}", 
                     partition, response.statusCode(), response.body());
            return Collections.emptyList();
        }
        
        ConsumeResponse consumeResponse = objectMapper.readValue(response.body(), ConsumeResponse.class);
        
        if (!consumeResponse.isSuccess()) {
            log.error("Consume failed for partition {}: {}", partition, consumeResponse.getErrorMessage());
            return Collections.emptyList();
        }
        
        return consumeResponse.getMessages() != null ? consumeResponse.getMessages() : Collections.emptyList();
    }
    
    /**
     * Find partition leader from cached metadata
     */
    private BrokerNode findPartitionLeader(int partition) {
        if (topicMetadata == null || topicMetadata.getPartitions() == null) {
            return null;
        }
        
        return topicMetadata.getPartitions().stream()
                .filter(p -> p.getPartitionId() == partition)
                .map(p -> p.getLeader())
                .findFirst()
                .orElse(null);
    }
    
    /**
     * Refresh topic metadata if cache expired
     */
    private void refreshTopicMetadataIfNeeded() throws Exception {
        long now = System.currentTimeMillis();
        if (topicMetadata == null || (now - metadataLastFetch) > METADATA_CACHE_MS) {
            topicMetadata = fetchTopicMetadata();
            metadataLastFetch = now;
            log.debug("Refreshed topic metadata for: {}", subscribedTopic);
        }
    }
    
    /**
     * Fetch topic metadata from metadata service
     */
    private TopicMetadata fetchTopicMetadata() throws Exception {
        String metadataUrl = config.getMetadataServiceUrl();
        
        if (metadataUrl == null || metadataUrl.isEmpty()) {
            ServiceDiscovery.ServiceInfo metadataService = ServiceDiscovery.getAllMetadataServices().get(0);
            metadataUrl = metadataService.getUrl();
        }
        
        String url = metadataUrl + "/api/v1/metadata/topics/" + subscribedTopic;
        
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(5));
        
        // Add JWT token if available
        String authHeader = tokenManager.getAuthorizationHeader();
        if (authHeader != null) {
            requestBuilder.header("Authorization", authHeader);
        }
        
        HttpRequest httpRequest = requestBuilder.build();
        
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to fetch topic metadata: HTTP " + response.statusCode());
        }
        
        return objectMapper.readValue(response.body(), TopicMetadata.class);
    }
    
    /**
     * Auto-commit if interval elapsed
     */
    private void autoCommitIfNeeded() {
        long now = System.currentTimeMillis();
        
        if (now - lastAutoCommitTime >= config.getAutoCommitIntervalMs()) {
            try {
                commitAsync();
                lastAutoCommitTime = now;
            } catch (Exception e) {
                log.error("Auto-commit failed", e);
            }
        }
    }

    @Override
    public void commitSync() {
        validateNotClosed();
        
        if (currentOffsets.isEmpty()) {
            log.debug("No offsets to commit");
            return;
        }
        
        try {
            // Send heartbeat with current offsets (synchronous commit)
            ConsumerGroupOperationResponse response = sendHeartbeat();
            
            if (!response.isSuccess()) {
                throw new RuntimeException("Commit failed: " + response.getErrorMessage());
            }
            
            log.debug("Committed offsets synchronously: {}", currentOffsets);
            lastAutoCommitTime = System.currentTimeMillis();
            
        } catch (Exception e) {
            log.error("Failed to commit offsets synchronously", e);
            throw new RuntimeException("Commit failed", e);
        }
    }

    @Override
    public void commitAsync() {
        validateNotClosed();
        
        if (currentOffsets.isEmpty()) {
            log.debug("No offsets to commit");
            return;
        }
        
        // Async commit - offsets will be sent in next heartbeat
        log.debug("Async commit scheduled, offsets will be sent in next heartbeat: {}", currentOffsets);
        lastAutoCommitTime = System.currentTimeMillis();
        
        // Background heartbeat thread automatically sends currentOffsets every 3 seconds
        // No explicit action needed
    }

    @Override
    public void seek(TopicPartition partition, long offset) {
        validateNotClosed();
        
        // TODO: Seek to specific offset
        log.debug("Seeking to offset {} for partition {}", offset, partition);
    }

    @Override
    public void seekToBeginning(Collection<TopicPartition> partitions) {
        validateNotClosed();
        
        // TODO: Seek to beginning
        log.debug("Seeking to beginning for partitions: {}", partitions);
    }

    @Override
    public void seekToEnd(Collection<TopicPartition> partitions) {
        validateNotClosed();
        
        // TODO: Seek to end
        log.debug("Seeking to end for partitions: {}", partitions);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        
        log.info("Closing DMQConsumer (consumerId: {}, groupId: {})...", consumerId, groupId);
        
        closed = true;
        currentState = ConsumerGroupState.LEAVING;
        
        try {
            // 1. Stop heartbeat thread
            if (heartbeatExecutor != null) {
                heartbeatExecutor.shutdown();
                try {
                    if (!heartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        heartbeatExecutor.shutdownNow();
                        log.warn("Heartbeat thread did not terminate gracefully");
                    } else {
                        log.info("Heartbeat thread stopped");
                    }
                } catch (InterruptedException e) {
                    heartbeatExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            
            // 2. Commit final offsets
            if (!currentOffsets.isEmpty()) {
                try {
                    commitSync();
                    log.info("Final offsets committed");
                } catch (Exception e) {
                    log.error("Failed to commit final offsets", e);
                }
            }
            
            // 3. Cleanup (broker will detect timeout and remove consumer)
            // No explicit leave API - broker uses heartbeat timeout
            assignedPartitions.clear();
            currentOffsets.clear();
            
            log.info("DMQConsumer closed successfully");
            
        } catch (Exception e) {
            log.error("Error during close", e);
        }
    }
    
    /**
     * Cleanup resources (called on subscription failure)
     */
    private void cleanup() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }
        assignedPartitions.clear();
        currentOffsets.clear();
        currentState = ConsumerGroupState.NOT_JOINED;
    }

    private void validateNotClosed() {
        if (closed) {
            throw new IllegalStateException("Consumer is closed");
        }
    }
}
