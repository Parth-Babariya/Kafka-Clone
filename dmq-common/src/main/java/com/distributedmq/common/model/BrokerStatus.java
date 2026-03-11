package com.distributedmq.common.model;

/**
 * Status of a broker node
 */
public enum BrokerStatus {
    ONLINE,
    OFFLINE,
    STARTING,
    SHUTTING_DOWN,
    FAILED
}
