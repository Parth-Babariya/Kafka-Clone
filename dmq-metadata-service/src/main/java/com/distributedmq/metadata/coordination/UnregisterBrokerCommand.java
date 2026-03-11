package com.distributedmq.metadata.coordination;

import lombok.Builder;
import lombok.Data;

/**
 * Command to unregister a broker from the cluster
 */
@Data
@Builder
public class UnregisterBrokerCommand {
    private final int brokerId;
    private final long timestamp;
}