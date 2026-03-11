package com.distributedmq.storage.heartbeat;

import com.distributedmq.common.dto.ControllerInfo;
import com.distributedmq.common.dto.HeartbeatResponse;
import com.distributedmq.storage.replication.MetadataStore;
import com.distributedmq.storage.service.ControllerDiscoveryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Heartbeat Sender Service with Controller Discovery and Failover
 * 
 * Periodically sends heartbeats to the CONTROLLER to signal this broker is alive.
 * Features:
 * - Controller discovery on startup
 * - Automatic rediscovery on 3/5 consecutive heartbeat failures
 * - Heartbeats paused during rediscovery
 * - Exponential retry for each heartbeat attempt
 * - Metadata staleness detection via version checking
 * - Periodic forced refresh every 2 minutes (configurable)
 */
@Slf4j
public class HeartbeatSender {

    private final Integer brokerId;
    private final RestTemplate restTemplate;
    private final MetadataStore metadataStore;
    private final ControllerDiscoveryService controllerDiscoveryService;
    
    // Current controller info (updated by discovery or CONTROLLER_CHANGED push)
    private volatile String currentControllerUrl;
    private volatile Integer currentControllerId;
    private volatile Long currentControllerTerm;

    @Value("${dmq.storage.heartbeat.interval-ms:5000}")
    private long heartbeatIntervalMs;

    @Value("${dmq.storage.heartbeat.retry-attempts:3}")
    private int retryAttempts;

    @Value("${dmq.storage.heartbeat.retry-delay-ms:1000}")
    private long retryDelayMs;

    @Value("${storage.metadata.periodic-refresh-interval-ms:120000}")
    private long periodicRefreshIntervalMs;

    @Value("${storage.heartbeat.version-mismatch-threshold:3}")
    private int versionMismatchThreshold;
    
    @Value("${storage.heartbeat.failure-threshold:3}")
    private int heartbeatFailureThreshold;  // Trigger rediscovery at 3/5 failures

    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile boolean enabled = true;
    private volatile boolean heartbeatPaused = false;  // Pause during rediscovery

    // Metadata version tracking
    private volatile int consecutiveVersionMismatches = 0;
    
    // Heartbeat failure tracking (0-5 counter)
    private volatile int consecutiveHeartbeatFailures = 0;
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    public HeartbeatSender(Integer brokerId, RestTemplate restTemplate, 
                          MetadataStore metadataStore, 
                          ControllerDiscoveryService controllerDiscoveryService) {
        this.brokerId = brokerId;
        this.restTemplate = restTemplate;
        this.metadataStore = metadataStore;
        this.controllerDiscoveryService = controllerDiscoveryService;
    }

    @PostConstruct
    public void init() {
        log.info("========================================");
        log.info("Heartbeat Sender initializing for broker {}", brokerId);
        log.info("Heartbeat interval: {} ms ({} seconds)", heartbeatIntervalMs, heartbeatIntervalMs / 1000);
        log.info("Retry attempts per heartbeat: {}", retryAttempts);
        log.info("Failure threshold for rediscovery: {}/{}", heartbeatFailureThreshold, MAX_CONSECUTIVE_FAILURES);
        log.info("Version mismatch threshold: {}", versionMismatchThreshold);
        log.info("Periodic refresh interval: {} ms ({} minutes)", periodicRefreshIntervalMs, periodicRefreshIntervalMs / 60000);
        
        // Discover controller on startup
        try {
            log.info("🔍 Discovering controller on startup...");
            ControllerInfo controllerInfo = controllerDiscoveryService.discoverController();
            updateControllerInfo(controllerInfo);
            log.info("✅ Initial controller discovery successful: {}", currentControllerUrl);
            
            // Register broker with discovered controller
            log.info("📝 Registering broker {} with controller...", brokerId);
            metadataStore.registerWithController(currentControllerUrl);
            
            // Pull initial metadata from controller
            log.info("📡 Pulling initial metadata from controller...");
            metadataStore.pullInitialMetadataFromController(currentControllerUrl);
            
        } catch (Exception e) {
            log.error("❌ Failed to discover controller on startup: {}", e.getMessage(), e);
            throw new IllegalStateException("Cannot start heartbeat sender without controller", e);
        }
        
        log.info("========================================");
    }

    /**
     * Send periodic heartbeat to controller
     * Runs every heartbeatIntervalMs (default: 5 seconds)
     * Also checks metadata version and triggers refresh if needed
     */
    @Scheduled(fixedDelayString = "${dmq.storage.heartbeat.interval-ms:5000}")
    public void sendHeartbeat() {
        if (!enabled) {
            log.trace("Heartbeat sender is disabled, skipping");
            return;
        }
        
        if (heartbeatPaused) {
            log.debug("Heartbeat paused during rediscovery, skipping");
            return;
        }

        // Sync controller info from MetadataStore (in case CONTROLLER_CHANGED notification updated it)
        syncControllerInfoFromMetadataStore();

        if (currentControllerUrl == null) {
            log.error("No controller URL available, cannot send heartbeat");
            return;
        }

        String endpoint = currentControllerUrl + "/api/v1/metadata/heartbeat/" + brokerId;
        
        log.debug("Sending heartbeat to controller: {}", endpoint);

        boolean success = false;
        int attempt = 0;
        HeartbeatResponse heartbeatResponse = null;

        // Exponential retry for THIS heartbeat attempt
        while (!success && attempt < retryAttempts) {
            attempt++;
            
            try {
                ResponseEntity<HeartbeatResponse> response = restTemplate.postForEntity(
                    endpoint, null, HeartbeatResponse.class);
                
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    success = true;
                    heartbeatResponse = response.getBody();
                    successCount.incrementAndGet();
                    consecutiveHeartbeatFailures = 0;  // Reset on success
                    log.debug("Heartbeat sent successfully (attempt {}/{}): version={}", 
                        attempt, retryAttempts, heartbeatResponse.getMetadataVersion());
                } else {
                    log.warn("Heartbeat returned non-2xx status (attempt {}/{}): {}", 
                        attempt, retryAttempts, response.getStatusCode());
                }
                
            } catch (Exception e) {
                log.error("Failed to send heartbeat (attempt {}/{}): {}", 
                    attempt, retryAttempts, e.getMessage());
                
                // Retry with exponential delay if not the last attempt
                if (attempt < retryAttempts) {
                    try {
                        long delay = retryDelayMs * attempt;  // 1s, 2s, 3s
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("Heartbeat retry interrupted");
                        break;
                    }
                }
            }
        }

        if (!success) {
            failureCount.incrementAndGet();
            consecutiveHeartbeatFailures++;
            
            log.error("❌ Failed to send heartbeat after {} attempts. Consecutive failures: {}/{}", 
                retryAttempts, consecutiveHeartbeatFailures, MAX_CONSECUTIVE_FAILURES);
            
            // Trigger rediscovery at 3/5, 4/5, 5/5 thresholds
            if (consecutiveHeartbeatFailures >= heartbeatFailureThreshold) {
                log.warn("⚠️ Heartbeat failure threshold reached ({}/{}), triggering controller rediscovery", 
                    consecutiveHeartbeatFailures, MAX_CONSECUTIVE_FAILURES);
                triggerControllerRediscovery();
            }
            
            if (consecutiveHeartbeatFailures >= MAX_CONSECUTIVE_FAILURES) {
                log.error("❌ FATAL: Maximum consecutive heartbeat failures ({}) reached! Storage node cannot operate.", 
                    MAX_CONSECUTIVE_FAILURES);
                // Could throw exception here or mark node as degraded
            }
            
            return;
        }

        // Check metadata version from heartbeat response
        if (heartbeatResponse != null && heartbeatResponse.getMetadataVersion() != null) {
            checkMetadataVersion(heartbeatResponse.getMetadataVersion());
        }

        // Check if periodic refresh is needed
        checkPeriodicRefresh();
    }

    /**
     * Trigger controller rediscovery
     * Pauses heartbeats, queries metadata nodes, resumes on success
     */
    private void triggerControllerRediscovery() {
        try {
            log.info("🔄 Pausing heartbeats for controller rediscovery");
            heartbeatPaused = true;
            
            // Calculate attempt number based on failures: 3→1, 4→2, 5→3
            int attemptNumber = consecutiveHeartbeatFailures - heartbeatFailureThreshold + 1;
            
            log.info("Starting controller rediscovery (attempt {}/3)", attemptNumber);
            ControllerInfo newController = controllerDiscoveryService.rediscoverController(attemptNumber);
            
            if (newController != null) {
                log.info("✅ Controller rediscovered: ID={}, URL={}", 
                    newController.getControllerId(), newController.getControllerUrl());
                updateControllerInfo(newController);
                consecutiveHeartbeatFailures = 0;  // Reset on successful rediscovery
            }
            
        } catch (Exception e) {
            log.error("❌ Controller rediscovery failed: {}", e.getMessage(), e);
            // Don't reset consecutiveHeartbeatFailures - let it increment to 5
        } finally {
            log.info("▶️ Resuming heartbeats");
            heartbeatPaused = false;
        }
    }
    
    /**
     * Sync controller info from MetadataStore
     * Called before each heartbeat to catch CONTROLLER_CHANGED notifications
     */
    private void syncControllerInfoFromMetadataStore() {
        String metadataControllerUrl = metadataStore.getCurrentControllerUrl();
        Integer metadataControllerId = metadataStore.getCurrentControllerId();
        
        // If MetadataStore has different controller info, sync it
        if (metadataControllerUrl != null && !metadataControllerUrl.equals(currentControllerUrl)) {
            String oldUrl = currentControllerUrl;
            Integer oldId = currentControllerId;
            
            this.currentControllerUrl = metadataControllerUrl;
            this.currentControllerId = metadataControllerId;
            this.currentControllerTerm = metadataStore.getCurrentControllerTerm();
            
            log.info("🔄 Synced controller from MetadataStore: {} (ID={}) → {} (ID={}, term={})", 
                oldUrl, oldId, currentControllerUrl, currentControllerId, currentControllerTerm);
                
            // Reset failure counter when switching to new controller
            consecutiveHeartbeatFailures = 0;
        }
    }
    
    /**
     * Update cached controller information
     * Called by discovery service or CONTROLLER_CHANGED push notification
     */
    public void updateControllerInfo(ControllerInfo controllerInfo) {
        String oldUrl = currentControllerUrl;
        Integer oldId = currentControllerId;
        
        this.currentControllerUrl = controllerInfo.getControllerUrl();
        this.currentControllerId = controllerInfo.getControllerId();
        this.currentControllerTerm = controllerInfo.getControllerTerm();
        
        if (oldUrl != null && !oldUrl.equals(currentControllerUrl)) {
            log.info("🔄 Controller changed: {} (ID={}) → {} (ID={}, term={})", 
                oldUrl, oldId, currentControllerUrl, currentControllerId, currentControllerTerm);
        } else {
            log.debug("Controller info updated: ID={}, URL={}, term={}", 
                currentControllerId, currentControllerUrl, currentControllerTerm);
        }
        
        // Update in MetadataStore for other components to use
        metadataStore.updateCurrentControllerInfo(
            currentControllerId, currentControllerUrl, currentControllerTerm);
    }
    
    /**
     * Get current controller URL (for external components)
     */
    public String getCurrentControllerUrl() {
        return currentControllerUrl;
    }
    
    /**
     * Get current controller ID (for external components)
     */
    public Integer getCurrentControllerId() {
        return currentControllerId;
    }
    
    /**
     * Check metadata version from heartbeat response
     * Triggers refresh after 3 consecutive mismatches
     */
    private void checkMetadataVersion(Long remoteVersion) {
        Long localVersion = metadataStore.getCurrentMetadataVersion();
        
        if (remoteVersion > localVersion) {
            consecutiveVersionMismatches++;
            log.warn("⚠️ Metadata version mismatch detected: local={}, remote={} (consecutive mismatches: {}/{})",
                    localVersion, remoteVersion, consecutiveVersionMismatches, versionMismatchThreshold);
            
            if (consecutiveVersionMismatches >= versionMismatchThreshold) {
                log.warn("🔄 Triggering metadata refresh after {} consecutive version mismatches", 
                    consecutiveVersionMismatches);
                triggerMetadataRefresh("version mismatch");
                consecutiveVersionMismatches = 0; // Reset after triggering refresh
            }
        } else if (remoteVersion.equals(localVersion)) {
            // Versions match - reset counter
            if (consecutiveVersionMismatches > 0) {
                log.debug("Metadata versions now match (local={}, remote={}), resetting mismatch counter from {}",
                        localVersion, remoteVersion, consecutiveVersionMismatches);
                consecutiveVersionMismatches = 0;
            }
        } else {
            // Remote version is lower than local (unusual but possible after metadata service restart)
            log.warn("⚠️ Remote metadata version ({}) is lower than local ({}). Metadata service may have restarted.",
                    remoteVersion, localVersion);
        }
    }

    /**
     * Check if periodic refresh is needed (every 2 minutes by default)
     */
    private void checkPeriodicRefresh() {
        Long lastRefreshTime = metadataStore.getLastMetadataRefreshTime();
        if (lastRefreshTime == null) {
            lastRefreshTime = System.currentTimeMillis();
        }
        
        long timeSinceRefresh = System.currentTimeMillis() - lastRefreshTime;
        
        if (timeSinceRefresh >= periodicRefreshIntervalMs) {
            log.info("🔄 Triggering periodic metadata refresh (age: {} ms, threshold: {} ms)",
                    timeSinceRefresh, periodicRefreshIntervalMs);
            triggerMetadataRefresh("periodic refresh");
        }
    }

    /**
     * Trigger metadata refresh by pulling from metadata service
     */
    private void triggerMetadataRefresh(String reason) {
        if (currentControllerUrl == null) {
            log.error("Cannot refresh metadata: controller URL not available (reason: {})", reason);
            return;
        }
        
        try {
            log.info("Refreshing metadata from controller {} due to: {}", currentControllerUrl, reason);
            metadataStore.pullInitialMetadataFromController(currentControllerUrl);
            log.info("Metadata refresh completed successfully");
        } catch (Exception e) {
            log.error("Failed to refresh metadata from controller {}: {}", 
                    currentControllerUrl, e.getMessage(), e);
        }
    }

    /**
     * Get heartbeat statistics for monitoring
     */
    public HeartbeatStats getStats() {
        return HeartbeatStats.builder()
            .brokerId(brokerId)
            .successCount(successCount.get())
            .failureCount(failureCount.get())
            .consecutiveFailures(consecutiveHeartbeatFailures)
            .enabled(enabled)
            .heartbeatPaused(heartbeatPaused)
            .currentControllerUrl(currentControllerUrl)
            .currentControllerId(currentControllerId)
            .heartbeatIntervalMs(heartbeatIntervalMs)
            .build();
    }

    /**
     * Enable heartbeat sending
     */
    public void enable() {
        log.info("Enabling heartbeat sender for broker {}", brokerId);
        this.enabled = true;
    }

    /**
     * Disable heartbeat sending (for graceful shutdown)
     */
    public void disable() {
        log.warn("Disabling heartbeat sender for broker {}", brokerId);
        this.enabled = false;
    }

    /**
     * Heartbeat statistics
     */
    @lombok.Builder
    @lombok.Data
    public static class HeartbeatStats {
        private final Integer brokerId;
        private final int successCount;
        private final int failureCount;
        private final int consecutiveFailures;
        private final boolean enabled;
        private final boolean heartbeatPaused;
        private final String currentControllerUrl;
        private final Integer currentControllerId;
        private final long heartbeatIntervalMs;
    }
}
