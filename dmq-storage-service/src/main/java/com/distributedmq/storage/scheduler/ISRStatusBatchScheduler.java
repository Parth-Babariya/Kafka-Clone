package com.distributedmq.storage.scheduler;

import com.distributedmq.common.dto.ISRStatusBatchRequest;
import com.distributedmq.common.dto.ISRStatusBatchResponse;
import com.distributedmq.common.dto.ISRStatusUpdate;
import com.distributedmq.storage.service.StorageService;
import com.distributedmq.storage.replication.MetadataStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Scheduler for sending ISR status batch updates to controller
 * Reports ISR membership changes, lag thresholds, and partition health
 * Runs every 30 seconds to provide batch ISR status updates
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ISRStatusBatchScheduler {

    private final StorageService storageService;
    private final MetadataStore metadataStore;
    private final RestTemplate restTemplate;

    @Value("${dmq.controller.url:http://localhost:8080}")
    private String controllerUrl;

    @Value("${dmq.storage.node.id:1}")
    private Integer nodeId;

    /**
     * Send ISR status batch update to controller every 30 seconds
     * Includes ISR membership changes and lag threshold breaches
     */
    @Scheduled(fixedRate = 30000) // 30 seconds
    public void sendISRStatusBatch() {
        try {
            // Collect ISR status updates
            List<ISRStatusUpdate> isrUpdates = collectISRStatusUpdates();

            if (isrUpdates.isEmpty()) {
                log.debug("No ISR status updates to send for node {}", nodeId);
                return;
            }

            // Build batch request
            ISRStatusBatchRequest request = ISRStatusBatchRequest.builder()
                    .nodeId(nodeId)
                    .isrUpdates(isrUpdates)
                    .metadataVersion(metadataStore.getCurrentMetadataVersion())
                    .timestamp(System.currentTimeMillis())
                    .build();

            // Send to controller
            String endpoint = controllerUrl + "/api/v1/metadata/isr-status-batch";
            ResponseEntity<ISRStatusBatchResponse> response =
                    restTemplate.postForEntity(endpoint, request, ISRStatusBatchResponse.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                ISRStatusBatchResponse batchResponse = response.getBody();

                log.debug("ISR status batch sent successfully. Updates: {}, Controller version: {}",
                        isrUpdates.size(), batchResponse.getControllerMetadataVersion());

                // Process any controller instructions
                if (batchResponse.getInstructions() != null && !batchResponse.getInstructions().isEmpty()) {
                    log.info("Received ISR controller instructions: {}", batchResponse.getInstructions());
                    // TODO: Process ISR-related controller instructions
                }
            } else {
                log.warn("ISR status batch failed with status: {}", response.getStatusCode());
            }

        } catch (Exception e) {
            log.error("Error sending ISR status batch: {}", e.getMessage(), e);
        }
    }

    /**
     * Collect ISR status updates that need to be reported to controller
     * This includes lag threshold breaches and ISR membership changes
     */
    private List<ISRStatusUpdate> collectISRStatusUpdates() {
        List<ISRStatusUpdate> updates = new ArrayList<>();
        long currentTime = System.currentTimeMillis();

        // Get all partitions this node manages
        // TODO: Implement method to get all managed partitions from MetadataStore
        // For now, we'll use a placeholder approach

        // Check for lag threshold breaches (simplified implementation)
        // In a real implementation, this would track historical lag and detect breaches
        List<String> partitionKeys = getManagedPartitionKeys();

        for (String partitionKey : partitionKeys) {
            String[] parts = partitionKey.split("-", 2);
            if (parts.length != 2) continue;

            String topic = parts[0];
            Integer partition = Integer.parseInt(parts[1]);

            // Check if this node is a follower for lag monitoring
            if (metadataStore.isFollowerForPartition(topic, partition)) {
                Long lag = calculateCurrentLag(topic, partition);

                if (lag != null && lag > getLagThreshold()) {
                    // Lag threshold breached - report to controller
                    ISRStatusUpdate update = ISRStatusUpdate.builder()
                            .topic(topic)
                            .partition(partition)
                            .updateType(ISRStatusUpdate.UpdateType.LAG_THRESHOLD_BREACHED)
                            .currentLag(lag)
                            .lagThreshold(getLagThreshold())
                            .isInISR(true) // Assume in ISR unless we detect otherwise
                            .timestamp(currentTime)
                            .build();

                    updates.add(update);
                    log.info("Lag threshold breached for {}-{}: lag={}, threshold={}",
                            topic, partition, lag, getLagThreshold());
                }
            }
        }

        // TODO: Add ISR membership change detection
        // This would compare current ISR membership with previously reported membership

        return updates;
    }

    /**
     * Get partition keys for partitions managed by this node
     * TODO: Implement proper method in MetadataStore or StorageService
     */
    private List<String> getManagedPartitionKeys() {
        // Placeholder implementation - in real system, this would come from MetadataStore
        // For now, return empty list to avoid errors
        return new ArrayList<>();
    }

    /**
     * Calculate current lag for a partition (simplified)
     * TODO: Use the same lag calculation as in StorageServiceImpl
     */
    private Long calculateCurrentLag(String topic, Integer partition) {
        try {
            // This is a simplified calculation - in real implementation,
            // we'd use the same logic as StorageServiceImpl.calculateLag()
            Long followerLeo = storageService.getLogEndOffset(topic, partition);
            // TODO: Get leader HWM from stored values
            return 0L; // Placeholder
        } catch (Exception e) {
            log.warn("Error calculating current lag for {}-{}: {}", topic, partition, e.getMessage());
            return null;
        }
    }

    /**
     * Get lag threshold for ISR management
     * TODO: Make this configurable
     */
    private Long getLagThreshold() {
        return 10000L; // 10 seconds worth of lag (placeholder)
    }
}