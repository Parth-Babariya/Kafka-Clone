# Quick Start - DMQ GUI Client

## Launch GUI

```bash
cd dmq-client\GUI-Client
java -jar target\dmq-gui-client.jar
```

Or use:
```bash
.\run-gui.bat
```

## Visual Overview

The GUI has 3 tabs and an output console at the bottom:

```
┌─────────────────────────────────────────────────────────┐
│ Metadata Service URL: http://localhost:9091             │
├─────────────────────────────────────────────────────────┤
│  [Producer] [Consumer] [Topics]                         │
│                                                          │
│  Topic: _________________                                │
│  Key:   _________________                                │
│  Value: _________________                                │
│         _________________                                │
│  Partition (optional): ___                               │
│  Acks: [1 (Leader) ▼]                                   │
│                                                          │
│  [Produce Single Message]  [Produce Batch (File)]       │
├─────────────────────────────────────────────────────────┤
│ Output                                                   │
│ ════════════════════════════════════════════════════════│
│ Command: produce --topic test --value "Hello"           │
│ ════════════════════════════════════════════════════════│
│ ✓ SUCCESS (Exit Code: 0)                                │
│ ────────────────────────────────────────────────────────│
│ Message produced successfully to partition 0 offset 42  │
│                                                          │
│                                              [Clear Output]│
└─────────────────────────────────────────────────────────┘
```

## Usage Examples

### Example 1: Create Topic
1. Click **Topics** tab
2. Enter topic name: `test-topic`
3. Partitions: `3`, Replication: `2`
4. Click **Create Topic**
5. See success in output console

### Example 2: Produce Single Message
1. Click **Producer** tab
2. Topic: `test-topic`
3. Key: `user-123`
4. Value: `{"action": "login", "timestamp": 1234567890}`
5. Partition: (leave empty for auto)
6. Acks: `1 (Leader)`
7. Click **Produce Single Message**

### Example 3: Produce Batch
1. Click **Producer** tab
2. Topic: `test-topic`
3. Click **Produce Batch (File)**
4. Select your batch file (JSON array)
5. See batch production in console

### Example 4: Consume Messages
1. Click **Consumer** tab
2. Topic: `test-topic`
3. Partition: `0`
4. Start Offset: `0`
5. Max Messages: `10`
6. Click **Consume Messages**
7. See formatted messages in console

## Output Console Features

- **Command Display**: Shows exact CLI command executed
- **Status Indicator**: ✓ SUCCESS or ✗ FAILED
- **Exit Code**: Shows process exit code
- **Formatted Output**: Clean, readable output from CLI
- **Auto-scroll**: Automatically scrolls to latest output
- **Clear Button**: Clear console to start fresh

## Tips

1. **Background Execution**: All commands run in background threads (UI remains responsive)
2. **Button Disable**: Buttons disabled during execution to prevent multiple simultaneous commands
3. **CLI Dependency**: Ensure `dmq-client/target/mycli.jar` exists before running GUI
4. **Service URLs**: Update metadata service URL at top if using different port
5. **Batch Files**: Use same JSON format as CLI batch files

## Troubleshooting

**GUI won't start:**
- Check Java 11+ installed: `java -version`
- Verify JAR exists: `ls target\dmq-gui-client.jar`

**Commands fail:**
- Check CLI JAR exists: `ls ..\target\mycli.jar`
- Verify metadata/storage services running
- Check metadata URL at top of GUI

**No output shown:**
- Check console for errors
- Verify CLI command in output window
- Try same command in terminal directly

**File chooser issues:**
- Use absolute paths for batch files
- Ensure batch file is valid JSON array
- Check file permissions

## Development

Source: `DMQGuiClient.java` - Single Java Swing file
Build: `mvn clean package`
Run: `java -jar target/dmq-gui-client.jar`

The GUI is intentionally minimal - it wraps CLI commands without reimplementing business logic.
