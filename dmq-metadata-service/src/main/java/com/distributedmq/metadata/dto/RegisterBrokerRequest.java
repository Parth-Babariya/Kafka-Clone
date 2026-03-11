package com.distributedmq.metadata.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for registering a broker
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterBrokerRequest {

    private Integer id;
    private String host;
    private Integer port;
}