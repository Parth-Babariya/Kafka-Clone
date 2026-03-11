package com.distributedmq.common.dto;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.util.Base64;

/**
 * Custom serializer to convert byte arrays to Base64 JSON strings
 */
public class Base64ByteArraySerializer extends JsonSerializer<byte[]> {
    
    @Override
    public void serialize(byte[] value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null || value.length == 0) {
            gen.writeString("");
        } else {
            String base64Value = Base64.getEncoder().encodeToString(value);
            gen.writeString(base64Value);
        }
    }
}
