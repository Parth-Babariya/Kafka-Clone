package com.distributedmq.metadata.coordination;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Raft Network Client for RPC communications
 */
@Slf4j
@Component
public class RaftNetworkClient {

    private final RestTemplate restTemplate;
    private final ExecutorService executorService;

    public RaftNetworkClient() {
        this.restTemplate = new RestTemplate();
        this.executorService = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName("raft-rpc-" + t.getId());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Send RequestVote RPC to a peer
     */
    public CompletableFuture<RequestVoteResponse> sendRequestVote(
            RaftNodeConfig.NodeInfo peer, RequestVoteRequest request) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = String.format("http://%s:%d/api/v1/raft/request-vote",
                        peer.getHost(), peer.getPort());

                log.debug("Sending RequestVote to {}: term={}, candidate={}",
                        peer.getAddress(), request.getTerm(), request.getCandidateId());

                ResponseEntity<RequestVoteResponse> response = restTemplate.postForEntity(
                        url, request, RequestVoteResponse.class);

                RequestVoteResponse voteResponse = response.getBody();
                log.debug("Received RequestVote response from {}: granted={}",
                        peer.getAddress(), voteResponse != null ? voteResponse.isVoteGranted() : false);

                return voteResponse;

            } catch (Exception e) {
                log.warn("Failed to send RequestVote to {}: {}", peer.getAddress(), e.getMessage());
                // Return default response (vote not granted)
                return RequestVoteResponse.builder()
                        .term(request.getTerm())
                        .voteGranted(false)
                        .build();
            }
        }, executorService);
    }

    /**
     * Send AppendEntries RPC to a peer
     */
    public CompletableFuture<AppendEntriesResponse> sendAppendEntries(
            RaftNodeConfig.NodeInfo peer, AppendEntriesRequest request) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = String.format("http://%s:%d/api/v1/raft/append-entries",
                        peer.getHost(), peer.getPort());

                boolean isHeartbeat = request.getEntries() == null || request.getEntries().isEmpty();
                log.debug("Sending AppendEntries to {}: term={}, leader={}, heartbeat={}",
                        peer.getAddress(), request.getTerm(), request.getLeaderId(), isHeartbeat);

                ResponseEntity<AppendEntriesResponse> response = restTemplate.postForEntity(
                        url, request, AppendEntriesResponse.class);

                AppendEntriesResponse appendResponse = response.getBody();
                log.debug("Received AppendEntries response from {}: success={}",
                        peer.getAddress(), appendResponse != null ? appendResponse.isSuccess() : false);

                return appendResponse;

            } catch (Exception e) {
                log.warn("Failed to send AppendEntries to {}: {}", peer.getAddress(), e.getMessage());
                // Return default response (not successful)
                return AppendEntriesResponse.builder()
                        .term(request.getTerm())
                        .success(false)
                        .build();
            }
        }, executorService);
    }

    /**
     * Send RequestVote to all peers and collect responses
     */
    public List<CompletableFuture<RequestVoteResponse>> sendRequestVoteToAll(
            List<RaftNodeConfig.NodeInfo> peers, RequestVoteRequest request) {

        return peers.stream()
                .map(peer -> sendRequestVote(peer, request))
                .collect(Collectors.toList());
    }

    /**
     * Send AppendEntries to all peers and collect responses
     */
    public List<CompletableFuture<AppendEntriesResponse>> sendAppendEntriesToAll(
            List<RaftNodeConfig.NodeInfo> peers, AppendEntriesRequest request) {

        return peers.stream()
                .map(peer -> sendAppendEntries(peer, request))
                .collect(Collectors.toList());
    }

    /**
     * Shutdown the network client
     */
    public void shutdown() {
        executorService.shutdown();
        log.info("Raft network client shutdown");
    }
}