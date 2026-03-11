# Testing Continuous Consumer Group Consumption in GUI

## Prerequisites
1. All 3 metadata services running (ports 9091, 9092, 9093)
2. All 3 storage brokers running (ports 8081, 8082, 8083)
3. GUI Client launched (`run-gui.bat`)

## Test Scenario: Continuous Consumer Group with Real-time Message Delivery

### Step 1: Setup - Create Topic
1. Go to **Topics** tab
2. Enter:
   - **Topic Name**: `live-orders`
   - **Partitions**: `3`
   - **Replication Factor**: `2`
3. Click **Create Topic**
4. Verify: "Topic created successfully" in output

### Step 2: Start Continuous Consumer Group
1. Go to **Consumer** tab
2. Enter:
   - **Topic**: `live-orders`
   - **Group ID / App ID**: `order-processor-1`
   - **Max Messages**: `50` (per poll)
3. Click **"Consume (Group - Continuous)"** button
4. Observe output:
   ```
   [INFO] Starting continuous consumer... Press 'Stop' to terminate.
   
   ========================================
   Consumer Group Consumption
   ========================================
   Topic:         live-orders
   App ID:        order-processor-1
   
   Subscribing to topic and joining consumer group...
   [OK] Successfully joined consumer group
   
   Poll #1...
   (waiting for messages...)
   ```

**Important**: The consumer is now:
- ✅ Connected to metadata service (found consumer group)
- ✅ Joined consumer group via group leader broker
- ✅ Received partition assignments (e.g., P0, P1, P2)
- ✅ Sending heartbeat + offset commits every 3 seconds
- ✅ Continuously polling for new messages

### Step 3: Produce Messages While Consumer is Running

**Keep the consumer running**, then:

1. Go to **Producer** tab (in the same GUI or open another GUI instance)
2. Enter:
   - **Topic**: `live-orders`
   - **Key**: `order-1`
   - **Value**: `{"orderId": 1, "amount": 100}`
   - **Acks**: `1 (Leader)`
3. Click **Produce Single Message**
4. **Immediately check Consumer tab output** - You should see:
   ```
   Poll #2...
     Received 1 messages:
       P0 | Offset: 0 | 2025-11-23 00:55:30 | Key: order-1 | Value: {"orderId": 1, "amount": 100}
   ```

### Step 4: Produce Multiple Messages Rapidly

1. In **Producer** tab, quickly produce several messages:
   - `order-2`, `{"orderId": 2, "amount": 200}`
   - `order-3`, `{"orderId": 3, "amount": 300}`
   - `order-4`, `{"orderId": 4, "amount": 400}`
   - `order-5`, `{"orderId": 5, "amount": 500}`

2. Watch Consumer output update in real-time:
   ```
   Poll #3...
     Received 4 messages:
       P0 | Offset: 1 | Key: order-2 | Value: {"orderId": 2, "amount": 200}
       P1 | Offset: 0 | Key: order-3 | Value: {"orderId": 3, "amount": 300}
       P2 | Offset: 0 | Key: order-4 | Value: {"orderId": 4, "amount": 400}
       P0 | Offset: 2 | Key: order-5 | Value: {"orderId": 5, "amount": 500}
   ```

**Observation**: Messages are distributed across partitions (P0, P1, P2) based on key hashing.

### Step 5: Test Batch Production

1. Create a batch file `test-batch.txt`:
   ```
   order-10:{"orderId": 10, "amount": 1000}
   order-11:{"orderId": 11, "amount": 1100}
   order-12:{"orderId": 12, "amount": 1200}
   order-13:{"orderId": 13, "amount": 1300}
   order-14:{"orderId": 14, "amount": 1400}
   order-15:{"orderId": 15, "amount": 1500}
   ```

2. In **Producer** tab:
   - Click **Produce Batch (File)**
   - Select `test-batch.txt`
   - Click Open

3. Watch consumer output show all 6 messages appear:
   ```
   Poll #4...
     Received 6 messages:
       P1 | Offset: 1 | Key: order-10 | Value: {"orderId": 10, "amount": 1000}
       P2 | Offset: 1 | Key: order-11 | Value: {"orderId": 11, "amount": 1100}
       ...
   ```

### Step 6: Test Multiple Consumers in Same Group

**Open a second GUI instance** (or use CLI):

**GUI Method**:
1. Launch another `run-gui.bat`
2. Go to Consumer tab
3. Use **same Group ID**: `order-processor-1`
4. Same Topic: `live-orders`
5. Click **"Consume (Group - Continuous)"**

**CLI Method**:
```powershell
java -jar dmq-client/target/mycli.jar consume-group --topic live-orders --app-id order-processor-1 --continuous --metadata-url http://localhost:9092
```

**Expected Behavior**:
- **Rebalancing** will occur
- Partitions will be redistributed between the 2 consumers
- Example:
  - Consumer 1: Gets P0, P1
  - Consumer 2: Gets P2
- Each consumer only receives messages from its assigned partitions

**Test**: Produce more messages and observe that different consumers handle different messages.

### Step 7: Stop Consumer Gracefully

1. In GUI, click **"Stop"** button (red button next to consume buttons)
2. Observe output:
   ```
   [STOPPED] Consumer process terminated
   [INFO] Consumer stopped gracefully
   ```

3. Consumer:
   - Sends leave-group request to group leader
   - Stops heartbeat thread
   - Commits final offsets
   - Exits cleanly

4. **If second consumer is still running**, it will:
   - Detect first consumer left (via heartbeat timeout)
   - Trigger rebalancing
   - Get reassigned all partitions (P0, P1, P2)

### Step 8: Verify Offset Commits

1. Stop all consumers
2. Start a new consumer with **same Group ID**:
   - Topic: `live-orders`
   - Group ID: `order-processor-1`
3. Click **"Consume (Group - Continuous)"**

**Expected**: Consumer resumes from last committed offsets (won't re-read old messages)

### Step 9: Different App ID = Different Group

1. Start consumer with **different App ID**:
   - Topic: `live-orders`
   - Group ID: `analytics-processor` (NEW)
2. Click **"Consume (Group - Continuous)"**

**Expected**: This consumer reads from offset 0 (beginning) because it's a NEW group with independent offset tracking.

## What You're Testing

### ✅ Consumer Group Features:
1. **Automatic Partition Assignment**: Consumers automatically get assigned partitions
2. **Rebalancing**: Adding/removing consumers triggers rebalancing
3. **Offset Management**: Offsets committed via heartbeat every 3-5 seconds
4. **Heartbeat**: Consumer sends heartbeat every 3 seconds to stay alive
5. **Real-time Consumption**: New messages appear immediately (1-second poll interval)
6. **Graceful Shutdown**: Leave group cleanly when stopped

### ✅ Backend Operations Visible:
```
Consumer subscribes
    ↓
Metadata Service: Find/create group (topic + appId)
    ↓
Returns: Group leader broker
    ↓
Consumer joins group at leader broker
    ↓
Leader assigns partitions
    ↓
Consumer polls assigned partitions
    ↓
Heartbeat thread sends offsets every 3s
    ↓
New messages fetched in real-time
    ↓
Stop → Leave group → Clean exit
```

## Troubleshooting

### Consumer Stuck at "Subscribing..."
- **Check**: Metadata service leader
- **Fix**: Use correct metadata URL (e.g., `http://localhost:9092` if Node 2 is leader)
- **Command**: Click "Get Raft Leader" button to find active leader

### No Messages Appearing
- **Check**: Are messages being produced to correct topic?
- **Check**: Consumer might be reading from "latest" offset (only new messages)
- **Fix**: Produce new messages while consumer is running

### "Error: 503 Service Unavailable"
- **Cause**: Querying a follower node instead of leader
- **Fix**: Update Metadata Service URL to leader node

### Multiple Consumers Not Rebalancing
- **Check**: Are they using the same App ID?
- **Check**: Ensure all use same metadata service URL
- **Wait**: Rebalancing may take 5-10 seconds

## Success Criteria

✅ Consumer successfully joins group  
✅ Partition assignments displayed  
✅ New messages appear in real-time as they're produced  
✅ Multiple consumers with same App ID share partitions  
✅ Offsets are committed (verified by restart test)  
✅ Stop button cleanly terminates consumer  
✅ GUI shows streaming output without freezing  

## Advanced Testing

### Test Failover:
1. Start 2 consumers in same group
2. Stop one consumer
3. Observe remaining consumer gets all partitions after rebalance

### Test Throughput:
1. Start consumer
2. Use batch production to send 100 messages rapidly
3. Observer consumer processes all messages in a few polls

### Test Pretty Mode:
1. Enable "Pretty Mode" checkbox
2. Output shows only essential info (no logs, timestamps, decorations)
3. Cleaner, more readable output
