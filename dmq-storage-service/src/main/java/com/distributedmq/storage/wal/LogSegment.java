package com.distributedmq.storage.wal;

import com.distributedmq.common.model.Message;
import com.distributedmq.storage.config.StorageConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.CRC32;

/**
 * Represents a single log segment file
 */
@Slf4j
public class LogSegment implements AutoCloseable {

    private final Path baseDir;
    private final long baseOffset;
    private final File logFile;
    private FileOutputStream fileOutputStream;
    private DataOutputStream dataOutputStream;
    private long currentSize;
    private long lastOffset = -1; // Track last offset in memory
    private LogIndex index; // Index for fast offset lookups

    private static final int INT_BYTES = Integer.BYTES; // always 4 bytes in java

    // Read-write lock for concurrent access: multiple readers, single writer
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    public LogSegment(Path baseDir, long baseOffset) throws IOException {
        this.baseDir = baseDir;
        this.baseOffset = baseOffset;
        this.logFile = new File(baseDir.toFile(), String.format(StorageConfig.LOG_FILE_FORMAT, baseOffset));

        // Create file if it doesn't exist
        logFile.getParentFile().mkdirs();
        logFile.createNewFile();

        this.fileOutputStream = new FileOutputStream(logFile, true);
        this.dataOutputStream = new DataOutputStream(fileOutputStream);
        this.currentSize = logFile.length();

        // Initialize index
        this.index = new LogIndex(baseDir, baseOffset);

        log.debug("Created log segment: {}", logFile.getPath());
    }

    /**
     * Append message to segment
     */
    public void append(Message message) throws IOException {
        rwLock.writeLock().lock();
        try {
            // Get current position before writing
            long position = currentSize;

            // Format: [size][crc][offset][timestamp][key_length][key][value_length][value]
            
            byte[] serialized = serializeMessage(message);
            CRC32 crc = new CRC32();
            crc.update(serialized);
            int checksum = (int) crc.getValue();
            
            dataOutputStream.writeInt(serialized.length + 4); // size includes CRC
            dataOutputStream.writeInt(checksum);
            dataOutputStream.write(serialized);
            
            int totalEntrySize = Integer.BYTES + Integer.BYTES + serialized.length;
            currentSize += totalEntrySize;
            lastOffset = message.getOffset(); // Update last offset in memory

            // Index the message for fast lookups
            index.indexMessage(message.getOffset(), position, totalEntrySize);

        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Append batch of messages atomically to segment
     * 
     * Optimization: Pre-serializes all messages and writes in single I/O operation
     * This reduces system calls and improves throughput significantly
     */
    public void appendBatch(List<Message> messages) throws IOException {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        
        rwLock.writeLock().lock();
        try {
            // Track starting position for indexing
            long startPosition = currentSize;
            
            // Step 1: Pre-serialize all messages and calculate checksums
            List<byte[]> serializedMessages = new ArrayList<>(messages.size());
            List<Integer> checksums = new ArrayList<>(messages.size());
            int totalBatchSize = 0;
            
            for (Message message : messages) {
                byte[] serialized = serializeMessage(message);
                
                CRC32 crc = new CRC32();
                crc.update(serialized);
                int checksum = (int) crc.getValue();
                
                serializedMessages.add(serialized);
                checksums.add(checksum);
                
                // Calculate size: size_field + crc_field + message_data
                totalBatchSize += Integer.BYTES + Integer.BYTES + serialized.length;
            }
            
            // Step 2: Allocate buffer for entire batch
            ByteArrayOutputStream batchBuffer = new ByteArrayOutputStream(totalBatchSize);
            DataOutputStream batchStream = new DataOutputStream(batchBuffer);
            
            // Step 3: Write all messages to buffer
            for (int i = 0; i < messages.size(); i++) {
                byte[] serialized = serializedMessages.get(i);
                int checksum = checksums.get(i);
                
                batchStream.writeInt(serialized.length + 4); // size includes CRC
                batchStream.writeInt(checksum);
                batchStream.write(serialized);
            }
            
            // Step 4: Single write operation to disk (atomic)
            byte[] batchData = batchBuffer.toByteArray();
            dataOutputStream.write(batchData);
            
            // Step 5: Update segment state
            currentSize += batchData.length;
            lastOffset = messages.get(messages.size() - 1).getOffset();
            
            // Step 6: Index all messages for fast lookups
            long position = startPosition;
            for (int i = 0; i < messages.size(); i++) {
                Message message = messages.get(i);
                int messageSize = Integer.BYTES + Integer.BYTES + serializedMessages.get(i).length;
                index.indexMessage(message.getOffset(), position, messageSize);
                position += messageSize;
            }
            
            log.debug("Batch appended {} messages to segment {} (total {} bytes)", 
                     messages.size(), baseOffset, batchData.length);
            
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Flush to disk
     */
    public void flush() throws IOException {
        rwLock.writeLock().lock();
        try {
            dataOutputStream.flush();
            fileOutputStream.getFD().sync();
            index.flush();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Close segment
     */
    @Override
    public void close() throws IOException {
        rwLock.writeLock().lock();
        try {
            closeOutputStreams();
            index.close();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public long size() {
        return currentSize;
    }

    /**
     * Serialize message to bytes
     */
    private byte[] serializeMessage(Message message) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        
        // Write offset
        dos.writeLong(message.getOffset());
        
        // Write timestamp
        dos.writeLong(message.getTimestamp());
        
        // Write key
        if (message.getKey() != null) {
            byte[] keyBytes = message.getKey().getBytes();
            dos.writeInt(keyBytes.length);
            dos.write(keyBytes);
        } else {
            dos.writeInt(StorageConfig.NULL_KEY_LENGTH);
        }
        
        // Write value
        dos.writeInt(message.getValue().length);
        dos.write(message.getValue());
        
        return baos.toByteArray();
    }

    /**
     * Deserialize message from bytes
     */
    private Message deserializeMessage(byte[] data) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
        
        // Read offset
        long offset = dis.readLong();
        
        // Read timestamp
        long timestamp = dis.readLong();
        
        // Read key
        int keyLength = dis.readInt();
        String key = null;
        if (keyLength != StorageConfig.NULL_KEY_LENGTH) {
            byte[] keyBytes = new byte[keyLength];
            dis.readFully(keyBytes);
            key = new String(keyBytes);
        }
        
        // Read value
        int valueLength = dis.readInt();
        byte[] value = new byte[valueLength];
        dis.readFully(value);
        
        return Message.builder()
                .offset(offset)
                .timestamp(timestamp)
                .key(key)
                .value(value)
                .build();
    }

    /**
     * Read the last message from the segment to get the last offset
     */
    public long getLastOffset() throws IOException {
        rwLock.readLock().lock();
        try {
            // If we have the last offset in memory, return it
            if (lastOffset >= 0) {
                return lastOffset;
            }
            
            if (currentSize == 0) {
                return -1; // No messages
            }
            
            try (FileInputStream fis = new FileInputStream(logFile);
                 DataInputStream dis = new DataInputStream(fis)) {
                
                long foundLastOffset = -1;
                while (dis.available() > 0) {
                    int totalLength = dis.readInt();
                    int expectedChecksum = dis.readInt();
                    
                    byte[] messageData = new byte[totalLength - 4]; // exclude CRC
                    dis.readFully(messageData);
                    
                    // Validate checksum
                    CRC32 crc = new CRC32();
                    crc.update(messageData);
                    int actualChecksum = (int) crc.getValue();
                    
                    if (expectedChecksum != actualChecksum) {
                        log.error("Checksum validation failed for message at position in segment {}", logFile.getName());
                        throw new IOException("Corrupted message detected in segment " + logFile.getName());
                    }
                    
                    Message message = deserializeMessage(messageData);
                    foundLastOffset = message.getOffset();
                }
                
                // Update in-memory cache
                lastOffset = foundLastOffset;
                return foundLastOffset;
            }
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Read messages from this segment starting from the given offset
     */
    public List<Message> readFromOffset(long startOffset, int maxMessages) throws IOException {
        rwLock.readLock().lock();
        try {
            List<Message> messages = new ArrayList<>();
            
            if (currentSize == 0) {
                return messages;
            }
            
            // Use index to find starting position
            long startPosition = index.findPosition(startOffset);
            if (startPosition == -1) {
                // No index entry, start from beginning
                startPosition = 0;
            }
            
            try (FileInputStream fis = new FileInputStream(logFile);
                 DataInputStream dis = new DataInputStream(fis)) {
                
                // Seek to the approximate position
                if (startPosition > 0) {
                    fis.skip(startPosition);
                }
                
                while (dis.available() > 0 && messages.size() < maxMessages) {
                    int totalLength = dis.readInt();
                    int expectedChecksum = dis.readInt();
                    
                    byte[] messageData = new byte[totalLength - 4]; // exclude CRC
                    dis.readFully(messageData);
                    
                    // Validate checksum
                    CRC32 crc = new CRC32();
                    crc.update(messageData);
                    int actualChecksum = (int) crc.getValue();
                    
                    if (expectedChecksum != actualChecksum) {
                        log.error("Checksum validation failed for message at offset {} in segment {}", 
                                 startOffset, logFile.getName());
                        throw new IOException("Corrupted message detected in segment " + logFile.getName());
                    }
                    
                    Message message = deserializeMessage(messageData);
                    
                    // Only include messages at or after the start offset
                    if (message.getOffset() >= startOffset) {
                        messages.add(message);
                    }
                }
            }
            
            return messages;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Get the base offset of this segment
     */
    public long getBaseOffset() {
        return baseOffset;
    }

    /**
     * Close output streams to allow reading
     */
    private void closeOutputStreams() throws IOException {
        if (dataOutputStream != null) {
            try {
                dataOutputStream.close();
            } catch (IOException e) {
                log.warn("Failed to close data output stream", e);
            } finally {
                dataOutputStream = null;
            }
        }
        if (fileOutputStream != null) {
            try {
                fileOutputStream.close();
            } catch (IOException e) {
                log.warn("Failed to close file output stream", e);
            } finally {
                fileOutputStream = null;
            }
        }
    }

    // TODO: Add CRC checksum
    // TODO: Add compression
}
