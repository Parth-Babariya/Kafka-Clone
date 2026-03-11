package com.distributedmq.metadata.coordination;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RequestVote RPC - Sent by candidates to request votes
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequestVoteRequest {
    private long term;           // candidate's term
    private int candidateId;     // candidate requesting vote
    private long lastLogIndex;   // index of candidate's last log entry
    private long lastLogTerm;    // term of candidate's last log entry
}