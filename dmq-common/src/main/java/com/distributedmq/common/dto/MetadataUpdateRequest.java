package com.distributedmq.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Request to update metadata in storage nodes
 * Sent by metadata service to storage nodes
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetadataUpdateRequest {

    /**
     * Type of metadata update
     * Determines how storage nodes should apply the update
     */
    public enum UpdateType {
        FULL_SNAPSHOT,        // Complete cluster state (clear all + rebuild)
        ISR_UPDATE,           // Only ISR changed for specific partitions
        LEADER_UPDATE,        // Only leader changed for specific partitions
        TOPIC_CREATED,        // New topic added (add partitions)
        TOPIC_DELETED,        // Topic removed (remove partitions)
        BROKER_UPDATE,        // Broker status changed
        CONTROLLER_CHANGED    // Controller (Raft leader) changed
    }

    // Type of update (defaults to FULL_SNAPSHOT for backward compatibility)
    @Builder.Default
    private UpdateType updateType = UpdateType.FULL_SNAPSHOT;

    // Metadata version for ordering and conflict resolution
    private Long version;

    // Broker information updates
    private List<BrokerInfo> brokers;

    // Partition leadership updates
    private List<PartitionMetadata> partitions;

    // Topics deleted (for TOPIC_DELETED type)
    private List<String> deletedTopics;

    // Controller information (for CONTROLLER_CHANGED type)
    private Integer controllerId;
    private String controllerUrl;
    private Long controllerTerm;

    // Timestamp of this metadata update
    private Long timestamp;

    /**
     * Broker information
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BrokerInfo {
        private Integer id;
        private String host;
        private Integer port;
        private boolean isAlive;
    }

    /**
     * Partition metadata
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartitionMetadata {
        private String topic;
        private Integer partition;
        private Integer leaderId;
        private List<Integer> followerIds;
        private List<Integer> isrIds;
        private Long leaderEpoch;
    }
}