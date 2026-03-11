# Manual Testing Instructions

## Problem Identified
The automated testing is having terminal isolation issues. Please follow these MANUAL steps:

---

## Step 1: Start the Service (Terminal 1)

Open a NEW PowerShell terminal and run:

```powershell
cd C:\Users\123ad\Downloads\Distri_Kafka\Kafka-Clone\dmq-metadata-service
.\start.bat
```

**Wait for these logs**:
```
✅ Started MetadataServiceApplication in X.XXX seconds
✅ Tomcat started on port(s): 9091 (http)
✅ Node 1 became leader for term XX
✅ Registering broker via Raft consensus: 1
```

**KEEP THIS TERMINAL OPEN** - Do NOT run any other commands here!

---

## Step 2: Test in a SEPARATE Terminal (Terminal 2)

Open a SECOND PowerShell terminal (leave Terminal 1 running) and run these tests:

### Test 1: Raft Status
```powershell
Invoke-RestMethod -Uri 'http://localhost:9091/api/v1/raft/status' -Method GET | ConvertTo-Json
```

**Expected**:
```json
{
  "nodeId": 1,
  "state": "LEADER",
  "currentTerm": 84,
  "leaderId": 1
}
```

---

### Test 2: List Brokers
```powershell
Invoke-RestMethod -Uri 'http://localhost:9091/api/v1/metadata/brokers' -Method GET | ConvertTo-Json -Depth 3
```

**Expected**: At least broker ID 1

---

### Test 3: Create Topic
```powershell
$body = @{
    topicName = 'test-topic-1'
    partitionCount = 3
    replicationFactor = 1
    retentionMs = 86400000
    compressionType = 'none'
    minInsyncReplicas = 1
} | ConvertTo-Json

Invoke-RestMethod -Uri 'http://localhost:9091/api/v1/metadata/topics' `
    -Method POST `
    -ContentType 'application/json' `
    -Body $body | ConvertTo-Json -Depth 5
```

**Important:** Send to the **CONTROLLER** node only! Check which node is leader first:
```powershell
# Check which node is the leader (controller)
Invoke-RestMethod -Uri 'http://localhost:9091/api/v1/metadata/controller' | ConvertTo-Json
Invoke-RestMethod -Uri 'http://localhost:9092/api/v1/metadata/controller' | ConvertTo-Json
Invoke-RestMethod -Uri 'http://localhost:9093/api/v1/metadata/controller' | ConvertTo-Json

# Then send the create topic request to the controller URL shown above
```

**Watch Terminal 1 logs** for:
```
INFO  MetadataServiceImpl - Creating topic: test-topic-1
INFO  RaftController - Appending command: RegisterTopicCommand
INFO  RaftController - Command committed at index X
INFO  MetadataStateMachine - Applied RegisterTopicCommand: test-topic-1
INFO  [db-persist-1] Async persisted topic test-topic-1
```

**If you get 500 error**, check Terminal 1 for the stack trace!

---

### Test 4: List Topics
```powershell
Invoke-RestMethod -Uri 'http://localhost:9091/api/v1/metadata/topics' -Method GET | ConvertTo-Json -Depth 3
```

**Expected**: Should show test-topic-1

---

### Test 5: Get Specific Topic
```powershell
Invoke-RestMethod -Uri 'http://localhost:9091/api/v1/metadata/topics/test-topic-1' -Method GET | ConvertTo-Json -Depth 5
```

**Watch Terminal 1 logs** for:
```
INFO  MetadataServiceImpl - Reading topic metadata for: test-topic-1
DEBUG MetadataServiceImpl - Reading from state machine (not database)
```

---

### Test 6: Delete Topic
```powershell
Invoke-RestMethod -Uri 'http://localhost:9091/api/v1/metadata/topics/test-topic-1' -Method DELETE
```

**Watch Terminal 1 logs** for:
```
INFO  MetadataServiceImpl - Deleting topic: test-topic-1
INFO  RaftController - Appending command: DeleteTopicCommand
INFO  RaftController - Command committed at index X
INFO  MetadataStateMachine - Applied DeleteTopicCommand: test-topic-1
```

---

## If You Get Errors

### 404 Not Found
- Check you're using `/api/v1/metadata/*` or `/api/v1/raft/*` endpoints
- Verify service is running: `Test-NetConnection localhost -Port 9091`

### 500 Internal Server Error
- **CHECK TERMINAL 1** for the Java stack trace
- Look for lines with `ERROR` or `Exception`
- Copy the entire stack trace and share it

### Connection Refused
- Service isn't running
- Check Terminal 1 - did it shut down?
- Restart with `.\start.bat`

---

## Success Criteria

✅ Raft status shows "LEADER"  
✅ Can create topics  
✅ Can list topics  
✅ Can read topic metadata  
✅ Can delete topics  
✅ Terminal 1 shows Raft consensus logs  
✅ Terminal 1 shows "Reading from state machine (not database)"  
✅ Terminal 1 shows async database persistence  

---

## What to Share

If tests fail, please share:
1. The exact command you ran
2. The error message from Terminal 2
3. **The stack trace from Terminal 1** (this is crucial!)
4. The last 50 lines of Terminal 1 logs

This will help diagnose the issue!
