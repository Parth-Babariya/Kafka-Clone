package com.distributedmq.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Represents a message in the distributed messaging system
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message implements Serializable {
    private static final long serialVersionUID = 1L;

    private String key;
    private byte[] value;
    private String topic;
    private Integer partition;
    private Long offset;
    private Long timestamp;
    private MessageHeaders headers;

    // TODO: Implement message serialization/deserialization
    // TODO: Add compression support
    // TODO: Add checksum validation
}
