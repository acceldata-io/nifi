/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nifi.processors.kafka.pubsub;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.OutOfOrderSequenceException;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.nifi.annotation.behavior.DynamicProperty;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.ConfigVerificationResult;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.PropertyDescriptor.Builder;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.kafka.shared.attribute.StandardTransitUriProvider;
import org.apache.nifi.kafka.shared.property.PublishStrategy;
import org.apache.nifi.kafka.shared.transaction.TransactionIdSupplier;
import org.apache.nifi.kafka.shared.validation.KafkaClientCustomValidationFunction;
import org.apache.nifi.kafka.shared.property.FailureStrategy;
import org.apache.nifi.kafka.shared.property.provider.KafkaPropertyProvider;
import org.apache.nifi.kafka.shared.component.KafkaPublishComponent;
import org.apache.nifi.kafka.shared.property.provider.StandardKafkaPropertyProvider;
import org.apache.nifi.kafka.shared.validation.DynamicPropertyValidator;
import org.apache.nifi.kafka.shared.validation.KafkaDeprecationValidator;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.DataUnit;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.VerifiableProcessor;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.record.path.RecordPath;
import org.apache.nifi.record.path.RecordPathResult;
import org.apache.nifi.record.path.util.RecordPathCache;
import org.apache.nifi.record.path.validation.RecordPathValidator;
import org.apache.nifi.schema.access.SchemaNotFoundException;
import org.apache.nifi.serialization.MalformedRecordException;
import org.apache.nifi.serialization.RecordReader;
import org.apache.nifi.serialization.RecordReaderFactory;
import org.apache.nifi.serialization.RecordSetWriterFactory;
import org.apache.nifi.serialization.record.Record;
import org.apache.nifi.serialization.record.RecordSchema;
import org.apache.nifi.serialization.record.RecordSet;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.apache.nifi.expression.ExpressionLanguageScope.FLOWFILE_ATTRIBUTES;
import static org.apache.nifi.expression.ExpressionLanguageScope.NONE;
import static org.apache.nifi.expression.ExpressionLanguageScope.VARIABLE_REGISTRY;
import static org.apache.nifi.kafka.shared.attribute.KafkaFlowFileAttribute.KAFKA_CONSUMER_OFFSETS_COMMITTED;

@Tags({"Apache", "Kafka", "Record", "csv", "json", "avro", "logs", "Put", "Send", "Message", "PubSub", "2.6"})
@CapabilityDescription("Sends the contents of a FlowFile as individual records to Apache Kafka using the Kafka 2.6 Producer API. "
    + "The contents of the FlowFile are expected to be record-oriented data that can be read by the configured Record Reader. "
    + "The complementary NiFi processor for fetching messages is ConsumeKafkaRecord_2_6.")
@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@DynamicProperty(name = "The name of a Kafka configuration property.", value = "The value of a given Kafka configuration property.",
    description = "These properties will be added on the Kafka configuration after loading any provided configuration properties."
    + " In the event a dynamic property represents a property that was already set, its value will be ignored and WARN message logged."
    + " For the list of available Kafka properties please refer to: http://kafka.apache.org/documentation.html#configuration. ",
    expressionLanguageScope = VARIABLE_REGISTRY)
@WritesAttribute(attribute = "msg.count", description = "The number of messages that were sent to Kafka for this FlowFile. This attribute is added only to "
    + "FlowFiles that are routed to success.")
@SeeAlso({PublishKafka_2_6.class, ConsumeKafka_2_6.class, ConsumeKafkaRecord_2_6.class})
public class PublishKafkaRecord_2_6 extends AbstractProcessor implements KafkaPublishComponent, VerifiableProcessor {
    protected static final String MSG_COUNT = "msg.count";

    static final AllowableValue DELIVERY_REPLICATED = new AllowableValue("all", "Guarantee Replicated Delivery",
        "FlowFile will be routed to failure unless the message is replicated to the appropriate "
            + "number of Kafka Nodes according to the Topic configuration");
    static final AllowableValue DELIVERY_ONE_NODE = new AllowableValue("1", "Guarantee Single Node Delivery",
        "FlowFile will be routed to success if the message is received by a single Kafka node, "
            + "whether or not it is replicated. This is faster than <Guarantee Replicated Delivery> "
            + "but can result in data loss if a Kafka node crashes");
    static final AllowableValue DELIVERY_BEST_EFFORT = new AllowableValue("0", "Best Effort",
        "FlowFile will be routed to success after successfully sending the content to a Kafka node, "
            + "without waiting for any acknowledgment from the node at all. This provides the best performance but may result in data loss.");

    static final AllowableValue ROUND_ROBIN_PARTITIONING = new AllowableValue(Partitioners.RoundRobinPartitioner.class.getName(),
        Partitioners.RoundRobinPartitioner.class.getSimpleName(),
        "Messages will be assigned partitions in a round-robin fashion, sending the first message to Partition 1, "
            + "the next Partition to Partition 2, and so on, wrapping as necessary.");
    static final AllowableValue RANDOM_PARTITIONING = new AllowableValue("org.apache.kafka.clients.producer.internals.DefaultPartitioner",
        "DefaultPartitioner", "The default partitioning strategy will choose the sticky partition that changes when the batch is full "
                + "(See KIP-480 for details about sticky partitioning).");
    static final AllowableValue RECORD_PATH_PARTITIONING = new AllowableValue(Partitioners.RecordPathPartitioner.class.getName(),
        "RecordPath Partitioner", "Interprets the <Partition> property as a RecordPath that will be evaluated against each Record to determine which partition the Record will go to. All Records " +
        "that have the same value for the given RecordPath will go to the same Partition.");
    static final AllowableValue EXPRESSION_LANGUAGE_PARTITIONING = new AllowableValue(Partitioners.ExpressionLanguagePartitioner.class.getName(), "Expression Language Partitioner",
        "Interprets the <Partition> property as Expression Language that will be evaluated against each FlowFile. This Expression will be evaluated once against the FlowFile, " +
            "so all Records in a given FlowFile will go to the same partition.");

    static final AllowableValue RECORD_METADATA_FROM_RECORD = new AllowableValue("Metadata From Record", "Metadata From Record", "The Kafka Record's Topic and Partition will be determined by " +
        "looking at the /metadata/topic and /metadata/partition fields of the Record, respectively. If these fields are invalid or not present, the Topic Name and Partition/Partitioner class " +
        "properties of the processor will be considered.");
    static final AllowableValue RECORD_METADATA_FROM_PROPERTIES = new AllowableValue("Use Configured Values", "Use Configured Values", "The Kafka Record's Topic will be determined using the 'Topic " +
        "Name' processor property. The partition will be determined using the 'Partition' and 'Partitioner class' properties.");

    static final PropertyDescriptor TOPIC = new Builder()
        .name("topic")
        .displayName("Topic Name")
        .description("The name of the Kafka Topic to publish to.")
        .required(true)
        .addValidator(StandardValidators.NON_BLANK_VALIDATOR)
        .expressionLanguageSupported(FLOWFILE_ATTRIBUTES)
        .build();

    static final PropertyDescriptor RECORD_READER = new Builder()
        .name("record-reader")
        .displayName("Record Reader")
        .description("The Record Reader to use for incoming FlowFiles")
        .identifiesControllerService(RecordReaderFactory.class)
        .expressionLanguageSupported(NONE)
        .required(true)
        .build();

    static final PropertyDescriptor RECORD_WRITER = new Builder()
        .name("record-writer")
        .displayName("Record Writer")
        .description("The Record Writer to use in order to serialize the data before sending to Kafka")
        .identifiesControllerService(RecordSetWriterFactory.class)
        .expressionLanguageSupported(NONE)
        .required(true)
        .build();

    static final PropertyDescriptor PUBLISH_STRATEGY = new PropertyDescriptor.Builder()
            .name("publish-strategy")
            .displayName("Publish Strategy")
            .description("The format used to publish the incoming FlowFile record to Kafka.")
            .required(true)
            .defaultValue(PublishStrategy.USE_VALUE.getValue())
            .allowableValues(PublishStrategy.class)
            .build();

    static final PropertyDescriptor MESSAGE_KEY_FIELD = new Builder()
        .name("message-key-field")
        .displayName("Message Key Field")
        .description("The name of a field in the Input Records that should be used as the Key for the Kafka message.")
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .expressionLanguageSupported(FLOWFILE_ATTRIBUTES)
        .dependsOn(PUBLISH_STRATEGY, PublishStrategy.USE_VALUE.getValue())
        .required(false)
        .build();

    static final PropertyDescriptor DELIVERY_GUARANTEE = new Builder()
        .name("acks")
        .displayName("Delivery Guarantee")
        .description("Specifies the requirement for guaranteeing that a message is sent to Kafka. Corresponds to Kafka's 'acks' property.")
        .required(true)
        .expressionLanguageSupported(NONE)
        .allowableValues(DELIVERY_BEST_EFFORT, DELIVERY_ONE_NODE, DELIVERY_REPLICATED)
        .defaultValue(DELIVERY_REPLICATED.getValue())
        .build();

    static final PropertyDescriptor METADATA_WAIT_TIME = new Builder()
        .name("max.block.ms")
        .displayName("Max Metadata Wait Time")
        .description("The amount of time publisher will wait to obtain metadata or wait for the buffer to flush during the 'send' call before failing the "
            + "entire 'send' call. Corresponds to Kafka's 'max.block.ms' property")
        .required(true)
        .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
        .expressionLanguageSupported(VARIABLE_REGISTRY)
        .defaultValue("5 sec")
        .build();

    static final PropertyDescriptor ACK_WAIT_TIME = new Builder()
        .name("ack.wait.time")
        .displayName("Acknowledgment Wait Time")
        .description("After sending a message to Kafka, this indicates the amount of time that we are willing to wait for a response from Kafka. "
            + "If Kafka does not acknowledge the message within this time period, the FlowFile will be routed to 'failure'.")
        .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
        .expressionLanguageSupported(NONE)
        .required(true)
        .defaultValue("5 secs")
        .build();

    static final PropertyDescriptor MAX_REQUEST_SIZE = new Builder()
        .name("max.request.size")
        .displayName("Max Request Size")
        .description("The maximum size of a request in bytes. Corresponds to Kafka's 'max.request.size' property and defaults to 1 MB (1048576).")
        .required(true)
        .addValidator(StandardValidators.DATA_SIZE_VALIDATOR)
        .defaultValue("1 MB")
        .build();

    static final PropertyDescriptor PARTITION_CLASS = new Builder()
        .name("partitioner.class")
        .displayName("Partitioner class")
        .description("Specifies which class to use to compute a partition id for a message. Corresponds to Kafka's 'partitioner.class' property.")
        .allowableValues(ROUND_ROBIN_PARTITIONING, RANDOM_PARTITIONING, RECORD_PATH_PARTITIONING, EXPRESSION_LANGUAGE_PARTITIONING)
        .defaultValue(RANDOM_PARTITIONING.getValue())
        .required(false)
        .build();

    static final PropertyDescriptor PARTITION = new Builder()
        .name("partition")
        .displayName("Partition")
        .description("Specifies which Partition Records will go to. How this value is interpreted is dictated by the <Partitioner class> property.")
        .required(false)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .expressionLanguageSupported(FLOWFILE_ATTRIBUTES)
        .build();

    static final PropertyDescriptor COMPRESSION_CODEC = new Builder()
        .name("compression.type")
        .displayName("Compression Type")
        .description("This parameter allows you to specify the compression codec for all data generated by this producer.")
        .required(true)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .allowableValues("none", "gzip", "snappy", "lz4")
        .defaultValue("none")
        .build();

    static final PropertyDescriptor ATTRIBUTE_NAME_REGEX = new Builder()
        .name("attribute-name-regex")
        .displayName("Attributes to Send as Headers (Regex)")
        .description("A Regular Expression that is matched against all FlowFile attribute names. "
            + "Any attribute whose name matches the regex will be added to the Kafka messages as a Header. "
            + "If not specified, no FlowFile attributes will be added as headers.")
        .addValidator(StandardValidators.REGULAR_EXPRESSION_VALIDATOR)
        .expressionLanguageSupported(NONE)
        .dependsOn(PUBLISH_STRATEGY, PublishStrategy.USE_VALUE.getValue())
        .required(false)
        .build();
    static final PropertyDescriptor USE_TRANSACTIONS = new Builder()
        .name("use-transactions")
        .displayName("Use Transactions")
        .description("Specifies whether or not NiFi should provide Transactional guarantees when communicating with Kafka. If there is a problem sending data to Kafka, "
            + "and this property is set to false, then the messages that have already been sent to Kafka will continue on and be delivered to consumers. "
            + "If this is set to true, then the Kafka transaction will be rolled back so that those messages are not available to consumers. Setting this to true "
            + "requires that the <Delivery Guarantee> property be set to \"Guarantee Replicated Delivery.\"")
        .expressionLanguageSupported(NONE)
        .allowableValues("true", "false")
        .defaultValue("true")
        .required(true)
        .build();
    static final PropertyDescriptor TRANSACTIONAL_ID_PREFIX = new Builder()
        .name("transactional-id-prefix")
        .displayName("Transactional Id Prefix")
        .description("When Use Transaction is set to true, KafkaProducer config 'transactional.id' will be a generated UUID and will be prefixed with this string.")
        .expressionLanguageSupported(VARIABLE_REGISTRY)
        .addValidator(StandardValidators.NON_EMPTY_EL_VALIDATOR)
        .dependsOn(USE_TRANSACTIONS, "true")
        .required(false)
        .build();
    static final PropertyDescriptor MESSAGE_HEADER_ENCODING = new Builder()
        .name("message-header-encoding")
        .displayName("Message Header Encoding")
        .description("For any attribute that is added as a message header, as configured via the <Attributes to Send as Headers> property, "
            + "this property indicates the Character Encoding to use for serializing the headers.")
        .addValidator(StandardValidators.CHARACTER_SET_VALIDATOR)
        .defaultValue("UTF-8")
        .required(false)
        .build();
    static final PropertyDescriptor RECORD_KEY_WRITER = new PropertyDescriptor.Builder()
            .name("record-key-writer")
            .displayName("Record Key Writer")
            .description("The Record Key Writer to use for outgoing FlowFiles")
            .identifiesControllerService(RecordSetWriterFactory.class)
            .dependsOn(PUBLISH_STRATEGY, PublishStrategy.USE_WRAPPER.getValue())
            .build();
    static final PropertyDescriptor RECORD_METADATA_STRATEGY = new Builder()
        .name("Record Metadata Strategy")
        .displayName("Record Metadata Strategy")
        .description("Specifies whether the Record's metadata (topic and partition) should come from the Record's metadata field or if it should come from the configured Topic Name and Partition / " +
            "Partitioner class properties")
        .required(true)
        .allowableValues(RECORD_METADATA_FROM_PROPERTIES, RECORD_METADATA_FROM_RECORD)
        .defaultValue(RECORD_METADATA_FROM_PROPERTIES.getValue())
        .dependsOn(PUBLISH_STRATEGY, PublishStrategy.USE_WRAPPER.getValue())
        .build();

    static final Relationship REL_SUCCESS = new Relationship.Builder()
        .name("success")
        .description("FlowFiles for which all content was sent to Kafka.")
        .build();

    static final Relationship REL_FAILURE = new Relationship.Builder()
        .name("failure")
        .description("Any FlowFile that cannot be sent to Kafka will be routed to this Relationship")
        .build();

    private static final List<PropertyDescriptor> PROPERTIES;
    private static final Set<Relationship> RELATIONSHIPS;

    private volatile PublisherPool publisherPool = null;
    private final RecordPathCache recordPathCache = new RecordPathCache(25);

    static {
        final List<PropertyDescriptor> properties = new ArrayList<>();
        properties.add(BOOTSTRAP_SERVERS);
        properties.add(TOPIC);
        properties.add(RECORD_READER);
        properties.add(RECORD_WRITER);
        properties.add(USE_TRANSACTIONS);
        properties.add(TRANSACTIONAL_ID_PREFIX);
        properties.add(FAILURE_STRATEGY);
        properties.add(DELIVERY_GUARANTEE);
        properties.add(PUBLISH_STRATEGY);
        properties.add(RECORD_KEY_WRITER);
        properties.add(RECORD_METADATA_STRATEGY);
        properties.add(ATTRIBUTE_NAME_REGEX);
        properties.add(MESSAGE_HEADER_ENCODING);
        properties.add(SECURITY_PROTOCOL);
        properties.add(SASL_MECHANISM);
        properties.add(KERBEROS_CREDENTIALS_SERVICE);
        properties.add(SELF_CONTAINED_KERBEROS_USER_SERVICE);
        properties.add(KERBEROS_SERVICE_NAME);
        properties.add(KERBEROS_PRINCIPAL);
        properties.add(KERBEROS_KEYTAB);
        properties.add(SASL_USERNAME);
        properties.add(SASL_PASSWORD);
        properties.add(TOKEN_AUTHENTICATION);
        properties.add(AWS_PROFILE_NAME);
        properties.add(SSL_CONTEXT_SERVICE);
        properties.add(MESSAGE_KEY_FIELD);
        properties.add(MAX_REQUEST_SIZE);
        properties.add(ACK_WAIT_TIME);
        properties.add(METADATA_WAIT_TIME);
        properties.add(PARTITION_CLASS);
        properties.add(PARTITION);
        properties.add(COMPRESSION_CODEC);

        PROPERTIES = Collections.unmodifiableList(properties);

        final Set<Relationship> relationships = new HashSet<>();
        relationships.add(REL_SUCCESS);
        relationships.add(REL_FAILURE);
        RELATIONSHIPS = Collections.unmodifiableSet(relationships);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return RELATIONSHIPS;
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return PROPERTIES;
    }

    @Override
    protected PropertyDescriptor getSupportedDynamicPropertyDescriptor(final String propertyDescriptorName) {
        return new Builder()
            .description("Specifies the value for '" + propertyDescriptorName + "' Kafka Configuration.")
            .name(propertyDescriptorName)
            .addValidator(new DynamicPropertyValidator(ProducerConfig.class))
            .dynamic(true)
            .expressionLanguageSupported(VARIABLE_REGISTRY)
            .build();
    }

    @Override
    protected Collection<ValidationResult> customValidate(final ValidationContext validationContext) {
        KafkaDeprecationValidator.validate(getClass(), getIdentifier(), validationContext);

        final KafkaClientCustomValidationFunction validationFunction = new KafkaClientCustomValidationFunction();
        final Collection<ValidationResult> results = validationFunction.apply(validationContext);

        final boolean useTransactions = validationContext.getProperty(USE_TRANSACTIONS).asBoolean();
        if (useTransactions) {
            final String deliveryGuarantee = validationContext.getProperty(DELIVERY_GUARANTEE).getValue();
            if (!DELIVERY_REPLICATED.getValue().equals(deliveryGuarantee)) {
                results.add(new ValidationResult.Builder()
                    .subject("Delivery Guarantee")
                    .valid(false)
                    .explanation("In order to use Transactions, the Delivery Guarantee must be \"Guarantee Replicated Delivery.\" "
                        + "Either change the <Use Transactions> property or the <Delivery Guarantee> property.")
                    .build());
            }
        }

        final String partitionClass = validationContext.getProperty(PARTITION_CLASS).getValue();
        if (RECORD_PATH_PARTITIONING.getValue().equals(partitionClass)) {
            final String rawRecordPath = validationContext.getProperty(PARTITION).getValue();
            if (rawRecordPath == null) {
                results.add(new ValidationResult.Builder()
                    .subject("Partition")
                    .valid(false)
                    .explanation("The <Partition> property must be specified if using the RecordPath Partitioning class")
                    .build());
            } else if (!validationContext.isExpressionLanguagePresent(rawRecordPath)) {
                final ValidationResult result = new RecordPathValidator().validate(PARTITION.getDisplayName(), rawRecordPath, validationContext);
                if (result != null) {
                    results.add(result);
                }
            }
        } else if (EXPRESSION_LANGUAGE_PARTITIONING.getValue().equals(partitionClass)) {
            final String rawRecordPath = validationContext.getProperty(PARTITION).getValue();
            if (rawRecordPath == null) {
                results.add(new ValidationResult.Builder()
                    .subject("Partition")
                    .valid(false)
                    .explanation("The <Partition> property must be specified if using the Expression Language Partitioning class")
                    .build());
            }
        }

        return results;
    }

    private synchronized PublisherPool getPublisherPool(final ProcessContext context) {
        PublisherPool pool = publisherPool;
        if (pool != null) {
            return pool;
        }

        return publisherPool = createPublisherPool(context);
    }

    protected PublisherPool createPublisherPool(final ProcessContext context) {
        final int maxMessageSize = context.getProperty(MAX_REQUEST_SIZE).asDataSize(DataUnit.B).intValue();
        final long maxAckWaitMillis = context.getProperty(ACK_WAIT_TIME).asTimePeriod(TimeUnit.MILLISECONDS);

        final String attributeNameRegex = context.getProperty(ATTRIBUTE_NAME_REGEX).getValue();
        final Pattern attributeNamePattern = attributeNameRegex == null ? null : Pattern.compile(attributeNameRegex);
        final boolean useTransactions = context.getProperty(USE_TRANSACTIONS).asBoolean();
        final String transactionalIdPrefix = context.getProperty(TRANSACTIONAL_ID_PREFIX).evaluateAttributeExpressions().getValue();
        Supplier<String> transactionalIdSupplier = new TransactionIdSupplier(transactionalIdPrefix);
        final PublishStrategy publishStrategy = PublishStrategy.valueOf(context.getProperty(PUBLISH_STRATEGY).getValue());

        final String charsetName = context.getProperty(MESSAGE_HEADER_ENCODING).evaluateAttributeExpressions().getValue();
        final Charset charset = Charset.forName(charsetName);
        final RecordSetWriterFactory recordKeyWriterFactory = context.getProperty(RECORD_KEY_WRITER).asControllerService(RecordSetWriterFactory.class);

        final KafkaPropertyProvider propertyProvider = new StandardKafkaPropertyProvider(ProducerConfig.class);
        final Map<String, Object> kafkaProperties = propertyProvider.getProperties(context);
        kafkaProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        kafkaProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        kafkaProperties.put("max.request.size", String.valueOf(maxMessageSize));

        return new PublisherPool(kafkaProperties, getLogger(), maxMessageSize, maxAckWaitMillis,
                useTransactions, transactionalIdSupplier, attributeNamePattern, charset, publishStrategy, recordKeyWriterFactory);
    }

    @OnStopped
    public void closePool() {
        if (publisherPool != null) {
            publisherPool.close();
        }

        publisherPool = null;
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        final List<FlowFile> flowFiles = PublishKafkaUtil.pollFlowFiles(session);
        if (flowFiles.isEmpty()) {
            return;
        }

        final PublisherPool pool = getPublisherPool(context);
        if (pool == null) {
            context.yield();
            return;
        }

        final String securityProtocol = context.getProperty(SECURITY_PROTOCOL).getValue();
        final String bootstrapServers = context.getProperty(BOOTSTRAP_SERVERS).evaluateAttributeExpressions().getValue();
        final RecordSetWriterFactory writerFactory = context.getProperty(RECORD_WRITER).asControllerService(RecordSetWriterFactory.class);
        final RecordReaderFactory readerFactory = context.getProperty(RECORD_READER).asControllerService(RecordReaderFactory.class);
        final boolean useTransactions = context.getProperty(USE_TRANSACTIONS).asBoolean();
        final PublishFailureStrategy failureStrategy = getFailureStrategy(context);

        final PublishMetadataStrategy publishMetadataStrategy;
        final String recordMetadataStrategy = context.getProperty(RECORD_METADATA_STRATEGY).getValue();
        if (RECORD_METADATA_FROM_RECORD.getValue().equalsIgnoreCase(recordMetadataStrategy)) {
            publishMetadataStrategy = PublishMetadataStrategy.USE_RECORD_METADATA;
        } else {
            publishMetadataStrategy = PublishMetadataStrategy.USE_CONFIGURED_VALUES;
        }

        final long startTime = System.nanoTime();
        try (final PublisherLease lease = obtainPublisher(context, pool)) {
            try {
                if (useTransactions) {
                    lease.beginTransaction();
                }

                // Send each FlowFile to Kafka asynchronously.
                final Iterator<FlowFile> itr = flowFiles.iterator();
                while (itr.hasNext()) {
                    final FlowFile flowFile = itr.next();

                    if (!isScheduled()) {
                        // If stopped, re-queue FlowFile instead of sending it
                        if (useTransactions) {
                            session.rollback();
                            lease.rollback();
                            return;
                        }

                        session.transfer(flowFile);
                        itr.remove();
                        continue;
                    }

                    final String topic = context.getProperty(TOPIC).evaluateAttributeExpressions(flowFile).getValue();
                    final String messageKeyField = context.getProperty(MESSAGE_KEY_FIELD).evaluateAttributeExpressions(flowFile).getValue();

                    final Function<Record, Integer> partitioner = getPartitioner(context, flowFile);

                    try {
                        session.read(flowFile, in -> {
                            try {
                                final RecordReader reader = readerFactory.createRecordReader(flowFile, in, getLogger());
                                final RecordSet recordSet = reader.createRecordSet();

                                final RecordSchema schema = writerFactory.getSchema(flowFile.getAttributes(), recordSet.getSchema());
                                lease.publish(flowFile, recordSet, writerFactory, schema, messageKeyField, topic, partitioner, publishMetadataStrategy);
                            } catch (final SchemaNotFoundException | MalformedRecordException e) {
                                throw new ProcessException(e);
                            }
                        });

                        // If consumer offsets haven't been committed, add them to the transaction.
                        if (useTransactions && "false".equals(flowFile.getAttribute(KAFKA_CONSUMER_OFFSETS_COMMITTED))) {
                            PublishKafkaUtil.addConsumerOffsets(lease, flowFile, getLogger());
                        }
                    } catch (final Exception e) {
                        // The FlowFile will be obtained and the error logged below, when calling publishResult.getFailedFlowFiles()
                        lease.fail(flowFile, e);
                    }
                }

                // Complete the send
                final PublishResult publishResult = lease.complete();

                if (publishResult.isFailure()) {
                    getLogger().info("Failed to send FlowFile to kafka; transferring to specified failure strategy");
                    failureStrategy.routeFlowFiles(session, flowFiles);
                    return;
                }

                // Transfer any successful FlowFiles.
                final long transmissionMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
                for (FlowFile success : flowFiles) {
                    final String topic = context.getProperty(TOPIC).evaluateAttributeExpressions(success).getValue();

                    final int msgCount = publishResult.getSuccessfulMessageCount(success);
                    success = session.putAttribute(success, MSG_COUNT, String.valueOf(msgCount));
                    session.adjustCounter("Messages Sent", msgCount, true);

                    final String transitUri = StandardTransitUriProvider.getTransitUri(securityProtocol, bootstrapServers, topic);
                    session.getProvenanceReporter().send(success, transitUri, "Sent " + msgCount + " messages", transmissionMillis);
                    session.transfer(success, REL_SUCCESS);
                }
            } catch (final ProducerFencedException | OutOfOrderSequenceException | AuthorizationException e) {
                lease.poison();
                getLogger().error("Failed to send messages to Kafka; will yield Processor and transfer FlowFiles to specified failure strategy");
                failureStrategy.routeFlowFiles(session, flowFiles);
                context.yield();
            }
        }
    }

    private PublisherLease obtainPublisher(final ProcessContext context, final PublisherPool pool) {
        try {
            return pool.obtainPublisher();
        } catch (final KafkaException e) {
            getLogger().error("Failed to obtain Kafka Producer", e);
            context.yield();
            throw e;
        }
    }

    private Function<Record, Integer> getPartitioner(final ProcessContext context, final FlowFile flowFile) {
        final String partitionClass = context.getProperty(PARTITION_CLASS).getValue();
        if (RECORD_PATH_PARTITIONING.getValue().equals(partitionClass)) {
            final String recordPath = context.getProperty(PARTITION).evaluateAttributeExpressions(flowFile).getValue();
            final RecordPath compiled = recordPathCache.getCompiled(recordPath);

            return record -> evaluateRecordPath(compiled, record);
        } else if (EXPRESSION_LANGUAGE_PARTITIONING.getValue().equals(partitionClass)) {
            final String partition = context.getProperty(PARTITION).evaluateAttributeExpressions(flowFile).getValue();
            final int hash = Objects.hashCode(partition);
            return (record) -> hash;
        }

        return null;
    }

    private Integer evaluateRecordPath(final RecordPath recordPath, final Record record) {
        final RecordPathResult result = recordPath.evaluate(record);
        final LongAccumulator accumulator = new LongAccumulator(Long::sum, 0);

        result.getSelectedFields().forEach(fieldValue -> {
            final Object value = fieldValue.getValue();
            final long hash = Objects.hashCode(value);
            accumulator.accumulate(hash);
        });

        return accumulator.intValue();
    }

    private PublishFailureStrategy getFailureStrategy(final ProcessContext context) {
        final String strategy = context.getProperty(FAILURE_STRATEGY).getValue();
        if (FailureStrategy.ROLLBACK.getValue().equals(strategy)) {
            return (session, flowFiles) -> session.rollback();
        } else {
            return (session, flowFiles) -> session.transfer(flowFiles, REL_FAILURE);
        }
    }

    @Override
    public List<ConfigVerificationResult> verify(final ProcessContext context, final ComponentLog verificationLogger, final Map<String, String> attributes) {
        final String topic = context.getProperty(TOPIC).evaluateAttributeExpressions(attributes).getValue();
        try (final PublisherPool pool = createPublisherPool(context)) {
            return pool.verifyConfiguration(topic);
        }
    }
}
