package com.distributedmq.metadata.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;

/**
 * JPA Entity for Consumer Group Registry
 * Stores minimal routing information: (topic, app_id) -> group_leader_broker_id
 * 
 * The group leader broker (Storage Service) manages all operational state in-memory:
 * - Member list
 * - Partition assignments
 * - Committed offsets
 * 
 * This entity only tracks which broker coordinates which consumer group.
 */
@Entity
@Table(
    name = "consumer_groups",
    indexes = {
        @Index(name = "idx_consumer_groups_topic_app", columnList = "topic,app_id"),
        @Index(name = "idx_consumer_groups_leader", columnList = "group_leader_broker_id")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_topic_app_id", columnNames = {"topic", "app_id"})
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsumerGroupEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * Generated group ID in format: G_<topic>_<appId>
     * Example: G_orders_order_processor
     */
    @Column(nullable = false, unique = true, length = 512)
    private String groupId;
    
    /**
     * Topic this consumer group subscribes to
     */
    @Column(nullable = false, length = 255)
    private String topic;
    
    /**
     * Application ID - uniquely identifies the consumer application
     * Combined with topic, forms the unique group identifier
     */
    @Column(nullable = false, length = 255, name = "app_id")
    private String appId;
    
    /**
     * Broker ID of the Storage Service acting as group leader/coordinator
     * This broker manages all group state in-memory
     */
    @Column(nullable = false, name = "group_leader_broker_id")
    private Integer groupLeaderBrokerId;
    
    /**
     * Timestamp when the group was created (milliseconds since epoch)
     */
    @Column(nullable = false)
    private Long createdAt;
    
    /**
     * Timestamp when the group was last modified (milliseconds since epoch)
     * Updated when group leader is reassigned due to broker failure
     */
    @Column(nullable = false)
    private Long lastModifiedAt;
    
    @PrePersist
    protected void onCreate() {
        long now = System.currentTimeMillis();
        if (createdAt == null) {
            createdAt = now;
        }
        if (lastModifiedAt == null) {
            lastModifiedAt = now;
        }
    }
    
    @PreUpdate
    protected void onUpdate() {
        lastModifiedAt = System.currentTimeMillis();
    }
    
    /**
     * Generate group ID from topic and app ID
     */
    public static String generateGroupId(String topic, String appId) {
        return "G_" + topic + "_" + appId;
    }
}
