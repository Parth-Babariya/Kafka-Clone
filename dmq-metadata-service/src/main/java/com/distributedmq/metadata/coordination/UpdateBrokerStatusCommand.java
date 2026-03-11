package com.distributedmq.metadata.coordination;

import com.distributedmq.common.model.BrokerStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Raft command for updating broker status
 * Used when:
 * - Broker sends heartbeat (updates lastHeartbeatTime, status=ONLINE)
 * - Broker failure detected (status=OFFLINE)
 * - Broker gracefully shuts down (status=OFFLINE)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateBrokerStatusCommand implements Serializable {
    private static final long serialVersionUID = 1L;

    private int brokerId;
    private BrokerStatus status;
    private long lastHeartbeatTime;
    private long timestamp;
}
