package com.distributedmq.metadata.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for consistency check operations
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsistencyCheckResponse {

    private String status; // CONSISTENT, INCONSISTENT
    private Integer brokersChecked;
    private Integer topicsChecked;
    private Long checkTimestamp;
    private String details;
}