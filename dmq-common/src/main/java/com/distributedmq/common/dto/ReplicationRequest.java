package com.distributedmq.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request to replicate messages to follower brokers
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplicationRequest {
    private String topic;
    private Integer partition;
    private List<ProduceRequest.ProduceMessage> messages; // Messages to replicate
    private Long baseOffset; // Base offset of the batch
    private Integer leaderId; // Leader broker ID
    private Long leaderEpoch; // Leader epoch for validation

    // Leader's current high water mark for lag calculation
    private Long leaderHighWaterMark;
    
    // Phase 1: ISR Lag Monitoring - Leader's Log End Offset for lag calculation
    private Long leaderLogEndOffset; // Leader's LEO so follower can calculate lag

    // Replication metadata
    private Long timeoutMs;
    private Integer requiredAcks; // Usually 1 for replication (leader ack)
}