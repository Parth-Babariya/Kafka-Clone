package com.distributedmq.metadata.controller;

import com.distributedmq.common.dto.ISRLagReport;
import com.distributedmq.metadata.coordination.RaftController;
import com.distributedmq.metadata.service.ISRLagProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Phase 2: ISR Lag Reporting
 * Phase 3: ISR Auto-Management
 * 
 * REST controller for receiving ISR lag reports from storage services.
 * Only the active controller (Raft leader) processes lag reports.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/metadata/isr")
@RequiredArgsConstructor
public class ISRController {
    
    private final RaftController raftController;
    private final ISRLagProcessor isrLagProcessor; // Phase 3: Auto-management
    
    /**
     * Receive lag report from storage service (follower broker)
     * Endpoint: POST /api/v1/metadata/isr/lag
     * 
     * Only the active controller processes these reports.
     * Non-controller nodes will log and ignore the report.
     */
    @PostMapping("/lag")
    public ResponseEntity<Void> receiveLagReport(@RequestBody ISRLagReport report) {
        log.info("Received ISR lag report from broker {} with {} partitions",
                report.getBrokerId(), 
                report.getPartitions() != null ? report.getPartitions().size() : 0);
        
        // Only controller processes lag reports
        if (!raftController.isControllerLeader()) {
            log.debug("Not the active controller, ignoring lag report from broker {}", 
                    report.getBrokerId());
            return ResponseEntity.ok().build();
        }
        
        // Log partition details for visibility
        if (report.getPartitions() != null) {
            for (ISRLagReport.PartitionLag lag : report.getPartitions()) {
                log.debug("Lag report for {}-{}: offsetLag={}, timeSinceCaughtUp={}ms, isOutOfSync={}, followerLEO={}, leaderLEO={}",
                        lag.getTopic(), lag.getPartition(),
                        lag.getOffsetLag(), lag.getTimeSinceCaughtUp(),
                        lag.getIsOutOfSync(), lag.getFollowerLEO(), lag.getLeaderLEO());
            }
        }
        
        // Phase 3: Process lag reports and update ISR automatically
        try {
            isrLagProcessor.processLagReport(report);
            log.debug("Successfully processed lag report from broker {}", report.getBrokerId());
        } catch (Exception e) {
            log.error("Error processing lag report from broker {}: {}", 
                    report.getBrokerId(), e.getMessage(), e);
            // Still return 200 OK to avoid retries from storage service
        }
        
        return ResponseEntity.ok().build();
    }
    
    /**
     * Health check endpoint for ISR controller
     * Can be used to verify the endpoint is reachable
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        boolean isController = raftController.isControllerLeader();
        String status = isController ? "CONTROLLER" : "FOLLOWER";
        return ResponseEntity.ok("ISR Controller Status: " + status);
    }
}
