package com.distributedmq.storage.replication;

import com.distributedmq.common.dto.MetadataUpdateRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * consider this class as updated information, as it will be keep updated by metadata service if any changes occur
 * additioinally if some change is stale we will req metadata service for latest data upfront or in periodically after expiry time
 * Also if storage node encounter any change in knowledge like follower is down etc then i will trigger update metadata req through contrller of cluster
 * Stores metadata about partitions, leaders, followers, and ISR
 * This data is updated by the metadata service
 */
@Slf4j
public class MetadataStore {

    // topic-partition -> leader broker ID
    private final Map<String, Integer> partitionLeaders = new ConcurrentHashMap<>();

    // topic-partition -> list of follower broker IDs
    private final Map<String, List<Integer>> partitionFollowers = new ConcurrentHashMap<>();

    // topic-partition -> list of ISR broker IDs
    private final Map<String, List<Integer>> partitionISR = new ConcurrentHashMap<>();

    // topic-partition -> leader epoch
    private final Map<String, Long> partitionLeaderEpochs = new ConcurrentHashMap<>();

    // broker ID -> broker info
    private final Map<Integer, BrokerInfo> brokers = new ConcurrentHashMap<>();

    // This broker's ID (injected from config)
    private Integer localBrokerId;
    
    // This broker's host and port (from config/services.json)
    private String localBrokerHost;
    private Integer localBrokerPort;

    // Metadata service URL for notifications
    private String metadataServiceUrl;

    // RestTemplate for HTTP calls
    private final RestTemplate restTemplate = new RestTemplate();

    // Current metadata version known by this storage service
    private volatile Long currentMetadataVersion = 0L;

    // Last metadata update timestamp
    private volatile Long lastMetadataUpdateTimestamp = 0L;

    // Last metadata refresh time (for periodic refresh tracking)
    private volatile Long lastMetadataRefreshTime = System.currentTimeMillis();

    // Current controller info (updated by discovery or CONTROLLER_CHANGED push)
    private volatile Integer currentControllerId;
    private volatile String currentControllerUrl;
    private volatile Long currentControllerTerm;

    public void setLocalBrokerId(Integer brokerId) {
        this.localBrokerId = brokerId;
        log.info("Local broker ID set to: {}", brokerId);
    }

    public Integer getLocalBrokerId() {
        return localBrokerId;
    }
    
    public void setLocalBrokerHost(String host) {
        this.localBrokerHost = host;
        log.info("Local broker host set to: {}", host);
    }
    
    public void setLocalBrokerPort(Integer port) {
        this.localBrokerPort = port;
        log.info("Local broker port set to: {}", port);
    }

    public void setMetadataServiceUrl(String url) {
        this.metadataServiceUrl = url;
        log.info("Metadata service URL set to: {}", url);
    }

    public String getMetadataServiceUrl() {
        return metadataServiceUrl;
    }

    /**
     * Update metadata from metadata service (bulk update)
     * This replaces the current metadata with the new snapshot
     */
    public void updateMetadata(MetadataUpdateRequest request) {
        log.info("Updating metadata: type={}, version={}, {} brokers, {} partitions",
                request.getUpdateType(),
                request.getVersion(),
                request.getBrokers() != null ? request.getBrokers().size() : 0,
                request.getPartitions() != null ? request.getPartitions().size() : 0);

        // Update metadata version and timestamp
        if (request.getVersion() != null) {
            this.currentMetadataVersion = request.getVersion();
        }
        this.lastMetadataUpdateTimestamp = request.getTimestamp() != null ?
                request.getTimestamp() : System.currentTimeMillis();
        
        // Reset refresh time when metadata is updated via push
        this.lastMetadataRefreshTime = System.currentTimeMillis();

        // Update broker information if present
        if (request.getBrokers() != null) {
            for (MetadataUpdateRequest.BrokerInfo brokerInfo : request.getBrokers()) {
                BrokerInfo broker = BrokerInfo.builder()
                        .id(brokerInfo.getId())
                        .host(brokerInfo.getHost())
                        .port(brokerInfo.getPort())
                        .isAlive(brokerInfo.isAlive())
                        .build();
                updateBroker(broker);
            }
        }

        // Handle different update types
        MetadataUpdateRequest.UpdateType updateType = request.getUpdateType();
        if (updateType == null) {
            updateType = MetadataUpdateRequest.UpdateType.FULL_SNAPSHOT;  // Default for backward compatibility
        }

        switch (updateType) {
            case FULL_SNAPSHOT:
                handleFullSnapshotUpdate(request);
                break;
            
            case ISR_UPDATE:
                handleISRUpdate(request);
                break;
            
            case LEADER_UPDATE:
                handleLeaderUpdate(request);
                break;
            
            case TOPIC_CREATED:
                handleTopicCreatedUpdate(request);
                break;
            
            case TOPIC_DELETED:
                handleTopicDeletedUpdate(request);
                break;
            
            case BROKER_UPDATE:
                // Broker updates already handled above
                log.info("Broker status update processed (no partition changes)");
                break;
            
            case CONTROLLER_CHANGED:
                handleControllerChangedUpdate(request);
                break;
            
            default:
                log.warn("Unknown update type: {}, treating as FULL_SNAPSHOT", updateType);
                handleFullSnapshotUpdate(request);
                break;
        }

        log.info("Metadata update completed: version={}, type={}", 
                this.currentMetadataVersion, updateType);
    }

    /**
     * Handle full snapshot replacement (clear all + rebuild)
     * Used for: initial sync, topic deletion, major changes
     */
    private void handleFullSnapshotUpdate(MetadataUpdateRequest request) {
        if (request.getPartitions() == null) {
            log.warn("Full snapshot update with no partitions, skipping");
            return;
        }

        // Clear all existing partition mappings
        int oldPartitionCount = partitionLeaders.size();
        log.info("🔄 Full snapshot: Clearing {} existing partition mappings", oldPartitionCount);
        
        partitionLeaders.clear();
        partitionFollowers.clear();
        partitionISR.clear();
        partitionLeaderEpochs.clear();
        
        // Rebuild partition mappings from the new snapshot
        for (MetadataUpdateRequest.PartitionMetadata partition : request.getPartitions()) {
            updatePartitionLeadership(
                    partition.getTopic(),
                    partition.getPartition(),
                    partition.getLeaderId(),
                    partition.getFollowerIds(),
                    partition.getIsrIds(),
                    partition.getLeaderEpoch()
            );
        }
        
        int newPartitionCount = partitionLeaders.size();
        log.info("✅ Full snapshot completed: {} old partitions → {} new partitions", 
                oldPartitionCount, newPartitionCount);
    }

    /**
     * Handle incremental ISR update (only update ISR for specified partitions)
     * Used for: ISR membership changes
     */
    private void handleISRUpdate(MetadataUpdateRequest request) {
        if (request.getPartitions() == null || request.getPartitions().isEmpty()) {
            log.warn("ISR update with no partitions, skipping");
            return;
        }

        log.info("🔄 Incremental ISR update for {} partitions", request.getPartitions().size());
        
        for (MetadataUpdateRequest.PartitionMetadata partition : request.getPartitions()) {
            String key = partition.getTopic() + "-" + partition.getPartition();
            
            List<Integer> oldISR = partitionISR.get(key);
            partitionISR.put(key, partition.getIsrIds());
            
            log.info("✅ Updated ISR for {}: {} → {}", 
                    key, oldISR, partition.getIsrIds());
        }
    }

    /**
     * Handle incremental leader update (only update leader for specified partitions)
     * Used for: Leader elections
     */
    private void handleLeaderUpdate(MetadataUpdateRequest request) {
        if (request.getPartitions() == null || request.getPartitions().isEmpty()) {
            log.warn("Leader update with no partitions, skipping");
            return;
        }

        log.info("🔄 Incremental leader update for {} partitions", request.getPartitions().size());
        
        for (MetadataUpdateRequest.PartitionMetadata partition : request.getPartitions()) {
            String key = partition.getTopic() + "-" + partition.getPartition();
            
            Integer oldLeader = partitionLeaders.get(key);
            partitionLeaders.put(key, partition.getLeaderId());
            partitionLeaderEpochs.put(key, partition.getLeaderEpoch());
            
            // Update followers list if provided
            if (partition.getFollowerIds() != null) {
                partitionFollowers.put(key, partition.getFollowerIds());
            }
            
            // Update ISR if provided
            if (partition.getIsrIds() != null) {
                partitionISR.put(key, partition.getIsrIds());
            }
            
            log.info("✅ Updated leader for {}: {} → {}, epoch={}", 
                    key, oldLeader, partition.getLeaderId(), partition.getLeaderEpoch());
        }
    }

    /**
     * Handle topic creation (add new partitions)
     * Used for: Topic creation
     */
    private void handleTopicCreatedUpdate(MetadataUpdateRequest request) {
        if (request.getPartitions() == null || request.getPartitions().isEmpty()) {
            log.warn("Topic creation with no partitions, skipping");
            return;
        }

        log.info("🔄 Topic created: adding {} partitions", request.getPartitions().size());
        
        for (MetadataUpdateRequest.PartitionMetadata partition : request.getPartitions()) {
            String key = partition.getTopic() + "-" + partition.getPartition();
            
            // Only add if not already present (safety check)
            if (!partitionLeaders.containsKey(key)) {
                updatePartitionLeadership(
                        partition.getTopic(),
                        partition.getPartition(),
                        partition.getLeaderId(),
                        partition.getFollowerIds(),
                        partition.getIsrIds(),
                        partition.getLeaderEpoch()
                );
                log.info("✅ Added new partition: {}", key);
            } else {
                log.debug("Partition {} already exists, skipping", key);
            }
        }
    }

    /**
     * Handle topic deletion (remove all partitions for deleted topics)
     * Used for: Topic deletion
     */
    private void handleTopicDeletedUpdate(MetadataUpdateRequest request) {
        if (request.getDeletedTopics() == null || request.getDeletedTopics().isEmpty()) {
            log.warn("Topic deletion with no topics specified, skipping");
            return;
        }

        log.info("🔄 Topic deletion: removing partitions for {} topics", request.getDeletedTopics().size());
        
        for (String topicName : request.getDeletedTopics()) {
            int removedCount = 0;
            
            // Remove all partitions matching the topic
            List<String> keysToRemove = partitionLeaders.keySet().stream()
                    .filter(key -> key.startsWith(topicName + "-"))
                    .collect(Collectors.toList());
            
            for (String key : keysToRemove) {
                partitionLeaders.remove(key);
                partitionFollowers.remove(key);
                partitionISR.remove(key);
                partitionLeaderEpochs.remove(key);
                removedCount++;
            }
            
            log.info("✅ Removed {} partitions for deleted topic: {}", removedCount, topicName);
        }
    }

    /**
     * Handle controller change notification from metadata service
     * Applies random jitter (0-5s) to prevent thundering herd when all storage nodes update simultaneously
     */
    private void handleControllerChangedUpdate(MetadataUpdateRequest request) {
        if (request.getControllerId() == null || request.getControllerUrl() == null) {
            log.warn("Controller change notification missing controller info, skipping");
            return;
        }

        Integer oldControllerId = this.currentControllerId;
        String oldControllerUrl = this.currentControllerUrl;

        log.info("🔄 Controller change notification received: controllerId={}, controllerUrl={}, term={} (old: controllerId={}, url={})",
                request.getControllerId(), request.getControllerUrl(), request.getControllerTerm(),
                oldControllerId, oldControllerUrl);

        // Apply random jitter (0-5 seconds) to prevent thundering herd
        try {
            int jitterMs = new java.util.Random().nextInt(5000); // 0-5000ms
            log.debug("Applying random jitter of {}ms before updating controller info", jitterMs);
            Thread.sleep(jitterMs);
        } catch (InterruptedException e) {
            log.warn("Interrupted during controller change jitter, proceeding with update", e);
            Thread.currentThread().interrupt();
        }

        // Update controller info
        updateCurrentControllerInfo(request.getControllerId(), request.getControllerUrl(), request.getControllerTerm());

        log.info("✅ Controller updated: {} → {} (term: {})",
                oldControllerId, request.getControllerId(), request.getControllerTerm());
    }

    /**
     * Update current controller information (called by HeartbeatSender on discovery/rediscovery)
     * Thread-safe update of volatile fields
     */
    public void updateCurrentControllerInfo(Integer controllerId, String controllerUrl, Long controllerTerm) {
        this.currentControllerId = controllerId;
        this.currentControllerUrl = controllerUrl;
        this.currentControllerTerm = controllerTerm;

        log.info("📌 Updated controller cache: id={}, url={}, term={}", controllerId, controllerUrl, controllerTerm);
    }

    /**
     * Update partition leadership information
     */
    public void updatePartitionLeadership(String topic, Integer partition,
                                        Integer leaderId, List<Integer> followers,
                                        List<Integer> isr, Long leaderEpoch) {
        String key = getPartitionKey(topic, partition);

        partitionLeaders.put(key, leaderId);
        partitionFollowers.put(key, new ArrayList<>(followers));
        partitionISR.put(key, new ArrayList<>(isr));
        partitionLeaderEpochs.put(key, leaderEpoch);

        log.debug("Updated leadership for {}-{}: leader={}, followers={}, isr={}, epoch={}",
                topic, partition, leaderId, followers, isr, leaderEpoch);
    }

    /**
     * Check if this broker is the leader for a partition
     */
    public boolean isLeaderForPartition(String topic, Integer partition) {
        String key = getPartitionKey(topic, partition);
        Integer leaderId = partitionLeaders.get(key);
        return leaderId != null && leaderId.equals(localBrokerId);
    }

    /**
     * Check if this broker is a follower for a partition
     */
    public boolean isFollowerForPartition(String topic, Integer partition) {
        String key = getPartitionKey(topic, partition);
        List<Integer> followers = partitionFollowers.get(key);
        boolean result = followers != null && followers.contains(localBrokerId);

        log.debug("isFollowerForPartition: topic={}, partition={}, key={}, followers={}, localBrokerId={}, result={}",
                topic, partition, key, followers, localBrokerId, result);

        return result;
    }

    /**
     * Get followers for a partition
     */
    public List<BrokerInfo> getFollowersForPartition(String topic, Integer partition) {
        String key = getPartitionKey(topic, partition);
        List<Integer> followerIds = partitionFollowers.get(key);

        if (followerIds == null) {
            return Collections.emptyList();
        }

        return followerIds.stream()
                .map(brokers::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Get ISR for a partition
     */
    public List<BrokerInfo> getISRForPartition(String topic, Integer partition) {
        String key = getPartitionKey(topic, partition);
        List<Integer> isrIds = partitionISR.get(key);

        if (isrIds == null) {
            return Collections.emptyList();
        }

        return isrIds.stream()
                .map(brokers::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Get leader epoch for a partition
     */
    public Long getLeaderEpoch(String topic, Integer partition) {
        String key = getPartitionKey(topic, partition);
        return partitionLeaderEpochs.getOrDefault(key, 0L);
    }

    /**
     * Phase 2: ISR Lag Reporting
     * Get all partitions where this broker is a follower (not leader)
     * Used by ISRLagReporter to identify partitions to report lag for
     */
    public List<PartitionInfo> getPartitionsWhereFollower() {
        List<PartitionInfo> followerPartitions = new ArrayList<>();
        
        // Iterate through all known partitions
        for (String partitionKey : partitionFollowers.keySet()) {
            List<Integer> followers = partitionFollowers.get(partitionKey);
            
            // Check if this broker is in the followers list
            if (followers != null && followers.contains(localBrokerId)) {
                // Extract topic and partition from key
                String[] parts = partitionKey.split("-");
                if (parts.length >= 2) {
                    String topic = parts[0];
                    Integer partition = Integer.parseInt(parts[parts.length - 1]);
                    
                    // Get additional metadata
                    Integer leaderId = partitionLeaders.get(partitionKey);
                    List<Integer> isrIds = partitionISR.get(partitionKey);
                    Long leaderEpoch = partitionLeaderEpochs.getOrDefault(partitionKey, 0L);
                    
                    followerPartitions.add(new PartitionInfo(
                        topic, 
                        partition, 
                        leaderId,
                        isrIds != null ? isrIds : Collections.emptyList(),
                        leaderEpoch
                    ));
                }
            }
        }
        
        log.debug("Found {} partitions where broker {} is a follower", 
                followerPartitions.size(), localBrokerId);
        return followerPartitions;
    }

    /**
     * Update broker information
     */
    public void updateBroker(BrokerInfo broker) {
        brokers.put(broker.getId(), broker);
        log.debug("Updated broker info: {}", broker);
    }

    /**
     * Remove broker (when it goes offline) -> may trigger metadata update through conntroller of cluster
     */
    public void removeBroker(Integer brokerId) {
        brokers.remove(brokerId);
        log.info("Removed broker {}", brokerId);
    }

    /**
     * Get all known brokers
     */
    public List<BrokerInfo> getAllBrokers() {
        return new ArrayList<>(brokers.values());
    }

    /**
     * Get broker by ID
     */
    public BrokerInfo getBroker(Integer brokerId) {
        return brokers.get(brokerId);
    }

    /**
     * Generate partition key from topic and partition
     */
    private String getPartitionKey(String topic, Integer partition) {
        return topic + "-" + partition;
    }

    /**
     * Get partition count for a topic
     * Counts partitions by scanning partition keys
     */
    public Integer getPartitionCount(String topic) {
        if (topic == null) {
            return null;
        }
        
        // Count partition keys that start with "topic-"
        String prefix = topic + "-";
        int count = 0;
        
        for (String key : partitionLeaders.keySet()) {
            if (key.startsWith(prefix)) {
                count++;
            }
        }
        
        log.debug("Partition count for topic {}: {}", topic, count);
        return count > 0 ? count : null;
    }

    /**
     * Notify metadata service about partition leadership change
     * Called when this broker detects a leadership change
     */
    public void notifyPartitionLeadershipChange(String topic, Integer partition,
                                              Integer newLeaderId, List<Integer> followers,
                                              List<Integer> isr, Long leaderEpoch) {
        if (metadataServiceUrl == null) {
            log.warn("Metadata service URL not configured, cannot notify about leadership change");
            return;
        }

        try {
            // Create metadata update request
            MetadataUpdateRequest.PartitionMetadata partitionMetadata =
                    new MetadataUpdateRequest.PartitionMetadata();
            partitionMetadata.setTopic(topic);
            partitionMetadata.setPartition(partition);
            partitionMetadata.setLeaderId(newLeaderId);
            partitionMetadata.setFollowerIds(followers);
            partitionMetadata.setIsrIds(isr);
            partitionMetadata.setLeaderEpoch(leaderEpoch);

            MetadataUpdateRequest updateRequest = MetadataUpdateRequest.builder()
                    .partitions(List.of(partitionMetadata))
                    .timestamp(System.currentTimeMillis())
                    .build();

            // Send notification to metadata service
            notifyMetadataService(updateRequest);

            log.info("Notified metadata service about partition leadership change for {}-{}",
                    topic, partition);

        } catch (Exception e) {
            log.error("Failed to notify metadata service about partition leadership change: {}", e.getMessage());
        }
    }

    /**
     * Notify metadata service about broker status change
     * Called when this broker detects another broker going offline/online
     */
    public void notifyBrokerStatusChange(Integer brokerId, boolean isAlive) {
        if (metadataServiceUrl == null) {
            log.warn("Metadata service URL not configured, cannot notify about broker status change");
            return;
        }

        try {
            // Create broker info
            MetadataUpdateRequest.BrokerInfo brokerInfo =
                    new MetadataUpdateRequest.BrokerInfo();
            brokerInfo.setId(brokerId);
            brokerInfo.setAlive(isAlive);

            MetadataUpdateRequest updateRequest = MetadataUpdateRequest.builder()
                    .brokers(List.of(brokerInfo))
                    .timestamp(System.currentTimeMillis())
                    .build();

            // Send notification to metadata service
            notifyMetadataService(updateRequest);

            log.info("Notified metadata service about broker {} status change: {}",
                    brokerId, isAlive ? "online" : "offline");

        } catch (Exception e) {
            log.error("Failed to notify metadata service about broker status change: {}", e.getMessage());
        }
    }

    /**
     * Send notification to metadata service
     */
    private void notifyMetadataService(MetadataUpdateRequest updateRequest) {
        try {
            String url = metadataServiceUrl + "/storage-updates";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<MetadataUpdateRequest> requestEntity = new HttpEntity<>(updateRequest, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(url, requestEntity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Successfully notified metadata service about update");
            } else {
                log.warn("Metadata service returned non-success status: {}", response.getStatusCode());
            }

        } catch (Exception e) {
            log.error("Failed to notify metadata service: {}", e.getMessage());
        }
    }

    /**
     * Register this broker with the metadata service
     * Retries on failure with exponential backoff
     */
    /**
     * Register broker with metadata service using discovered controller URL
     * Called by HeartbeatSender after controller discovery
     */
    public void registerWithController(String controllerUrl) {
        int maxAttempts = 5;
        int attempt = 0;
        long retryDelay = 2000; // Start with 2 seconds

        while (attempt < maxAttempts) {
            attempt++;
            
            try {
                // Create registration request using actual broker configuration
                Map<String, Object> registration = new HashMap<>();
                registration.put("id", localBrokerId);
                registration.put("host", localBrokerHost != null ? localBrokerHost : "localhost");
                registration.put("port", localBrokerPort != null ? localBrokerPort : 8081);
                registration.put("rack", "default");

                log.info("📝 Registering broker {} at {}:{} with controller at {} (attempt {}/{})",
                        localBrokerId, 
                        registration.get("host"), 
                        registration.get("port"),
                        controllerUrl,
                        attempt,
                        maxAttempts);

                // Send registration request
                String endpoint = controllerUrl + "/api/v1/metadata/brokers";
                Map<String, Object> response = restTemplate.postForObject(endpoint, registration, Map.class);

                if (response != null) {
                    log.info("✅ Successfully registered broker {} with controller: {}", localBrokerId, response);
                    return; // Success - exit
                } else {
                    log.warn("Broker registration returned null response (attempt {}/{})", attempt, maxAttempts);
                }

            } catch (Exception e) {
                log.error("Failed to register broker (attempt {}/{}): {}", attempt, maxAttempts, e.getMessage());
                
                // If not the last attempt, wait before retrying
                if (attempt < maxAttempts) {
                    try {
                        log.info("Retrying broker registration in {} ms...", retryDelay);
                        Thread.sleep(retryDelay);
                        retryDelay *= 2; // Exponential backoff: 2s, 4s, 8s, 16s
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("Broker registration retry interrupted");
                        return;
                    }
                }
            }
        }
        
        log.error("❌ Failed to register broker {} after {} attempts!", 
            localBrokerId, maxAttempts);
    }

    /**
     * OLD METHOD - kept for backward compatibility but should not be used
     * Use registerWithController() instead
     */
    public void registerWithMetadataService() {
        if (metadataServiceUrl == null) {
            log.warn("Metadata service URL not configured, cannot register broker");
            return;
        }

        int maxAttempts = 5;
        int attempt = 0;
        long retryDelay = 2000; // Start with 2 seconds

        while (attempt < maxAttempts) {
            attempt++;
            
            try {
                // Create registration request using actual broker configuration
                Map<String, Object> registration = new HashMap<>();
                registration.put("id", localBrokerId);
                registration.put("host", localBrokerHost != null ? localBrokerHost : "localhost");
                registration.put("port", localBrokerPort != null ? localBrokerPort : 8081);
                registration.put("rack", "default");

                log.info("Registering broker {} at {}:{} with metadata service at {} (attempt {}/{})",
                        localBrokerId, 
                        registration.get("host"), 
                        registration.get("port"),
                        metadataServiceUrl,
                        attempt,
                        maxAttempts);

                // Send registration request
                String endpoint = metadataServiceUrl + "/api/v1/metadata/brokers";
                Map<String, Object> response = restTemplate.postForObject(endpoint, registration, Map.class);

                if (response != null) {
                    log.info("✅ Successfully registered broker {} with metadata service: {}", localBrokerId, response);
                    return; // Success - exit
                } else {
                    log.warn("Broker registration returned null response (attempt {}/{})", attempt, maxAttempts);
                }

            } catch (Exception e) {
                log.error("Failed to register broker (attempt {}/{}): {}", attempt, maxAttempts, e.getMessage());
                
                // If not the last attempt, wait before retrying
                if (attempt < maxAttempts) {
                    try {
                        log.info("Retrying broker registration in {} ms...", retryDelay);
                        Thread.sleep(retryDelay);
                        retryDelay *= 2; // Exponential backoff: 2s, 4s, 8s, 16s
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("Broker registration retry interrupted");
                        return;
                    }
                }
            }
        }
        
        log.error("❌ Failed to register broker {} after {} attempts. Heartbeats will continue to fail until manual registration!", 
            localBrokerId, maxAttempts);
    }

    /**
     * Pull initial metadata from discovered controller
     * Called by HeartbeatSender after controller discovery and broker registration
     */
    public void pullInitialMetadataFromController(String controllerUrl) {
        int maxAttempts = 5;
        int attempt = 0;
        long retryDelay = 2000; // Start with 2 seconds

        while (attempt < maxAttempts) {
            attempt++;
            
            try {
                String endpoint = controllerUrl + "/api/v1/metadata/cluster";
                log.info("📡 Pulling initial cluster metadata from controller {} (attempt {}/{})", 
                    endpoint, attempt, maxAttempts);

                // Fetch cluster metadata
                Map<String, Object> response = restTemplate.getForObject(endpoint, Map.class);

                if (response != null) {
                    log.info("✅ Successfully pulled initial metadata from controller: version={}, {} brokers, {} topics",
                            response.get("version"),
                            response.get("brokers") != null ? 
                                ((List<?>) response.get("brokers")).size() : 0,
                            response.get("topics") != null ? 
                                ((List<?>) response.get("topics")).size() : 0);

                    // Extract and store metadata version
                    if (response.get("version") != null) {
                        this.currentMetadataVersion = ((Number) response.get("version")).longValue();
                        log.info("Metadata version set to: {}", this.currentMetadataVersion);
                    }

                    // Extract controller info from response
                    if (response.get("controllerInfo") != null) {
                        Map<String, Object> controllerInfo = (Map<String, Object>) response.get("controllerInfo");
                        Integer controllerId = controllerInfo.get("controllerId") != null ? 
                            ((Number) controllerInfo.get("controllerId")).intValue() : null;
                        String newControllerUrl = (String) controllerInfo.get("controllerUrl");
                        Long controllerTerm = controllerInfo.get("controllerTerm") != null ?
                            ((Number) controllerInfo.get("controllerTerm")).longValue() : null;
                        
                        // Update cached controller info
                        updateCurrentControllerInfo(controllerId, newControllerUrl, controllerTerm);
                        
                        // Verify it matches the controller we're talking to
                        if (newControllerUrl != null && !newControllerUrl.equals(controllerUrl)) {
                            log.warn("⚠️ Controller URL mismatch! Expected: {}, Got: {} - Controller may have changed during init",
                                controllerUrl, newControllerUrl);
                        }
                    }

                    // Process brokers
                    if (response.get("brokers") != null) {
                        List<Map<String, Object>> brokersList = 
                            (List<Map<String, Object>>) response.get("brokers");
                        for (Map<String, Object> brokerMap : brokersList) {
                            BrokerInfo broker = BrokerInfo.builder()
                                    .id(((Number) brokerMap.get("id")).intValue())
                                    .host((String) brokerMap.get("host"))
                                    .port(((Number) brokerMap.get("port")).intValue())
                                    .isAlive((Boolean) brokerMap.getOrDefault("alive", true))
                                    .build();
                            updateBroker(broker);
                        }
                        log.info("Processed {} brokers from initial metadata", brokersList.size());
                    }

                    // Process topics and partitions using FULL SNAPSHOT REPLACEMENT
                    if (response.get("topics") != null) {
                        // Clear all existing partition mappings for full snapshot replacement
                        int oldPartitionCount = partitionLeaders.size();
                        log.info("Clearing {} existing partition mappings for full snapshot refresh", 
                                oldPartitionCount);
                        
                        partitionLeaders.clear();
                        partitionFollowers.clear();
                        partitionISR.clear();
                        partitionLeaderEpochs.clear();
                        
                        // Rebuild partition mappings from cluster metadata
                        List<Map<String, Object>> topicsList = 
                            (List<Map<String, Object>>) response.get("topics");
                        int totalPartitions = 0;
                        
                        for (Map<String, Object> topicMap : topicsList) {
                            String topicName = (String) topicMap.get("topicName");
                            List<Map<String, Object>> partitionsList = 
                                (List<Map<String, Object>>) topicMap.get("partitions");
                            
                            if (partitionsList != null) {
                                for (Map<String, Object> partitionMap : partitionsList) {
                                    String topic = (String) partitionMap.get("topicName");
                                    Integer partition = ((Number) partitionMap.get("partitionId")).intValue();
                                    
                                    // Extract leader
                                    Map<String, Object> leaderMap = 
                                        (Map<String, Object>) partitionMap.get("leader");
                                    Integer leaderId = leaderMap != null ? 
                                        ((Number) leaderMap.get("brokerId")).intValue() : null;
                                    
                                    // Extract ISR
                                    List<Map<String, Object>> isrList = 
                                        (List<Map<String, Object>>) partitionMap.get("isr");
                                    List<Integer> isrIds = isrList != null ? 
                                        isrList.stream()
                                            .map(isr -> ((Number) isr.get("brokerId")).intValue())
                                            .collect(Collectors.toList()) : 
                                        Collections.emptyList();
                                    
                                    // Extract replicas (followers)
                                    List<Map<String, Object>> replicasList = 
                                        (List<Map<String, Object>>) partitionMap.get("replicas");
                                    List<Integer> replicaIds = replicasList != null ? 
                                        replicasList.stream()
                                            .map(replica -> ((Number) replica.get("brokerId")).intValue())
                                            .collect(Collectors.toList()) : 
                                        Collections.emptyList();
                                    
                                    // Followers are replicas excluding the leader
                                    List<Integer> followerIds = replicaIds.stream()
                                        .filter(id -> !id.equals(leaderId))
                                        .collect(Collectors.toList());
                                    
                                    Long leaderEpoch = System.currentTimeMillis();
                                    
                                    // Update partition metadata
                                    updatePartitionLeadership(topic, partition, leaderId, 
                                        followerIds, isrIds, leaderEpoch);
                                    totalPartitions++;
                                }
                            }
                        }
                        
                        log.info("Partition snapshot refresh completed: {} old partitions → {} new partitions from {} topics", 
                            oldPartitionCount, totalPartitions, topicsList.size());
                    }

                    this.lastMetadataUpdateTimestamp = System.currentTimeMillis();
                    this.lastMetadataRefreshTime = System.currentTimeMillis();
                    log.info("✅ Initial metadata pull from controller completed successfully");
                    return; // Success - exit

                } else {
                    log.warn("Cluster metadata pull returned null response (attempt {}/{})", 
                        attempt, maxAttempts);
                }

            } catch (Exception e) {
                log.error("Failed to pull initial metadata (attempt {}/{}): {}", 
                    attempt, maxAttempts, e.getMessage(), e);
                
                // If not the last attempt, wait before retrying
                if (attempt < maxAttempts) {
                    try {
                        log.info("Retrying metadata pull in {} ms...", retryDelay);
                        Thread.sleep(retryDelay);
                        retryDelay *= 2; // Exponential backoff: 2s, 4s, 8s, 16s
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("Metadata pull retry interrupted");
                        return;
                    }
                }
            }
        }
        
        log.error("❌ Failed to pull initial metadata from controller after {} attempts!", maxAttempts);
    }

    /**
     * OLD METHOD - kept for backward compatibility
     * Use pullInitialMetadataFromController() instead
     */
    public void pullInitialMetadata() {
        if (metadataServiceUrl == null) {
            log.warn("Metadata service URL not configured, cannot pull initial metadata");
            return;
        }

        int maxAttempts = 5;
        int attempt = 0;
        long retryDelay = 2000; // Start with 2 seconds

        while (attempt < maxAttempts) {
            attempt++;
            
            try {
                String endpoint = metadataServiceUrl + "/api/v1/metadata/cluster";
                log.info("Pulling initial cluster metadata from {} (attempt {}/{})", 
                    endpoint, attempt, maxAttempts);

                // Fetch cluster metadata
                Map<String, Object> response = restTemplate.getForObject(endpoint, Map.class);

                if (response != null) {
                    log.info("✅ Successfully pulled initial metadata: version={}, {} brokers, {} topics",
                            response.get("version"),
                            response.get("brokers") != null ? 
                                ((List<?>) response.get("brokers")).size() : 0,
                            response.get("topics") != null ? 
                                ((List<?>) response.get("topics")).size() : 0);

                    // Extract and verify controller info from snapshot
                    if (response.get("controllerInfo") != null) {
                        Map<String, Object> controllerInfoMap = (Map<String, Object>) response.get("controllerInfo");
                        Integer snapshotControllerId = ((Number) controllerInfoMap.get("controllerId")).intValue();
                        String snapshotControllerUrl = (String) controllerInfoMap.get("controllerUrl");
                        Long snapshotControllerTerm = controllerInfoMap.get("controllerTerm") != null ? 
                                ((Number) controllerInfoMap.get("controllerTerm")).longValue() : null;
                        
                        log.info("📌 Snapshot controller info: id={}, url={}, term={}", 
                                snapshotControllerId, snapshotControllerUrl, snapshotControllerTerm);
                        
                        // Verify against cached controller (from heartbeat discovery)
                        if (currentControllerId != null && !currentControllerId.equals(snapshotControllerId)) {
                            log.warn("⚠️ Controller mismatch! Cached: id={}, url={} | Snapshot: id={}, url={}. Using snapshot data.",
                                    currentControllerId, currentControllerUrl, 
                                    snapshotControllerId, snapshotControllerUrl);
                        }
                        
                        // Update controller info from snapshot (snapshot is source of truth)
                        updateCurrentControllerInfo(snapshotControllerId, snapshotControllerUrl, snapshotControllerTerm);
                    }
                    
                    // Extract active metadata nodes list for failover
                    if (response.get("activeMetadataNodes") != null) {
                        List<Map<String, Object>> metadataNodesList = 
                                (List<Map<String, Object>>) response.get("activeMetadataNodes");
                        log.info("📋 Active metadata nodes: {}", metadataNodesList.stream()
                                .map(node -> String.format("id=%s, url=%s, isLeader=%s", 
                                        node.get("id"), node.get("url"), node.get("isLeader")))
                                .collect(Collectors.joining(", ")));
                        // TODO: Store metadata nodes list for load balancing/failover (future enhancement)
                    }

                    // Extract and store metadata version
                    if (response.get("version") != null) {
                        this.currentMetadataVersion = ((Number) response.get("version")).longValue();
                        log.info("Metadata version set to: {}", this.currentMetadataVersion);
                    }

                    // Process brokers
                    if (response.get("brokers") != null) {
                        List<Map<String, Object>> brokersList = 
                            (List<Map<String, Object>>) response.get("brokers");
                        for (Map<String, Object> brokerMap : brokersList) {
                            BrokerInfo broker = BrokerInfo.builder()
                                    .id(((Number) brokerMap.get("id")).intValue())
                                    .host((String) brokerMap.get("host"))
                                    .port(((Number) brokerMap.get("port")).intValue())
                                    .isAlive((Boolean) brokerMap.getOrDefault("alive", true))
                                    .build();
                            updateBroker(broker);
                        }
                        log.info("Processed {} brokers from initial metadata", brokersList.size());
                    }

                    // Process topics and partitions using FULL SNAPSHOT REPLACEMENT
                    if (response.get("topics") != null) {
                        // Clear all existing partition mappings for full snapshot replacement
                        int oldPartitionCount = partitionLeaders.size();
                        log.info("Clearing {} existing partition mappings for full snapshot refresh", 
                                oldPartitionCount);
                        
                        partitionLeaders.clear();
                        partitionFollowers.clear();
                        partitionISR.clear();
                        partitionLeaderEpochs.clear();
                        
                        // Rebuild partition mappings from cluster metadata
                        List<Map<String, Object>> topicsList = 
                            (List<Map<String, Object>>) response.get("topics");
                        int totalPartitions = 0;
                        
                        for (Map<String, Object> topicMap : topicsList) {
                            String topicName = (String) topicMap.get("topicName");
                            List<Map<String, Object>> partitionsList = 
                                (List<Map<String, Object>>) topicMap.get("partitions");
                            
                            if (partitionsList != null) {
                                for (Map<String, Object> partitionMap : partitionsList) {
                                    String topic = (String) partitionMap.get("topicName");
                                    Integer partition = ((Number) partitionMap.get("partitionId")).intValue();
                                    
                                    // Extract leader
                                    Map<String, Object> leaderMap = 
                                        (Map<String, Object>) partitionMap.get("leader");
                                    Integer leaderId = leaderMap != null ? 
                                        ((Number) leaderMap.get("brokerId")).intValue() : null;
                                    
                                    // Extract ISR
                                    List<Map<String, Object>> isrList = 
                                        (List<Map<String, Object>>) partitionMap.get("isr");
                                    List<Integer> isrIds = isrList != null ? 
                                        isrList.stream()
                                            .map(isr -> ((Number) isr.get("brokerId")).intValue())
                                            .collect(Collectors.toList()) : 
                                        Collections.emptyList();
                                    
                                    // Extract replicas (followers)
                                    List<Map<String, Object>> replicasList = 
                                        (List<Map<String, Object>>) partitionMap.get("replicas");
                                    List<Integer> replicaIds = replicasList != null ? 
                                        replicasList.stream()
                                            .map(replica -> ((Number) replica.get("brokerId")).intValue())
                                            .collect(Collectors.toList()) : 
                                        Collections.emptyList();
                                    
                                    // Followers are replicas excluding the leader
                                    List<Integer> followerIds = replicaIds.stream()
                                        .filter(id -> !id.equals(leaderId))
                                        .collect(Collectors.toList());
                                    
                                    Long leaderEpoch = System.currentTimeMillis();
                                    
                                    // Update partition metadata
                                    updatePartitionLeadership(topic, partition, leaderId, 
                                        followerIds, isrIds, leaderEpoch);
                                    totalPartitions++;
                                }
                            }
                        }
                        
                        log.info("Partition snapshot refresh completed: {} old partitions → {} new partitions from {} topics", 
                            oldPartitionCount, totalPartitions, topicsList.size());
                    }

                    this.lastMetadataUpdateTimestamp = System.currentTimeMillis();
                    this.lastMetadataRefreshTime = System.currentTimeMillis();
                    log.info("✅ Initial metadata pull completed successfully");
                    return; // Success - exit

                } else {
                    log.warn("Cluster metadata pull returned null response (attempt {}/{})", 
                        attempt, maxAttempts);
                }

            } catch (Exception e) {
                log.error("Failed to pull initial metadata (attempt {}/{}): {}", 
                    attempt, maxAttempts, e.getMessage(), e);
                
                // If not the last attempt, wait before retrying
                if (attempt < maxAttempts) {
                    try {
                        log.info("Retrying metadata pull in {} ms...", retryDelay);
                        Thread.sleep(retryDelay);
                        retryDelay *= 2; // Exponential backoff: 2s, 4s, 8s, 16s
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("Metadata pull retry interrupted");
                        return;
                    }
                }
            }
        }
        
        log.error("❌ Failed to pull initial metadata after {} attempts. Storage service will have stale metadata!", 
            maxAttempts);
    }

    /**
     * Get current metadata version
     */
    public Long getCurrentMetadataVersion() {
        return currentMetadataVersion;
    }

    /**
     * Get last metadata update timestamp
     */
    public Long getLastMetadataUpdateTimestamp() {
        return lastMetadataUpdateTimestamp;
    }

    /**
     * Get last metadata refresh time (for periodic refresh tracking)
     */
    public Long getLastMetadataRefreshTime() {
        return lastMetadataRefreshTime;
    }

    /**
     * Get current controller URL (for HeartbeatSender sync)
     */
    public String getCurrentControllerUrl() {
        return currentControllerUrl;
    }

    /**
     * Get current controller ID (for HeartbeatSender sync)
     */
    public Integer getCurrentControllerId() {
        return currentControllerId;
    }

    /**
     * Get current controller term (for HeartbeatSender sync)
     */
    public Long getCurrentControllerTerm() {
        return currentControllerTerm;
    }

    // TODO: Add methods to sync with metadata service (pull model - request metadata)
    // These will be implemented when metadata service is available
    // - requestMetadataRefresh() - Request latest metadata from metadata service
    // - registerWithMetadataService() - Register this broker with metadata service

    // TODO: Add methods to sync with metadata service (for later implementation)
    // - fetchPartitionMetadata()
    // - registerBroker()
    // - updateISR() - ISR management for advanced replication

    // TODO: Add initialization methods for testing/basic setup (needed for replication flow)
    // - initializeTestMetadata() - populate sample brokers and partition leadership
    // - loadFromConfig() - load static metadata from configuration

    /**
     * Phase 2: ISR Lag Reporting
     * Simple partition info holder for lag reporting
     */
    public static class PartitionInfo {
        private final String topic;
        private final Integer partition;
        private final Integer leaderId;
        private final List<Integer> isrIds;
        private final Long leaderEpoch;
        
        public PartitionInfo(String topic, Integer partition, Integer leaderId, 
                           List<Integer> isrIds, Long leaderEpoch) {
            this.topic = topic;
            this.partition = partition;
            this.leaderId = leaderId;
            this.isrIds = isrIds;
            this.leaderEpoch = leaderEpoch;
        }
        
        public String getTopic() { return topic; }
        public Integer getPartition() { return partition; }
        public Integer getLeaderId() { return leaderId; }
        public List<Integer> getIsrIds() { return isrIds; }
        public Long getLeaderEpoch() { return leaderEpoch; }
    }
}

