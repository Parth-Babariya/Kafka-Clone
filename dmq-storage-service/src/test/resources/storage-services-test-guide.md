# DMQ Storage Service - Testing Guide

## Overview
This guide provides comprehensive testing instructions for the DMQ Storage Service producer flow. The service implements a Kafka-compatible message storage system with WAL-based persistence and basic replication framework.

## Prerequisites
- Java 11 or higher
- Maven 3.6+
- PowerShell (for Windows testing)
- curl or PowerShell Invoke-WebRequest

## Service Startup

### 1. Build the Project
```bash
# From project root
mvn clean install -DskipTests -q
```

### 2. Start Storage Service
```bash
# From dmq-storage-service directory
cd dmq-storage-service
mvn spring-boot:run
```

**Expected Output:**
```
Tomcat started on port(s): 8082 (http) with context path ''
Started StorageServiceApplication in X.XXX seconds
```

### 3. Verify Service Health
```bash
curl http://localhost:8082/actuator/health
# Expected: {"status":"UP"}
```

## API Endpoints Testing

### Base URL
```
http://localhost:8082/api/v1/storage
```

---

## 1. Message Production (POST /messages)

**Endpoint:** `POST /api/v1/storage/messages`

**Purpose:** Produce messages to a topic partition (leader only)

### Request Format
```json
{
  "topic": "string",
  "partition": 0,
  "messages": [
    {
      "key": "string",           // Optional
      "value": "string",         // Base64 encoded, Required
      "timestamp": 1234567890    // Optional, defaults to current time
    }
  ],
  "producerId": "string",        // Optional, for idempotent producers
  "producerEpoch": 0,            // Optional, for idempotent producers
  "requiredAcks": 1              // 0=no ack, 1=leader ack, -1=all ISR ack
}
```

### Test Cases

#### Test Case 1.1: Single Message Production
**Request:**
```json
{
  "topic": "test-topic",
  "partition": 0,
  "messages": [
    {
      "key": "test-key",
      "value": "dGVzdC12YWx1ZQ=="
    }
  ],
  "producerId": "producer-1",
  "producerEpoch": 0,
  "requiredAcks": 1
}
```

**Command (PowerShell):**
```powershell
# Create test file
@'
{"topic":"test-topic","partition":0,"messages":[{"key":"test-key","value":"dGVzdC12YWx1ZQ=="}],"producerId":"producer-1","producerEpoch":0,"requiredAcks":1}
'@ | Out-File -FilePath test.json -Encoding UTF8

# Send request
(Invoke-WebRequest -Uri "http://localhost:8082/api/v1/storage/messages" -Method POST -ContentType "application/json" -Body (Get-Content test.json -Raw)).Content
```

**Expected Response:**
```json
{
  "topic": "test-topic",
  "partition": 0,
  "results": [
    {
      "offset": 0,
      "timestamp": 1760611122167,
      "errorCode": "NONE",
      "errorMessage": null
    }
  ],
  "throttleTimeMs": null,
  "errorMessage": null,
  "errorCode": "NONE",
  "success": true
}
```

#### Test Case 1.2: Batch Message Production
**Request:**
```json
{
  "topic": "test-topic",
  "partition": 0,
  "messages": [
    {
      "key": "key1",
      "value": "dmFsdWUx"
    },
    {
      "key": "key2",
      "value": "dmFsdWUy"
    },
    {
      "key": "key3",
      "value": "dmFsdWUz"
    }
  ],
  "producerId": "producer-1",
  "producerEpoch": 0,
  "requiredAcks": 1
}
```

**Command (PowerShell):**
```powershell
# Create batch test file
@'
{"topic":"test-topic","partition":0,"messages":[{"key":"key1","value":"dmFsdWUx"},{"key":"key2","value":"dmFsdWUy"},{"key":"key3","value":"dmFsdWUz"}],"producerId":"producer-1","producerEpoch":0,"requiredAcks":1}
'@ | Out-File -FilePath batch.json -Encoding UTF8

# Send request
(Invoke-WebRequest -Uri "http://localhost:8082/api/v1/storage/messages" -Method POST -ContentType "application/json" -Body (Get-Content batch.json -Raw)).Content
```

**Expected Response:**
```json
{
  "topic": "test-topic",
  "partition": 0,
  "results": [
    {
      "offset": 1,
      "timestamp": 1760611296744,
      "errorCode": "NONE",
      "errorMessage": null
    },
    {
      "offset": 2,
      "timestamp": 1760611296745,
      "errorCode": "NONE",
      "errorMessage": null
    },
    {
      "offset": 3,
      "timestamp": 1760611296746,
      "errorCode": "NONE",
      "errorMessage": null
    }
  ],
  "throttleTimeMs": null,
  "errorMessage": null,
  "errorCode": "NONE",
  "success": true
}
```

#### Test Case 1.3: Invalid Request (Empty Messages)
**Request:**
```json
{
  "topic": "test-topic",
  "partition": 0,
  "messages": []
}
```

**Expected Response:**
```json
{
  "topic": "test-topic",
  "partition": 0,
  "success": false,
  "errorCode": "INVALID_REQUEST",
  "errorMessage": "Invalid request"
}
```

#### Test Case 1.4: Invalid ACKs Value
**Request:**
```json
{
  "topic": "test-topic",
  "partition": 0,
  "messages": [
    {
      "key": "test-key",
      "value": "dGVzdC12YWx1ZQ=="
    }
  ],
  "requiredAcks": 2
}
```

**Expected Response:**
```json
{
  "topic": "test-topic",
  "partition": 0,
  "success": false,
  "errorCode": "INVALID_REQUEST",
  "errorMessage": "Invalid request"
}
```

---

## 2. Message Consumption (POST /consume)

**Endpoint:** `POST /api/v1/storage/consume`

**Purpose:** Fetch messages from a topic partition

**Note:** Consumer read functionality is not yet implemented (WAL.read() returns empty list)

### Request Format
```json
{
  "topic": "string",
  "partition": 0,
  "offset": 0,
  "maxMessages": 100
}
```

### Test Case 2.1: Consume Messages
**Request:**
```json
{
  "topic": "test-topic",
  "partition": 0,
  "offset": 0,
  "maxMessages": 10
}
```

**Command (PowerShell):**
```powershell
# Create consume test file
@'
{"topic":"test-topic","partition":0,"offset":0,"maxMessages":10}
'@ | Out-File -FilePath consume.json -Encoding UTF8

# Send request
(Invoke-WebRequest -Uri "http://localhost:8082/api/v1/storage/consume" -Method POST -ContentType "application/json" -Body (Get-Content consume.json -Raw)).Content
```

**Expected Response (Current - Read Not Implemented):**
```json
{
  "messages": [],
  "highWaterMark": 4,
  "errorMessage": null,
  "success": true
}
```

---

## 3. High Water Mark (GET /partitions/{topic}/{partition}/high-water-mark)

**Endpoint:** `GET /api/v1/storage/partitions/{topic}/{partition}/high-water-mark`

**Purpose:** Get the current high water mark for a partition

### Test Case 3.1: Get High Water Mark
**Request:**
```
GET /api/v1/storage/partitions/test-topic/0/high-water-mark
```

**Command (PowerShell):**
```powershell
(Invoke-WebRequest -Uri "http://localhost:8082/api/v1/storage/partitions/test-topic/0/high-water-mark" -Method GET).Content
```

**Expected Response:**
```
4
```

---

## 4. Replication (POST /replicate)

**Endpoint:** `POST /api/v1/storage/replicate`

**Purpose:** Receive replication requests from leader brokers (follower endpoint)

**Note:** This endpoint is used internally by the replication system and is not typically called directly by clients.

### Request Format
```json
{
  "topic": "string",
  "partition": 0,
  "messages": [
    {
      "key": "string",
      "value": "string",
      "timestamp": 1234567890
    }
  ],
  "baseOffset": 0,
  "leaderId": 1,
  "leaderEpoch": 1,
  "timeoutMs": 500,
  "requiredAcks": 1
}
```

### Response Format
```json
{
  "topic": "string",
  "partition": 0,
  "followerId": 2,
  "baseOffset": 0,
  "messageCount": 3,
  "success": true,
  "errorCode": "NONE",
  "errorMessage": null,
  "replicationTimeMs": 1234567890
}
```

---

## 4. Error Scenarios

### 4.1 Leader Not Available (Simulated)
**Request:** Valid produce request when `isLeaderForPartition()` returns false

**Expected Response:**
```json
{
  "topic": "test-topic",
  "partition": 0,
  "success": false,
  "errorCode": "NOT_LEADER_FOR_PARTITION",
  "errorMessage": "Not leader for partition"
}
```

### 4.2 Replication Failure (Simulated)
**Request:** Valid produce request when replication fails

**Expected Response:**
```json
{
  "topic": "test-topic",
  "partition": 0,
  "success": false,
  "errorCode": "INVALID_REQUEST",
  "errorMessage": "Replication to followers failed"
}
```

---

## Test Automation Scripts

### PowerShell Test Script
```powershell
# test-storage-service.ps1

function Test-StorageService {
    param(
        [string]$baseUrl = "http://localhost:8082/api/v1/storage"
    )

    Write-Host "Testing DMQ Storage Service..." -ForegroundColor Green

    # Test 1: Single message production
    Write-Host "Test 1: Single message production" -ForegroundColor Yellow
    $singleMessage = @{
        topic = "test-topic"
        partition = 0
        messages = @(@{key="test-key"; value="dGVzdC12YWx1ZQ=="})
        producerId = "producer-1"
        producerEpoch = 0
        requiredAcks = 1
    } | ConvertTo-Json

    try {
        $response = Invoke-WebRequest -Uri "$baseUrl/messages" -Method POST -ContentType "application/json" -Body $singleMessage
        Write-Host "✓ Single message test passed" -ForegroundColor Green
        Write-Host "Response: $($response.Content)" -ForegroundColor Gray
    } catch {
        Write-Host "✗ Single message test failed: $($_.Exception.Message)" -ForegroundColor Red
    }

    # Test 2: Batch message production
    Write-Host "Test 2: Batch message production" -ForegroundColor Yellow
    $batchMessages = @{
        topic = "test-topic"
        partition = 0
        messages = @(
            @{key="key1"; value="dmFsdWUx"},
            @{key="key2"; value="dmFsdWUy"},
            @{key="key3"; value="dmFsdWUz"}
        )
        producerId = "producer-1"
        producerEpoch = 0
        requiredAcks = 1
    } | ConvertTo-Json

    try {
        $response = Invoke-WebRequest -Uri "$baseUrl/messages" -Method POST -ContentType "application/json" -Body $batchMessages
        Write-Host "✓ Batch message test passed" -ForegroundColor Green
        Write-Host "Response: $($response.Content)" -ForegroundColor Gray
    } catch {
        Write-Host "✗ Batch message test failed: $($_.Exception.Message)" -ForegroundColor Red
    }

    # Test 3: High water mark
    Write-Host "Test 3: High water mark check" -ForegroundColor Yellow
    try {
        $response = Invoke-WebRequest -Uri "$baseUrl/partitions/test-topic/0/high-water-mark" -Method GET
        Write-Host "✓ High water mark test passed: $($response.Content)" -ForegroundColor Green
    } catch {
        Write-Host "✗ High water mark test failed: $($_.Exception.Message)" -ForegroundColor Red
    }

    Write-Host "Testing completed!" -ForegroundColor Green
}

# Run tests
Test-StorageService
```

### Bash Test Script
```bash
#!/bin/bash
# test-storage-service.sh

BASE_URL="http://localhost:8082/api/v1/storage"

echo "Testing DMQ Storage Service..."

# Test 1: Single message production
echo "Test 1: Single message production"
curl -X POST "$BASE_URL/messages" \
  -H "Content-Type: application/json" \
  -d '{
    "topic": "test-topic",
    "partition": 0,
    "messages": [{"key": "test-key", "value": "dGVzdC12YWx1ZQ=="}],
    "producerId": "producer-1",
    "producerEpoch": 0,
    "requiredAcks": 1
  }'

echo -e "\nTest 2: Batch message production"
curl -X POST "$BASE_URL/messages" \
  -H "Content-Type: application/json" \
  -d '{
    "topic": "test-topic",
    "partition": 0,
    "messages": [
      {"key": "key1", "value": "dmFsdWUx"},
      {"key": "key2", "value": "dmFsdWUy"},
      {"key": "key3", "value": "dmFsdWUz"}
    ],
    "producerId": "producer-1",
    "producerEpoch": 0,
    "requiredAcks": 1
  }'

echo -e "\nTest 3: High water mark"
curl -X GET "$BASE_URL/partitions/test-topic/0/high-water-mark"

echo -e "\nTesting completed!"
```

---

## Data Persistence Verification

### Check WAL Files
After running tests, verify data persistence:

```bash
# Check if log files were created
ls -la ./data/broker-1/logs/test-topic/0/

# Expected output:
# 00000000000000000000.log (contains messages)
# Files should exist and have non-zero size
```

### Message Decoding
Messages are stored as base64. To decode test values:
```bash
# Decode test message
echo "dGVzdC12YWx1ZQ==" | base64 -d
# Output: test-value

echo "dmFsdWUx" | base64 -d
# Output: value1
```

---

## Performance Benchmarks

### Single Message Throughput
```bash
# Run 100 single messages
for i in {1..100}; do
  curl -X POST "http://localhost:8082/api/v1/storage/messages" \
    -H "Content-Type: application/json" \
    -d "{\"topic\":\"perf-test\",\"partition\":0,\"messages\":[{\"value\":\"dGVzdA==\"}]}"
done
```

### Batch Message Throughput
```bash
# Run batches of 10 messages each
for i in {1..10}; do
  curl -X POST "http://localhost:8082/api/v1/storage/messages" \
    -H "Content-Type: application/json" \
    -d "{\"topic\":\"perf-test\",\"partition\":0,\"messages\":$(printf '{\"value\":\"dGVzdA==\"},%.0s' {1..10} | sed 's/,$//')}"
done
```

---

## Troubleshooting

### Common Issues

#### 1. Port 8082 Already in Use
```bash
# Find process using port 8082
netstat -ano | findstr :8082

# Kill the process (replace PID)
taskkill /PID <PID> /F
```

#### 2. Build Failures
```bash
# Clean and rebuild
mvn clean install -DskipTests -q
```

#### 3. Service Won't Start
- Check Java version: `java -version` (should be 11+)
- Check Maven version: `mvn -version`
- Verify no other service is using port 8082

#### 4. Empty Consumer Response
- Consumer read functionality is not yet implemented
- WAL.read() method returns empty list (expected behavior)
- High water mark will show correct values

---

## Current Implementation Status

### ✅ Implemented Features
- Message production (single & batch)
- WAL-based persistence
- Offset assignment
- High water mark management
- Request validation
- Leader election framework
- **Replication framework (network calls implemented)**

### ❌ Not Yet Implemented
- Consumer message reading (WAL.read())
- Actual network replication
- ISR management
- Metadata service integration
- Idempotent producer validation
- Message compression
- Log compaction

---

## Next Steps for Development

1. **Implement WAL.read()** for consumer functionality
2. ~~**Add network replication** using Netty~~ **✅ Network replication implemented**
3. **Integrate with metadata service** for leader election
4. **Implement ISR management**
5. **Add comprehensive error handling**
6. **Performance optimization and monitoring**

---

*Last Updated: October 16, 2025*
*Tested With: DMQ Storage Service v1.0.0-SNAPSHOT*
*Replication: Network-based replication implemented*