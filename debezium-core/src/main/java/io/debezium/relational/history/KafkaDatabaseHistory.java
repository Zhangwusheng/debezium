/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.relational.history;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.apache.kafka.common.config.ConfigDef.Width;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceRecord;

import io.debezium.annotation.NotThreadSafe;
import io.debezium.config.Configuration;
import io.debezium.config.Field;
import io.debezium.document.DocumentReader;
import io.debezium.relational.Tables;
import io.debezium.relational.ddl.DdlParser;
import io.debezium.util.Collect;

/**
 * A {@link DatabaseHistory} implementation that records schema changes as normal {@link SourceRecord}s on the specified topic,
 * and that recovers the history by establishing a Kafka Consumer re-processing all messages on that topic.
 * 
 * @author Randall Hauch
 */
@NotThreadSafe
public class KafkaDatabaseHistory extends AbstractDatabaseHistory {

    public static final Field TOPIC = Field.create(CONFIGURATION_FIELD_PREFIX_STRING + "kafka.topic")
                                           .withDisplayName("Database history topic name")
                                           .withType(Type.STRING)
                                           .withWidth(Width.LONG)
                                           .withImportance(Importance.HIGH)
                                           .withDescription("The name of the topic for the database schema history")
                                           .withValidation(Field::isRequired);

    public static final Field BOOTSTRAP_SERVERS = Field.create(CONFIGURATION_FIELD_PREFIX_STRING + "kafka.bootstrap.servers")
                                                       .withDisplayName("Kafka broker addresses")
                                                       .withType(Type.STRING)
                                                       .withWidth(Width.LONG)
                                                       .withImportance(Importance.HIGH)
                                                       .withDescription("A list of host/port pairs that the connector will use for establishing the initial "
                                                               + "connection to the Kafka cluster for retrieving database schema history previously stored "
                                                               + "by the connector. This should point to the same Kafka cluster used by the Kafka Connect "
                                                               + "process.")
                                                       .withValidation(Field::isRequired);

    public static final Field RECOVERY_POLL_INTERVAL_MS = Field.create(CONFIGURATION_FIELD_PREFIX_STRING
            + "kafka.recovery.poll.interval.ms")
                                                               .withDisplayName("Poll interval during database history recovery (ms)")
                                                               .withType(Type.INT)
                                                               .withWidth(Width.SHORT)
                                                               .withImportance(Importance.LOW)
                                                               .withDescription("The number of milliseconds to wait while polling for persisted data during recovery.")
                                                               .withDefault(100)
                                                               .withValidation(Field::isInteger);

    public static final Field RECOVERY_POLL_ATTEMPTS = Field.create(CONFIGURATION_FIELD_PREFIX_STRING + "kafka.recovery.attempts")
                                                            .withDisplayName("Max attempts to recovery database history")
                                                            .withType(Type.INT)
                                                            .withWidth(Width.SHORT)
                                                            .withImportance(Importance.LOW)
                                                            .withDescription("The number of attempts in a row that no data are returned from Kafka before recover completes. "
                                                                    + "The maximum amount of time to wait after receiving no data is (recovery.attempts) x (recovery.poll.interval.ms).")
                                                            .withDefault(4)
                                                            .withValidation(Field::isInteger);

    public static Field.Set ALL_FIELDS = Field.setOf(TOPIC, BOOTSTRAP_SERVERS, DatabaseHistory.NAME,
                                                     RECOVERY_POLL_INTERVAL_MS, RECOVERY_POLL_ATTEMPTS);

    private static final String CONSUMER_PREFIX = CONFIGURATION_FIELD_PREFIX_STRING + "consumer.";
    private static final String PRODUCER_PREFIX = CONFIGURATION_FIELD_PREFIX_STRING + "producer.";

    private final DocumentReader reader = DocumentReader.defaultReader();
    private final Integer partition = new Integer(0);
    private String topicName;
    private Configuration consumerConfig;
    private Configuration producerConfig;
    private volatile KafkaProducer<String, String> producer;
    private int recoveryAttempts = -1;
    private int pollIntervalMs = -1;

    @Override
    public void configure(Configuration config, HistoryRecordComparator comparator) {
        super.configure(config, comparator);
        if (!config.validateAndRecord(ALL_FIELDS, logger::error)) {
            throw new ConnectException("Error configuring an instance of " + getClass().getSimpleName() + "; check the logs for details");
        }
        this.topicName = config.getString(TOPIC);
        this.pollIntervalMs = config.getInteger(RECOVERY_POLL_INTERVAL_MS);
        this.recoveryAttempts = config.getInteger(RECOVERY_POLL_ATTEMPTS);

        String bootstrapServers = config.getString(BOOTSTRAP_SERVERS);
        // Copy the relevant portions of the configuration and add useful defaults ...
        String dbHistoryName = config.getString(DatabaseHistory.NAME, UUID.randomUUID().toString());
        this.consumerConfig = config.subset(CONSUMER_PREFIX, true).edit()
                                    .withDefault(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                                    .withDefault(ConsumerConfig.CLIENT_ID_CONFIG, dbHistoryName)
                                    .withDefault(ConsumerConfig.GROUP_ID_CONFIG, dbHistoryName)
                                    .withDefault(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1) // get even smallest message
                                    .withDefault(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                                    .withDefault(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000)
                                    .withDefault(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                                                 OffsetResetStrategy.EARLIEST.toString().toLowerCase())
                                    .withDefault(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                                    .withDefault(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                                    .build();
        this.producerConfig = config.subset(PRODUCER_PREFIX, true).edit()
                                    .withDefault(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                                    .withDefault(ProducerConfig.CLIENT_ID_CONFIG, dbHistoryName)
                                    .withDefault(ProducerConfig.ACKS_CONFIG, 1)
                                    .withDefault(ProducerConfig.RETRIES_CONFIG, 1) // may result in duplicate messages, but that's
                                                                                   // okay
                                    .withDefault(ProducerConfig.BATCH_SIZE_CONFIG, 1024 * 32) // 32KB
                                    .withDefault(ProducerConfig.LINGER_MS_CONFIG, 0)
                                    .withDefault(ProducerConfig.BUFFER_MEMORY_CONFIG, 1024 * 1024) // 1MB
                                    .withDefault(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                                    .withDefault(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                                    .build();
        logger.info("KafkaDatabaseHistory Consumer config: " + consumerConfig.withMaskedPasswords());
        logger.info("KafkaDatabaseHistory Producer config: " + producerConfig.withMaskedPasswords());
    }

    @Override
    public void start() {
        super.start();
        if (this.producer == null) {
            this.producer = new KafkaProducer<>(this.producerConfig.asProperties());
        }
    }

    @Override
    protected void storeRecord(HistoryRecord record) {
        if (this.producer == null) {
            throw new IllegalStateException("No producer is available. Ensure that 'start()' is called before storing database history records.");
        }
        logger.trace("Storing record into database history: {}", record);
        try {
            ProducerRecord<String, String> produced = new ProducerRecord<>(topicName, partition, null, record.toString());
            Future<RecordMetadata> future = this.producer.send(produced);
            // Flush and then wait ...
            this.producer.flush();
            RecordMetadata metadata = future.get(); // block forever since we have to be sure this gets recorded
            if (metadata != null) {
                logger.debug("Stored record in topic '{}' partition {} at offset {} ",
                             metadata.topic(), metadata.partition(), metadata.offset());
            }
        } catch (InterruptedException e) {
            logger.error("Interrupted while waiting for response to storing record into database history: {}", record);
        } catch (ExecutionException e) {
            logger.error("Error while storing database history record into Kafka: {}", record, e);
        }
    }

    @Override
    protected void recoverRecords(Tables schema, DdlParser ddlParser, Consumer<HistoryRecord> records) {
        try (KafkaConsumer<String, String> historyConsumer = new KafkaConsumer<String, String>(consumerConfig.asProperties());) {
            // Subscribe to the only partition for this topic, and seek to the beginning of that partition ...
            TopicPartition topicPartition = new TopicPartition(topicName, partition);
            logger.debug("Subscribing to database history topic '{}' partition {} at offset 0", topicPartition.topic(),
                         topicPartition.partition());
            historyConsumer.subscribe(Collect.arrayListOf(topicName));

            // Read all messages in the topic ...
            int remainingEmptyPollResults = this.recoveryAttempts;
            while (remainingEmptyPollResults > 0) {
                ConsumerRecords<String, String> recoveredRecords = historyConsumer.poll(this.pollIntervalMs);
                logger.debug("Read {} records from database history", recoveredRecords.count());
                if (recoveredRecords.isEmpty()) {
                    --remainingEmptyPollResults;
                } else {
                    remainingEmptyPollResults = this.recoveryAttempts;
                    for (ConsumerRecord<String, String> record : recoveredRecords) {
                        try {
                            HistoryRecord recordObj = new HistoryRecord(reader.read(record.value()));
                            records.accept(recordObj);
                            logger.trace("Recovered database history: {}" + recordObj);
                        } catch (IOException e) {
                            logger.error("Error while deserializing history record", e);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void stop() {
        try {
            if (this.producer != null) {
                try {
                    this.producer.flush();
                } finally {
                    this.producer.close();
                }
            }
        } finally {
            this.producer = null;
            super.stop();
        }
    }

    @Override
    public String toString() {
        if (topicName != null) {
            return "Kakfa topic " + topicName + (partition != null ? (":" + partition) : "")
                    + " using brokers at " + producerConfig.getString(BOOTSTRAP_SERVERS);
        }
        return "Kafka topic";
    }
}
