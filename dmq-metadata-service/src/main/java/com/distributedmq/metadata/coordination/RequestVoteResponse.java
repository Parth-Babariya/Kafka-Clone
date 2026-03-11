package com.distributedmq.metadata.coordination;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response to RequestVote RPC
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequestVoteResponse {
    private long term;           // currentTerm, for candidate to update itself
    private boolean voteGranted; // true means candidate received vote
}