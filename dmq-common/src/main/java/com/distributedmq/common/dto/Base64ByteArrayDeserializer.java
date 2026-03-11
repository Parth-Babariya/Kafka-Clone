package com.distributedmq.common.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.util.Base64;

/**
 * Custom deserializer to convert Base64 JSON strings to byte arrays
 */
public class Base64ByteArrayDeserializer extends JsonDeserializer<byte[]> {
    
    @Override
    public byte[] deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String base64Value = p.getValueAsString();
        if (base64Value == null || base64Value.isEmpty()) {
            return new byte[0];
        }
        return Base64.getDecoder().decode(base64Value);
    }
}
