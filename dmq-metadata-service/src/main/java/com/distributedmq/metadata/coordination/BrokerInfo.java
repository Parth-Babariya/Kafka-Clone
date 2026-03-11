package com.distributedmq.metadata.coordination;

import com.distributedmq.common.model.BrokerStatus;
import lombok.Builder;
import lombok.Data;

/**
 * Information about a registered broker in Raft state
 * This represents the broker's metadata stored in the Raft state machine
 */
@Data
@Builder
public class BrokerInfo {
    private final int brokerId;
    private final String host;
    private final int port;
    private final long registrationTime;
    
    // Status tracking (Phase 4)
    private BrokerStatus status;  // ONLINE or OFFLINE
    private long lastHeartbeatTime;  // Timestamp of last heartbeat received
}