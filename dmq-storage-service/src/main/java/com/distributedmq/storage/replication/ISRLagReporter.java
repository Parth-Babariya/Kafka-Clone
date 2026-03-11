package com.distributedmq.storage.replication;

import com.distributedmq.common.dto.ISRLagReport;
import com.distributedmq.storage.config.StorageConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 2: ISR Lag Reporting
 * 
 * Periodically reports lag information to the metadata service (controller).
 * Only sends reports when there's something meaningful to report:
 * - Replica is out of sync (needs ISR removal)
 * - Replica just caught up (needs ISR addition)
 * - Replica is not in ISR but is caught up (ready to join ISR)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ISRLagReporter {
    
    private final ReplicaLagTracker lagTracker;
    private final MetadataStore metadataStore;
    private final StorageConfig config;
    private final RestTemplate restTemplate;
    
    /**
     * Report lag to metadata service every 15 seconds (configurable)
     * Only reports when there's something to report
     */
    @Scheduled(fixedDelayString = "${storage.replication.lag-report-interval-ms:15000}")
    public void reportLagToMetadata() {
        // Skip if metadata service URL is not configured
        String metadataServiceUrl = metadataStore.getMetadataServiceUrl();
        if (metadataServiceUrl == null || metadataServiceUrl.isEmpty()) {
            log.debug("Metadata service URL not configured, skipping lag report");
            return;
        }
        
        try {
            // Get all partitions where this broker is a follower
            List<MetadataStore.PartitionInfo> followerPartitions = 
                metadataStore.getPartitionsWhereFollower();
            
            if (followerPartitions.isEmpty()) {
                log.debug("No follower partitions, skipping lag report");
                return;
            }
            
            // Build lag report
            List<ISRLagReport.PartitionLag> partitionLags = new ArrayList<>();
            
            for (MetadataStore.PartitionInfo partition : followerPartitions) {
                // Get lag info from tracker
                ReplicaLagTracker.LagInfo lagInfo = lagTracker.getLagInfo(
                    partition.getTopic(), partition.getPartition());
                
                if (lagInfo == null) {
                    // No lag info yet (no replication received)
                    continue;
                }
                
                // Determine if we should report this partition
                if (shouldReportPartition(lagInfo, partition)) {
                    ISRLagReport.PartitionLag partitionLag = ISRLagReport.PartitionLag.builder()
                        .topic(partition.getTopic())
                        .partition(partition.getPartition())
                        .offsetLag(lagInfo.getOffsetLag())
                        .timeSinceCaughtUp(lagInfo.getTimeSinceCaughtUp())
                        .followerLEO(lagInfo.getFollowerLEO())
                        .leaderLEO(lagInfo.getLeaderLEO())
                        .isOutOfSync(lagTracker.isReplicaOutOfSync(
                            partition.getTopic(), partition.getPartition()))
                        .timeSinceLastFetch(lagInfo.getTimeSinceLastFetch())
                        .build();
                    
                    partitionLags.add(partitionLag);
                }
            }
            
            // Only send report if there's something to report
            if (!partitionLags.isEmpty()) {
                ISRLagReport report = ISRLagReport.builder()
                    .brokerId(config.getBroker().getId())
                    .partitions(partitionLags)
                    .timestamp(System.currentTimeMillis())
                    .build();
                
                sendLagReport(report);
                
                log.info("Sent ISR lag report to metadata service: {} partitions reported", 
                    partitionLags.size());
            } else {
                log.debug("No partitions to report (all in-sync or no state changes)");
            }
            
        } catch (Exception e) {
            log.error("Error generating or sending lag report: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Determine if a partition should be included in the lag report
     * Only report if:
     * 1. Replica is out of sync (needs ISR removal)
     * 2. Replica is caught up but not in ISR (needs ISR addition)
     * 3. Always report if offset lag > 0 (for monitoring/visibility)
     */
    private boolean shouldReportPartition(ReplicaLagTracker.LagInfo lagInfo, 
                                         MetadataStore.PartitionInfo partition) {
        // Check if out of sync (should be removed from ISR)
        boolean isOutOfSync = lagInfo.getOffsetLag() > config.getReplication().getReplicaLagMaxMessages() ||
                              lagInfo.getTimeSinceLastFetch() > config.getReplication().getReplicaLagTimeMaxMs();
        
        if (isOutOfSync) {
            log.debug("Reporting {}-{}: out of sync (offsetLag={}, timeSinceLastFetch={})",
                partition.getTopic(), partition.getPartition(),
                lagInfo.getOffsetLag(), lagInfo.getTimeSinceLastFetch());
            return true;
        }
        
        // Check if caught up but not in ISR (should be added to ISR)
        boolean isCaughtUp = lagInfo.getOffsetLag() < 100 && 
                            lagInfo.getTimeSinceCaughtUp() < 1000;
        boolean isInISR = partition.getIsrIds().contains(config.getBroker().getId());
        
        if (isCaughtUp && !isInISR) {
            log.debug("Reporting {}-{}: caught up but not in ISR (offsetLag={})",
                partition.getTopic(), partition.getPartition(), lagInfo.getOffsetLag());
            return true;
        }
        
        // Report if there's any lag (for visibility)
        if (lagInfo.getOffsetLag() > 0) {
            log.debug("Reporting {}-{}: has lag (offsetLag={})",
                partition.getTopic(), partition.getPartition(), lagInfo.getOffsetLag());
            return true;
        }
        
        // Don't report if fully in-sync with no lag
        return false;
    }
    
    /**
     * Send lag report to metadata service via REST API
     */
    private void sendLagReport(ISRLagReport report) {
        try {
            String metadataServiceUrl = metadataStore.getMetadataServiceUrl();
            String url = metadataServiceUrl + "/api/v1/metadata/isr/lag";
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<ISRLagReport> requestEntity = new HttpEntity<>(report, headers);
            
            ResponseEntity<Void> response = restTemplate.postForEntity(url, requestEntity, Void.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                log.debug("Successfully sent lag report to metadata service");
            } else {
                log.warn("Metadata service returned non-success status: {}", response.getStatusCode());
            }
            
        } catch (Exception e) {
            log.error("Failed to send lag report to metadata service: {}", e.getMessage());
        }
    }
    
    /**
     * Get metadata service URL (for external configuration)
     */
    public String getMetadataServiceUrl() {
        return metadataStore.getMetadataServiceUrl();
    }
}
