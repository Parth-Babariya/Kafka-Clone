# MyCli - DistributedMQ Command Line Interface

A simple command-line tool for interacting with the DistributedMQ system.

## Build

```bash
mvn package -DskipTests -pl dmq-client
```

The executable JAR will be created at: `dmq-client/target/mycli.jar`

## Usage

```bash
java -jar mycli.jar <command> [options]
```

### Available Commands

- `create-topic` - Create a new topic
- `produce` - Produce messages to a topic
- `help` - Show help message
- `version` - Show version information

## Examples

### Create a Topic

```bash
java -jar mycli.jar create-topic --name orders --partitions 3 --replication-factor 2
```

With custom metadata service URL:

```bash
java -jar mycli.jar create-topic --name logs --partitions 10 --replication-factor 3 --metadata-url http://localhost:9091
```

### Produce Messages

Simple message:

```bash
java -jar mycli.jar produce --topic orders --value "Order data"
```

With key (for partition selection):

```bash
java -jar mycli.jar produce --topic orders --key order-123 --value "Order data"
```

To specific partition:

```bash
java -jar mycli.jar produce --topic logs --value "Log message" --partition 0
```

With acknowledgment mode:

```bash
java -jar mycli.jar produce --topic events --key evt-456 --value "Event" --acks 1
```

Acknowledgment modes:
- `-1` or `all`: Wait for all replicas (most durable)
- `1` or `leader`: Wait for leader only (default)
- `0` or `none`: Fire and forget (fastest)

## Configuration

The CLI uses the following configuration priority:

1. Command-line arguments (highest priority)
2. Configuration file (not yet implemented)
3. Default values (lowest priority)

### Default Values

- Metadata service URL: `http://localhost:9091`
- Acknowledgment mode: `1` (leader)
- Partitioning: Hash-based on key, or round-robin if no key

## Architecture

### Producer Flow

1. **Metadata Discovery**: Fetch topic metadata from metadata service
2. **Partition Selection**:
   - Explicit partition: Use if provided with `--partition`
   - Key-based: Hash the key to select partition
   - Round-robin: If no key or partition specified
3. **Leader Discovery**: Find the leader broker for the selected partition
4. **Send Message**: Send produce request to the leader broker
5. **Response**: Display success/failure with offset and timestamp

### Metadata Caching

The producer caches topic metadata to avoid repeated lookups during a CLI session. The cache is cleared when the producer is closed.

## Error Handling

The CLI provides clear error messages for common issues:

- Invalid topic name or partition
- Metadata service unavailable
- No leader available for partition
- Network timeout
- Invalid acknowledgment mode

## Future Enhancements

Planned features:

1. **Consumer Support**: Add consume command for reading messages
2. **Interactive Mode**: Start an interactive shell session
3. **File Input**: Read messages from file for batch production
4. **Configuration File**: Support for config file (~/.mycli.conf)
5. **Consumer Group Management**: Join/leave consumer groups
6. **Topic Management**: List, describe, delete topics
7. **Compression**: Support for message compression
8. **Batch Production**: Send multiple messages efficiently
9. **Transaction Support**: Atomic multi-partition writes
10. **Output Formats**: JSON, CSV, or custom formats

## Dependencies

- Java 11+
- Jackson for JSON serialization
- Java 11 HttpClient for REST calls
- dmq-common module (included in shaded JAR)

## Development

### Project Structure

```
dmq-client/
├── src/main/java/com/distributedmq/client/
│   ├── cli/
│   │   ├── MyCli.java              # Main entry point
│   │   ├── Command.java            # Command interface
│   │   ├── commands/
│   │   │   ├── CreateTopicCommand.java
│   │   │   └── ProduceCommand.java
│   │   └── utils/
│   │       ├── ArgumentParser.java     # Simple CLI parser
│   │       └── MetadataServiceClient.java
│   └── producer/
│       └── SimpleProducer.java     # Producer implementation
└── pom.xml                         # Maven Shade Plugin config
```

### Adding New Commands

1. Create a new class implementing `Command` interface
2. Add the command to the dispatcher in `MyCli.java`
3. Implement `execute()` and `printHelp()` methods
4. Update this README with usage examples

## License

Same as the parent project.
