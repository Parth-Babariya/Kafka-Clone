package com.distributedmq.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for heartbeat acknowledgment
 * Includes current metadata version for staleness detection
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HeartbeatResponse {
    
    /**
     * Whether the heartbeat was processed successfully
     */
    private boolean success;
    
    /**
     * Current metadata version from the metadata service
     * Storage services use this to detect stale metadata
     */
    private Long metadataVersion;
    
    /**
     * Optional message (success or error)
     */
    private String message;
    
    /**
     * Timestamp when the heartbeat was processed
     */
    private Long timestamp;
}
