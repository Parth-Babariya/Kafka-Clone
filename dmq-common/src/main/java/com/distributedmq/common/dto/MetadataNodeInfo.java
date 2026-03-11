package com.distributedmq.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Metadata node information for load balancing and failover
 * Included in snapshot responses
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetadataNodeInfo {
    
    /**
     * Metadata service node ID
     */
    private Integer id;
    
    /**
     * Full URL of the metadata service
     * Example: "http://localhost:9091"
     */
    private String url;
    
    /**
     * Whether this node is the current Raft leader (controller)
     */
    private Boolean isLeader;
    
    /**
     * Health status of this node
     */
    private Boolean healthy;
    
    /**
     * Last heartbeat timestamp from this node
     */
    private Long lastSeen;
}
