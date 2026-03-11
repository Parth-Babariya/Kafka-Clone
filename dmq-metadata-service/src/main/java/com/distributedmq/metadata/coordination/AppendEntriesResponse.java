package com.distributedmq.metadata.coordination;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response to AppendEntries RPC
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppendEntriesResponse {
    private long term;          // currentTerm, for leader to update itself
    private boolean success;    // true if follower contained entry matching prevLogIndex and prevLogTerm
}