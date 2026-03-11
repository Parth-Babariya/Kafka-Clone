package com.distributedmq.metadata.coordination;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * Command to register a broker in the cluster
 */
@Data
@Builder
public class RegisterBrokerCommand implements Serializable {

    private static final long serialVersionUID = 1L;
    
    private final int brokerId;
    private final String host;
    private final int port;
    private final long timestamp;
}