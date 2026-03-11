package com.distributedmq.storage.consumergroup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background job to execute pending rebalances
 * Checks every 1 second if stabilization period is over
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RebalanceScheduler {
    
    private final ConsumerGroupManager groupManager;
    
    /**
     * Check for pending rebalances every 1 second
     */
    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void checkRebalances() {
        try {
            groupManager.checkPendingRebalances();
        } catch (Exception e) {
            log.error("Error checking pending rebalances", e);
        }
    }
}
