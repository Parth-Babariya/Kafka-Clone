# Produce & Consume Message Testing Guide

## 📝 Produce Message

**Endpoint:** `POST /api/v1/storage/messages`  
**Send to:** Leader broker for the partition

### PowerShell Command:
```powershell
# Replace these values:
$topicName = "test-topic-1"        # Your topic name
$partitionId = 0                  # Leader partition ID
$leaderBrokerUrl = "http://localhost:8081"  # Leader broker URL

$body = @{
    topic = $topicName
    partition = $partitionId
    messages = @(
        @{
            key = "test-key-1"
            value = [System.Text.Encoding]::UTF8.GetBytes("Hello, Kafka Clone!")
            timestamp = $null  # Will use current time
        }
    )
    producerId = "test-producer-1"
    producerEpoch = 0
    requiredAcks = 1  # Wait for leader acknowledgment
    timeoutMs = 5000
} | ConvertTo-Json -Depth 5

Invoke-RestMethod -Uri "$leaderBrokerUrl/api/v1/storage/messages" `
    -Method POST `
    -ContentType 'application/json' `
    -Body $body | ConvertTo-Json -Depth 5
```

**Expected Response:**
```json
{
  "topic": "test-topic-1",
  "partition": 0,
  "success": true,
  "baseOffset": 0,
  "logAppendTime": 1730000000000,
  "logStartOffset": 0,
  "recordErrors": []
}
```

---

## 📖 Consume Messages

**Endpoint:** `POST /api/v1/storage/consume`  
**Send to:** Any broker that has the partition replica

### PowerShell Command:
```powershell
# Replace these values:
$topicName = "test-topic-1"        # Your topic name
$partitionId = 0                  # Partition ID
$brokerUrl = "http://localhost:8081"  # Any broker URL
$consumerGroup = "test-group-1"   # Consumer group name
$startOffset = 0                  # Offset to start consuming from

$body = @{
    consumerGroup = $consumerGroup
    topic = $topicName
    partition = $partitionId
    offset = $startOffset
    maxMessages = 10
    maxWaitMs = 1000
    minBytes = 1
    maxBytes = 1048576  # 1MB
} | ConvertTo-Json

Invoke-RestMethod -Uri "$brokerUrl/api/v1/storage/consume" `
    -Method POST `
    -ContentType 'application/json' `
    -Body $body | ConvertTo-Json -Depth 5
```

**Expected Response:**
```json
{
  "topic": "test-topic-1",
  "partition": 0,
  "messages": [
    {
      "offset": 0,
      "key": "test-key-1",
      "value": "SGVsbG8sIEthZmthIENsb25lIQ==",  // Base64 encoded
      "timestamp": 1730000000000,
      "headers": {}
    }
  ],
  "highWaterMark": 1,
  "lastStableOffset": 1
}
```

---

## 🔍 Find Leader Broker for Partition

Before producing, you need to find which broker is the leader for your partition:

```powershell
# Get topic metadata from any metadata node
$metadataUrl = "http://localhost:9091"  # Any metadata node
$topicName = "test-topic-1"

$topicInfo = Invoke-RestMethod -Uri "$metadataUrl/api/v1/metadata/topics/$topicName" | ConvertTo-Json -Depth 5

# Look for partition 0 leader in the response
# The response will show which broker ID is the leader for each partition
```

---

## 🧪 Complete Test Flow

### Step 1: Find Leader Broker
```powershell
# Get topic metadata to find partition leaders
Invoke-RestMethod -Uri 'http://localhost:9091/api/v1/metadata/topics/test-topic-1' | ConvertTo-Json -Depth 5
```

### Step 2: Produce Message
```powershell
$body = @{
    topic = 'test-topic-1'
    partition = 0
    messages = @(
        @{
            key = 'test-key-1'
            value = [System.Text.Encoding]::UTF8.GetBytes('Hello from PowerShell!')
        }
    )
    producerId = 'powershell-producer'
    producerEpoch = 0
    requiredAcks = 1
    timeoutMs = 5000
} | ConvertTo-Json -Depth 5

# Replace with actual leader broker URL
Invoke-RestMethod -Uri 'http://localhost:8081/api/v1/storage/messages' `
    -Method POST `
    -ContentType 'application/json' `
    -Body $body | ConvertTo-Json -Depth 5
```

### Step 3: Consume Message
```powershell
$body = @{
    consumerGroup = 'test-consumer-group'
    topic = 'test-topic-1'
    partition = 0
    offset = 0
    maxMessages = 10
    maxWaitMs = 1000
    minBytes = 1
    maxBytes = 1048576
} | ConvertTo-Json

# Can send to any broker
Invoke-RestMethod -Uri 'http://localhost:8081/api/v1/storage/consume' `
    -Method POST `
    -ContentType 'application/json' `
    -Body $body | ConvertTo-Json -Depth 5
```

---

## ⚠️ Important Notes

1. **Produce to Leader Only**: Send produce requests only to the leader broker for that partition
2. **Consume from Any Replica**: Send consume requests to any broker that has the partition
3. **Message Value is Base64**: The `value` field in responses is Base64 encoded bytes
4. **Offsets Start at 0**: First message in partition has offset 0
5. **Consumer Groups**: Use consistent consumer group names for offset tracking

---

## 🔧 Decode Base64 Message Value

To decode the Base64 message value in PowerShell:

```powershell
# After getting consume response
$messageValue = "SGVsbG8sIEthZmthIENsb25lIQ=="  # From response
$decodedMessage = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($messageValue))
Write-Host "Decoded message: $decodedMessage"
```