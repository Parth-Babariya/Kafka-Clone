package com.distributedmq.metadata.controller;

import com.distributedmq.metadata.coordination.RaftState;
import lombok.Builder;
import lombok.Data;

/**
 * Raft cluster status for monitoring/debugging
 */
@Data
@Builder
public class RaftStatus {
    private final Integer nodeId;
    private final long currentTerm;
    private final RaftState state;
    private final boolean isLeader;
    private final Integer leaderId;
    private final long commitIndex;
    private final long lastApplied;
}