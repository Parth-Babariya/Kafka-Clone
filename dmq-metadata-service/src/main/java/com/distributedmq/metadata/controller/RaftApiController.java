package com.distributedmq.metadata.controller;

import com.distributedmq.metadata.coordination.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API endpoints for Raft consensus protocol
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/raft")
@RequiredArgsConstructor
public class RaftApiController {

    @Autowired
    private RaftController raftController;

    /**
     * Handle RequestVote RPC from other nodes
     */
    @PostMapping("/request-vote")
    public ResponseEntity<RequestVoteResponse> handleRequestVote(@RequestBody RequestVoteRequest request) {
        log.debug("Received RequestVote RPC: term={}, candidate={}", request.getTerm(), request.getCandidateId());

        RequestVoteResponse response = raftController.handleRequestVote(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Handle AppendEntries RPC from leader
     */
    @PostMapping("/append-entries")
    public ResponseEntity<AppendEntriesResponse> handleAppendEntries(@RequestBody AppendEntriesRequest request) {
        try {
            log.debug("Received AppendEntries RPC: term={}, leader={}, entries={}",
                    request.getTerm(), request.getLeaderId(),
                    request.getEntries() != null ? request.getEntries().size() : 0);

            AppendEntriesResponse response = raftController.handleAppendEntries(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error processing AppendEntries RPC", e);
            // Return error response
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AppendEntriesResponse.builder()
                            .term(raftController.getCurrentTerm())
                            .success(false)
                            .build());
        }
    }

    /**
     * Get current Raft status (for debugging/monitoring)
     */
    @GetMapping("/status")
    public ResponseEntity<RaftStatus> getStatus() {
        RaftStatus status = RaftStatus.builder()
                .nodeId(raftController.getNodeId())
                .currentTerm(raftController.getCurrentTerm())
                .state(raftController.getState())
                .isLeader(raftController.isControllerLeader())
                .leaderId(raftController.getControllerLeaderId())
                .commitIndex(raftController.getCommitIndex())
                .lastApplied(raftController.getLastApplied())
                .build();

        return ResponseEntity.ok(status);
    }
    
    /**
     * Get current state machine statistics (for debugging)
     */
    @GetMapping("/state-machine/stats")
    public ResponseEntity<java.util.Map<String, Object>> getStateMachineStats() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("nodeId", raftController.getNodeId());
        stats.put("topicCount", raftController.getStateMachine().getTopicCount());
        stats.put("brokerCount", raftController.getStateMachine().getBrokerCount());
        stats.put("allTopicNames", new java.util.ArrayList<>(raftController.getStateMachine().getAllTopics().keySet()));
        return ResponseEntity.ok(stats);
    }
}