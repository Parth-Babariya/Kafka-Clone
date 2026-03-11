package com.distributedmq.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utility for partitioning logic
 */
public class PartitionUtil {

    /**
     * Calculate partition based on key hash
     */
    public static int getPartition(String key, int numPartitions) {
        if (key == null || key.isEmpty()) {
            return (int) (System.currentTimeMillis() % numPartitions);
        }
        
        int hash = Math.abs(key.hashCode());
        return hash % numPartitions;
    }

    /**
     * Murmur2 hash algorithm (similar to Kafka)
     */
    public static int murmur2(byte[] data) {
        int length = data.length;
        int seed = 0x9747b28c;
        
        int m = 0x5bd1e995;
        int r = 24;
        
        int h = seed ^ length;
        int length4 = length / 4;
        
        for (int i = 0; i < length4; i++) {
            int i4 = i * 4;
            int k = (data[i4] & 0xff) + ((data[i4 + 1] & 0xff) << 8) +
                    ((data[i4 + 2] & 0xff) << 16) + ((data[i4 + 3] & 0xff) << 24);
            k *= m;
            k ^= k >>> r;
            k *= m;
            h *= m;
            h ^= k;
        }
        
        return Math.abs(h);
    }

    // TODO: Add round-robin partitioning
    // TODO: Add custom partitioner support
}
