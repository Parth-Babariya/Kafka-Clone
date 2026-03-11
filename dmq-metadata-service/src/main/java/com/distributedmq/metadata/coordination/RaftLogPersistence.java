package com.distributedmq.metadata.coordination;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Raft Log Persistence Layer
 * Handles durable storage of Raft log entries
 */
@Slf4j
@Component
public class RaftLogPersistence {

    private static final String LOG_FILE_NAME = "raft.log";
    private static final String LOG_METADATA_FILE_NAME = "raft-log.metadata";
    private static final String RAFT_STATE_FILE_NAME = "raft-state.metadata";

    private final String logDir;
    private final ConcurrentNavigableMap<Long, RaftLogEntry> logEntries;
    private volatile long lastLogIndex;
    private volatile long lastLogTerm;
    private volatile long commitIndex;

    public RaftLogPersistence(@org.springframework.beans.factory.annotation.Value("${kraft.raft.log-dir:./data/raft-log}") String logDir) {
        this.logDir = logDir;
        this.logEntries = new ConcurrentSkipListMap<>();
        this.lastLogIndex = 0;
        this.lastLogTerm = 0;
        this.commitIndex = 0;
    }

    @PostConstruct
    public void init() {
        try {
            createLogDirectory();
            loadPersistedLog();
            log.info("Raft log persistence initialized. Last log index: {}, Last log term: {}",
                    lastLogIndex, lastLogTerm);
        } catch (Exception e) {
            log.error("Failed to initialize Raft log persistence", e);
            throw new RuntimeException("Raft log initialization failed", e);
        }
    }

    /**
     * Append a new entry to the log
     */
    public synchronized void appendEntry(RaftLogEntry entry) {
        log.debug("Appending log entry at index: {}, term: {}", entry.getIndex(), entry.getTerm());

        // Validate entry
        if (entry.getIndex() != lastLogIndex + 1) {
            throw new IllegalArgumentException(
                String.format("Invalid log index. Expected: %d, Got: %d",
                    lastLogIndex + 1, entry.getIndex()));
        }

        // Store in memory
        logEntries.put(entry.getIndex(), entry);

        // Update metadata
        lastLogIndex = entry.getIndex();
        lastLogTerm = entry.getTerm();

        // Persist entire log to disk (rewrite file)
        persistEntireLog();

        log.debug("Successfully appended log entry: {}", entry);
    }

    /**
     * Append multiple entries to the log (used by followers)
     */
    public synchronized void appendEntries(long startIndex, List<RaftLogEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }

        log.debug("Appending {} log entries starting from index: {}", entries.size(), startIndex);

        // Validate and append entries
        long expectedIndex = startIndex;
        for (RaftLogEntry entry : entries) {
            if (entry.getIndex() != expectedIndex) {
                throw new IllegalArgumentException(
                    String.format("Invalid entry index. Expected: %d, Got: %d",
                        expectedIndex, entry.getIndex()));
            }

            logEntries.put(entry.getIndex(), entry);
            expectedIndex++;
        }

        // Update metadata
        RaftLogEntry lastEntry = entries.get(entries.size() - 1);
        lastLogIndex = lastEntry.getIndex();
        lastLogTerm = lastEntry.getTerm();

        // Persist entire log to disk (rewrite file)
        persistEntireLog();

        log.debug("Successfully appended {} log entries", entries.size());
    }

    /**
     * Get log entry at specific index
     */
    public RaftLogEntry getEntry(long index) {
        return logEntries.get(index);
    }

    /**
     * Get log entries from startIndex to endIndex (inclusive)
     */
    public List<RaftLogEntry> getEntries(long startIndex, long endIndex) {
        if (startIndex > endIndex) {
            return new ArrayList<>();
        }
        return new ArrayList<>(logEntries.subMap(startIndex, true, endIndex, true).values());
    }

    /**
     * Get all entries from startIndex onwards
     */
    public List<RaftLogEntry> getEntriesFrom(long startIndex) {
        return new ArrayList<>(logEntries.tailMap(startIndex).values());
    }

    /**
     * Get the last log index
     */
    public long getLastLogIndex() {
        return lastLogIndex;
    }

    /**
     * Get the last log term
     */
    public long getLastLogTerm() {
        return lastLogTerm;
    }

    public long getTermForIndex(long index) {
        RaftLogEntry entry = logEntries.get(index);
        return entry != null ? entry.getTerm() : 0;
    }

    public synchronized void persistMetadata(RaftPersistentState state) {
        // Update local commit index
        this.commitIndex = state.getCommitIndex();

        File metadataFile = new File(logDir, RAFT_STATE_FILE_NAME);
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(metadataFile))) {
            dos.writeLong(state.getCurrentTerm());
            dos.writeInt(state.getVotedFor() != null ? state.getVotedFor() : -1);
            dos.writeLong(state.getCommitIndex());
        } catch (IOException e) {
            log.error("Failed to persist Raft state", e);
            throw new RuntimeException("Raft state persistence failed", e);
        }
    }

    public RaftPersistentState loadPersistedState() {
        File metadataFile = new File(logDir, RAFT_STATE_FILE_NAME);
        if (!metadataFile.exists()) {
            return null;
        }

        try (DataInputStream dis = new DataInputStream(new FileInputStream(metadataFile))) {
            long currentTerm = dis.readLong();
            int votedFor = dis.readInt();
            long commitIndex = dis.readLong();

            return RaftPersistentState.builder()
                    .currentTerm(currentTerm)
                    .votedFor(votedFor != -1 ? votedFor : null)
                    .commitIndex(commitIndex)
                    .build();
        } catch (IOException e) {
            log.error("Failed to load persisted Raft state", e);
            return null;
        }
    }

    /**
     * Get current commit index
     */
    public long getCommitIndex() {
        return commitIndex;
    }

    /**
     * Update commit index
     */
    public synchronized void updateCommitIndex(long newCommitIndex) {
        if (newCommitIndex > commitIndex && newCommitIndex <= lastLogIndex) {
            commitIndex = newCommitIndex;
            persistMetadata();
            log.debug("Updated commit index to: {}", commitIndex);
        }
    }

    /**
     * Check if log contains entry at given index and term
     */
    public boolean containsEntry(long index, long term) {
        RaftLogEntry entry = logEntries.get(index);
        return entry != null && entry.getTerm() == term;
    }

    /**
     * Remove entries from log starting from given index
     * Used when receiving conflicting entries from leader
     */
    public synchronized void truncateFrom(long fromIndex) {
        if (fromIndex > lastLogIndex) {
            return; // Nothing to truncate
        }

        // Remove entries from memory
        logEntries.tailMap(fromIndex).clear();

        // Update metadata
        if (fromIndex > 1) {
            RaftLogEntry lastRemaining = logEntries.get(fromIndex - 1);
            if (lastRemaining != null) {
                lastLogIndex = lastRemaining.getIndex();
                lastLogTerm = lastRemaining.getTerm();
            }
        } else {
            lastLogIndex = 0;
            lastLogTerm = 0;
        }

        // Ensure commit index doesn't exceed last log index
        if (commitIndex > lastLogIndex) {
            commitIndex = lastLogIndex;
        }

        // Persist changes
        persistMetadata();
        persistEntireLog();

        log.info("Truncated log from index {} onwards. New last index: {}", fromIndex, lastLogIndex);
    }

    /**
     * Get log entries for AppendEntries RPC
     */
    public List<RaftLogEntry> getEntriesForAppend(long prevLogIndex, long maxEntries) {
        long startIndex = prevLogIndex + 1;
        long endIndex = Math.min(lastLogIndex, startIndex + maxEntries - 1);

        if (startIndex > endIndex) {
            return new ArrayList<>();
        }

        return getEntries(startIndex, endIndex);
    }

    private void createLogDirectory() throws IOException {
        Path logPath = Paths.get(logDir);
        if (!Files.exists(logPath)) {
            Files.createDirectories(logPath);
            log.info("Created Raft log directory: {}", logDir);
        }
    }

    private void loadPersistedLog() throws IOException, ClassNotFoundException {
        File metadataFile = new File(logDir, LOG_METADATA_FILE_NAME);
        if (metadataFile.exists()) {
            loadLogMetadata(metadataFile);
        }

        File logFile = new File(logDir, LOG_FILE_NAME);
        if (logFile.exists()) {
            loadLogEntries(logFile);
        }

        log.info("Loaded persisted Raft log. Last index: {}, Commit index: {}", lastLogIndex, commitIndex);
    }

    private void loadLogMetadata(File metadataFile) throws IOException {
        try (DataInputStream dis = new DataInputStream(new FileInputStream(metadataFile))) {
            lastLogIndex = dis.readLong();
            lastLogTerm = dis.readLong();
            commitIndex = dis.readLong();
        } catch (EOFException e) {
            log.warn("Corrupted or incomplete log metadata file (EOF), starting fresh: {}", e.getMessage());
            // Reset to defaults for corrupted file
            lastLogIndex = 0;
            lastLogTerm = 0;
            commitIndex = 0;
            // Delete the corrupted file so it doesn't cause issues next time
            if (metadataFile.exists()) {
                metadataFile.delete();
            }
        } catch (IOException e) {
            log.warn("Corrupted or incomplete log metadata file (IO), starting fresh: {}", e.getMessage());
            // Reset to defaults for corrupted file
            lastLogIndex = 0;
            lastLogTerm = 0;
            commitIndex = 0;
            // Delete the corrupted file so it doesn't cause issues next time
            if (metadataFile.exists()) {
                metadataFile.delete();
            }
        }
        log.debug("Loaded log metadata: lastLogIndex={}, lastLogTerm={}, commitIndex={}",
                lastLogIndex, lastLogTerm, commitIndex);
    }

    private void loadLogEntries(File logFile) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(logFile))) {
            while (true) {
                try {
                    RaftLogEntry entry = (RaftLogEntry) ois.readObject();
                    logEntries.put(entry.getIndex(), entry);
                } catch (EOFException e) {
                    break; // End of file
                } catch (ClassNotFoundException | IOException e) {
                    log.warn("Corrupted log entry in file, stopping load: {}", e.getMessage());
                    break; // Stop loading on corruption
                }
            }
        } catch (IOException e) {
            log.warn("Corrupted or incomplete log file, starting fresh: {}", e.getMessage());
            // Clear any partially loaded entries
            logEntries.clear();
            // Delete the corrupted file so it doesn't cause issues next time
            if (logFile.exists()) {
                logFile.delete();
            }
        }
        log.debug("Loaded {} log entries from disk", logEntries.size());
    }

    private synchronized void persistEntireLog() {
        File logFile = new File(logDir, LOG_FILE_NAME);
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(logFile, false))) {
            for (RaftLogEntry entry : logEntries.values()) {
                oos.writeObject(entry);
            }
        } catch (IOException e) {
            log.error("Failed to persist entire log", e);
            throw new RuntimeException("Log persistence failed", e);
        }
    }

    private synchronized void persistMetadata() {
        File metadataFile = new File(logDir, LOG_METADATA_FILE_NAME);
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(metadataFile))) {
            dos.writeLong(lastLogIndex);
            dos.writeLong(lastLogTerm);
            dos.writeLong(commitIndex);
        } catch (IOException e) {
            log.error("Failed to persist log metadata", e);
            throw new RuntimeException("Log metadata persistence failed", e);
        }
    }


}