# Kafka Transform
Filter and transform messages from one Kafka topic to another.

## Description

```
                       ______________
                      | Rules Server |
                      |______________|
                             ||  
                     ________\/_______
 ______________     |   [http cache]  |     ______________ 
| Source Topic |==> | Kafka Transform |==> |  Sink Topic  |
|______________|    |_________________|    |______________|
```

### Features
- Filter topics
- Adapt formats
- Dynamically reload rules
- Scale by just launching more containers
- Multiply messages (produce multiple messages from a single message)

### What it does
For each message on the source topic Kafka Transform will:
1. Request the current rules to the rules url (can be cached) or the env variable
1. For each rule:
    1. Evaluate the query over the message
    1. If matches, apply the template of the rule
    1. Send the result of the template to the sink topic

**Note:**
- Every message inside the source topic must be a valid Json value (if not it will produce a deserialization error, see below)
- Every message that is not a Json Object will be discarded (non objects are incompatible with the query language, see Query section)
- Keys will be copied with no change and can have any value

## Run

#### Single host

```bash
docker run -d \
  -e rules.url=http://localhost/rules.json \
  -e kafka.application.id=my-transform \
  -e kafka.bootstrap.servers=localhost:9092 \
  -e kafka.topic.source=sourceTopic \
  -e kafka.topic.sink=sinkTopic \
  --name my-transform \
  socialmetrix/kafka-transform
```

#### Docker Swarm

Using remote rules service
```bash
docker service create \
  -e rules.url=http://localhost/rules.json \
  -e kafka.application.id=my-transform \
  -e kafka.bootstrap.servers=localhost:9092 \
  -e kafka.topic.source=sourceTopic \
  -e kafka.topic.sink=sinkTopic \
  --name my-transform \
  socialmetrix/kafka-transform
```

Using local rules
```bash
docker service create \
  -e rules.type=local \
  -e 'rules.local=[{"query":"value:9","template":"{{$this}}"}]' \
  -e kafka.application.id=my-transform \
  -e kafka.bootstrap.servers=localhost:9092 \
  -e kafka.topic.source=sourceTopic \
  -e kafka.topic.sink=sinkTopic \
  --name my-transform \
  socialmetrix/kafka-transform
```

## Scale
You can scale the service just by launching more processes.

Please note that the actual scale limit is the **number of partitions** the **source topic** has.

For example: if you have 3 partitions you will get the best throughput using 3 Kafka Transform processes (each will consume a single partition of the topic).

## Configuration
Configuration parameters can be defined with environment variables.

Look on the following sections for the specific parameter for each component.

The default configuration is defined in `src/main/resources/application.conf`.

## Kafka Streams
Under the hood the application uses Kafka Streams client 1.0 (retro-compatible with previous server versions) to consume and produce messages to and from Kafka topics.

It transform the messages with stateless transformations and filtering without modifying the original key of the messages.

You can configure every property of the Kafka Streams client using the prefix "kafka." on the environment variables. [See More](https://kafka.apache.org/10/javadoc/org/apache/kafka/streams/StreamsConfig.html)

#### Required config
```
kafka.application.id = ???
kafka.bootstrap.servers = ???
kafka.topic.source = ???
kafka.topic.sink = ???
```

## Rules
Rules are Json objects provided by an HTTP GET request with the following format:
```json
{
  "query": "lucene query",
  "template": {}
}
```

Example:
```json
{
  "query": "value:9 OR value:15",
  "template": {
    "display": "{{value}} is 9 or 15",
    "value": "{{value}}"
  }
}
```

The service must return a Json Array of rule objects containing the string field `query` and the field `template` (it can be any Json Template valid expression, see below).

### Examples

##### Filter
Only objects with `name = John` will be copied to de sink topic.
```json
{
  "query": "name:john",
  "template": "{{$this}}"
}
```

##### Transform
All messages will be transformed to an object having a single field mixing the user id and the address number on the value.
```json
{
  "query": "*:*",
  "template": {
    "user-{{id}}": "{{address.number}}"
  }
}
```

### Remote or Local rules
You can have rules hosted in an external HTTP server or defined locally using `rules.local` env variable.

For remote rules we use [play-ws](https://github.com/playframework/play-ws) as HTTP client.

The rules service must be able to listen to GET requests to `rules.url` and return a Json Array with Rule objects as defined before.

##### Configuration
By default the `rules.type` is `remote`. If you want to use local rules, you must change `rules.type` to `local` and define `rules.local` with a json array of rules.

The url of the rules is a required for `remote` rules:
```
rules.url=http://rules.service.com/rules
```

Also, you can override the default values of the following reference.conf files using env variables:
- [play-ws-standalone](https://github.com/playframework/play-ws/blob/v1.1.6/play-ws-standalone/src/main/resources/reference.conf)
- [play-ahc-ws-standalone](https://github.com/playframework/play-ws/blob/v1.1.6/play-ahc-ws-standalone/src/main/resources/reference.conf)

Every request is retried with exponential backoff. To change the retry defaults use:
```
retry.maxExecutions=10
retry.baseWait="50 millis"
retry.maxWait="5 seconds"
```

To configure the maximum time of a single request:
```
sync.timeout="5 minutes"
```

#### Cache
The HTTP client will try to cache the response of the rules service to avoid calling it on every message.

For example, if you use on the service:
```
Cache-Control: max-age=20, stale-while-revalidate=10, stale-if-error=600
```
This will only call the server once every 20 seconds, reloading the current rules on a background thread (to avoid stopping the processing) and allowing to continue the usage of the current rules after 10 minutes if the server is offline or returning 5xx responses.

##### WARNING
Due to a current limitation on the HTTP client, if the rule service is down the cached rules will continue to be returned without error even after the `stale-if-error` time.

To minimize this effect there is a configuration key to clean the cache if it was not updated on a defined time period:
```
play.ws.cache.expire="30 minutes"
```

You can use any time unit defined here: [Time Units](https://github.com/lightbend/config/blob/master/HOCON.md#duration-format)

### Query
Queries are [Lucene standard query expressions](https://lucene.apache.org/core/7_2_1/queryparser/index.html) matched against the json object found on each message.

You can use all expressions supported by the Lucene default query parser.

Every value field is temporarily "indexed" using a [MemoryIndex](https://lucene.apache.org/core/7_2_1/memory/org/apache/lucene/index/memory/MemoryIndex.html).
If an object or array is empty, it doesn't get indexed.

**Warning:** You can't use expressions without a field (there is no default field defined). So, in order to match multiple terms against a single field you could:
- Enclose the terms with `()`: `fullName:(John Doe)`
- Repeat the field name for each term: `fullName:John fullName:Doe`

#### Boolean Queries
You can use:
- Match mandatory or optionally: `AND`, `OR` (between query expressions)
- Negate a term: `-` (in front of a query expression)

The default operator is `AND`. This means that:
`firstName:John lastName:Doe` is the same as `firstName:John AND lastName:Doe`.

#### Nested fields
Nested fields are flattened using `LuceneMatcher.fieldSeparator` config (defaults to `.`).

Fields of objects inside an array are all indexed on the same field. Because of this, multiple values will match at the same time.

##### Example
```json
{
  "users": [
    { "name": "John", "age": 22 },
    { "name": "Jane", "age": 33 },
    { "name": "Other", "age": 44 }
  ]
}
```

So, the following queries will match:
- `users.name:John`
- `users.name:Jane`
- `users.name:John AND users.name:Jane`
- `users.name:John AND users.age:22`
- `users.name:John AND users.age:33` (there is no relation between fields of an array)

**Note:** Order of terms inside an array is important, the terms are indexed as if all where concatenated one after the other in order.

- This will match: `users.name:"John Jane Other"`
- This won't match: `users.name:"Other John Jane"`

#### Types
We need to differentiate the types inside the json message and the types inside the query values.

##### Query Values Type Inference
For query values, type inference is applied to see if they are Integer Numbers, Floating Point Numbers or String.

Single value:
1. If the value is quoted, its a terms query without change
1. If the value can be parsed as a Long its converted to a query using [LongPoint](https://lucene.apache.org/core/7_2_1/core/org/apache/lucene/document/LongPoint.html)
1. If the value can be parsed as a Double its converted to a query using [DoublePoint](https://lucene.apache.org/core/7_2_1/core/org/apache/lucene/document/DoublePoint.html)
1. If none of the above match, its a terms query without change

Ranges:
1. If one of the ends is a wildcard `*`
    1. If one of the ends can be parsed as Long, its converted to a range query using `LongPoint`
    1. If one of the ends can be parsed as Double, its converted to a range query using `DoublePoint`
1. If both ends are defined
    1. If both ends can be parsed as Long, its converted to a range query using `LongPoint`.
    1. If both ends can be parsed as Double, its converted to a range query using `DoublePoint`.
1. If none of the above match, its a terms range query without change

**Note:** Ranges can be inclusive `[X TO X]` and exclusive `{X TO X}`. Also, both types can be combines `[0 TO 123}`.

**Warning:** Ranges currently doesn't support quoting to provide type inference. 

**Warning:** You can't match all values (`numericField:*` or `numericField:[* TO *]`) on a numeric field (the query is interpreted as a terms query because there is no possible inference without data or schema).

Although you can create range for numeric fields if one of both ends is defined (e.g. `numericField:[* TO 123]`).

**Warning:** Integer queries won't match floating point values and vice versa (on single values and rages).

So, if the values are floating point you must get sure to use at least a `.` to induce the inference (e.g. `7.0`). 

##### Match all
This expression will match any message: `*:*`.

##### Booleans
Json message booleans are converted to the strings `true` and `false`.
Use those values to match on the query.

##### Integer Numbers
Json message integer values are indexed using [LongPoint](https://lucene.apache.org/core/7_2_1/core/org/apache/lucene/document/LongPoint.html)

##### Floating Point Numbers
Json message floating point values are indexed using [DoublePoint](https://lucene.apache.org/core/7_2_1/core/org/apache/lucene/document/DoublePoint.html)

##### String
- Json message and query values are analyzed with:
  - [StandardTokenizer](https://lucene.apache.org/core/7_2_1/core/org/apache/lucene/analysis/standard/StandardTokenizer.html)
  - [ASCIIFoldingFilter](https://lucene.apache.org/core/7_2_1/analyzers-common/org/apache/lucene/analysis/miscellaneous/ASCIIFoldingFilter.html)
  - [LowerCaseFilter](https://lucene.apache.org/core/7_2_1/analyzers-common/org/apache/lucene/analysis/core/LowerCaseFilter.html)
- Query values are only parsed as string terms if they can't be parsed as Long or Doubles (see warning below).

This implies that matching is case insensitive, tokens are split using the standard tokenizer and non ascii characters will get translated to its ascii representation (e.g. `à` -> `a`).

**Warning:** many unicode characters will be lost.

**Warning:** if the query value is numeric, it will get parsed as a [LongPoint](https://lucene.apache.org/core/7_2_1/core/org/apache/lucene/document/LongPoint.html) or [DoublePoint](https://lucene.apache.org/core/7_2_1/core/org/apache/lucene/document/DoublePoint.html).
In that case, matching against a string field will always fail. To fix it you must **escape the query number** using **double quotes `"`**.

### Template
Json Template uses Json to transform Json.
The final value will be the result of replacing every placeholder or operation with the corresponding values of the message data.
The rest of the template will be copied as is.

#### Syntax
A valid template is any valid json value (even single values like strings or numbers).

Delimiters and operations must be defined between "{{" a "}}".
The parts of the template without delimiters will be constant and copied without any change.

You can customize the delimiters and other parts of the syntax with the following keys:

```
JsonTemplate.delimiters.start = "{{"
JsonTemplate.delimiters.end = "}}"
JsonTemplate.fieldSeparator = "."
JsonTemplate.metaPrefix = "$"
JsonTemplate.thisIdentifier = "this"
JsonTemplate.commandPrefix = "#"
```

**Note:** Currently, you can't escape delimiters. 

#### Field identifiers
Fields can be nested with "." to allow access nested objects.

You can't access fields inside a nested array (the template won't be able to chose which one to chose). To access array elements you must use `map` or `flatmap`.

#### Meta-fields
- `$this`: to copy, interpolate, etc. the current json value

#### Errors
If the templates expects a field missing on the data or the operations expects other data type than the provided, an exception will be thrown.

This prevent the processing of invalid data. Please check the Error Handling section for more information.

#### Interpolate
Strings can be interpolated, even inside field names.

Use `{{fieldName}}` inside any string in your template to replace it with the value (converted to a string).

**Warning:** Only non-container nodes (any value besides array or objects) can be interpolated, they will get translated with the default toString implementation.

##### Example
Data
```json
{
  "id": 1,
  "name": "Smith"
}
```

Template
```json
{ "user-{{id}}": "Name: {{name}}" }
```

Result
```json
{ "user-1": "Name: Smith" }
```

#### Copy
You can copy a part of the tree using a string interpolated with a single variable and no text around it: `"{{fieldName}}"`.

Note that the data type and the nested nodes will remain untouched.

**Note:** Field names will always only be interpolated (because they need to be a string).

##### Example
Data
```json
{
  "id": 1,
  "user": {
    "firstName": "John",
    "lastName": "Smith"
  }
}
```

Template
```json
"{{user}}"
```

Result
```json
{
  "firstName": "John",
  "lastName": "Smith"
}
```

#### Map
Transform every element inside an array.

This operation changes the value of `$this` on every iteration.

Use `{{#map fieldName}}` as field name inside a single field object. The value of that field will be the template applied to every element of the data.
The wrapper object will be discarded (it is used only for syntactic purposes).

**Note:** The type of the field must be Array.

##### Example
Data
```json
{
  "id": 1,
  "users": [
    {
      "firstName": "John",
      "lastName": "Smith"
    },
    {
      "firstName": "Jane",
      "lastName": "Doe"
    }
  ]
}
```

Template
```json
{
  "{{#map users}}": "{{firstName}} {{lastName}}"
}
```

Result
```json
[
  "John Smith",
  "Jane Doe"
]
```

#### FlatMap
FlatMap is very similar to map, but the the result of the template on each value must be an Array.
This result will be appended to the resulting value.

This operation changes the value of `$this` on every iteration.

Use `{{#flatmap fieldName}}` as field name inside a single field object. The value of that field will be the template applied to every element of the data.
The wrapper object will be discarded (it is used only for syntactic purposes).

**Note:** The type of the field must be Array and the result of the tempalte must be an Array.

**Note:** The difference with `map` is that if map returns an array, the final result will be an array of arrays.

##### Example
Data
```json
{
  "id": 1,
  "users": [
    {
      "name": "John",
      "address": "123 Av"
    },
    {
      "name": "Jane",
      "address": "456 Av"
    }
  ]
}
```

Template
```json
{
  "{{#flatmap users}}": [
    { "name": "{{name}}" },
    { "address": "{{address}}" }
  ]
}
```

Result
```json
[
  { "name": "John" },
  { "address": "123 Av" },
  { "name": "Jane" },
  { "address": "456 Av" }
]
```

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

**Note:** If the consumers are stopped, after a little while the command will fail with `Consumer group `APPLICATION_ID` is rebalancing.`

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

## Logging
We use logback to produce the log output. The default configuration can be found on `src/main/resources/logback.xml`.

## Test Locally
Run these commands from the root of the project:

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

# Clean up everything
docker-compose down
```

## TODO
- Support custom error handling
  - ignore missing field or type errors
  - send errors to a different kafka topic
- Support constant rules without using a remote http server
- Json Template
  - Default values on missing fields or type errors
  - Add more meta-fields to the templates
    - $parent
    - $index
    - $root
  - Lookup missing fields on parent objects
  - Add template playground page to test templates with values
- Lucene Matcher  
  - Improve performance using MemoryIndex.reset and caching queries
  - Add support for Date fields
  - Add multi field matching
  - Add template playground page to test queries with values
  - Support custom analyzers
  - Support schemas to avoid inference over query values (e.g. avro + schema registry)
  - Support quoted ranges for type inference
  - Support match all (single value and ranges) on numeric fields