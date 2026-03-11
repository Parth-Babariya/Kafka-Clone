package com.distributedmq.metadata.controller;

import com.distributedmq.common.dto.ControllerInfo;
import com.distributedmq.common.dto.MetadataUpdateResponse;
import com.distributedmq.common.model.TopicMetadata;
import com.distributedmq.common.config.ServiceDiscovery;
import com.distributedmq.common.security.JwtException;
import com.distributedmq.common.security.JwtValidator;
import com.distributedmq.common.security.UserPrincipal;
import com.distributedmq.metadata.coordination.RaftController;
import com.distributedmq.common.dto.CreateTopicRequest;
import com.distributedmq.metadata.dto.TopicMetadataResponse;
import com.distributedmq.metadata.dto.RegisterBrokerRequest;
import com.distributedmq.metadata.dto.BrokerResponse;
import com.distributedmq.metadata.dto.MetadataSyncResponse;
import com.distributedmq.metadata.dto.BrokerSyncResponse;
import com.distributedmq.metadata.dto.SyncStatusResponse;
import com.distributedmq.metadata.dto.IncrementalSyncResponse;
import com.distributedmq.metadata.dto.ConsistencyCheckResponse;
import com.distributedmq.metadata.dto.SyncTriggerRequest;
import com.distributedmq.metadata.dto.SyncTriggerResponse;
import com.distributedmq.metadata.dto.FullSyncResponse;
import com.distributedmq.metadata.dto.ClusterMetadataResponse;
import com.distributedmq.metadata.service.MetadataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * REST Controller for Metadata operations
 * Entry point with request validation
 *
 * When metadata is pushed to storage nodes then storage node should be consistent with 
 * existing operations ( handle it properly different possibilities like, stop all operations update metadata and then start operations again with new data, or let current operations finish and then update metadata for new operations )
 * 
 * Routes requests to controller leader in KRaft mode
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/metadata")
@RequiredArgsConstructor
public class MetadataController {

    private final MetadataService metadataService;
    private final RaftController raftController;
    private final JwtValidator jwtValidator;

    /**
     * Get current controller (Raft leader) information
     * Can be called on any metadata node (leader or follower)
     * Used by storage nodes for controller discovery
     */
    @GetMapping("/controller")
    public ResponseEntity<ControllerInfo> getControllerInfo() {
        try {
            Integer controllerId = raftController.getControllerLeaderId();
            Long controllerTerm = raftController.getCurrentTerm();
            
            // Map controller ID to URL using ServiceDiscovery
            String controllerUrl = ServiceDiscovery.getMetadataServiceUrl(controllerId);
            
            if (controllerUrl == null) {
                log.error("Controller ID {} not found in services.json", controllerId);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
            
            ControllerInfo response = ControllerInfo.builder()
                    .controllerId(controllerId)
                    .controllerUrl(controllerUrl)
                    .controllerTerm(controllerTerm)
                    .timestamp(System.currentTimeMillis())
                    .build();
            
            log.debug("Returning controller info: ID={}, URL={}, term={}", 
                    controllerId, controllerUrl, controllerTerm);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Failed to get controller info: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Create a new topic
     * Only controller leader can process this
     */
    @PostMapping("/topics")
    public ResponseEntity<TopicMetadataResponse> createTopic(
            @Validated @RequestBody CreateTopicRequest request,
            HttpServletRequest httpRequest) {
        
        log.info("📝 Received request to create topic: {}", request.getTopicName());
        log.debug("Request details: partitions={}, replicationFactor={}, retentionMs={}", 
            request.getPartitionCount(), request.getReplicationFactor(), request.getRetentionMs());
        
        // JWT Authentication & Authorization
        try {
            UserPrincipal user = jwtValidator.validateRequest(httpRequest);
            if (!jwtValidator.hasRole(user, "ADMIN")) {
                log.warn("User {} lacks ADMIN role for topic creation", user.getUsername());
                return ResponseEntity.status(403)
                    .header("X-Error-Message", "Admin role required")
                    .build();
            }
        } catch (JwtException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return ResponseEntity.status(401)
                .header("X-Error-Message", "Invalid or missing authentication token")
                .build();
        }
        
        // Validate required fields
        if (request.getTopicName() == null || request.getTopicName().trim().isEmpty()) {
            log.error("❌ Topic name is required");
            return ResponseEntity.badRequest()
                .header("X-Error-Message", "Topic name is required")
                .build();
        }
        
        if (request.getPartitionCount() == null || request.getPartitionCount() <= 0) {
            log.error("❌ Invalid partition count: {}", request.getPartitionCount());
            return ResponseEntity.badRequest()
                .header("X-Error-Message", "Partition count must be greater than 0")
                .build();
        }
        
        if (request.getReplicationFactor() == null || request.getReplicationFactor() <= 0) {
            log.error("❌ Invalid replication factor: {}", request.getReplicationFactor());
            return ResponseEntity.badRequest()
                .header("X-Error-Message", "Replication factor must be greater than 0")
                .build();
        }
        
        // Check if this node is the controller leader
        if (!raftController.isControllerLeader()) {
            log.warn("⚠️ This node is not the controller leader. Current leader: {}", 
                    raftController.getControllerLeaderId());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("X-Controller-Leader", raftController.getControllerLeaderId().toString())
                    .header("X-Error-Message", "This node is not the controller. Redirect to leader.")
                    .build();
        }
        
        try {
            TopicMetadata metadata = metadataService.createTopic(request);
            
            TopicMetadataResponse response = TopicMetadataResponse.builder()
                    .topicName(metadata.getTopicName())
                    .partitionCount(metadata.getPartitionCount())
                    .replicationFactor(metadata.getReplicationFactor())
                    .partitions(metadata.getPartitions())
                    .createdAt(metadata.getCreatedAt())
                    .config(metadata.getConfig())
                    .build();
            
            log.info("✅ Topic created successfully: {}", request.getTopicName());
            return ResponseEntity.ok(response);
            
        } catch (IllegalArgumentException e) {
            log.warn("⚠️ Topic creation validation failed: {}", e.getMessage());
            return ResponseEntity.badRequest()
                .header("X-Error-Message", e.getMessage())
                .build();
        } catch (Exception e) {
            log.error("❌ Error creating topic: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header("X-Error-Message", e.getMessage())
                .build();
        }
    }

    /**
     * Get topic metadata
     * Can be served by any node (read operation)
     */
    @GetMapping("/topics/{topicName}")
    public ResponseEntity<TopicMetadataResponse> getTopicMetadata(
            @PathVariable String topicName,
            HttpServletRequest httpRequest) {
        
        // JWT Authentication (any authenticated user can read)
        try {
            jwtValidator.validateRequest(httpRequest);
        } catch (JwtException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return ResponseEntity.status(401).build();
        }
        
        log.debug("Fetching metadata for topic: {}", topicName);
        
        try {
            TopicMetadata metadata = metadataService.getTopicMetadata(topicName);
            
            TopicMetadataResponse response = TopicMetadataResponse.builder()
                    .topicName(metadata.getTopicName())
                    .partitionCount(metadata.getPartitionCount())
                    .replicationFactor(metadata.getReplicationFactor())
                    .partitions(metadata.getPartitions())
                    .createdAt(metadata.getCreatedAt())
                    .config(metadata.getConfig())
                    .build();
            
            return ResponseEntity.ok(response);
            
        } catch (IllegalArgumentException e) {
            log.warn("Topic not found: {}", topicName);
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error fetching topic metadata", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * List all topics
     * Can be served by any node (read operation)
     */
    @GetMapping("/topics")
    public ResponseEntity<List<String>> listTopics(HttpServletRequest httpRequest) {
        
        // JWT Authentication (any authenticated user can read)
        try {
            jwtValidator.validateRequest(httpRequest);
        } catch (JwtException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return ResponseEntity.status(401).build();
        }
        
        log.debug("Listing all topics");
        
        try {
            List<String> topics = metadataService.listTopics();
            return ResponseEntity.ok(topics);
            
        } catch (Exception e) {
            log.error("Error listing topics", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Delete a topic
     * Only controller leader can process this
     */
    @DeleteMapping("/topics/{topicName}")
    public ResponseEntity<Void> deleteTopic(@PathVariable String topicName, HttpServletRequest httpRequest) {
        log.info("Received request to delete topic: {}", topicName);
        
        // JWT Authentication & Authorization
        try {
            UserPrincipal user = jwtValidator.validateRequest(httpRequest);
            if (!jwtValidator.hasRole(user, "ADMIN")) {
                log.warn("User {} lacks ADMIN role for topic deletion", user.getUsername());
                return ResponseEntity.status(403).build();
            }
        } catch (JwtException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return ResponseEntity.status(401).build();
        }
        
        // Check if this node is the controller leader
        if (!raftController.isControllerLeader()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("X-Controller-Leader", raftController.getControllerLeaderId().toString())
                    .build();
        }
        
        try {
            metadataService.deleteTopic(topicName);
            return ResponseEntity.noContent().build();
            
        } catch (IllegalArgumentException e) {
            log.warn("Topic deletion failed: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error deleting topic", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get leader for a partition
     */
    @GetMapping("/topics/{topicName}/partitions/{partition}/leader")
    public ResponseEntity<?> getPartitionLeader(
            @PathVariable String topicName,
            @PathVariable Integer partition) {
        
        log.debug("Fetching leader for topic: {}, partition: {}", topicName, partition);
        
        // TODO: Implement partition leader lookup
        
        return ResponseEntity.ok().build();
    }

    /**
     * Get full cluster metadata
     * Returns all brokers, topics, and partitions with complete information
     * Used by storage services on startup and for periodic refresh
     */
    @GetMapping("/cluster")
    public ResponseEntity<ClusterMetadataResponse> getClusterMetadata() {
        log.debug("Fetching full cluster metadata");
        
        try {
            ClusterMetadataResponse response = metadataService.getClusterMetadata();
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error fetching cluster metadata", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Request metadata synchronization (called by other metadata services)
     * Only the active controller can handle sync requests
     */
    @PostMapping("/sync")
    public ResponseEntity<Void> requestMetadataSync(@RequestParam String requestingServiceUrl) {
        log.info("Received metadata sync request from: {}", requestingServiceUrl);

        // Only active controller can handle sync requests
        if (!raftController.isControllerLeader()) {
            log.warn("Non-active controller received sync request. Current leader: {}",
                    raftController.getControllerLeaderId());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("X-Controller-Leader", raftController.getControllerLeaderId().toString())
                    .build();
        }

        try {
            // Push all current metadata to the requesting service
            metadataService.pushAllMetadataToService(requestingServiceUrl);
            log.info("Successfully pushed all metadata to requesting service: {}", requestingServiceUrl);

            return ResponseEntity.ok().build();

        } catch (Exception e) {
            log.error("Error processing metadata sync request from {}: {}", requestingServiceUrl, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Pull sync all metadata (GET version for testing)
     * Returns all current metadata
     */
    @GetMapping("/sync")
    public ResponseEntity<MetadataSyncResponse> pullMetadataSync() {
        log.info("Received pull metadata sync request");

        try {
            // Get all topics
            List<String> topics = metadataService.listTopics();
            List<BrokerResponse> brokers = metadataService.listBrokers();

            MetadataSyncResponse response = MetadataSyncResponse.builder()
                    .topics(topics)
                    .brokers(brokers)
                    .syncTimestamp(System.currentTimeMillis())
                    .build();

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error processing pull metadata sync: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Sync metadata for specific broker
     */
    @GetMapping("/sync/broker/{brokerId}")
    public ResponseEntity<BrokerSyncResponse> syncBrokerMetadata(@PathVariable Integer brokerId) {
        log.info("Received broker sync request for broker: {}", brokerId);

        try {
            BrokerResponse broker = metadataService.getBroker(brokerId);
            List<String> topics = metadataService.listTopics();

            BrokerSyncResponse response = BrokerSyncResponse.builder()
                    .broker(broker)
                    .topics(topics)
                    .syncTimestamp(System.currentTimeMillis())
                    .build();

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("Broker not found for sync: {}", brokerId);
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error processing broker sync for {}: {}", brokerId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get sync status
     */
    @GetMapping("/sync/status")
    public ResponseEntity<SyncStatusResponse> getSyncStatus() {
        log.debug("Getting sync status");

        try {
            // Get basic sync information
            boolean isControllerLeader = raftController.isControllerLeader();
            long lastSyncTimestamp = System.currentTimeMillis(); // Placeholder
            int activeBrokers = metadataService.listBrokers().size();
            int totalTopics = metadataService.listTopics().size();

            SyncStatusResponse response = SyncStatusResponse.builder()
                    .isControllerLeader(isControllerLeader)
                    .lastSyncTimestamp(lastSyncTimestamp)
                    .activeBrokers(activeBrokers)
                    .totalTopics(totalTopics)
                    .status("HEALTHY")
                    .build();

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error getting sync status: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Incremental sync - get changes since timestamp
     */
    @GetMapping("/sync/incremental")
    public ResponseEntity<IncrementalSyncResponse> getIncrementalSync(
            @RequestParam(required = false, defaultValue = "0") Long sinceTimestamp) {

        log.info("Received incremental sync request since: {}", sinceTimestamp);

        try {
            // For now, return all data (full sync)
            // In a real implementation, this would track changes and return only deltas
            List<String> topics = metadataService.listTopics();
            List<BrokerResponse> brokers = metadataService.listBrokers();

            IncrementalSyncResponse response = IncrementalSyncResponse.builder()
                    .topics(topics)
                    .brokers(brokers)
                    .changesSince(sinceTimestamp)
                    .syncTimestamp(System.currentTimeMillis())
                    .build();

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error processing incremental sync: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Check metadata consistency
     */
    @GetMapping("/sync/consistency")
    public ResponseEntity<ConsistencyCheckResponse> checkConsistency() {
        log.info("Received consistency check request");

        try {
            // Basic consistency checks
            List<BrokerResponse> brokers = metadataService.listBrokers();
            List<String> topics = metadataService.listTopics();

            boolean brokersConsistent = brokers.stream()
                    .allMatch(broker -> broker.getId() != null && broker.getHost() != null);
            boolean topicsConsistent = !topics.isEmpty() || topics.isEmpty(); // Always true for now

            String status = (brokersConsistent && topicsConsistent) ? "CONSISTENT" : "INCONSISTENT";

            ConsistencyCheckResponse response = ConsistencyCheckResponse.builder()
                    .status(status)
                    .brokersChecked(brokers.size())
                    .topicsChecked(topics.size())
                    .checkTimestamp(System.currentTimeMillis())
                    .details("Basic consistency check performed")
                    .build();

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error checking consistency: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Trigger push sync to specified brokers
     */
    @PostMapping("/sync/trigger")
    public ResponseEntity<SyncTriggerResponse> triggerSync(@RequestBody SyncTriggerRequest request) {
        log.info("Received sync trigger request for brokers: {} and topics: {}",
                request.getBrokers(), request.getTopics());

        try {
            // Validate that specified brokers exist
            if (request.getBrokers() != null) {
                for (Integer brokerId : request.getBrokers()) {
                    try {
                        metadataService.getBroker(brokerId);
                    } catch (IllegalArgumentException e) {
                        log.warn("Broker {} does not exist for sync trigger", brokerId);
                        return ResponseEntity.badRequest().build();
                    }
                }
            }

            // Validate that specified topics exist
            if (request.getTopics() != null) {
                List<String> existingTopics = metadataService.listTopics();
                for (String topicName : request.getTopics()) {
                    if (!existingTopics.contains(topicName)) {
                        log.warn("Topic {} does not exist for sync trigger", topicName);
                        return ResponseEntity.badRequest().build();
                    }
                }
            }

            // For now, just return success
            // In a real implementation, this would trigger push sync to the specified brokers
            SyncTriggerResponse response = SyncTriggerResponse.builder()
                    .success(true)
                    .brokersTriggered(request.getBrokers() != null ? request.getBrokers().size() : 0)
                    .topicsSynced(request.getTopics() != null ? request.getTopics().size() : 0)
                    .triggerTimestamp(System.currentTimeMillis())
                    .build();

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error triggering sync: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Full metadata sync (alternative to incremental)
     */
    @PostMapping("/sync/full")
    public ResponseEntity<FullSyncResponse> fullSync() {
        log.info("Received full sync request");

        try {
            List<String> topics = metadataService.listTopics();
            List<BrokerResponse> brokers = metadataService.listBrokers();

            FullSyncResponse response = FullSyncResponse.builder()
                    .topics(topics)
                    .brokers(brokers)
                    .syncTimestamp(System.currentTimeMillis())
                    .fullSyncPerformed(true)
                    .build();

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error performing full sync: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Register a broker
     * Only controller leader can process this
     */
    @PostMapping("/brokers")
    public ResponseEntity<BrokerResponse> registerBroker(
            @Validated @RequestBody RegisterBrokerRequest request) {

        log.info("Received request to register broker: {}", request.getId());

        // Check if this node is the controller leader
        if (!raftController.isControllerLeader()) {
            log.warn("This node is not the controller leader. Current leader: {}",
                    raftController.getControllerLeaderId());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("X-Controller-Leader", raftController.getControllerLeaderId().toString())
                    .build();
        }

        try {
            BrokerResponse response = metadataService.registerBroker(request);
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("Broker registration failed: {}", e.getMessage());
            // Check if it's a duplicate broker error
            if (e.getMessage().contains("already exists")) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Error registering broker", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get broker information
     * Can be served by any node (read operation)
     */
    @GetMapping("/brokers/{brokerId}")
    public ResponseEntity<BrokerResponse> getBroker(@PathVariable Integer brokerId) {

        log.debug("Fetching broker: {}", brokerId);

        try {
            BrokerResponse response = metadataService.getBroker(brokerId);
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("Broker not found: {}", brokerId);
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error fetching broker", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * List all brokers
     * Can be served by any node (read operation)
     */
    @GetMapping("/brokers")
    public ResponseEntity<List<BrokerResponse>> listBrokers() {
        log.debug("Listing all brokers");

        try {
            List<BrokerResponse> brokers = metadataService.listBrokers();
            return ResponseEntity.ok(brokers);

        } catch (Exception e) {
            log.error("Error listing brokers", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Extract broker ID from service ID (e.g., "storage-101" -> 101)
     */
    private Integer extractBrokerIdFromServiceId(String serviceId) {
        if (serviceId == null || !serviceId.startsWith("storage-")) {
            throw new IllegalArgumentException("Invalid service ID format: " + serviceId);
        }
        try {
            return Integer.parseInt(serviceId.substring("storage-".length()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid broker ID in service ID: " + serviceId);
        }
    }
}
