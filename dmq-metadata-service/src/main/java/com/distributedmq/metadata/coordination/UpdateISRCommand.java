package com.distributedmq.metadata.coordination;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * Raft command for updating In-Sync Replicas (ISR)
 * Used when replicas fall behind or catch up
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateISRCommand implements Serializable {
    private static final long serialVersionUID = 1L;

    private String topicName;
    private int partitionId;
    private List<Integer> newISR;
    private long timestamp;
}
