# DMQ Common Module

This module contains shared models, DTOs, exceptions, and utilities used across all DMQ services.

## Package Structure

```
com.distributedmq.common/
├── model/              # Domain models
│   ├── Message.java
│   ├── MessageHeaders.java
│   ├── TopicMetadata.java
│   ├── TopicConfig.java
│   ├── PartitionMetadata.java
│   ├── BrokerNode.java
│   ├── BrokerStatus.java
│   └── ConsumerOffset.java
├── dto/                # Data Transfer Objects
│   ├── ProduceRequest.java
│   ├── ProduceResponse.java
│   ├── ConsumeRequest.java
│   └── ConsumeResponse.java
├── exception/          # Custom exceptions
│   ├── DMQException.java
│   ├── TopicNotFoundException.java
│   ├── PartitionNotAvailableException.java
│   └── LeaderNotAvailableException.java
└── util/               # Utility classes
    ├── ChecksumUtil.java
    └── PartitionUtil.java
```

## Key Components

### Models
- **Message**: Represents a message with key, value, topic, partition, offset, and timestamp
- **TopicMetadata**: Contains topic configuration and partition information
- **PartitionMetadata**: Tracks partition leader, replicas, and ISR
- **BrokerNode**: Represents a storage node in the cluster

### DTOs
- **ProduceRequest/Response**: For message production
- **ConsumeRequest/Response**: For message consumption

### Utilities
- **PartitionUtil**: Partitioning logic (hash-based, murmur2)
- **ChecksumUtil**: CRC32 checksums for data integrity

## Usage

This module is a dependency for:
- dmq-client
- dmq-metadata-service
- dmq-storage-service
