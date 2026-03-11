package com.distributedmq.storage.consumergroup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background job to monitor consumer heartbeats
 * Checks every 5 seconds for dead consumers (no heartbeat for 15 seconds)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsumerHeartbeatMonitor {
    
    private final ConsumerGroupManager groupManager;
    
    /**
     * Check for dead consumers every 5 seconds
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void monitorHeartbeats() {
        try {
            groupManager.checkDeadConsumers();
        } catch (Exception e) {
            log.error("Error monitoring consumer heartbeats", e);
        }
    }
}
