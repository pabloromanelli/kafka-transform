# Kafka Transform
Filter and transform messages from one Kafka topic to another.

## Error handling
On any processing error, Kafka Transform will stop consuming and exit the process.

If the error is not transient and is related to the last message of a consumer group's topic partition, the following tools will be usefull to continue processing.

### Consumer Group status
To know which is the current offset on a particular partition for a consumer group we can use:
```bash
kafka-consumer-groups.sh --bootstrap-server $SERVER --describe --group $APPLICATION_ID
```
```
GROUP                          TOPIC                          PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             OWNER
kafka-transform                sourceTopic                    0          4499            4500            1               kafka-transform-98305c80-c535-4102-8b62-a5a7f5cf9239-StreamThread-1-consumer_/172.19.0.4
```

**Note:** The `CURRENT-OFFSET` is not the actual offset the application is handling, it represents the last offset commit.
To get the actual value you should gracefully shutdown the application and then execute the command.

**Note:** If the consumers are stopped, after a little while the command will fail with `Consumer group `<APPLICATION_ID>` is rebalancing.`.

### Peek the current message
Using the offset and partition from `kafka-consumer-groups.sh` we can see wich message is generating the error on the application.
```
kafka-console-consumer.sh --bootstrap-server $SERVER --max-messages 1 --topic $TOPIC --partition $PARTITION --offset $OFFSET
```

### Advance the Consumer Group
1. Stop all consumers of the group
1. Run kafka-verifiable-consumer.sh
1. Stop it after consuming the message (ctrl+c)
1. Start the application

```
kafka-verifiable-consumer.sh --broker-list $SERVER --group-id $APPLICATION_ID --topic sourceTopic --max-messages 1 --verbose
```

**Note:** If the application is running you will see an error like the following:
```
{"timestamp":1520368635720,"partitions":[],"name":"partitions_revoked"}
[2018-03-06 20:37:15,780] ERROR Attempt to join group kafka-transform failed due to fatal error: The group member's supported protocols are incompatible with those of existing members. (org.apache.kafka.clients.consumer.internals.AbstractCoordinator)
{"timestamp":1520368635783,"name":"shutdown_complete"}
Exception in thread "main" org.apache.kafka.common.errors.InconsistentGroupProtocolException: The group member's supported protocols are incompatible with those of existing members.
```

### Topic offset range
```bash
kafka-run-class.sh kafka.tools.GetOffsetShell --broker-list $SERVER --topic $TOPIC
```
```
<topic>:0:3137
```

## Test Locally
```bash
docker-compose up -d

# Checkout the output topic messages
docker exec -ti kafkatransform_kafka_1 kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic sinkTopic --from-beginning

# Produce more data manually
docker exec -ti kafkatransform_kafka_1 kafka-console-producer.sh --broker-list kafka:9092 --topic sourceTopic

# Check consumer group status
docker exec -ti kafkatransform_kafka_1 kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group kafka-transform

# Peek message from topic
docker exec -ti kafkatransform_kafka_1 kafka-console-consumer.sh --bootstrap-server kafka:9092 --max-messages 1 --topic sourceTopic --partition 0 --offset 2332

# Advance consumer group by 1 (stop it with ctrl+c)
docker exec -ti kafkatransform_kafka_1 kafka-verifiable-consumer.sh --broker-list kafka:9092 --group-id kafka-transform --topic sourceTopic --max-messages 1 --verbose
```