package com.distributedmq.client.producer;

import lombok.Builder;
import lombok.Data;

/**
 * Configuration for Producer
 */
@Data
@Builder
public class ProducerConfig {
    
    // Metadata service endpoints
    private String metadataServiceUrl;
    
    // Storage service endpoints (optional, can be discovered)
    private String storageServiceUrl;
    
    // Producer settings
    @Builder.Default
    private Integer batchSize = 16384; // 16KB
    
    @Builder.Default
    private Long lingerMs = 0L;
    
    @Builder.Default
    private Integer maxInFlightRequests = 5;
    
    @Builder.Default
    private Integer requiredAcks = 1; // 0, 1, or -1
    
    @Builder.Default
    private Long requestTimeoutMs = 30000L;
    
    @Builder.Default
    private Integer retries = 3;
    
    @Builder.Default
    private String compressionType = "none";
    
    @Builder.Default
    private Integer maxBlockMs = 60000;

    // TODO: Add more configuration options
    // TODO: Add SSL/TLS configuration
    // TODO: Add authentication configuration
}
