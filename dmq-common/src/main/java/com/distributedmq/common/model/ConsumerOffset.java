package com.distributedmq.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Represents consumer offset information
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsumerOffset implements Serializable {
    private static final long serialVersionUID = 1L;

    private String consumerGroup;
    private String topic;
    private Integer partition;
    private Long offset;
    private Long timestamp;
    private String metadata;

    // TODO: Add commit timestamp tracking
    // TODO: Add consumer instance information
}
