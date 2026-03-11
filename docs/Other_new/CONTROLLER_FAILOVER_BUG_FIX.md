# Bug Fix: Brokers Marked OFFLINE After Controller Failover

## 🐛 Problem Description

After a controller change (leader election), existing brokers that reconnect and resume sending heartbeats remain marked as **OFFLINE** in the metadata service, even though they are successfully sending heartbeats.

### Symptoms:
- Broker 101: status=OFFLINE (but heartbeats resumed)
- Broker 102: status=OFFLINE (but heartbeats resumed)  
- Broker 103: status=ONLINE (started after controller change)

### Root Causes Identified:

#### Bug #1: Discovery Service Using `anyOf()` Instead of `allOf()`
**File:** `ControllerDiscoveryService.java`  
**Issue:** `CompletableFuture.anyOf()` returns the FIRST completed future (success OR failure). When one metadata node failed quickly (connection refused), the discovery was marked as failed even though other nodes successfully returned controller info.

**Fix:** Changed to `CompletableFuture.allOf()` to wait for all nodes to respond, then check all results for first successful response.

---

#### Bug #2: New Controller Not Rebuilding Heartbeat State
**File:** `RaftController.java`  
**Issue:** When a new controller becomes leader, it does NOT rebuild the in-memory heartbeat state from Raft state. This causes:

1. **Before failover**: Brokers 101, 102 registered, status=ONLINE, heartbeats tracked in-memory
2. **Controller fails**: New controller elected (e.g., node 3)
3. **New controller state**: 
   - Raft state shows brokers as ONLINE (replicated from old controller)
   - In-memory heartbeat map is **EMPTY** (not rebuilt)
4. **Brokers reconnect**: Send heartbeats to new controller
5. **HeartbeatService.processHeartbeat()**:
   - Line 80: Updates in-memory map
   - Line 84: Checks `if (broker.getStatus() == BrokerStatus.OFFLINE)`
   - **FALSE** because Raft state still shows ONLINE!
   - Line 102: Falls to else branch - just logs "already ONLINE"
   - **No Raft update happens!**
6. **Meanwhile**: `checkHeartbeats()` scheduled task runs every 10 seconds
   - No in-memory heartbeat for brokers 101, 102
   - Uses Raft state as fallback (last heartbeat from OLD controller)
   - Sees heartbeat is stale (> 30 seconds old)
   - **Marks brokers as OFFLINE via Raft**
7. **Result**: Brokers show OFFLINE even though sending heartbeats

**Fix:** Call `heartbeatService.rebuildHeartbeatState()` in `RaftController.becomeLeader()` to restore in-memory heartbeat timestamps from Raft state.

---

## ✅ Changes Made

### 1. ControllerDiscoveryService.java
**Fixed discovery logic to wait for ALL nodes:**

```java
// OLD (BROKEN): anyOf() returns first completed (success OR failure)
CompletableFuture<Object> firstSuccess = CompletableFuture.anyOf(futures.toArray(...));
Object result = firstSuccess.get(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);

// NEW (FIXED): allOf() waits for all, then checks for first success
CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(...));
allFutures.get(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);

// Check ALL results for first non-null success
for (CompletableFuture<ControllerInfo> future : futures) {
    if (future.isDone() && !future.isCompletedExceptionally()) {
        ControllerInfo result = future.get();
        if (result != null && result.getControllerId() != null) {
            return result;  // Success!
        }
    }
}
```

### 2. RaftController.java
**Injected HeartbeatService:**
```java
@Autowired(required = false)
private com.distributedmq.metadata.service.HeartbeatService heartbeatService;
```

**Call rebuild on leader election:**
```java
private void becomeLeader() {
    // ... existing code ...
    
    log.info("🎖️ Node {} became leader for term {}", nodeId, currentTerm);

    // Rebuild in-memory heartbeat state after controller failover
    if (heartbeatService != null) {
        try {
            heartbeatService.rebuildHeartbeatState();
            log.info("✅ Rebuilt in-memory heartbeat state for new controller");
        } catch (Exception e) {
            log.error("❌ Failed to rebuild heartbeat state: {}", e.getMessage(), e);
        }
    } else {
        log.warn("⚠️ HeartbeatService not available, heartbeat state not rebuilt");
    }

    // Start sending heartbeats
    startHeartbeatTimer();
    // ... rest of code ...
}
```

---

## 🧪 Testing

### Scenario: Controller Failover with Existing Brokers

1. **Start 3 metadata nodes** (9091, 9092, 9093)
2. **Start 2 storage brokers** (101, 102) - register with controller
3. **Check broker status** - both should be ONLINE
4. **Stop current controller** (e.g., node 1 on port 9091)
5. **New leader elected** (e.g., node 3 on port 9093)
6. **Brokers discover new controller** (should succeed with new discovery logic)
7. **Brokers send heartbeats to new controller**
8. **Check broker status again** - both should remain ONLINE!

### Expected Logs (New Controller):

```
INFO  RaftController - 🎖️ Node 3 became leader for term 17
INFO  HeartbeatService - Rebuilding in-memory heartbeat state after controller failover
INFO  HeartbeatService - Restored heartbeat state for broker 101: last seen at 1761497900000
INFO  HeartbeatService - Restored heartbeat state for broker 102: last seen at 1761497900500
INFO  HeartbeatService - Rebuilt in-memory heartbeat state for 2 ONLINE brokers
INFO  RaftController - ✅ Rebuilt in-memory heartbeat state for new controller
```

### Expected Logs (Broker Heartbeat):

```
DEBUG HeartbeatService - Processing heartbeat from broker: 101
DEBUG HeartbeatService - Updated in-memory heartbeat for broker 101 at 1761498000000
DEBUG HeartbeatService - Broker 101 already ONLINE, heartbeat recorded in-memory only
```

### Expected Broker Status:

```powershell
Invoke-RestMethod -Uri 'http://localhost:9093/api/v1/metadata/brokers' | ConvertTo-Json -Depth 3
```

```json
{
  "value": [
    {
      "id": 101,
      "status": "ONLINE",  ✅ NOT OFFLINE!
      "host": "localhost",
      "port": 8081
    },
    {
      "id": 102,
      "status": "ONLINE",  ✅ NOT OFFLINE!
      "host": "localhost",
      "port": 8082
    }
  ]
}
```

---

## 📝 Summary

**Two critical bugs fixed:**

1. ✅ **Discovery Service**: Changed from `anyOf()` to `allOf()` + result checking
   - Brokers can now find new controller even if one metadata node is down
   
2. ✅ **Heartbeat State Rebuild**: New controller rebuilds in-memory heartbeat state
   - Existing brokers remain ONLINE after controller failover
   - Prevents false OFFLINE status due to stale heartbeat timestamps

**Compilation:** ✅ SUCCESS  
**Ready for testing:** ✅ YES

