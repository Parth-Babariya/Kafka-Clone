package com.distributedmq.metadata.coordination;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Raft command for deleting a topic
 * Removes topic and all its partitions from cluster metadata
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeleteTopicCommand implements Serializable {
    private static final long serialVersionUID = 1L;

    private String topicName;
    private long timestamp;
}
