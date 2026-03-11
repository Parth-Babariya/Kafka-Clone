package com.distributedmq.metadata.coordination;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Raft log entry
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RaftLogEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Term when entry was received by leader
     */
    private long term;

    /**
     * Index in the log
     */
    private long index;

    /**
     * Command/data to apply to state machine
     * Could be topic creation, partition assignment, etc.
     */
    private Object command;

    /**
     * Type of command for processing
     */
    private RaftCommandType commandType;
}
