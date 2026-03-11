package com.distributedmq.metadata.coordination;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * AppendEntries RPC - Replicates log entries; also used as heartbeat
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppendEntriesRequest {
    private long term;              // leader's term
    private int leaderId;           // so follower can redirect clients
    private long prevLogIndex;      // index of log entry immediately preceding new ones
    private long prevLogTerm;       // term of prevLogIndex entry
    private List<RaftLogEntry> entries;  // log entries to store (empty for heartbeat)
    private long leaderCommit;      // leader's commitIndex
}