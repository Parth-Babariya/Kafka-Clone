package com.distributedmq.storage;

import com.distributedmq.common.model.Message;
import com.distributedmq.storage.config.StorageConfig;
import com.distributedmq.storage.wal.LogIndex;
import com.distributedmq.storage.wal.LogSegment;
import com.distributedmq.storage.wal.WriteAheadLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for segment index functionality
 */
public class IndexTest {

    @TempDir
    Path tempDir;

    @Test
    void testLogIndexCreationAndLookup() throws Exception {
        // Create index with aggressive indexing for testing
        LogIndex index = new LogIndex(tempDir, 0, 100, 1); // Index every 100 bytes or every message

        // Index some messages
        index.indexMessage(0, 0, 100);
        index.indexMessage(1, 100, 100);
        index.indexMessage(5, 500, 100); // Sparse indexing

        // Test lookups
        assertEquals(0, index.findPosition(0)); // Exact match
        assertEquals(100, index.findPosition(1)); // Exact match
        assertEquals(100, index.findPosition(2)); // Should find floor (1)
        assertEquals(500, index.findPosition(5)); // Exact match
        assertEquals(500, index.findPosition(10)); // Should find floor (5)

        index.close();
    }

    @Test
    void testIndexPersistence() throws Exception {
        Path testDir = tempDir.resolve("test-index");
        testDir.toFile().mkdirs();

        // Create and populate index with aggressive indexing
        LogIndex index1 = new LogIndex(testDir, 0, 100, 1);
        index1.indexMessage(0, 0, 100);
        index1.indexMessage(2, 200, 100);
        index1.close();

        // Load index from disk
        LogIndex index2 = new LogIndex(testDir, 0, 100, 1);
        assertEquals(0, index2.findPosition(0));
        assertEquals(0, index2.findPosition(1));
        assertEquals(200, index2.findPosition(2));
        index2.close();

        // Verify index file exists
        File indexFile = new File(testDir.toFile(), "00000000000000000000.index");
        assertTrue(indexFile.exists());
        assertTrue(indexFile.length() > 0);
    }

    @Test
    void testSegmentWithIndex() throws Exception {
        StorageConfig config = new StorageConfig();
        config.getBroker().setId(1);
        config.getBroker().setDataDir(tempDir.toString());

        try (WriteAheadLog wal = new WriteAheadLog("test-topic", 0, config)) {
            // Append messages
            for (int i = 0; i < 10; i++) {
                Message msg = Message.builder()
                        .key("key" + i)
                        .value(("value" + i).getBytes())
                        .topic("test-topic")
                        .partition(0)
                        .timestamp(System.currentTimeMillis())
                        .build();
                wal.append(msg);
            }

            // Update high water mark
            wal.updateHighWaterMark(wal.getLogEndOffset());

            // Read from middle - should use index for fast lookup
            List<Message> messages = wal.read(5L, 10);
            assertNotNull(messages);
            assertEquals(5, messages.size()); // offsets 5,6,7,8,9

            // Verify content
            for (int i = 0; i < messages.size(); i++) {
                Message msg = messages.get(i);
                assertEquals(5L + i, msg.getOffset());
                assertEquals("key" + (5 + i), msg.getKey());
            }
        }
    }
}