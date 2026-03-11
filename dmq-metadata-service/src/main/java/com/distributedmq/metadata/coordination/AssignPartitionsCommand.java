package com.distributedmq.metadata.coordination;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * Raft command for assigning partitions to brokers
 * Contains partition-to-broker assignments with leaders and replicas
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignPartitionsCommand implements Serializable {
    private static final long serialVersionUID = 1L;

    private String topicName;
    private List<PartitionAssignment> assignments;
    private long timestamp;
}
