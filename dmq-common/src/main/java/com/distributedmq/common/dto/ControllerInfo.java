package com.distributedmq.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Controller information shared across cluster
 * Returned by /controller endpoint and included in snapshots
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ControllerInfo {
    
    /**
     * ID of the current controller (Raft leader)
     */
    private Integer controllerId;
    
    /**
     * Full URL of the current controller
     * Example: "http://localhost:9091"
     */
    private String controllerUrl;
    
    /**
     * Raft term number for staleness detection
     */
    private Long controllerTerm;
    
    /**
     * Timestamp when this info was generated
     */
    private Long timestamp;
}
