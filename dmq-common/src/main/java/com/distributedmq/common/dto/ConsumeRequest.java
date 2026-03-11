package com.distributedmq.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request to consume messages
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsumeRequest {
    private String consumerGroup;
    private String topic;
    private Integer partition;
    private Long offset;
    private Integer maxMessages;
    private Long maxWaitMs;
    private Integer minBytes;
    private Integer maxBytes;

    // TODO: Add consumer session management
    // TODO: Add isolation level support
}
