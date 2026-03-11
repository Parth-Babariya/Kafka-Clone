package com.distributedmq.storage.replication;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Information about a broker in the cluster
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
//verify what arguments are really needed
public class BrokerInfo {
    private Integer id;
    private String host;
    private Integer port;
    private boolean isAlive;
}