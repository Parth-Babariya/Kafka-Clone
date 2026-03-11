package com.distributedmq.metadata.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for broker information
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrokerResponse {

    private Integer id;
    private String host;
    private Integer port;
    private String status; // ONLINE, OFFLINE
    private String address; // host:port
    private Long registeredAt;
}