package com.distributedmq.client.consumer;

import lombok.Builder;
import lombok.Data;

/**
 * Configuration for Consumer
 */
@Data
@Builder
public class ConsumerConfig {
    
    // Metadata service endpoint
    private String metadataServiceUrl;
    
    // Consumer group settings
    private String groupId;
    
    @Builder.Default
    private String clientId = "dmq-consumer";
    
    @Builder.Default
    private Boolean enableAutoCommit = true;
    
    @Builder.Default
    private Long autoCommitIntervalMs = 5000L;
    
    @Builder.Default
    private String autoOffsetReset = "latest"; // earliest, latest, none
    
    @Builder.Default
    private Integer maxPollRecords = 500;
    
    @Builder.Default
    private Long maxPollIntervalMs = 300000L;
    
    @Builder.Default
    private Long sessionTimeoutMs = 10000L;
    
    @Builder.Default
    private Long heartbeatIntervalMs = 3000L;
    
    @Builder.Default
    private Integer fetchMinBytes = 1;
    
    @Builder.Default
    private Integer fetchMaxBytes = 52428800; // 50MB
    
    @Builder.Default
    private Long fetchMaxWaitMs = 500L;

    // TODO: Add more configuration options
    // TODO: Add SSL/TLS configuration
    // TODO: Add authentication configuration
}
