package com.distributedmq.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Configuration for a topic
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopicConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    @Builder.Default
    private Long retentionMs = 604800000L; // 7 days default
    
    @Builder.Default
    private Long retentionBytes = -1L; // unlimited
    
    @Builder.Default
    private Integer segmentBytes = 1073741824; // 1GB
    
    @Builder.Default
    private String compressionType = "none";
    
    @Builder.Default
    private Integer minInsyncReplicas = 1;

    // TODO: Add more configuration options
}
