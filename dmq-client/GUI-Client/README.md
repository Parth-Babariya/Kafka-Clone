# DMQ GUI Client

A minimal GUI wrapper for the DMQ CLI commands to provide easy testing and visualization.

## Features

- **Producer Mode**: Send single or batch messages with ease
- **Consumer Mode**: Read messages with offset control
- **Topic Management**: Create, list, and describe topics
- **Beautified Output**: Clean, formatted command output display
- **CLI Wrapper**: Uses existing CLI implementation (no new code)

## Prerequisites

- Java 11+
- DMQ CLI JAR built at `../target/mycli.jar`
- Running metadata and storage services

## Building

From the `dmq-client/GUI-Client` directory:

```bash
mvn clean package
```

This creates `target/dmq-gui-client.jar`

## Running

```bash
java -jar target/dmq-gui-client.jar
```

Or use the provided launcher script:

### Windows
```bash
.\run-gui.bat
```

### Linux/Mac
```bash
./run-gui.sh
```

## Usage

### Producer Tab
1. Enter topic name
2. Enter key (optional)
3. Enter value/message
4. Select partition (optional - auto if empty)
5. Select acks level (0, 1, or -1)
6. Click "Produce Single Message" or "Produce Batch (File)"

### Consumer Tab
1. Enter topic name
2. Enter partition number
3. Enter start offset
4. Enter max messages to fetch
5. Click "Consume Messages"

### Topics Tab
1. Enter topic name
2. Enter partitions and replication factor for creation
3. Click "Create Topic", "List Topics", or "Describe Topic"

## Output Window

The bottom output area shows:
- Command being executed
- Success/failure status with exit code
- Formatted CLI output
- Clear button to reset output

## Notes

- GUI runs CLI commands in background threads (non-blocking)
- All buttons disabled during command execution
- Uses Java Swing for lightweight, cross-platform UI
- No external dependencies beyond JDK
- Wraps existing CLI - zero new business logic
