package com.distributedmq.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating a topic
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateTopicRequest {
    
    private String topicName;
    private Integer partitionCount;
    private Integer replicationFactor;
    
    private Long retentionMs;
    private Long retentionBytes;
    private Integer segmentBytes;
    private String compressionType;
    private Integer minInsyncReplicas;
}
