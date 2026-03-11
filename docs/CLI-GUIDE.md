# MyCli - DistributedMQ Command Line Interface

Complete guide for using the MyCli tool to interact with DistributedMQ.

---

## Table of Contents

- [Installation](#installation)
- [Producer Commands](#producer-commands)
  - [Create Topic](#create-topic)
  - [Produce Messages](#produce-messages)
- [Consumer Commands](#consumer-commands)
  - [Consume Messages](#consume-messages)
  - [List Consumer Groups](#list-consumer-groups)
  - [Describe Consumer Group](#describe-consumer-group)
- [General Commands](#general-commands)

---

## Installation

The CLI is packaged as an executable JAR file located at:
```
dmq-client/target/mycli.jar
```

Run commands using:
```bash
java -jar dmq-client/target/mycli.jar <command> [options]
```

Or create an alias for convenience:
```bash
# Linux/Mac
alias mycli='java -jar /path/to/dmq-client/target/mycli.jar'

# Windows PowerShell
Set-Alias mycli "java -jar C:\path\to\dmq-client\target\mycli.jar"
```

---

## Producer Commands

### Create Topic

Create a new topic with specified partitions and replication factor.

#### Syntax
```bash
mycli create-topic --name <topic> --partitions <n> --replication-factor <n> [options]
```

#### Required Arguments
- `--name <topic>` - Topic name
- `--partitions <n>` - Number of partitions (must be ≥ 1)
- `--replication-factor <n>` - Replication factor (must be ≥ 1)

#### Optional Arguments
- `--metadata-url <url>` - Metadata service URL (default: auto-discovered from config)

#### Examples

**Basic topic creation:**
```bash
mycli create-topic --name orders --partitions 3 --replication-factor 2
```

**High-throughput topic:**
```bash
mycli create-topic --name logs --partitions 10 --replication-factor 3
```

**Single partition topic:**
```bash
mycli create-topic --name config --partitions 1 --replication-factor 1
```

**Specify metadata service:**
```bash
mycli create-topic --name events --partitions 5 --replication-factor 2 \
  --metadata-url http://localhost:9091
```

#### Output
```
Creating topic 'orders'...
  Partitions: 3
  Replication Factor: 2

✓ Topic 'orders' created successfully!
```

---

### Produce Messages

Send messages to a topic.

#### Syntax
```bash
mycli produce --topic <topic> --value <value> [options]
```

#### Required Arguments
- `--topic <topic>` - Topic name
- `--value <value>` - Message value/payload

#### Optional Arguments
- `--key <key>` - Message key (used for partition selection)
- `--partition <n>` - Target partition (overrides key-based partitioning)
- `--acks <n>` - Acknowledgment mode:
  - `-1` (default) - Wait for all in-sync replicas
  - `1` - Wait for leader only
  - `0` - Fire-and-forget (no acknowledgment)
- `--metadata-url <url>` - Metadata service URL (default: auto-discovered)

#### Examples

**Simple message:**
```bash
mycli produce --topic orders --value "Order data"
```

**Message with key (for consistent partitioning):**
```bash
mycli produce --topic orders --key order-123 --value "Order details for #123"
```

**Send to specific partition:**
```bash
mycli produce --topic logs --value "Application started" --partition 0
```

**Fire-and-forget (fastest, no confirmation):**
```bash
mycli produce --topic metrics --key cpu --value "85.5" --acks 0
```

**Leader acknowledgment only (faster than default):**
```bash
mycli produce --topic events --key event-456 --value "User logged in" --acks 1
```

**All replicas acknowledgment (safest, default):**
```bash
mycli produce --topic transactions --key txn-789 --value "Payment processed" --acks -1
```

**JSON payload:**
```bash
mycli produce --topic users --key user-100 \
  --value '{"name":"John","email":"john@example.com","age":30}'
```

**Multi-line value (with quotes):**
```bash
mycli produce --topic notifications --key notif-1 \
  --value "Line 1
Line 2
Line 3"
```

**Special characters:**
```bash
mycli produce --topic special --key test \
  --value "Special: !@#$%^&*()_+-=[]{}|;:',.<>?/~\`"
```

**Binary data (Base64 encoded automatically):**
```bash
mycli produce --topic binary --key data-1 --value "SGVsbG8gV29ybGQh"
```

#### Output
```
Producing message to topic 'orders'...
  Key: order-123
  Value: Order details for #123

✓ Message sent successfully!
  Topic: orders
  Partition: 2
  Offset: 1547
  Timestamp: 2025-11-22T12:30:45.123Z
```

#### Producer Best Practices

1. **Use keys for related messages:**
   ```bash
   # All messages with same key go to same partition (ordering guaranteed)
   mycli produce --topic orders --key customer-123 --value "Order #1"
   mycli produce --topic orders --key customer-123 --value "Order #2"
   ```

2. **Choose appropriate ack level:**
   - **Metrics/Logs:** `--acks 0` (fast, some loss acceptable)
   - **Events:** `--acks 1` (balanced)
   - **Transactions:** `--acks -1` (safe, slower)

3. **Explicit partition for sequential processing:**
   ```bash
   # All config updates to partition 0
   mycli produce --topic config --value "update1" --partition 0
   ```

4. **Test topics with small partition count:**
   ```bash
   mycli create-topic --name test --partitions 1 --replication-factor 1
   mycli produce --topic test --key test1 --value "test message"
   ```

---

## Consumer Commands

### Consume Messages

Read messages from a topic partition.

#### Syntax
```bash
mycli consume --topic <topic> --partition <n> [options]
```

#### Required Arguments
- `--topic <topic>` - Topic name
- `--partition <n>` - Partition number to consume from

#### Optional Arguments
- `--offset <n>` - Start consuming from specific offset
- `--from-beginning` - Start from offset 0 (earliest message)
- `--max-messages <n>` - Maximum number of messages to consume (default: 10)
- `--continuous` - Keep consuming new messages (Ctrl+C to stop)
- `--format <format>` - Output format: `text`, `json`, `hex`, `base64` (default: text)
- `--no-key` - Don't display message keys
- `--no-timestamp` - Don't display timestamps
- `--metadata-url <url>` - Metadata service URL

#### Examples

**Consume from beginning:**
```bash
mycli consume --topic orders --partition 0 --from-beginning
```

**Consume specific number of messages:**
```bash
mycli consume --topic logs --partition 0 --from-beginning --max-messages 100
```

**Start from specific offset:**
```bash
mycli consume --topic events --partition 2 --offset 1000 --max-messages 50
```

**Continuous consumption (tail mode):**
```bash
mycli consume --topic metrics --partition 0 --continuous
# Press Ctrl+C to stop
```

**Different output formats:**
```bash
# Plain text (default)
mycli consume --topic messages --partition 0 --format text

# Hex dump
mycli consume --topic binary-data --partition 0 --format hex

# Base64 encoding
mycli consume --topic encoded --partition 0 --format base64
```

**Minimal output (no keys or timestamps):**
```bash
mycli consume --topic simple --partition 0 --no-key --no-timestamp
```

**Consume recent messages:**
```bash
# Get last 10 messages
mycli consume --topic recent --partition 0 --max-messages 10
```

#### Output
```
Consuming from topic 'orders', partition 0...
  Starting from: beginning (offset 0)
  Max messages: 10

[2025-11-22 12:30:45] Offset: 0, Key: order-123, Value: Order details
[2025-11-22 12:31:10] Offset: 1, Key: order-124, Value: Another order
[2025-11-22 12:32:05] Offset: 2, Key: order-125, Value: Third order

✓ Consumed 3 messages
  Next offset: 3
```

---

### List Consumer Groups

Display all consumer groups in the cluster.

#### Syntax
```bash
mycli list-groups [options]
```

#### Optional Arguments
- `--metadata-url <url>` - Metadata service URL

#### Examples

**List all groups:**
```bash
mycli list-groups
```

**With specific metadata service:**
```bash
mycli list-groups --metadata-url http://localhost:9091
```

#### Output
```
Fetching consumer groups...

╔════════════════════════════════════════════════════════════════════════════╗
║                           Consumer Groups                                  ║
╠════════════════════════════════════════════════════════════════════════════╣
║ Group ID                                 Topic                             ║
╠════════════════════════════════════════════════════════════════════════════╣
║ G_orders_order-processor                 orders                            ║
║ G_logs_log-aggregator                    logs                              ║
║ G_events_analytics                       events                            ║
╚════════════════════════════════════════════════════════════════════════════╝

Total groups: 3

Use 'mycli describe-group --group <group-id>' for detailed information
```

---

### Describe Consumer Group

Get detailed information about a specific consumer group.

#### Syntax
```bash
mycli describe-group --group <group-id> [options]
```

#### Required Arguments
- `--group <group-id>` - Consumer group ID

#### Optional Arguments
- `--metadata-url <url>` - Metadata service URL

#### Examples

**Describe a group:**
```bash
mycli describe-group --group G_orders_order-processor
```

**With specific metadata service:**
```bash
mycli describe-group --group G_logs_log-aggregator \
  --metadata-url http://localhost:9091
```

#### Output
```
Fetching consumer group details...

╔════════════════════════════════════════════════════════════════════════════╗
║                        Consumer Group Details                              ║
╚════════════════════════════════════════════════════════════════════════════╝

Group ID:           G_orders_order-processor
Topic:              orders
App ID:             order-processor
Coordinator Broker: 101
Coordinator URL:    localhost:8081
Created At:         2025-11-22 10:15:30
Last Modified:      2025-11-22 12:30:45
```

---

## General Commands

### Help

Display help information.

```bash
# General help
mycli help
mycli --help
mycli -h

# Command-specific help
mycli create-topic --help
mycli produce --help
mycli consume --help
```

### Version

Display CLI version.

```bash
mycli version
mycli --version
mycli -v
```

Output:
```
MyCli version 1.0.0
```

---

## Quick Reference

### Common Workflows

**1. Create topic and produce messages:**
```bash
# Create topic
mycli create-topic --name my-topic --partitions 3 --replication-factor 2

# Produce some messages
mycli produce --topic my-topic --key msg1 --value "First message"
mycli produce --topic my-topic --key msg2 --value "Second message"
mycli produce --topic my-topic --key msg3 --value "Third message"
```

**2. Consume messages:**
```bash
# Read from beginning
mycli consume --topic my-topic --partition 0 --from-beginning

# Monitor continuously
mycli consume --topic my-topic --partition 0 --continuous
```

**3. Check consumer groups:**
```bash
# List all groups
mycli list-groups

# Get details
mycli describe-group --group G_my-topic_my-app
```

### Troubleshooting

**Connection Issues:**
```bash
# Specify metadata service explicitly
mycli produce --topic test --value "hello" \
  --metadata-url http://localhost:9091
```

**Check if topic exists:**
```bash
# Try consuming - will show error if topic doesn't exist
mycli consume --topic my-topic --partition 0 --max-messages 1
```

**View all commands:**
```bash
mycli --help
```

---

## Configuration

The CLI automatically discovers services from `config/services.json`:

```json
{
  "services": {
    "metadata-services": [
      {"id": 1, "host": "localhost", "port": 9091, "url": "http://localhost:9091"},
      {"id": 2, "host": "localhost", "port": 9092, "url": "http://localhost:9092"},
      {"id": 3, "host": "localhost", "port": 9093, "url": "http://localhost:9093"}
    ],
    "storage-services": [
      {"id": 101, "host": "localhost", "port": 8081, "url": "http://localhost:8081"},
      {"id": 102, "host": "localhost", "port": 8082, "url": "http://localhost:8082"},
      {"id": 103, "host": "localhost", "port": 8083, "url": "http://localhost:8083"}
    ]
  }
}
```

Override with `--metadata-url` option when needed.

---

## Error Handling

**Common Errors:**

1. **Topic not found:**
   ```
   ❌ Topic 'my-topic' not found. Create it first using: 
   mycli create-topic --name my-topic --partitions 3 --replication-factor 2
   ```

2. **Cannot connect to service:**
   ```
   ❌ Cannot connect to any metadata service. Please check if services are running.
   ```

3. **Invalid arguments:**
   ```
   ❌ Missing required argument: --topic
   ```

---

## Performance Tips

1. **Use `--acks 0` for high-throughput scenarios**
2. **Partition data by key for ordered processing**
3. **Create topics with multiple partitions for parallel consumption**
4. **Use `--continuous` mode for real-time monitoring**

---

## Support

For issues or questions:
- Check service status: `ps aux | grep java`
- View logs in service terminals
- Verify `config/services.json` is correct
- Ensure all metadata and storage services are running
