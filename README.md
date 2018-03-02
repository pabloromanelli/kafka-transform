# Kafka Transform
Filter and transform messages from one kafka topic to another.

## Test Locally
```bash
docker-compose up -d

# Checkout the output topic
docker exec -ti kafkatransform_kafka_1 kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic sinkTopic --from-beginning

# Produce more data manually
docker exec -ti kafkatransform_kafka_1 kafka-console-producer.sh --broker-list kafka:9092 --topic sourceTopic
```