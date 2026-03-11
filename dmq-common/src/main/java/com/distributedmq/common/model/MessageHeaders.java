package com.distributedmq.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Message headers for metadata
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageHeaders implements Serializable {
    private static final long serialVersionUID = 1L;

    @Builder.Default
    private Map<String, String> headers = new HashMap<>();

    public void addHeader(String key, String value) {
        headers.put(key, value);
    }

    public String getHeader(String key) {
        return headers.get(key);
    }
}
