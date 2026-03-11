# REPLICATION ISSUE - ROOT CAUSE ANALYSIS

**Date**: October 25, 2025  
**Issue**: Topics created on leader are NOT replicated to followers  
**Status**: 🔍 **DIAGNOSING**

---

## 🐛 **Problem Statement**

When creating a topic on the leader (Node 1):
- ✅ Topic created successfully on **Leader (Node 1)**
- ❌ Topic **NOT visible** on **Follower (Node 2)**
- ❌ Topic **NOT visible** on **Follower (Node 3)**

---

## ✅ **What IS Working**

### Raft Consensus
```
Node 1 (LEADER):   commitIndex=5, lastApplied=5, term=2
Node 2 (FOLLOWER): commitIndex=5, lastApplied=5, term=2
Node 3 (FOLLOWER): commitIndex=5, lastApplied=5, term=2
```

**Observations**:
- ✅ All nodes have **same commitIndex** (5)
- ✅ All nodes have **same lastApplied** (5)
- ✅ All nodes agree on **same term** (2)
- ✅ Leader election working correctly
- ✅ Raft log replication working
- ✅ Log entries being applied to state machine

---

## ❌ **What is NOT Working**

### Topic Replication
```
Node 1 topics: ["test-repl-check"]  ← Has topic
Node 2 topics: []                    ← NO topics
Node 3 topics: []                    ← NO topics
```

**Observations**:
- ❌ Followers do **NOT have topics** in MetadataStateMachine
- ❌ State machine `apply()` method called but topics NOT created
- ❌ `getAllTopics()` returns empty map on followers

---

## 🔍 **Root Cause Hypothesis**

### Theory 1: Command Deserialization Issue (MOST LIKELY)
**Hypothesis**: Raft commands are being deserialized as `Map` or `LinkedHashMap` instead of actual command objects (`RegisterTopicCommand`, etc.) on followers.

**Evidence**:
1. Raft `commitIndex` and `lastApplied` are synchronized ✅
2. `applyCommittedEntries()` is being called ✅
3. `stateMachine.apply(command)` is being called ✅  
4. **BUT** topics not appearing in state machine on followers ❌

**Why This Happens**:
- Leader creates `RegisterTopicCommand` object
- Leader serializes to JSON for AppendEntries RPC
- Follower receives JSON
- Follower deserializes as `Map<String, Object>` (generic deserialization)
- `MetadataStateMachine.apply()` receives `Map` instead of `RegisterTopicCommand`
- `apply()` method has fallback for Map-based commands BUT only for `RegisterBrokerCommand`
- **RegisterTopicCommand as Map is NOT handled!**

**Code Evidence** (MetadataStateMachine.java:106-119):
```java
// Handle Map-based commands (fallback for serialization issues)
else if (command instanceof Map) {
    Map<String, Object> map = (Map<String, Object>) command;
    if (map.containsKey("brokerId") && map.containsKey("host") && map.containsKey("port")) {
        // Only handles RegisterBrokerCommand!
        RegisterBrokerCommand cmd = RegisterBrokerCommand.builder()...
        applyRegisterBroker(cmd);
    } else {
        log.error("Unknown Map command structure: {}", map);  // ← THIS IS PROBABLY HAPPENING!
    }
}
```

**Missing Handlers**:
- ❌ NO Map fallback for `RegisterTopicCommand`
- ❌ NO Map fallback for `AssignPartitionsCommand`
- ❌ NO Map fallback for other commands

---

### Theory 2: Jackson Polymorphic Deserialization Not Configured
**Hypothesis**: Jackson doesn't know which concrete class to deserialize JSON into.

**Solution Needed**:
- Add `@JsonTypeInfo` annotations to command classes
- OR configure Jackson `ObjectMapper` with type information
- OR use custom deserializer

---

### Theory 3: RestTemplate Deserialization Issue
**Hypothesis**: `RestTemplate` in `RaftNetworkClient` deserializes `AppendEntriesRequest.entries` incorrectly.

**Code Location**: `RaftNetworkClient.java:75`
```java
ResponseEntity<AppendEntriesResponse> response = restTemplate.postForEntity(
    url, request, AppendEntriesResponse.class);
```

When follower receives this, Jackson deserializes `List<RaftLogEntry>` but the `command` field inside each entry becomes a `Map`.

---

## 🔧 **Diagnostic Steps Taken**

1. ✅ Checked Raft status - All synchronized
2. ✅ Created topic on leader - Success
3. ✅ Checked followers - No topics
4. ✅ Re-checked Raft status - commitIndex increased on all nodes
5. ✅ Verified `applyCommittedEntries()` logic - Correct
6. ✅ Verified `MetadataStateMachine.apply()` - Has Map fallback but incomplete

---

## 🎯 **Next Steps to Confirm**

### Step 1: Check Logs
Look for these log messages on **followers** (Node 2 & 3):
```
ERROR MetadataStateMachine - Unknown command type: LinkedHashMap
ERROR MetadataStateMachine - Unknown Map command structure: {topicName=..., partitionCount=..., ...}
```

If you see these, **Theory 1 is confirmed**.

### Step 2: Add Detailed Logging
Temporarily add logging to see what followers receive:
```java
public void apply(Object command) {
    log.info("Applying command of type: {}", command.getClass().getName());  // ADD THIS
    log.info("Command toString: {}", command);  // ADD THIS
    ...
}
```

### Step 3: Check RaftLogEntry Deserialization
Check what type `entry.getCommand()` returns on followers.

---

## 🛠️ **Proposed Fixes**

### Fix 1: Add Map Fallback for All Commands (Quick Fix)
Add handlers in `MetadataStateMachine.apply()` for:
- `RegisterTopicCommand`
- `AssignPartitionsCommand`  
- `DeleteTopicCommand`
- `UpdatePartitionLeaderCommand`
- `UpdateISRCommand`

### Fix 2: Configure Jackson Polymorphic Types (Proper Fix)
Add to all command classes:
```java
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "@class")
public class RegisterTopicCommand { ... }
```

### Fix 3: Custom ObjectMapper Configuration
Configure RestTemplate with proper ObjectMapper:
```java
@Bean
public RestTemplate raftRestTemplate() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.activateDefaultTyping(
        mapper.getPolymorphicTypeValidator(),
        ObjectMapper.DefaultTyping.NON_FINAL,
        JsonTypeInfo.As.PROPERTY
    );
    
    MappingJackson2HttpMessageConverter converter = 
        new MappingJackson2HttpMessageConverter(mapper);
    
    RestTemplate restTemplate = new RestTemplate();
    restTemplate.getMessageConverters().add(0, converter);
    return restTemplate;
}
```

---

## 📊 **Impact**

### Current State
- ❌ **3-node cluster replication BROKEN**
- ❌ Only leader has correct state
- ❌ Followers have empty state machines
- ❌ Controller failover will LOSE ALL METADATA

### After Fix
- ✅ All nodes will have consistent state
- ✅ Controller failover will work correctly
- ✅ Followers can serve read requests
- ✅ System resilient to leader failures

---

## 🚨 **Critical Priority**

This is a **CRITICAL BUG** that breaks the entire Raft consensus system. Without this fix:
- Controller failover will not work
- Metadata will be lost if leader fails
- System cannot operate in distributed mode

**Must be fixed before proceeding to Phase 4 (ISR Management).**

---

## 📝 **User Action Required**

Please check the **terminal logs** for Node 2 and Node 3 and look for:
1. Any ERROR messages from `MetadataStateMachine`
2. Messages containing "Unknown command type"
3. Messages containing "Unknown Map command structure"
4. The actual command details being logged

This will confirm which theory is correct and guide the fix.

---

**Status**: Awaiting log output from user to confirm root cause.
