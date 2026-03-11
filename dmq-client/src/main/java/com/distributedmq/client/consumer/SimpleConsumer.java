package com.distributedmq.client.consumer;

import com.distributedmq.client.cli.utils.MetadataServiceClient;
import com.distributedmq.client.cli.utils.TokenManager;
import com.distributedmq.common.config.ServiceDiscovery;
import com.distributedmq.common.dto.ConsumeRequest;
import com.distributedmq.common.dto.ConsumeResponse;
import com.distributedmq.common.model.BrokerNode;
import com.distributedmq.common.model.Message;
import com.distributedmq.common.model.TopicMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Simple consumer for fetching messages from topics
 * Supports both group-based and partition-specific consumption
 */
@Slf4j
public class SimpleConsumer {
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final MetadataServiceClient metadataClient;
    private final TokenManager tokenManager;
    private final String controllerUrl;
    private final Map<String, Long> partitionOffsets; // Track offsets per partition
    
    public SimpleConsumer(String metadataUrl) throws Exception {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
        this.metadataClient = new MetadataServiceClient(metadataUrl);
        this.tokenManager = TokenManager.getInstance();
        this.controllerUrl = metadataClient.discoverController();
        this.partitionOffsets = new HashMap<>();
        
        System.out.println("✓ Connected to controller: " + controllerUrl);
    }
    
    /**
     * Consume messages from a specific topic and partition
     */
    public List<Message> consume(String topic, int partition, long offset, int maxMessages) throws Exception {
        // Get topic metadata
        TopicMetadata metadata = metadataClient.getTopicMetadata(topic);
        
        // Find the leader for the partition
        BrokerNode leader = findPartitionLeader(metadata, partition);
        if (leader == null) {
            throw new IllegalStateException("No leader found for partition " + partition);
        }
        
        // Build consume request
        ConsumeRequest request = new ConsumeRequest();
        request.setTopic(topic);
        request.setPartition(partition);
        request.setOffset(offset);
        request.setMaxMessages(maxMessages);
        
        // Send request to storage broker
        String url = "http://" + leader.getAddress() + "/api/v1/storage/consume";
        String requestBody = objectMapper.writeValueAsString(request);
        
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30));
        
        // Add JWT token if available
        String authHeader = tokenManager.getAuthorizationHeader();
        if (authHeader != null) {
            requestBuilder.header("Authorization", authHeader);
        }
        
        HttpRequest httpRequest = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() == 401) {
            throw new RuntimeException("Authentication required. Please login first: mycli login --username <user>");
        }
        
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to consume messages: " + response.body());
        }
        
        ConsumeResponse consumeResponse = objectMapper.readValue(response.body(), ConsumeResponse.class);
        
        if (!consumeResponse.isSuccess()) {
            throw new RuntimeException("Consume failed: " + consumeResponse.getErrorMessage());
        }
        
        // Update local offset tracking
        if (consumeResponse.getMessages() != null && !consumeResponse.getMessages().isEmpty()) {
            String partitionKey = topic + "-" + partition;
            long lastOffset = consumeResponse.getMessages().get(consumeResponse.getMessages().size() - 1).getOffset();
            partitionOffsets.put(partitionKey, lastOffset + 1);
        }
        
        return consumeResponse.getMessages();
    }
    
    /**
     * Consume messages from beginning of partition
     */
    public List<Message> consumeFromBeginning(String topic, int partition, int maxMessages) throws Exception {
        return consume(topic, partition, 0, maxMessages);
    }
    
    /**
     * Consume latest messages (from current offset)
     */
    public List<Message> consumeLatest(String topic, int partition, int maxMessages) throws Exception {
        String partitionKey = topic + "-" + partition;
        long offset = partitionOffsets.getOrDefault(partitionKey, 0L);
        return consume(topic, partition, offset, maxMessages);
    }
    
    /**
     * Get current offset for a partition
     */
    public long getCurrentOffset(String topic, int partition) {
        String partitionKey = topic + "-" + partition;
        return partitionOffsets.getOrDefault(partitionKey, 0L);
    }
    
    /**
     * Reset offset for a partition
     */
    public void resetOffset(String topic, int partition, long offset) {
        String partitionKey = topic + "-" + partition;
        partitionOffsets.put(partitionKey, offset);
    }
    
    private BrokerNode findPartitionLeader(TopicMetadata metadata, int partition) {
        if (metadata.getPartitions() == null) {
            return null;
        }
        
        return metadata.getPartitions().stream()
                .filter(p -> p.getPartitionId() == partition)
                .map(p -> p.getLeader())
                .findFirst()
                .orElse(null);
    }
    
    public void close() {
        // Cleanup resources
        log.info("SimpleConsumer closed");
    }
}
