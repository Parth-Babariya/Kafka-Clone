package com.distributedmq.common.util;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/**
 * Utility for calculating checksums
 */
public class ChecksumUtil {

    /**
     * Calculate CRC32 checksum for byte array
     */
    public static long calculateCRC32(byte[] data) {
        CRC32 crc32 = new CRC32();
        crc32.update(data);
        return crc32.getValue();
    }

    /**
     * Verify CRC32 checksum
     */
    public static boolean verifyCRC32(byte[] data, long expectedChecksum) {
        return calculateCRC32(data) == expectedChecksum;
    }

    // TODO: Add other checksum algorithms
    // TODO: Add performance optimizations
}
