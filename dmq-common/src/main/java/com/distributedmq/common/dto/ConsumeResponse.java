package com.distributedmq.common.dto;

import com.distributedmq.common.model.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response to a consume request
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsumeResponse {
    private List<Message> messages;
    private Long highWaterMark;
    private String errorMessage;
    private boolean success;

    // TODO: Add throttle information
    // TODO: Add partition lag information
}
