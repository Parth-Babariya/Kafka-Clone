package com.distributedmq.storage.wal;

import com.distributedmq.common.model.Message;
import com.distributedmq.storage.config.StorageConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Write-Ahead Log implementation
 * Provides durable message storage with segment-based files
 */
@Slf4j
public class WriteAheadLog implements AutoCloseable {

    private final String topic;
    private final Integer partition;

    // NIO package data type for file and directory paths
    private final Path logDirectory;

    // AtomicLong: a thread-safe long value supporting atomic operations without explicit synchronization mechanisms
    private final AtomicLong nextOffset;
    private final AtomicLong highWaterMark;
    private final AtomicLong logEndOffset; // LEO - Log End Offset

    // LogSegment: a portion of the write-ahead log representing a contiguous range of messages stored on disk
    private LogSegment currentSegment;

    // Multi-segment reading infrastructure
    private final List<LogSegment> allSegments = new ArrayList<>();
    private final Map<Long, LogSegment> offsetToSegmentMap = new ConcurrentHashMap<>();
    private final ReadWriteLock segmentsLock = new ReentrantReadWriteLock();

    private final StorageConfig config;

    public WriteAheadLog(String topic, Integer partition, StorageConfig config) {
        this.topic = topic;
        this.partition = partition;
        this.config = config;

        this.logDirectory = Paths.get(config.getBrokerLogsDir(), topic, String.valueOf(partition));
        this.nextOffset = new AtomicLong(StorageConfig.DEFAULT_OFFSET);
        this.highWaterMark = new AtomicLong(StorageConfig.DEFAULT_HIGH_WATER_MARK);
        this.logEndOffset = new AtomicLong(StorageConfig.DEFAULT_LOG_END_OFFSET);

        initializeLog();
    }

    private void initializeLog() {
        try {
            Files.createDirectories(logDirectory);
            
            // Recover from existing segments or create new one
            recoverFromExistingSegments();
            
            // Discover all segments for multi-segment reading
            discoverAndLoadAllSegments();
            
            log.info("Initialized WAL for {}-{} at {} (nextOffset: {}, logEndOffset: {})", 
                    topic, partition, logDirectory, nextOffset.get(), logEndOffset.get());
            
        } catch (IOException e) {
            log.error("Failed to initialize WAL", e);
            throw new RuntimeException("Failed to initialize WAL", e);
        }
    }

    /**
     * Recover WAL state from existing log segments
     */
    private void recoverFromExistingSegments() throws IOException {
        // Find all existing segment files
        File[] segmentFiles = logDirectory.toFile().listFiles((dir, name) -> 
            name.endsWith(StorageConfig.LOG_FILE_EXTENSION));
        
        if (segmentFiles == null || segmentFiles.length == 0) {
            // No existing segments, create new one
            currentSegment = new LogSegment(logDirectory, 0);
            allSegments.add(currentSegment);
            return;
        }
        
        // Sort segments by base offset
        List<LogSegment> segments = new ArrayList<>();
        for (File segmentFile : segmentFiles) {
            String fileName = segmentFile.getName();
            String baseOffsetStr = fileName.substring(0, fileName.length() - StorageConfig.LOG_FILE_EXTENSION.length());
            try {
                long baseOffset = Long.parseLong(baseOffsetStr);
                segments.add(new LogSegment(logDirectory, baseOffset));
            } catch (NumberFormatException e) {
                log.warn("Skipping invalid segment file: {}", fileName);
            }
        }
        
        // Sort segments by base offset
        segments.sort(Comparator.comparingLong(LogSegment::getBaseOffset));
        
        // Validate all segments for consistency
        validateSegmentsConsistency(segments);
        
        // Add validated segments to allSegments
        allSegments.addAll(segments);
        
        // Find the latest valid segment
        LogSegment latestSegment = segments.get(segments.size() - 1);
        currentSegment = latestSegment;
        
        // Recover offsets by validating the entire log
        long recoveredNextOffset = recoverOffsetsFromValidatedSegments(segments);
        
        nextOffset.set(recoveredNextOffset);
        logEndOffset.set(recoveredNextOffset);
        highWaterMark.set(Math.min(highWaterMark.get(), recoveredNextOffset));
        
        log.info("Recovered WAL state: nextOffset={}, logEndOffset={}, highWaterMark={}, segments={}", 
                nextOffset.get(), logEndOffset.get(), highWaterMark.get(), segments.size());
    }

    /**
     * Validate consistency of all log segments
     */
    private void validateSegmentsConsistency(List<LogSegment> segments) throws IOException {
        if (segments.isEmpty()) {
            return;
        }
        
        long expectedBaseOffset = 0;
        long previousLastOffset = -1;
        
        for (int i = 0; i < segments.size(); i++) {
            LogSegment segment = segments.get(i);
            long segmentBaseOffset = segment.getBaseOffset();
            
            // Validate base offset continuity
            if (i == 0) {
                if (segmentBaseOffset != 0) {
                    log.warn("First segment base offset is not 0: {}", segmentBaseOffset);
                }
            } else {
                if (segmentBaseOffset != expectedBaseOffset) {
                    log.error("Segment base offset discontinuity: expected {}, found {}", 
                             expectedBaseOffset, segmentBaseOffset);
                    throw new IOException("Inconsistent segment base offsets in WAL recovery");
                }
            }
            
            // Validate segment content and get last offset
            long lastOffset = segment.getLastOffset();
            if (lastOffset >= 0) {
                // Validate offset continuity within segment
                if (lastOffset < segmentBaseOffset) {
                    log.error("Segment last offset {} is less than base offset {}", 
                             lastOffset, segmentBaseOffset);
                    throw new IOException("Invalid offset range in segment");
                }
                
                // Validate continuity between segments
                if (previousLastOffset >= 0 && segmentBaseOffset != (previousLastOffset + 1)) {
                    log.error("Segment continuity broken: previous last offset {}, current base offset {}", 
                             previousLastOffset, segmentBaseOffset);
                    throw new IOException("Broken continuity between segments");
                }
                
                previousLastOffset = lastOffset;
                expectedBaseOffset = lastOffset + 1;
            } else {
                // Empty segment
                expectedBaseOffset = segmentBaseOffset;
            }
            
            log.debug("Validated segment {}: baseOffset={}, lastOffset={}", 
                     segmentBaseOffset, segmentBaseOffset, lastOffset);
        }
        
        log.info("Successfully validated {} segments for consistency", segments.size());
    }

    /**
     * Recover next offset from validated segments
     */
    private long recoverOffsetsFromValidatedSegments(List<LogSegment> segments) throws IOException {
        if (segments.isEmpty()) {
            return 0;
        }
        
        // Start from the last segment
        LogSegment lastSegment = segments.get(segments.size() - 1);
        long lastOffset = lastSegment.getLastOffset();
        
        if (lastOffset >= 0) {
            return lastOffset + 1;
        } else {
            // Empty last segment, use its base offset
            return lastSegment.getBaseOffset();
        }
    }

    /**
     * Discover and load all segments for multi-segment reading
     */
    private void discoverAndLoadAllSegments() throws IOException {
        segmentsLock.writeLock().lock();
        try {
            // Don't clear allSegments, as it may contain segments from recovery
            offsetToSegmentMap.clear();
            
            // Find all .log files
            File[] segmentFiles = logDirectory.toFile().listFiles((dir, name) -> 
                name.endsWith(StorageConfig.LOG_FILE_EXTENSION));
            
            if (segmentFiles != null) {
                // Sort by base offset (filename)
                java.util.Arrays.sort(segmentFiles, Comparator.comparing(File::getName));
                
                for (File file : segmentFiles) {
                    String baseOffsetStr = file.getName().substring(0, 
                        file.getName().length() - StorageConfig.LOG_FILE_EXTENSION.length());
                    try {
                        long baseOffset = Long.parseLong(baseOffsetStr);
                        
                        // Check if segment already exists (from recovery)
                        LogSegment segment = allSegments.stream()
                            .filter(s -> s.getBaseOffset() == baseOffset)
                            .findFirst()
                            .orElse(null);
                        
                        if (segment == null) {
                            // Create new segment
                            segment = new LogSegment(logDirectory, baseOffset);
                            allSegments.add(segment);
                        }
                        
                        // Build offset-to-segment mapping using current lastOffset
                        long lastOffset = segment.getLastOffset();
                        if (lastOffset >= 0) {
                            // Map all offsets from base to last
                            for (long offset = baseOffset; offset <= lastOffset; offset++) {
                                offsetToSegmentMap.put(offset, segment);
                            }
                        }
                    } catch (NumberFormatException e) {
                        log.warn("Skipping invalid segment file: {}", file.getName());
                    } catch (IOException e) {
                        log.warn("Error loading segment {}, skipping", file.getName(), e);
                    }
                }
            }
            
            log.info("Discovered {} segments for {}-{}", allSegments.size(), topic, partition);
        } finally {
            segmentsLock.writeLock().unlock();
        }
    }

    /**
     * Append message to log and return assigned offset
     * Step 2: Assigns offsets to messages atomically
     */
    public long append(Message message) {
        synchronized (this) {
            long offset = nextOffset.getAndIncrement();
            message.setOffset(offset);
            
            try {
                // Check if we need to roll to new segment
                if (currentSegment != null && currentSegment.size() >= config.getWal().getSegmentSizeBytes()) {
                    rollToNewSegment();
                }
                
                // Create new segment if this is the first message
                if (currentSegment == null) {
                    currentSegment = new LogSegment(logDirectory, offset);
                    segmentsLock.writeLock().lock();
                    try {
                        allSegments.add(currentSegment);
                        offsetToSegmentMap.put(offset, currentSegment);
                    } finally {
                        segmentsLock.writeLock().unlock();
                    }
                }
                
                currentSegment.append(message);
                
                // Update Log End Offset (LEO)
                logEndOffset.set(offset + 1);
                
                log.debug("Appended message at offset {}", offset);
                
                return offset;
                
            } catch (IOException e) {
                log.error("Failed to append message", e);
                throw new RuntimeException("Failed to append message", e);
            }
        }
    }

    /**
     * Append batch of messages atomically to log
     * Returns list of assigned offsets
     * 
     * Optimization: Writes all messages in single I/O operation for better throughput
     * Assumption: Batch fits in current segment (no segment splits during batch)
     */
    public List<Long> appendBatch(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return new ArrayList<>();
        }
        
        synchronized (this) {
            List<Long> offsets = new ArrayList<>(messages.size());
            
            try {
                // Step 1: Assign offsets to all messages atomically
                for (Message message : messages) {
                    long offset = nextOffset.getAndIncrement();
                    message.setOffset(offset);
                    offsets.add(offset);
                }
                
                long firstOffset = offsets.get(0);
                long lastOffset = offsets.get(offsets.size() - 1);
                
                // Step 2: Check if we need to roll to new segment
                // Note: We check before writing. If batch doesn't fit, we still proceed
                // assuming batch is smaller than segment size (validated by max messages limit)
                if (currentSegment != null && currentSegment.size() >= config.getWal().getSegmentSizeBytes()) {
                    rollToNewSegment();
                }
                
                // Step 3: Create new segment if this is the first batch
                if (currentSegment == null) {
                    currentSegment = new LogSegment(logDirectory, firstOffset);
                    segmentsLock.writeLock().lock();
                    try {
                        allSegments.add(currentSegment);
                        offsetToSegmentMap.put(firstOffset, currentSegment);
                    } finally {
                        segmentsLock.writeLock().unlock();
                    }
                }
                
                // Step 4: Write entire batch atomically
                currentSegment.appendBatch(messages);
                
                // Step 5: Update Log End Offset (LEO) to point after last message
                logEndOffset.set(lastOffset + 1);
                
                log.debug("Appended batch of {} messages, offsets: {} to {}", 
                         messages.size(), firstOffset, lastOffset);
                
                return offsets;
                
            } catch (IOException e) {
                log.error("Failed to append batch of {} messages", messages.size(), e);
                throw new RuntimeException("Failed to append batch", e);
            }
        }
    }

    /**
     * Read messages starting from offset with multi-segment support
     * TODO: Add isolation level support (read_committed vs read_uncommitted)
     * Currently only supports read_committed (filters by HWM)
     */
    public List<Message> read(long startOffset, int maxMessages) {
        List<Message> messages = new ArrayList<>();
        
        segmentsLock.readLock().lock();
        try {
            // Ensure segments are loaded
            if (allSegments.isEmpty()) {
                discoverAndLoadAllSegments();
            }
            
            // Find segment containing startOffset
            LogSegment startSegment = offsetToSegmentMap.get(startOffset);
            if (startSegment == null) {
                // Offset might be in a segment not yet mapped, or offset too old
                startSegment = findSegmentForOffset(startOffset);
            }
            
            if (startSegment == null) {
                log.debug("No segment found for offset {} in {}-{}", startOffset, topic, partition);
                return messages;
            }
            
            // Read across segments until we have enough messages or run out of segments
            LogSegment currentSegment = startSegment;
            long currentOffset = startOffset;
            int segmentsRead = 0;
            int startSegmentIndex = allSegments.indexOf(startSegment);
            
            while (messages.size() < maxMessages && currentSegment != null) {
                try {
                    // Read from current segment
                    List<Message> segmentMessages = currentSegment.readFromOffset(currentOffset, maxMessages - messages.size());
                    
                    // Filter messages that are committed (before HWM) and add to results
                    for (Message message : segmentMessages) {
                        if (message.getOffset() < highWaterMark.get()) {
                            messages.add(message);
                            if (messages.size() >= maxMessages) {
                                break;
                            }
                        }
                    }
                    
                } catch (IOException e) {
                    log.error("Error reading from segment {} in {}-{}, skipping segment", 
                            currentSegment.getBaseOffset(), topic, partition);
                    // Continue to next segment instead of failing completely
                }
                
                // Move to next segment if we need more messages
                if (messages.size() < maxMessages) {
                    segmentsRead++;
                    LogSegment nextSegment = getNextSegment(currentSegment);
                    if (nextSegment != null) {
                        currentSegment = nextSegment;
                        currentOffset = currentSegment.getBaseOffset();
                    } else {
                        currentSegment = null; // No more segments
                    }
                }
            }
            
            log.debug("Read {} messages starting from offset {} across {} segments in {}-{}", 
                    messages.size(), startOffset, segmentsRead, topic, partition);
            
        } catch (Exception e) {
            log.error("Unexpected error during multi-segment read from offset {}: {}", startOffset, e.getMessage());
        } finally {
            segmentsLock.readLock().unlock();
        }
        
        return messages;
    }

    /**
     * Find all log segments for this partition
     */
    private List<LogSegment> findSegmentsForPartition() throws IOException {
        List<LogSegment> segments = new ArrayList<>();
        
        // Check if log directory exists
        if (!Files.exists(logDirectory)) {
            return segments;
        }
        
        // Find all .log files in the partition directory
        File[] segmentFiles = logDirectory.toFile().listFiles((dir, name) -> 
            name.endsWith(StorageConfig.LOG_FILE_EXTENSION));
        
        if (segmentFiles != null) {
            for (File segmentFile : segmentFiles) {
                String fileName = segmentFile.getName();
                String baseOffsetStr = fileName.substring(0, fileName.length() - StorageConfig.LOG_FILE_EXTENSION.length());
                try {
                    long baseOffset = Long.parseLong(baseOffsetStr);
                    segments.add(new LogSegment(logDirectory, baseOffset));
                } catch (NumberFormatException e) {
                    log.warn("Invalid segment file name: {}", fileName);
                }
            }
        }
        
        return segments;
    }

    /**
     * Find the segment that contains the given offset using binary search
     */
    private LogSegment findSegmentForOffset(long offset) {
        if (allSegments.isEmpty()) {
            return null;
        }
        
        // Binary search through segments
        int low = 0;
        int high = allSegments.size() - 1;
        
        while (low <= high) {
            int mid = (low + high) / 2;
            LogSegment segment = allSegments.get(mid);
            
            try {
                long baseOffset = segment.getBaseOffset();
                long lastOffset = segment.getLastOffset();
                
                if (offset >= baseOffset && offset <= lastOffset) {
                    return segment;
                } else if (offset < baseOffset) {
                    high = mid - 1;
                } else {
                    low = mid + 1;
                }
            } catch (IOException e) {
                log.warn("Error reading segment {} for offset lookup, skipping", segment.getBaseOffset());
                // Continue search, this segment might be corrupted
                if (offset < segment.getBaseOffset()) {
                    high = mid - 1;
                } else {
                    low = mid + 1;
                }
            }
        }
        
        return null; // Offset not found in any segment
    }

    /**
     * Get the next segment after the given segment
     */
    private LogSegment getNextSegment(LogSegment currentSegment) {
        int currentIndex = allSegments.indexOf(currentSegment);
        if (currentIndex >= 0 && currentIndex < allSegments.size() - 1) {
            return allSegments.get(currentIndex + 1);
        }
        return null;
    }

    /**
     * Flush pending writes to disk
     */
    public void flush() {
        try {
            if (currentSegment != null) {
                currentSegment.flush();
            }
        } catch (IOException e) {
            log.error("Failed to flush log", e);
        }
    }

    /**
     * Roll to new segment with proper segment tracking
     */
    private void rollToNewSegment() throws IOException {
        if (currentSegment != null) {
            currentSegment.close();
        }
        
        long newBaseOffset = nextOffset.get();
        currentSegment = new LogSegment(logDirectory, newBaseOffset);
        
        // Update segment tracking
        segmentsLock.writeLock().lock();
        try {
            allSegments.add(currentSegment);
            offsetToSegmentMap.put(newBaseOffset, currentSegment);
        } finally {
            segmentsLock.writeLock().unlock();
        }
        
        log.info("Rolled to new segment at offset {} for {}-{}", newBaseOffset, topic, partition);
    }

    public long getHighWaterMark() {
        return highWaterMark.get();
    }

    public void updateHighWaterMark(long offset) {
        highWaterMark.set(offset);
    }

    public long getLogEndOffset() {
        return logEndOffset.get();
    }

    public void updateLogEndOffset(long offset) {
        logEndOffset.set(offset);
    }

    /**
     * Close the WAL and release resources
     */
    @Override
    public void close() throws IOException {
        // Close current segment
        if (currentSegment != null) {
            currentSegment.close();
        }
        
        // Close all segments
        segmentsLock.writeLock().lock();
        try {
            for (LogSegment segment : allSegments) {
                if (segment != currentSegment) { // Already closed above
                    segment.close();
                }
            }
            allSegments.clear();
            offsetToSegmentMap.clear();
        } finally {
            segmentsLock.writeLock().unlock();
        }
    }

    // TODO: Add segment cleanup based on retention policy
    // TODO: Add log compaction

    // TODO: Add index for faster lookups
        // Implement an offset-to-position index to enable O(1) seeks within segments.
        // This index maps message offsets to their byte positions in the log file,
        // allowing efficient random access reads without scanning the entire segment.
        // Use a sparse index (e.g., every 4KB or configurable interval) to balance
        // memory usage and lookup speed. Store index entries in memory and persist
        // to disk for recovery.
}
