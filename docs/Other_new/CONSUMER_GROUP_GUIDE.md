# Consumer Group Guide

## Overview

DMQ now supports **consumer groups** for coordinated consumption with automatic partition assignment and rebalancing.

## Two Consumption Modes

### 1. Simple Consumption (Direct Partition Access)
- **Command**: `consume`
- **Use Case**: Direct read from specific partition
- **Features**: Manual partition/offset control
- **Example**:
  ```bash
  mycli consume --topic orders --partition 0 --offset 0 --max-messages 10
  ```

### 2. Consumer Group Consumption (Automatic Assignment)
- **Command**: `consume-group`
- **Use Case**: Multiple consumers coordinating via groups
- **Features**: 
  - Automatic partition assignment
  - Rebalancing when consumers join/leave
  - Offset management via heartbeat
  - Group coordination via metadata service
- **Example**:
  ```bash
  mycli consume-group --topic orders --app-id order-processor --continuous
  ```

## Consumer Group Flow

1. **Subscribe**: Consumer subscribes to topic with app-id
2. **Group Discovery**: Contacts metadata service to find/create consumer group
   - Group ID = `{topic}_{app-id}`
   - Example: `orders_order-processor`
3. **Join Group**: Joins group via group leader broker
4. **Partition Assignment**: Receives assigned partitions after rebalancing
5. **Poll Messages**: Continuously polls from assigned partitions
6. **Heartbeat**: Sends heartbeat with offset commits every 3 seconds
7. **Leave**: Gracefully leaves group on close

## CLI Commands

### consume-group
```bash
mycli consume-group --topic <topic> --app-id <app-id> [options]

Required:
  --topic <topic>           Topic name
  --app-id <app-id>         Application ID (consumer group identifier)

Optional:
  --max-messages <n>        Max messages per poll (default: 100)
  --poll-interval <ms>      Poll interval in milliseconds (default: 1000)
  --continuous              Keep polling (Ctrl+C to stop)
  --client-id <id>          Client ID (default: dmq-consumer)
  --metadata-url <url>      Metadata service URL
```

### Examples

#### Single Consumer
```bash
# Join group and consume once
mycli consume-group --topic logs --app-id log-processor

# Continuous consumption
mycli consume-group --topic events --app-id event-processor --continuous
```

#### Multiple Consumers (Same Group)
Run in separate terminals to see automatic partition assignment:

```bash
# Terminal 1
mycli consume-group --topic orders --app-id order-processor --continuous

# Terminal 2  
mycli consume-group --topic orders --app-id order-processor --continuous

# Terminal 3
mycli consume-group --topic orders --app-id order-processor --continuous
```

Each consumer gets a subset of partitions. When one stops, partitions are reassigned.

#### Multiple Groups (Different App IDs)
```bash
# Group 1: Analytics
mycli consume-group --topic events --app-id analytics --continuous

# Group 2: Monitoring
mycli consume-group --topic events --app-id monitoring --continuous
```

Each group independently consumes all messages (different offset tracking).

## GUI Usage

### Consumer Tab

**Fields**:
- **Topic**: Topic name
- **Group ID / App ID**: Application identifier for consumer groups (optional for simple consume)
- **Partition**: Partition number (for simple consume)
- **Start Offset**: Starting offset (for simple consume)
- **Max Messages**: Maximum messages to fetch

**Buttons**:
- **Consume (Partition)**: Direct partition consumption
  - Requires: Topic, Partition, Offset
  - Uses: `consume` command
  
- **Consume (Group)**: Consumer group consumption
  - Requires: Topic, Group ID/App ID
  - Uses: `consume-group` command
  - Automatic partition assignment

### Consumer Groups Tab

**Operations**:
- **List All Consumer Groups**: View all active groups
- **Describe Consumer Group**: See group details (group ID, topic, app ID, coordinator)

## Technical Details

### Backend Components

1. **DMQConsumer** (`dmq-client/src/main/java/com/distributedmq/client/consumer/DMQConsumer.java`)
   - Implements consumer group protocol
   - Handles subscription, join, heartbeat, polling
   - Auto-commits offsets via heartbeat

2. **ConsumerConfig** (`dmq-client/src/main/java/com/distributedmq/client/consumer/ConsumerConfig.java`)
   - `groupId`: Acts as app-id for group discovery
   - `heartbeatIntervalMs`: Heartbeat frequency (default: 3000ms)
   - `sessionTimeoutMs`: Session timeout (default: 10000ms)
   - `enableAutoCommit`: Auto-commit offsets (default: true)

3. **Metadata Service**
   - Endpoint: `/api/v1/consumer-groups/find-or-create`
   - Creates group based on `{topic}_{appId}`
   - Returns group leader broker for coordination

4. **Group Leader Broker**
   - Endpoints:
     - `/api/v1/consumer-groups/join`: Join group
     - `/api/v1/consumer-groups/heartbeat`: Heartbeat with offsets
     - `/api/v1/consumer-groups/leave`: Leave group
   - Manages partition assignment and rebalancing

### Configuration

**Consumer Config Builder**:
```java
ConsumerConfig config = ConsumerConfig.builder()
    .groupId("my-app-id")              // Required for groups
    .enableAutoCommit(true)            // Auto-commit offsets
    .autoCommitIntervalMs(5000L)       // Commit every 5s
    .maxPollRecords(500)               // Max messages per poll
    .heartbeatIntervalMs(3000L)        // Heartbeat every 3s
    .sessionTimeoutMs(10000L)          // 10s session timeout
    .build();
```

## Testing

Run the test script:
```bash
./test-consumer-cli.ps1
```

Tests include:
1. Simple consumption from partitions
2. Consumer group list/describe
3. Consumer group consumption with automatic assignment
4. Performance benchmarks

## When to Use Each Mode

### Use Simple Consumption When:
- Reading from specific partition/offset
- Implementing custom partition assignment
- Replaying messages from specific offset
- Single consumer application
- Stateless message processing

### Use Consumer Groups When:
- Multiple consumers need to share workload
- Automatic partition assignment needed
- High availability (consumer failover)
- Offset management handled automatically
- Scaling consumers up/down dynamically
- Coordination required between consumers

## Troubleshooting

### Consumer Not Receiving Messages
- Check if consumer successfully joined group (look for "Successfully joined consumer group")
- Verify partition assignment (should show assigned partitions)
- Check if messages exist in topic (`list-topics`, `describe-topic`)

### Multiple Consumers Get Same Messages
- Ensure all consumers use the **same app-id** to join same group
- Check group membership (`describe-group`)

### Consumer Stuck/Not Polling
- Check heartbeat logs (should heartbeat every 3 seconds)
- Verify group leader broker is running
- Check session timeout settings

### Rebalancing Issues
- Allow time for rebalancing (wait 10-15 seconds after join)
- Check group state (`describe-group`)
- Verify all consumers are sending heartbeats
