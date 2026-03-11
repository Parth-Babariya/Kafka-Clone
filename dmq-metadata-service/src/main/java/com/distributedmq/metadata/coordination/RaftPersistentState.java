package com.distributedmq.metadata.coordination;

import lombok.Builder;
import lombok.Data;

/**
 * Persistent Raft state that must survive restarts
 */
@Data
@Builder
public class RaftPersistentState {

    /**
     * Latest term server has seen (initialized to 0, increases monotonically)
     */
    private long currentTerm;

    /**
     * CandidateId that received vote in current term (or null if none)
     */
    private Integer votedFor;

    /**
     * Index of highest log entry known to be committed (initialized to 0, increases monotonically)
     */
    private long commitIndex;
}