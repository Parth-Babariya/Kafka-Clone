package com.distributedmq.storage;

import com.distributedmq.common.model.Message;
import com.distributedmq.storage.config.StorageConfig;
import com.distributedmq.storage.wal.WriteAheadLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic integration test for WAL reading functionality
 */
public class ConsumeFlowTest {

    @TempDir
    Path tempDir;

    private WriteAheadLog createWal() {
        StorageConfig config = new StorageConfig();
        config.getBroker().setId(1);
        config.getBroker().setDataDir(tempDir.toString());

        return new WriteAheadLog("test-topic", 0, config);
    }

    @Test
    void testWalReadEmpty() {
        // Test reading from empty WAL
        try (WriteAheadLog wal = createWal()) {
            List<Message> messages = wal.read(0L, 10);
            assertNotNull(messages);
            assertTrue(messages.isEmpty());
        } catch (Exception e) {
            fail("Test failed with exception: " + e.getMessage());
        }
    }

    @Test
    void testWalAppendAndRead() {
        // Append some messages
        try (WriteAheadLog wal = createWal()) {
            Message msg1 = Message.builder()
                    .key("key1")
                    .value("value1".getBytes())
                    .topic("test-topic")
                    .partition(0)
                    .timestamp(System.currentTimeMillis())
                    .build();

            Message msg2 = Message.builder()
                    .key("key2")
                    .value("value2".getBytes())
                    .topic("test-topic")
                    .partition(0)
                    .timestamp(System.currentTimeMillis())
                    .build();

            long offset1 = wal.append(msg1);
            long offset2 = wal.append(msg2);

            assertEquals(0L, offset1);
            assertEquals(1L, offset2);

            // Update high water mark to allow reading
            wal.updateHighWaterMark(wal.getLogEndOffset());

            // Read from offset 0
            List<Message> messages = wal.read(0L, 10);
            assertNotNull(messages);
            assertEquals(2, messages.size());

            // Verify message content
            Message readMsg1 = messages.get(0);
            assertEquals("key1", readMsg1.getKey());
            assertEquals("value1", new String(readMsg1.getValue()));
            assertEquals(0L, readMsg1.getOffset());

            Message readMsg2 = messages.get(1);
            assertEquals("key2", readMsg2.getKey());
            assertEquals("value2", new String(readMsg2.getValue()));
            assertEquals(1L, readMsg2.getOffset());
        } catch (Exception e) {
            fail("Test failed with exception: " + e.getMessage());
        }
    }

    @Test
    void testWalReadFromOffset() {
        // Append 3 messages
        try (WriteAheadLog wal = createWal()) {
            for (int i = 0; i < 3; i++) {
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

            // Read from offset 1 (should get messages 1 and 2)
            List<Message> messages = wal.read(1L, 10);
            assertNotNull(messages);
            assertEquals(2, messages.size());

            assertEquals(1L, messages.get(0).getOffset());
            assertEquals(2L, messages.get(1).getOffset());
        } catch (Exception e) {
            fail("Test failed with exception: " + e.getMessage());
        }
    }

    @Test
    void testWalReadWithMaxMessages() {
        // Append 5 messages
        try (WriteAheadLog wal = createWal()) {
            for (int i = 0; i < 5; i++) {
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

            // Read with maxMessages = 2
            List<Message> messages = wal.read(0L, 2);
            assertNotNull(messages);
            assertEquals(2, messages.size());

            assertEquals(0L, messages.get(0).getOffset());
            assertEquals(1L, messages.get(1).getOffset());
        } catch (Exception e) {
            fail("Test failed with exception: " + e.getMessage());
        }
    }
}