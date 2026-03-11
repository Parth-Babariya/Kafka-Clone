package com.distributedmq.metadata.coordination;

import com.distributedmq.common.model.TopicConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Internal state representation of a topic in MetadataStateMachine
 * This is the in-memory state replicated across all metadata nodes via Raft
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopicInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private String topicName;
    private int partitionCount;
    private int replicationFactor;
    private TopicConfig config;
    private long createdAt;
}