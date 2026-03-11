package com.distributedmq.storage.service;

import com.distributedmq.common.dto.ControllerInfo;
import com.distributedmq.storage.config.ClusterTopologyConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Service for discovering the current controller (Raft leader) in the metadata cluster
 * 
 * Features:
 * - Batched parallel queries (3 nodes at a time)
 * - First-successful-response pattern
 * - Exponential backoff retry (1s, 2s, 4s)
 * - Fallback to next batch if first batch fails
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ControllerDiscoveryService {

    private final RestTemplate restTemplate;
    private final ClusterTopologyConfig clusterTopologyConfig;

    private static final int BATCH_SIZE = 3;
    private static final int QUERY_TIMEOUT_MS = 2000;
    private static final int MAX_REDISCOVERY_ATTEMPTS = 3;

    /**
     * Discover controller on startup
     * Queries metadata nodes in batches until successful response
     */
    public ControllerInfo discoverController() {
        List<ClusterTopologyConfig.MetadataServiceInfo> allNodes = 
            clusterTopologyConfig.getMetadataServices();
        
        if (allNodes.isEmpty()) {
            throw new IllegalStateException("No metadata services configured in services.json");
        }

        log.info("🔍 Starting controller discovery from {} metadata nodes: {}", 
            allNodes.size(),
            allNodes.stream()
                .map(n -> n.getUrl())
                .toArray());

        // Split into batches
        List<List<ClusterTopologyConfig.MetadataServiceInfo>> batches = 
            createBatches(allNodes, BATCH_SIZE);

        log.info("Split into {} batches of size {}", batches.size(), BATCH_SIZE);

        for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
            List<ClusterTopologyConfig.MetadataServiceInfo> batch = batches.get(batchIndex);
            
            log.info("📡 Querying batch {}/{}: {} nodes [{}]", 
                batchIndex + 1, batches.size(), batch.size(),
                batch.stream().map(n -> n.getUrl()).toArray());

            ControllerInfo result = queryBatchInParallel(batch);
            
            if (result != null) {
                log.info("✅ Controller discovered: ID={}, URL={}, term={}", 
                    result.getControllerId(), result.getControllerUrl(), result.getControllerTerm());
                return result;
            }
            
            log.warn("⚠️ Batch {}/{} failed, trying next batch", batchIndex + 1, batches.size());
        }

        throw new IllegalStateException(
            "Failed to discover controller from all " + allNodes.size() + " metadata nodes");
    }

    /**
     * Rediscover controller with exponential backoff retry
     * Called when heartbeats fail (3/5, 4/5, 5/5 thresholds)
     */
    public ControllerInfo rediscoverController(int attemptNumber) {
        log.info("Rediscovery attempt {}/{}", attemptNumber, MAX_REDISCOVERY_ATTEMPTS);

        if (attemptNumber > MAX_REDISCOVERY_ATTEMPTS) {
            throw new IllegalStateException(
                "Failed to rediscover controller after " + MAX_REDISCOVERY_ATTEMPTS + " attempts");
        }

        try {
            return discoverController();
        } catch (Exception e) {
            log.error("Rediscovery attempt {} failed: {}", attemptNumber, e.getMessage());

            if (attemptNumber < MAX_REDISCOVERY_ATTEMPTS) {
                // Exponential backoff: 1s, 2s, 4s
                long delayMs = (long) Math.pow(2, attemptNumber - 1) * 1000;
                log.info("Waiting {}ms before next rediscovery attempt", delayMs);
                
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Rediscovery interrupted", ie);
                }

                // Recursive retry
                return rediscoverController(attemptNumber + 1);
            } else {
                throw new IllegalStateException(
                    "Failed to rediscover controller after " + MAX_REDISCOVERY_ATTEMPTS + " attempts", e);
            }
        }
    }

    /**
     * Query a batch of nodes in parallel, return first successful response
     */
    private ControllerInfo queryBatchInParallel(List<ClusterTopologyConfig.MetadataServiceInfo> batch) {
        List<CompletableFuture<ControllerInfo>> futures = new ArrayList<>();

        // Start all queries in parallel
        for (ClusterTopologyConfig.MetadataServiceInfo node : batch) {
            CompletableFuture<ControllerInfo> future = CompletableFuture.supplyAsync(() -> 
                queryControllerInfo(node.getUrl())
            );
            futures.add(future);
        }

        // Wait for ALL futures to complete (with timeout)
        try {
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
            );

            // Wait for all to complete or timeout
            allFutures.get(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            // Check results - return first non-null successful response
            for (CompletableFuture<ControllerInfo> future : futures) {
                if (future.isDone() && !future.isCompletedExceptionally()) {
                    ControllerInfo result = future.get();
                    if (result != null && result.getControllerId() != null) {
                        log.info("✅ Found controller in batch: ID={}, URL={}", 
                            result.getControllerId(), result.getControllerUrl());
                        // Cancel remaining futures (optional optimization)
                        futures.forEach(f -> f.cancel(true));
                        return result;
                    }
                }
            }

            log.warn("⚠️ No valid controller info found in batch");

        } catch (TimeoutException e) {
            log.warn("⏱️ Batch query timed out after {}ms, checking partial results", QUERY_TIMEOUT_MS);
            
            // Even with timeout, check if any completed successfully
            for (CompletableFuture<ControllerInfo> future : futures) {
                if (future.isDone() && !future.isCompletedExceptionally()) {
                    try {
                        ControllerInfo result = future.getNow(null);
                        if (result != null && result.getControllerId() != null) {
                            log.info("✅ Found controller from partial results: ID={}, URL={}", 
                                result.getControllerId(), result.getControllerUrl());
                            futures.forEach(f -> f.cancel(true));
                            return result;
                        }
                    } catch (Exception ignored) {
                        // Skip this future
                    }
                }
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ Batch query interrupted", e);
        } catch (ExecutionException e) {
            log.error("❌ Batch query execution failed: {}", e.getMessage());
        }

        return null;  // Batch failed
    }

    /**
     * Query single metadata node for controller info
     */
    private ControllerInfo queryControllerInfo(String metadataNodeUrl) {
        try {
            String endpoint = metadataNodeUrl + "/api/v1/metadata/controller";
            log.info("🔎 Querying controller info from: {}", endpoint);

            ControllerInfo response = restTemplate.getForObject(endpoint, ControllerInfo.class);

            if (response != null && response.getControllerId() != null) {
                log.info("📥 Received controller info from {}: controllerId={}, controllerUrl={}, term={}", 
                    metadataNodeUrl, response.getControllerId(), response.getControllerUrl(), response.getControllerTerm());
                return response;
            } else {
                log.warn("⚠️ Invalid controller info from {}: {}", metadataNodeUrl, response);
                return null;
            }

        } catch (Exception e) {
            log.warn("❌ Failed to query {}: {} - {}", metadataNodeUrl, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * Split list into batches of specified size
     */
    private List<List<ClusterTopologyConfig.MetadataServiceInfo>> createBatches(
            List<ClusterTopologyConfig.MetadataServiceInfo> items, int batchSize) {
        
        List<List<ClusterTopologyConfig.MetadataServiceInfo>> batches = new ArrayList<>();
        
        for (int i = 0; i < items.size(); i += batchSize) {
            int end = Math.min(i + batchSize, items.size());
            batches.add(items.subList(i, end));
        }
        
        return batches;
    }
}
