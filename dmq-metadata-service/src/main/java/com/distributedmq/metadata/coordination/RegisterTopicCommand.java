package com.distributedmq.metadata.coordination;

import com.distributedmq.common.model.TopicConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Raft command for registering a new topic
 * Submitted to Raft log for consensus across metadata service cluster
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterTopicCommand implements Serializable {
    private static final long serialVersionUID = 1L;

    private String topicName;
    private int partitionCount;
    private int replicationFactor;
    private TopicConfig config;
    private long timestamp;
}
