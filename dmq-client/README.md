# DMQ Client Module

Producer and Consumer client libraries for interacting with the DMQ cluster.

## Package Structure

```
com.distributedmq.client/
├── producer/
│   ├── Producer.java           # Producer interface
│   ├── ProducerConfig.java     # Producer configuration
│   └── DMQProducer.java        # Producer implementation
└── consumer/
    ├── Consumer.java           # Consumer interface
    ├── ConsumerConfig.java     # Consumer configuration
    ├── DMQConsumer.java        # Consumer implementation
    └── TopicPartition.java     # Topic-partition representation
```

## Producer Example

```java
ProducerConfig config = ProducerConfig.builder()
    .metadataServiceUrl("http://localhost:8081")
    .batchSize(16384)
    .requiredAcks(1)
    .build();

Producer producer = new DMQProducer(config);

// Send async
Future<ProduceResponse> future = producer.send("my-topic", "key", "value".getBytes());

// Send sync
ProduceResponse response = producer.sendSync("my-topic", "key", "value".getBytes());

producer.close();
```

## Consumer Example

```java
ConsumerConfig config = ConsumerConfig.builder()
    .metadataServiceUrl("http://localhost:8081")
    .groupId("my-consumer-group")
    .enableAutoCommit(true)
    .build();

Consumer consumer = new DMQConsumer(config);

consumer.subscribe(Arrays.asList("my-topic"));

while (true) {
    List<Message> messages = consumer.poll(1000);
    for (Message msg : messages) {
        // Process message
    }
}

consumer.close();
```

## TODO
- [ ] Implement metadata fetching from metadata service
- [ ] Implement message batching
- [ ] Implement producer retries
- [ ] Implement consumer group coordination
- [ ] Implement rebalancing protocol
- [ ] Add compression support
- [ ] Add SSL/TLS support
