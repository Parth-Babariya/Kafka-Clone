# Topic Creation Guide

## ✅ CORRECT: Send to Controller (Leader) Node

### Step 1: Find the Controller
```powershell
# Query each metadata node to find the controller
Invoke-RestMethod -Uri 'http://localhost:9091/api/v1/metadata/controller' | ConvertTo-Json
Invoke-RestMethod -Uri 'http://localhost:9092/api/v1/metadata/controller' | ConvertTo-Json
Invoke-RestMethod -Uri 'http://localhost:9093/api/v1/metadata/controller' | ConvertTo-Json
```

**Expected Response:**
```json
{
  "controllerId": 3,
  "controllerUrl": "http://localhost:9093",
  "controllerTerm": 16
}
```

### Step 2: Create Topic (Send to Controller URL)

**CORRECT JSON Format:**
```powershell
$body = @{
    topicName = 'test-topic-1'
    partitionCount = 3
    replicationFactor = 1
    retentionMs = 86400000        # 1 day in milliseconds (Long, not String)
    compressionType = 'none'       # Optional: 'none', 'gzip', 'snappy', 'lz4'
    minInsyncReplicas = 1          # Optional: minimum replicas in ISR
} | ConvertTo-Json

# Send to the CONTROLLER URL (from Step 1)
Invoke-RestMethod -Uri 'http://localhost:9093/api/v1/metadata/topics' `
    -Method POST `
    -ContentType 'application/json' `
    -Body $body | ConvertTo-Json -Depth 5
```

**Expected: 200 OK**
```json
{
  "topicName": "test-topic-1",
  "partitionCount": 3,
  "replicationFactor": 1,
  "partitions": [...],
  "createdAt": 1730000000000,
  "config": {...}
}
```

---

## ❌ WRONG FORMATS (Will Cause 400 Bad Request)

### ❌ Wrong: Nested config object
```json
{
  "topicName": "test-topic-1",
  "partitionCount": 3,
  "replicationFactor": 1,
  "config": {                    // ❌ Don't nest config!
    "retention.ms": "86400000"   // ❌ String instead of Long!
  }
}
```

### ❌ Wrong: Missing required fields
```json
{
  "topicName": "test-topic-1"
  // ❌ Missing partitionCount!
  // ❌ Missing replicationFactor!
}
```

### ❌ Wrong: Invalid values
```json
{
  "topicName": "test-topic-1",
  "partitionCount": 0,           // ❌ Must be > 0
  "replicationFactor": -1        // ❌ Must be > 0
}
```

---

## 🔄 Expected Responses

### ✅ 200 OK (Controller, Topic Created)
- Sent to controller node
- Topic created successfully
- Returns topic metadata

### ⚠️ 503 Service Unavailable (Non-Controller)
- Sent to follower/candidate node
- **This is CORRECT behavior!**
- Response headers include: `X-Controller-Leader: <leader-id>`
- Client should retry on the controller node

### ❌ 400 Bad Request (Validation Failed)
- Missing required fields (topicName, partitionCount, replicationFactor)
- Invalid values (partition count <= 0, replication factor <= 0)
- Wrong JSON format (nested config, string instead of number)
- Response headers include: `X-Error-Message: <error-details>`

### ❌ 500 Internal Server Error (System Error)
- Database error
- Raft consensus failure
- Unexpected exception
- Response headers include: `X-Error-Message: <error-details>`

---

## 🐛 Troubleshooting

### Getting 400 from Controller?
Check the response headers:
```powershell
$response = Invoke-WebRequest -Uri 'http://localhost:9093/api/v1/metadata/topics' `
    -Method POST `
    -ContentType 'application/json' `
    -Body $body

$response.Headers["X-Error-Message"]
```

Common issues:
- Missing `topicName`, `partitionCount`, or `replicationFactor`
- Partition count or replication factor is null or <= 0
- Wrong JSON format (nested config)
- String values instead of numbers

### Getting 503 from Controller?
The node is not the leader anymore! Re-query the controller:
```powershell
Invoke-RestMethod -Uri 'http://localhost:9091/api/v1/metadata/controller'
Invoke-RestMethod -Uri 'http://localhost:9092/api/v1/metadata/controller'
Invoke-RestMethod -Uri 'http://localhost:9093/api/v1/metadata/controller'
```

Use the `controllerUrl` from the response.

### Check Server Logs
Look for these emojis in the metadata service logs:
- 📝 Received request to create topic
- ✅ Topic created successfully
- ❌ Error creating topic
- ⚠️ Topic creation validation failed
- ⚠️ This node is not the controller leader

---

## 📋 CreateTopicRequest DTO Structure

```java
{
    topicName: String (required)
    partitionCount: Integer (required, > 0)
    replicationFactor: Integer (required, > 0)
    retentionMs: Long (optional, default: 604800000 = 7 days)
    retentionBytes: Long (optional, default: -1 = unlimited)
    segmentBytes: Integer (optional, default: 1073741824 = 1GB)
    compressionType: String (optional, default: "none")
    minInsyncReplicas: Integer (optional, default: 1)
}
```

**All config fields are flat, not nested!**
