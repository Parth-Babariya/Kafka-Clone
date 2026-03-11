package com.distributedmq.storage.wal;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Index for a log segment that maps offsets to file positions
 * Uses sparse indexing for memory efficiency
 */
@Slf4j
public class LogIndex implements AutoCloseable {

    private final Path baseDir;
    private final long baseOffset;
    private final File indexFile;

    // In-memory sparse index: offset -> position
    private final ConcurrentNavigableMap<Long, Long> offsetToPosition = new ConcurrentSkipListMap<>();

    // Index entry size: offset (8) + position (8) = 16 bytes
    private static final int INDEX_ENTRY_SIZE = 16;

    // Index every 4KB by default (configurable)
    private static final int DEFAULT_INDEX_INTERVAL_BYTES = 4096;
    // Also index every N messages for reliability
    private static final int DEFAULT_INDEX_INTERVAL_MESSAGES = 4;

    private final int indexIntervalBytes;
    private final int indexIntervalMessages;
    private long messageCount = 0;

    // Track current position in log file for indexing
    private long currentLogPosition = 0;
    private long lastIndexedOffset = -1;

    public LogIndex(Path baseDir, long baseOffset) {
        this(baseDir, baseOffset, DEFAULT_INDEX_INTERVAL_BYTES, DEFAULT_INDEX_INTERVAL_MESSAGES);
    }

    public LogIndex(Path baseDir, long baseOffset, int indexIntervalBytes) {
        this(baseDir, baseOffset, indexIntervalBytes, DEFAULT_INDEX_INTERVAL_MESSAGES);
    }

    public LogIndex(Path baseDir, long baseOffset, int indexIntervalBytes, int indexIntervalMessages) {
        this.baseDir = baseDir;
        this.baseOffset = baseOffset;
        this.indexIntervalBytes = indexIntervalBytes;
        this.indexIntervalMessages = indexIntervalMessages;
        this.indexFile = new File(baseDir.toFile(), String.format("%020d%s",
            baseOffset, ".index"));

        loadExistingIndex();
    }

    /**
     * Load existing index from disk
     */
    private void loadExistingIndex() {
        if (!indexFile.exists()) {
            return;
        }

        try (DataInputStream dis = new DataInputStream(new FileInputStream(indexFile))) {
            while (dis.available() >= INDEX_ENTRY_SIZE) {
                long offset = dis.readLong();
                long position = dis.readLong();
                offsetToPosition.put(offset, position);
            }
            log.debug("Loaded {} index entries for segment {}", offsetToPosition.size(), baseOffset);
        } catch (IOException e) {
            log.error("Failed to load index for segment {}, recreating", baseOffset, e);
            // Clear corrupted index
            offsetToPosition.clear();
        }
    }

    /**
     * Add an index entry when a message is appended
     * @param offset the message offset
     * @param position the position in the log file
     * @param messageSize the size of the message
     */
    public void indexMessage(long offset, long position, int messageSize) {
        currentLogPosition = position + messageSize;
        messageCount++;

        // Add sparse index entry
        if (shouldIndex(offset, position)) {
            offsetToPosition.put(offset, position);
            lastIndexedOffset = offset;

            // Persist to disk
            persistIndexEntry(offset, position);
        }
    }

    /**
     * Check if we should add an index entry at this position
     */
    private boolean shouldIndex(long offset, long position) {
        // Always index the first message
        if (offsetToPosition.isEmpty()) {
            return true;
        }

        // Index if we've processed enough messages
        if (messageCount % indexIntervalMessages == 0) {
            return true;
        }

        // Index if we've moved far enough from the last indexed position
        Long lastPosition = offsetToPosition.get(lastIndexedOffset);
        if (lastPosition != null) {
            return (position - lastPosition) >= indexIntervalBytes;
        }

        return true;
    }

    /**
     * Find the file position for a given offset using the index
     * @param offset the target offset
     * @return the file position, or -1 if not found
     */
    public long findPosition(long offset) {
        // Exact match
        Long exactPosition = offsetToPosition.get(offset);
        if (exactPosition != null) {
            return exactPosition;
        }

        // Find the closest lower offset
        Long floorOffset = offsetToPosition.floorKey(offset);
        if (floorOffset != null) {
            return offsetToPosition.get(floorOffset);
        }

        // No index entries yet
        return -1;
    }

    /**
     * Get the relative offset within the segment for a given absolute offset
     */
    public long getRelativeOffset(long absoluteOffset) {
        return absoluteOffset - baseOffset;
    }

    /**
     * Persist a single index entry to disk
     */
    private void persistIndexEntry(long offset, long position) {
        try (RandomAccessFile raf = new RandomAccessFile(indexFile, "rw")) {
            // Seek to end of file
            raf.seek(raf.length());

            // Write offset and position
            raf.writeLong(offset);
            raf.writeLong(position);

        } catch (IOException e) {
            log.error("Failed to persist index entry for offset {} in segment {}", offset, baseOffset, e);
        }
    }

    /**
     * Flush index to disk
     */
    public void flush() {
        // Index entries are written immediately, so no additional flush needed
        // But we could add batching here in the future
    }

    /**
     * Get the base offset of this index
     */
    public long getBaseOffset() {
        return baseOffset;
    }

    /**
     * Get the number of index entries
     */
    public int size() {
        return offsetToPosition.size();
    }

    /**
     * Check if the index is empty
     */
    public boolean isEmpty() {
        return offsetToPosition.isEmpty();
    }

    /**
     * Get the last indexed offset
     */
    public long getLastIndexedOffset() {
        return lastIndexedOffset;
    }

    /**
     * Get the current log position
     */
    public long getCurrentLogPosition() {
        return currentLogPosition;
    }

    @Override
    public void close() throws IOException {
        // Ensure any pending entries are flushed
        flush();
    }

    // TODO: Add index compaction/cleanup for old entries
    // TODO: Add index corruption detection and recovery
}