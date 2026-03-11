package com.distributedmq.metadata.coordination;

/**
 * Raft node states
 */
public enum RaftState {
    /**
     * Follower state - receives entries from leader
     */
    FOLLOWER,
    
    /**
     * Candidate state - requesting votes for leadership
     */
    CANDIDATE,
    
    /**
     * Leader state - handles all client requests and replicates log
     */
    LEADER
}
