/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.kroxylicious.testing.kafka.api.KafkaCluster;
import io.kroxylicious.testing.kafka.common.BrokerCluster;
import io.kroxylicious.testing.kafka.common.BrokerConfig;
import io.kroxylicious.testing.kafka.junit5ext.KafkaClusterExtension;
import io.strimzi.api.ResourceAnnotations;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.model.common.Condition;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import io.strimzi.api.kafka.model.topic.KafkaTopicBuilder;
import io.strimzi.api.kafka.model.topic.KafkaTopicStatus;
import io.strimzi.operator.common.featuregates.FeatureGates;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.topic.model.KubeRef;
import io.strimzi.operator.topic.model.TopicOperatorException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.AlterConfigsResult;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.CreatePartitionsResult;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.DeleteTopicsResult;
import org.apache.kafka.clients.admin.NewPartitionReassignment;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicCollection;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Stack;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

/**
 * This integration test suite provides coverage of the {@link BatchingTopicController}.
 * If you need to test individual units of code, use the the {@link BatchingTopicController}.
 */
@SuppressWarnings("checkstyle:ClassFanOutComplexity")
@ExtendWith(KafkaClusterExtension.class)
class TopicControllerIT {
    private static final Logger LOGGER = LogManager.getLogger(TopicControllerIT.class);

    private static final String NAMESPACE = TopicOperatorTestUtil.namespaceName(TopicControllerIT.class);
    public static final Map<String, String> SELECTOR = Map.of("foo", "FOO", "bar", "BAR");

    static KubernetesClient kubernetesClient;
    Admin[] kafkaAdminClient;
    Admin[] kafkaAdminClientOp;
    TopicOperatorMain operator;
    Stack<String> namespaces = new Stack<>();
    private TopicOperatorConfig operatorConfig;

    @BeforeAll
    public static void beforeAll() {
        kubernetesClient = TopicOperatorUtil.createKubernetesClient("test");
        TopicOperatorTestUtil.setupKubeCluster(kubernetesClient, NAMESPACE);
    }

    @AfterAll
    public static void afterAll() {
        TopicOperatorTestUtil.deleteNamespace(kubernetesClient, NAMESPACE);
        kubernetesClient.close();
    }

    @AfterEach
    public void afterEach() {
        if (operator != null) {
            assertTrue(operator.queue.isAlive());
            assertTrue(operator.queue.isReady());
        }
        if (operator != null) {
            operator.stop();
            operator = null;
        }
        if (kafkaAdminClient != null) {
            kafkaAdminClient[0].close();
            kafkaAdminClient = null;
        }
        while (!namespaces.isEmpty()) {
            TopicOperatorTestUtil.cleanupNamespace(kubernetesClient, namespaces.pop());
        }
    }

    private String createNamespace(String name) {
        namespaces.push(name);
        TopicOperatorTestUtil.createNamespace(kubernetesClient, name);
        return name;
    }

    private static Predicate<KafkaTopic> hasConditionMatching(String description,
                                                              Predicate<Condition> conditionPredicate) {
        return new Predicate<>() {
            @Override
            public boolean test(KafkaTopic kt) {
                return kt.getStatus() != null
                    && kt.getMetadata() != null
                    && kt.getStatus().getConditions() != null
                    && kt.getStatus().getConditions().stream()
                    .anyMatch(conditionPredicate);
            }

            public String toString() {
                return "status.condition which matches " + description;
            }
        };
    }

    private static Predicate<KafkaTopic> isReconcilatedAndHasConditionMatching(String description,
                                                                               Predicate<Condition> conditionPredicate) {
        return new Predicate<>() {
            @Override
            public boolean test(KafkaTopic kt) {
                return kt.getStatus() != null
                    && kt.getMetadata() != null
                    && kt.getMetadata().getGeneration().equals(kt.getStatus().getObservedGeneration())
                    && kt.getStatus().getConditions() != null
                    && kt.getStatus().getConditions().stream()
                    .anyMatch(conditionPredicate);
            }

            public String toString() {
                return "metadata.generation == status.observedGeneration and a status.condition which matches " + description;
            }
        };
    }

    private static Predicate<KafkaTopic> isPausedAndHasConditionMatching(String description,
                                                                         Predicate<Condition> conditionPredicate) {
        return new Predicate<>() {
            @Override
            public boolean test(KafkaTopic kt) {
                return kt.getStatus() != null
                    && kt.getMetadata() != null
                    && kt.getMetadata().getGeneration() != null
                    && kt.getStatus().getConditions() != null
                    && kt.getStatus().getConditions().stream()
                    .anyMatch(conditionPredicate);
            }

            public String toString() {
                return "status.generation and status.condition which matches " + description;
            }
        };
    }

    private static Predicate<KafkaTopic> readyIsTrue() {
        Predicate<Condition> conditionPredicate = condition ->
            "Ready".equals(condition.getType())
                && "True".equals(condition.getStatus());
        return isReconcilatedAndHasConditionMatching("Ready=True", conditionPredicate);
    }

    private static Predicate<KafkaTopic> pausedIsTrue() {
        Predicate<Condition> conditionPredicate = condition ->
            "ReconciliationPaused".equals(condition.getType())
                && "True".equals(condition.getStatus());
        return isPausedAndHasConditionMatching("ReconciliationPaused=True", conditionPredicate);
    }

    private static Predicate<KafkaTopic> readyIsFalse() {
        Predicate<Condition> conditionPredicate = condition ->
            "Ready".equals(condition.getType())
                && "False".equals(condition.getStatus());
        return isReconcilatedAndHasConditionMatching("Ready=False", conditionPredicate);
    }

    private static Predicate<KafkaTopic> readyIsFalseAndReasonIs(String requiredReason, String requiredMessage) {
        Predicate<Condition> conditionPredicate = condition ->
            "Ready".equals(condition.getType())
                && "False".equals(condition.getStatus())
                && requiredReason.equals(condition.getReason())
                && (requiredMessage == null || requiredMessage.equals(condition.getMessage()));
        String description = "Ready=False and Reason=" + requiredReason;
        if (requiredMessage != null) {
            description += " and Message=" + requiredMessage;
        }
        return isReconcilatedAndHasConditionMatching(description, conditionPredicate);
    }

    private static Predicate<KafkaTopic> readyIsTrueOrFalse() {
        return typeIsTrueOrFalse("Ready");
    }

    private static Predicate<KafkaTopic> unmanagedIsTrueOrFalse() {
        return typeIsTrueOrFalse("Unmanaged");
    }

    private static Predicate<KafkaTopic> typeIsTrueOrFalse(String type) {
        Predicate<Condition> conditionPredicate = condition ->
            type.equals(condition.getType())
                    && "True".equals(condition.getStatus())
                    || "False".equals(condition.getStatus());
        return isReconcilatedAndHasConditionMatching(type + "=True or False", conditionPredicate);
    }

    private static Predicate<KafkaTopic> unmanagedStatusTrue() {
        return typeIsTrueOrFalse("Unmanaged");
    }

    private static Predicate<KafkaTopic> unmanagedIsTrue() {
        Predicate<Condition> conditionPredicate = condition ->
            "Unmanaged".equals(condition.getType())
                && "True".equals(condition.getStatus());
        return hasConditionMatching("Unmanaged=True", conditionPredicate);
    }

    private KafkaTopic waitUntil(KafkaTopic kt, Predicate<KafkaTopic> condition) {
        Resource<KafkaTopic> resource = Crds.topicOperation(kubernetesClient).resource(kt);
        return TopicOperatorTestUtil.waitUntilCondition(resource, condition);
    }

    private void maybeStartOperator(TopicOperatorConfig config) throws ExecutionException, InterruptedException {
        if (kafkaAdminClient == null) {
            Map<String, Object> testConfig = config.adminClientConfig();
            testConfig.replace(AdminClientConfig.CLIENT_ID_CONFIG, config.clientId() + "-test");
            kafkaAdminClient = new Admin[]{Admin.create(testConfig)};
        }
        if (kafkaAdminClientOp == null) {
            Map<String, Object> adminConfig = config.adminClientConfig();
            adminConfig.replace(AdminClientConfig.CLIENT_ID_CONFIG, config.clientId() + "-operator");
            kafkaAdminClientOp = new Admin[]{Admin.create(adminConfig)};
        }
        if (operator == null) {
            this.operatorConfig = config;
            operator = TopicOperatorMain.operator(config, kubernetesClient, kafkaAdminClientOp[0]);
            assertFalse(operator.queue.isAlive());
            assertFalse(operator.queue.isReady());
            operator.start();
        }
    }

    private void assertNotExistsInKafka(String expectedTopicName) throws InterruptedException {
        try {
            kafkaAdminClient[0].describeTopics(Set.of(expectedTopicName)).topicNameValues().get(expectedTopicName).get();
            fail("Expected topic not to exist in Kafka, but describeTopics({" + expectedTopicName + "}) succeeded");
        } catch (ExecutionException e) {
            assertInstanceOf(UnknownTopicOrPartitionException.class, e.getCause());
        }
    }

    private void waitNotExistsInKafka(String expectedTopicName) throws InterruptedException, TimeoutException, ExecutionException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            try {
                kafkaAdminClient[0].describeTopics(Set.of(expectedTopicName)).topicNameValues().get(expectedTopicName).get();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                    return;
                }
                throw e;
            }
            //noinspection BusyWait
            Thread.sleep(100L);
        }
        throw new TimeoutException("Waiting for " + expectedTopicName + " to not exist in Kafka");
    }

    private static Set<Integer> replicationFactors(TopicDescription topicDescription) {
        return topicDescription.partitions().stream().map(replica -> replica.replicas().size()).collect(Collectors.toSet());
    }

    private static int numPartitions(TopicDescription topicDescription) {
        return topicDescription.partitions().size();
    }

    private Map<String, String> topicConfigMap(String topicName) throws InterruptedException, ExecutionException {
        ConfigResource topicResource = new ConfigResource(ConfigResource.Type.TOPIC, topicName);
        Config topicConfig = null;
        do {
            try {
                topicConfig = kafkaAdminClient[0].describeConfigs(Set.of(topicResource)).all().get().get(topicResource);
            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof UnknownTopicOrPartitionException)) {
                    throw e;
                }
            }
        } while (topicConfig == null);
        return topicConfig.entries().stream()
            .filter(ce -> ce.source() == ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG)
            .collect(Collectors.toMap(
                ConfigEntry::name,
                ConfigEntry::value
            ));
    }

    private static KafkaTopic kafkaTopic(String ns,
                                         String metadataName,
                                         Boolean managed,
                                         String topicName,
                                         Integer partitions,
                                         Integer replicas) {
        return kafkaTopic(ns, metadataName, SELECTOR, null, managed, topicName, partitions, replicas, null);
    }

    private static KafkaTopic kafkaTopic(String ns,
                                         String metadataName,
                                         Map<String, String> labels,
                                         Map<String, String> annotations,
                                         Boolean managed,
                                         String topicName,
                                         Integer partitions,
                                         Integer replicas,
                                         Map<String, Object> configs) {

        var metadataBuilder = new KafkaTopicBuilder()
            .withNewMetadata()
            .withName(metadataName)
            .withNamespace(ns)
            .withLabels(labels)
            .withAnnotations(annotations);
        if (managed != null) {
            metadataBuilder = metadataBuilder.addToAnnotations(TopicOperatorUtil.MANAGED, managed.toString());
        }
        return metadataBuilder.endMetadata()
            .withNewSpec()
            .withTopicName(topicName)
            .withPartitions(partitions)
            .withReplicas(replicas)
            .withConfig(configs)
            .endSpec()
            .build();
    }

    private static KafkaTopic kafkaTopicWithNoSpec(String metadataName, boolean spec) {
        var builder = new KafkaTopicBuilder()
            .withNewMetadata()
            .withName(metadataName)
            .withNamespace(NAMESPACE)
            .withLabels(SELECTOR)
            .addToAnnotations(TopicOperatorUtil.MANAGED, "true")
            .endMetadata();
        if (spec) {
            builder = builder.editOrNewSpec().endSpec();
        }
        return builder.build();
    }

    static KafkaTopic[] managedKafkaTopics() {
        var topicName = "topic" + System.nanoTime();
        return new KafkaTopic[] {
            kafkaTopic(NAMESPACE, topicName + "a", true, topicName + "a", 2, 1),
            kafkaTopic(NAMESPACE, topicName + "b", true, null, 2, 1),
            kafkaTopic(NAMESPACE, topicName + "c", true, topicName + "c".toUpperCase(Locale.ROOT), 2, 1),
            kafkaTopic(NAMESPACE, topicName + "d", null, topicName + "d", 2, 1),
            kafkaTopic(NAMESPACE, topicName + "e", null, null, 2, 1),
            kafkaTopic(NAMESPACE, topicName + "f", null, topicName + "f".toUpperCase(Locale.ROOT), 2, 1),
            // With a superset of the selector mappings
            kafkaTopic(NAMESPACE, topicName + "g", Map.of("foo", "FOO", "bar", "BAR", "quux", "QUUX"), null, true, topicName + "g", 2, 1, null),
        };
    }

    static KafkaTopic[] managedKafkaTopicsWithIllegalTopicNames() {
        var topicName = "topic" + System.nanoTime();
        return new KafkaTopic[] {
            kafkaTopic(NAMESPACE, topicName + "a", true, "..", 2, 1),
            kafkaTopic(NAMESPACE, topicName + "b", true, ".", 2, 1),
            kafkaTopic(NAMESPACE, topicName + "c", null, topicName + "c{}", 2, 1),
            kafkaTopic(NAMESPACE, topicName + "d", null, "x".repeat(256), 2, 1),
        };
    }

    static KafkaTopic[] managedKafkaTopicsWithConfigs() {
        var topicName = "topic" + System.nanoTime();
        var configs = Map.of(
            TopicConfig.CLEANUP_POLICY_CONFIG, List.of("compact"), // list typed
            TopicConfig.COMPRESSION_TYPE_CONFIG, "producer", // string typed
            TopicConfig.FLUSH_MS_CONFIG, 1234L, // long typed
            TopicConfig.INDEX_INTERVAL_BYTES_CONFIG, 1234, // int typed
            TopicConfig.MIN_CLEANABLE_DIRTY_RATIO_CONFIG, 0.6, // double typed
            TopicConfig.UNCLEAN_LEADER_ELECTION_ENABLE_CONFIG, true // boolean typed
        );
        return new KafkaTopic[] {
            kafkaTopic(NAMESPACE, topicName + "a", SELECTOR, null, true, topicName + "a", 2, 1, configs),
            kafkaTopic(NAMESPACE, topicName + "b", SELECTOR, null, true, null, 2, 1, configs),
            kafkaTopic(NAMESPACE, topicName + "c", SELECTOR, null, true, topicName + "c".toUpperCase(Locale.ROOT), 2, 1, configs),
            kafkaTopic(NAMESPACE, topicName + "d", SELECTOR, null, null, topicName + "d", 2, 1, configs),
            kafkaTopic(NAMESPACE, topicName + "e", SELECTOR, null, null, null, 2, 1, configs),
            kafkaTopic(NAMESPACE, topicName + "f", SELECTOR, null, null, topicName + "f".toUpperCase(Locale.ROOT), 2, 1, configs),
        };
    }

    static KafkaTopic[] unmanagedKafkaTopics() {
        var topicName = "topic" + System.nanoTime();
        return new KafkaTopic[] {
            kafkaTopic(NAMESPACE, topicName + "a", false, topicName + "a", 2, 1),
            kafkaTopic(NAMESPACE, topicName + "b", false, null, 2, 1),
            kafkaTopic(NAMESPACE, topicName + "c", false, topicName + "c".toUpperCase(Locale.ROOT), 2, 1),
        };
    }

    static KafkaTopic[] unselectedKafkaTopics() {
        var topicName = "topic" + System.nanoTime();
        return new KafkaTopic[] {
            kafkaTopic(NAMESPACE, topicName + "-a", Map.of(), null, true, topicName + "-a", 2, 1, null),
            kafkaTopic(NAMESPACE, topicName + "-b", Map.of("foo", "FOO"), null, true, topicName + "-b", 2, 1, null),
            kafkaTopic(NAMESPACE, topicName + "-c", Map.of("quux", "QUUX"), null, true, null, 2, 1, null),
        };
    }

    private void assertCreateSuccess(KafkaTopic kt, KafkaTopic reconciled) throws InterruptedException, ExecutionException, TimeoutException {
        assertCreateSuccess(kt, reconciled, Map.of());
    }

    private void assertCreateSuccess(KafkaTopic kt, KafkaTopic reconciled,
                                     Map<String, String> expectedConfigs) throws InterruptedException, ExecutionException, TimeoutException {
        assertCreateSuccess(kt, reconciled,
            kt.getSpec().getPartitions(),
            kt.getSpec().getReplicas(),
            expectedConfigs);
    }

    private void assertCreateSuccess(KafkaTopic kt, KafkaTopic reconciled,
                                     int expectedPartitions,
                                     int expectedReplicas,
                                     Map<String, String> expectedConfigs) throws InterruptedException, ExecutionException, TimeoutException {
        waitUntil(kt, readyIsTrue());
        var expectedTopicName = TopicOperatorUtil.topicName(kt);

        // Check updates to the KafkaTopic
        assertNotNull(reconciled.getMetadata().getFinalizers());
        assertEquals(operatorConfig.useFinalizer(), reconciled.getMetadata().getFinalizers().contains(BatchingTopicController.FINALIZER));
        assertEquals(expectedTopicName, reconciled.getStatus().getTopicName());
        assertNotNull(reconciled.getStatus().getTopicId());

        // Check topic in Kafka
        var topicDescription = awaitTopicDescription(expectedTopicName);
        assertEquals(expectedPartitions, numPartitions(topicDescription));
        assertEquals(Set.of(expectedReplicas), replicationFactors(topicDescription));
        assertEquals(expectedConfigs, topicConfigMap(expectedTopicName));
    }

    private KafkaTopic createTopic(KafkaCluster kc, KafkaTopic kt) throws ExecutionException, InterruptedException {
        return createTopic(kc, kt, TopicOperatorUtil.isManaged(kt) ? readyIsTrueOrFalse() : unmanagedIsTrue());
    }

    private KafkaTopic createTopic(KafkaCluster kc, KafkaTopic kt, Predicate<KafkaTopic> condition) throws ExecutionException, InterruptedException {
        String ns = createNamespace(kt.getMetadata().getNamespace());
        maybeStartOperator(topicOperatorConfig(ns, kc));

        // Create resource and await readiness
        var created = Crds.topicOperation(kubernetesClient).resource(kt).create();
        LOGGER.info("Test created KafkaTopic {} with resourceVersion {}",
            created.getMetadata().getName(), TopicOperatorUtil.resourceVersion(created));
        return waitUntil(created, condition);
    }

    private List<KafkaTopic> createTopicsConcurrently(KafkaCluster kc, KafkaTopic... kts) throws InterruptedException, ExecutionException {
        if (kts == null || kts.length == 0) {
            throw new IllegalArgumentException("You need pass at least one topic to be created");
        }
        String ns = createNamespace(kts[0].getMetadata().getNamespace());
        maybeStartOperator(topicOperatorConfig(ns, kc));
        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        CountDownLatch latch = new CountDownLatch(kts.length);
        List<KafkaTopic> result = new ArrayList<>();
        for (KafkaTopic kt : kts) {
            executor.submit(() -> {
                try {
                    var created = Crds.topicOperation(kubernetesClient).resource(kt).create();
                    LOGGER.info("Test created KafkaTopic {} with creationTimestamp {}",
                        created.getMetadata().getName(),
                        created.getMetadata().getCreationTimestamp());
                    var reconciled = waitUntil(created, readyIsTrueOrFalse());
                    result.add(reconciled);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                latch.countDown();
            });
        }
        latch.await(1, TimeUnit.MINUTES);
        try {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            if (!executor.isTerminated()) {
                executor.shutdownNow();
            }
        }
        return result;
    }

    private KafkaTopic pauseTopic(String namespace, String topicName) {
        var current = Crds.topicOperation(kubernetesClient).inNamespace(namespace).withName(topicName).get();
        var kafkaTopic = Crds.topicOperation(kubernetesClient).resource(new KafkaTopicBuilder(current)
            .editMetadata()
                .withAnnotations(Map.of(ResourceAnnotations.ANNO_STRIMZI_IO_PAUSE_RECONCILIATION, "true"))
            .endMetadata()
            .build()).update();
        LOGGER.info("Test paused KafkaTopic {} with resourceVersion {}",
            kafkaTopic.getMetadata().getName(), TopicOperatorUtil.resourceVersion(kafkaTopic));
        return waitUntil(kafkaTopic, pausedIsTrue());
    }

    private KafkaTopic unpauseTopic(String namespace, String topicName) {
        var current = Crds.topicOperation(kubernetesClient).inNamespace(namespace).withName(topicName).get();
        var kafkaTopic = Crds.topicOperation(kubernetesClient).resource(new KafkaTopicBuilder(current)
            .editMetadata()
                .withAnnotations(Map.of(ResourceAnnotations.ANNO_STRIMZI_IO_PAUSE_RECONCILIATION, "false"))
            .endMetadata()
            .build()).update();
        LOGGER.info("Test unpaused KafkaTopic {} with resourceVersion {}",
            kafkaTopic.getMetadata().getName(), TopicOperatorUtil.resourceVersion(kafkaTopic));
        return waitUntil(kafkaTopic, readyIsTrue());
    }

    private KafkaTopic unmanageTopic(String namespace, String topicName) {
        var current = Crds.topicOperation(kubernetesClient).inNamespace(namespace).withName(topicName).get();
        var kafkaTopic = Crds.topicOperation(kubernetesClient).resource(new KafkaTopicBuilder(current)
            .editMetadata()
                .withAnnotations(Map.of(TopicOperatorUtil.MANAGED, "false"))
            .endMetadata()
            .build()).update();
        LOGGER.info("Test unmanaged KafkaTopic {} with resourceVersion {}",
            kafkaTopic.getMetadata().getName(), TopicOperatorUtil.resourceVersion(kafkaTopic));
        return waitUntil(kafkaTopic, unmanagedIsTrue());
    }

    private KafkaTopic manageTopic(String namespace, String topicName) {
        var current = Crds.topicOperation(kubernetesClient).inNamespace(namespace).withName(topicName).get();
        var kafkaTopic = Crds.topicOperation(kubernetesClient).resource(new KafkaTopicBuilder(current)
            .editMetadata()
                .withAnnotations(Map.of(TopicOperatorUtil.MANAGED, "true"))
            .endMetadata()
            .build()).update();
        LOGGER.info("Test managed KafkaTopic {} with resourceVersion {}",
            kafkaTopic.getMetadata().getName(), TopicOperatorUtil.resourceVersion(kafkaTopic));
        return waitUntil(kafkaTopic, readyIsTrue());
    }

    private TopicDescription awaitTopicDescription(String expectedTopicName) throws InterruptedException, ExecutionException, TimeoutException {
        long deadline = System.nanoTime() + 30_000_000_000L;
        TopicDescription td = null;
        do {
            try {
                td = kafkaAdminClient[0].describeTopics(Set.of(expectedTopicName)).allTopicNames().get().get(expectedTopicName);
            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof UnknownTopicOrPartitionException)) {
                    throw e;
                }
            }
            if (System.nanoTime() > deadline) {
                throw new TimeoutException();
            }
        } while (td == null);
        return td;
    }

    private void assertUnknownTopic(String expectedTopicName) throws ExecutionException, InterruptedException {
        try {
            kafkaAdminClient[0].describeTopics(Set.of(expectedTopicName)).allTopicNames().get();
            fail("Expected topic '" + expectedTopicName + "' to not exist");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                return;
            }
            throw e;
        }
    }

    private KafkaTopic createTopicAndAssertSuccess(KafkaCluster kc, KafkaTopic kt)
        throws ExecutionException, InterruptedException, TimeoutException {
        var created = createTopic(kc, kt);
        assertCreateSuccess(kt, created);
        return created;
    }

    @ParameterizedTest
    @MethodSource("managedKafkaTopics")
    public void shouldCreateTopicInKafkaWhenManagedTopicCreatedInKube(KafkaTopic kt,
                                                                      @BrokerConfig(name = "auto.create.topics.enable", value = "false")
                                                                      KafkaCluster kafkaCluster)
        throws ExecutionException, InterruptedException, TimeoutException {
        createTopicAndAssertSuccess(kafkaCluster, kt);
    }

    @Test
    public void shouldCreateTopicInKafkaWhenKafkaTopicHasOnlyPartitions(
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        @BrokerConfig(name = "num.partitions", value = "4")
        @BrokerConfig(name = "default.replication.factor", value = "1")
        KafkaCluster kafkaCluster)
        throws ExecutionException, InterruptedException, TimeoutException {
        KafkaTopic kt = new KafkaTopicBuilder()
            .withNewMetadata()
            .withNamespace(NAMESPACE)
            .withName("my-topic")
            .withLabels(SELECTOR)
            .endMetadata()
            .withNewSpec()
            .withPartitions(5)
            .endSpec()
            .build();
        var created = createTopic(kafkaCluster, kt);
        assertCreateSuccess(kt, created, 5, 1, Map.of());
    }

    @Test
    public void shouldCreateTopicInKafkaWhenKafkaTopicHasOnlyReplicas(
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        @BrokerConfig(name = "num.partitions", value = "4")
        @BrokerConfig(name = "default.replication.factor", value = "1")
        KafkaCluster kafkaCluster)
        throws ExecutionException, InterruptedException, TimeoutException {
        KafkaTopic kt = new KafkaTopicBuilder()
            .withNewMetadata()
            .withNamespace(NAMESPACE)
            .withName("my-topic")
            .withLabels(SELECTOR)
            .endMetadata()
            .withNewSpec()
            .withReplicas(1)
            .endSpec()
            .build();
        var created = createTopic(kafkaCluster, kt);
        assertCreateSuccess(kt, created, 4, 1, Map.of());
    }

    @Test
    public void shouldCreateTopicInKafkaWhenKafkaTopicHasOnlyConfigs(
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        @BrokerConfig(name = "num.partitions", value = "4")
        @BrokerConfig(name = "default.replication.factor", value = "1")
        KafkaCluster kafkaCluster)
        throws ExecutionException, InterruptedException, TimeoutException {
        KafkaTopic kt = new KafkaTopicBuilder()
            .withNewMetadata()
            .withNamespace(NAMESPACE)
            .withName("my-topic")
            .withLabels(SELECTOR)
            .endMetadata()
            .withNewSpec()
            .addToConfig(TopicConfig.FLUSH_MS_CONFIG, "1000")
            .endSpec()
            .build();
        var created = createTopic(kafkaCluster, kt);
        assertCreateSuccess(kt, created, 4, 1, Map.of(TopicConfig.FLUSH_MS_CONFIG, "1000"));
    }


    @ParameterizedTest
    @MethodSource("unmanagedKafkaTopics")
    public void shouldNotCreateTopicInKafkaWhenUnmanagedTopicCreatedInKube(
        KafkaTopic kafkaTopic,
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster
    ) throws ExecutionException, InterruptedException {
        createTopic(kafkaCluster, kafkaTopic);
        assertNotExistsInKafka(TopicOperatorUtil.topicName(kafkaTopic));
    }

    @ParameterizedTest
    @MethodSource("unselectedKafkaTopics")
    public void shouldNotCreateTopicInKafkaWhenUnselectedTopicCreatedInKube(
        KafkaTopic kt,
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException, TimeoutException {
        // The difference between unmanaged and unselected is the former means the operator doesn't touch it
        // (presumably it's intended for another operator instance), but the latter does get a status update

        // given
        String ns = createNamespace(kt.getMetadata().getNamespace());
        maybeStartOperator(topicOperatorConfig(ns, kafkaCluster));

        // when

        // then
        try (var logCaptor = LogCaptor.logMessageMatches(BatchingTopicController.LOGGER,
            org.apache.logging.log4j.Level.DEBUG,
            "Ignoring KafkaTopic .*? not selected by selector",
            5L,
            TimeUnit.SECONDS)) {
            var created = Crds.topicOperation(kubernetesClient).resource(kt).create();
            LOGGER.info("Test created KafkaTopic {} with resourceVersion {}",
                created.getMetadata().getName(), TopicOperatorUtil.resourceVersion(created));
        }
        KafkaTopic kafkaTopic = Crds.topicOperation(kubernetesClient).inNamespace(ns).withName(kt.getMetadata().getName()).get();
        assertNull(kafkaTopic.getStatus());
    }

    @ParameterizedTest
    @MethodSource("managedKafkaTopics")
    public void shouldNotUpdateTopicInKafkaWhenKafkaTopicBecomesUnselected(
        KafkaTopic kt,
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException, TimeoutException {
        Map<String, String> unmatchedLabels = Map.of("foo", "FOO");
        assertFalse(BatchingTopicController.matchesSelector(SELECTOR, unmatchedLabels));

        // given
        var expectedTopicName = TopicOperatorUtil.topicName(kt);
        KafkaTopic unmanaged;
        try (var logCaptor = LogCaptor.logMessageMatches(BatchingTopicController.LOGGER,
            org.apache.logging.log4j.Level.DEBUG,
            "Ignoring KafkaTopic .*? not selected by selector",
            5L,
            TimeUnit.SECONDS)) {
            createTopicAndAssertSuccess(kafkaCluster, kt);
            assertTrue(operator.controller.topics.containsKey(expectedTopicName)
                    || operator.controller.topics.containsKey(expectedTopicName.toUpperCase(Locale.ROOT)),
                "Expect selected resource to be present in topics map");

            // when
            LOGGER.debug("##Modifying");
            unmanaged = TopicOperatorTestUtil.changeTopic(kubernetesClient, kt, theKt -> {
                theKt.getMetadata().setLabels(unmatchedLabels);
                theKt.getSpec().setPartitions(3);
                return theKt;
            });

            // then
            LOGGER.debug("##Checking");
        }
        assertNotNull(unmanaged.getMetadata().getFinalizers());
        assertTrue(unmanaged.getMetadata().getFinalizers().contains(BatchingTopicController.FINALIZER));
        assertNotNull(unmanaged.getStatus().getTopicName(), "Expect status.topicName to be unchanged from post-creation state");

        var topicDescription = awaitTopicDescription(expectedTopicName);
        assertEquals(kt.getSpec().getPartitions(), numPartitions(topicDescription));
        assertEquals(Set.of(kt.getSpec().getReplicas()), replicationFactors(topicDescription));
        assertEquals(Map.of(), topicConfigMap(expectedTopicName));

        Map<String, List<KubeRef>> topics = new HashMap<>(operator.controller.topics);
        assertFalse(topics.containsKey(expectedTopicName)
                || topics.containsKey(expectedTopicName.toUpperCase(Locale.ROOT)),
            "Transition to a non-selected resource should result in removal from topics map: " + topics);
    }

    @ParameterizedTest
    @MethodSource("unselectedKafkaTopics")
    public void shouldUpdateTopicInKafkaWhenKafkaTopicBecomesSelected(
        KafkaTopic kt,
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException, TimeoutException {
        Map<String, String> unmatchedLabels = kt.getMetadata().getLabels();
        assertFalse(BatchingTopicController.matchesSelector(SELECTOR, unmatchedLabels));

        // given
        var ns = createNamespace(kt.getMetadata().getNamespace());
        var expectedTopicName = TopicOperatorUtil.topicName(kt);
        maybeStartOperator(topicOperatorConfig(ns, kafkaCluster));

        KafkaTopic created;
        try (var logCaptor = LogCaptor.logMessageMatches(BatchingTopicController.LOGGER,
            org.apache.logging.log4j.Level.DEBUG,
            "Ignoring KafkaTopic .*? not selected by selector",
            5L,
            TimeUnit.SECONDS)) {
            created = Crds.topicOperation(kubernetesClient).resource(kt).create();
            LOGGER.info("Test created KafkaTopic {} with resourceVersion {}",
                created.getMetadata().getName(), TopicOperatorUtil.resourceVersion(created));
        }
        assertUnknownTopic(expectedTopicName);
        assertNull(created.getStatus(), "Expect status not to be set");
        assertTrue(created.getMetadata().getFinalizers().isEmpty());
        assertFalse(operator.controller.topics.containsKey(expectedTopicName)
                || operator.controller.topics.containsKey(expectedTopicName.toUpperCase(Locale.ROOT)),
            "Expect unselected resource to be absent from topics map");

        // when
        var managed = modifyTopicAndAwait(kt,
            theKt -> {
                theKt.getMetadata().setLabels(SELECTOR);
                theKt.getSpec().setPartitions(3);
                return theKt;
            },
            readyIsTrue());

        // then
        assertTrue(operator.controller.topics.containsKey(expectedTopicName)
                || operator.controller.topics.containsKey(expectedTopicName.toUpperCase(Locale.ROOT)),
            "Expect selected resource to be present in topics map");

        assertNotNull(managed.getMetadata().getFinalizers());
        assertTrue(managed.getMetadata().getFinalizers().contains(BatchingTopicController.FINALIZER));
        assertNotNull(managed.getStatus().getTopicName(), "Expect status.topicName to be unchanged from post-creation state");
        var topicDescription = awaitTopicDescription(expectedTopicName);
        assertEquals(3, numPartitions(topicDescription));

        assertTrue(operator.controller.topics.containsKey(expectedTopicName)
                || operator.controller.topics.containsKey(expectedTopicName.toUpperCase(Locale.ROOT)),
            "Expect selected resource to be present in topics map");

    }

    private void shouldUpdateTopicInKafkaWhenConfigChangedInKube(KafkaCluster kc,
                                                                 KafkaTopic kt,
                                                                 UnaryOperator<KafkaTopic> changer,
                                                                 UnaryOperator<Map<String, String>> expectedChangedConfigs) throws ExecutionException, InterruptedException, TimeoutException {
        // given
        var expectedTopicName = TopicOperatorUtil.topicName(kt);
        var expectedCreateConfigs = Map.of(
            TopicConfig.CLEANUP_POLICY_CONFIG, "compact", // list typed
            TopicConfig.COMPRESSION_TYPE_CONFIG, "producer", // string typed
            TopicConfig.FLUSH_MS_CONFIG, "1234", // long typed
            TopicConfig.INDEX_INTERVAL_BYTES_CONFIG, "1234", // int typed
            TopicConfig.MIN_CLEANABLE_DIRTY_RATIO_CONFIG, "0.6", // double typed
            TopicConfig.UNCLEAN_LEADER_ELECTION_ENABLE_CONFIG, "true" // boolean typed
        );
        Map<String, String> expectedConfigs = expectedChangedConfigs.apply(expectedCreateConfigs);
        assertNotEquals(expectedCreateConfigs, expectedConfigs);

        var created = createTopic(kc, kt);
        assertCreateSuccess(kt, created, expectedCreateConfigs);

        // when
        modifyTopicAndAwait(kt, changer, readyIsTrue());

        // then
        assertEquals(expectedConfigs, topicConfigMap(expectedTopicName));
    }

    @ParameterizedTest
    @MethodSource("managedKafkaTopicsWithConfigs")
    public void shouldUpdateTopicInKafkaWhenStringConfigChangedInKube(
        KafkaTopic kt,
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException, TimeoutException {
        shouldUpdateTopicInKafkaWhenConfigChangedInKube(kafkaCluster, kt,
            TopicControllerIT::setSnappyCompression,
            expectedCreateConfigs -> {
                Map<String, String> expectedUpdatedConfigs = new HashMap<>(expectedCreateConfigs);
                expectedUpdatedConfigs.put(TopicConfig.COMPRESSION_TYPE_CONFIG, "snappy");
                return expectedUpdatedConfigs;
            });
    }


    @ParameterizedTest
    @MethodSource("managedKafkaTopicsWithConfigs")
    public void shouldUpdateTopicInKafkaWhenIntConfigChangedInKube(
        KafkaTopic kt,
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException, TimeoutException {
        shouldUpdateTopicInKafkaWhenConfigChangedInKube(kafkaCluster, kt,
            theKt -> {
                theKt.getSpec().getConfig().put(TopicConfig.INDEX_INTERVAL_BYTES_CONFIG, 5678);
                return theKt;
            },
            expectedCreateConfigs -> {
                Map<String, String> expectedUpdatedConfigs = new HashMap<>(expectedCreateConfigs);
                expectedUpdatedConfigs.put(TopicConfig.INDEX_INTERVAL_BYTES_CONFIG, "5678");
                return expectedUpdatedConfigs;
            });
    }

    @ParameterizedTest
    @MethodSource("managedKafkaTopicsWithConfigs")
    public void shouldUpdateTopicInKafkaWhenLongConfigChangedInKube(
        KafkaTopic kt,
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException, TimeoutException {
        shouldUpdateTopicInKafkaWhenConfigChangedInKube(kafkaCluster, kt,
            theKt -> {
                theKt.getSpec().getConfig().put(TopicConfig.FLUSH_MS_CONFIG, 9876L);
                return theKt;
            },
            expectedCreateConfigs -> {
                Map<String, String> expectedUpdatedConfigs = new HashMap<>(expectedCreateConfigs);
                expectedUpdatedConfigs.put(TopicConfig.FLUSH_MS_CONFIG, "9876");
                return expectedUpdatedConfigs;
            });
    }

    @ParameterizedTest
    @MethodSource("managedKafkaTopicsWithConfigs")
    public void shouldUpdateTopicInKafkaWhenDoubleConfigChangedInKube(
        KafkaTopic kt,
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException, TimeoutException {
        shouldUpdateTopicInKafkaWhenConfigChangedInKube(kafkaCluster, kt,
            theKt -> {
                theKt.getSpec().getConfig().put(TopicConfig.MIN_CLEANABLE_DIRTY_RATIO_CONFIG, 0.1);
                return theKt;
            },
            expectedCreateConfigs -> {
                Map<String, String> expectedUpdatedConfigs = new HashMap<>(expectedCreateConfigs);
                expectedUpdatedConfigs.put(TopicConfig.MIN_CLEANABLE_DIRTY_RATIO_CONFIG, "0.1");
                return expectedUpdatedConfigs;
            });
    }

    @ParameterizedTest
    @MethodSource("managedKafkaTopicsWithConfigs")
    public void shouldUpdateTopicInKafkaWhenBooleanConfigChangedInKube(
        KafkaTopic kt,
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException, TimeoutException {
        shouldUpdateTopicInKafkaWhenConfigChangedInKube(kafkaCluster, kt,
            theKt -> {
                theKt.getSpec().getConfig().put(TopicConfig.UNCLEAN_LEADER_ELECTION_ENABLE_CONFIG, false);
                return theKt;
            },
            expectedCreateConfigs -> {
                Map<String, String> expectedUpdatedConfigs = new HashMap<>(expectedCreateConfigs);
                expectedUpdatedConfigs.put(TopicConfig.UNCLEAN_LEADER_ELECTION_ENABLE_CONFIG, "false");
                return expectedUpdatedConfigs;
            });
    }

    @ParameterizedTest
    @MethodSource("managedKafkaTopicsWithConfigs")
    public void shouldUpdateTopicInKafkaWhenListConfigChangedInKube(
        KafkaTopic kt,
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException, TimeoutException {
        shouldUpdateTopicInKafkaWhenConfigChangedInKube(kafkaCluster, kt,
            theKt -> {
                theKt.getSpec().getConfig().put(TopicConfig.CLEANUP_POLICY_CONFIG, List.of("compact", "delete"));
                return theKt;
            },
            expectedCreateConfigs -> {
                Map<String, String> expectedUpdatedConfigs = new HashMap<>(expectedCreateConfigs);
                expectedUpdatedConfigs.put(TopicConfig.CLEANUP_POLICY_CONFIG, "compact,delete");
                return expectedUpdatedConfigs;
            });
    }

    @ParameterizedTest
    @MethodSource("managedKafkaTopicsWithConfigs")
    public void shouldUpdateTopicInKafkaWhenConfigRemovedInKube(
        KafkaTopic kt,
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException, TimeoutException {
        shouldUpdateTopicInKafkaWhenConfigChangedInKube(kafkaCluster, kt,
            theKt -> {
                theKt.getSpec().getConfig().remove(TopicConfig.UNCLEAN_LEADER_ELECTION_ENABLE_CONFIG);
                return theKt;
            },
            expectedCreateConfigs -> {
                Map<String, String> expectedUpdatedConfigs = new HashMap<>(expectedCreateConfigs);
                expectedUpdatedConfigs.remove(TopicConfig.UNCLEAN_LEADER_ELECTION_ENABLE_CONFIG);
                return expectedUpdatedConfigs;
            });
    }

    @Test
    public void shouldNotRemoveInheritedConfigs(
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        @BrokerConfig(name = "compression.type", value = "snappy")
        KafkaCluster kafkaCluster,
        Admin admin) throws ExecutionException, InterruptedException, TimeoutException {

        // Scenario from https://github.com/strimzi/strimzi-kafka-operator/pull/8627#issuecomment-1600852809

        // given: a range of broker configs coming from a variety of sources
        admin.incrementalAlterConfigs(Map.of(
            new ConfigResource(ConfigResource.Type.BROKER, "0"), List.of(
                new AlterConfigOp(new ConfigEntry("log.cleaner.delete.retention.ms", "" + (1000L * 60 * 60)), AlterConfigOp.OpType.SET)),
            new ConfigResource(ConfigResource.Type.BROKER, ""), List.of(
                new AlterConfigOp(new ConfigEntry("log.segment.delete.delay.ms", "" + (1000L * 60 * 60)), AlterConfigOp.OpType.SET)))).all().get();

        TopicOperatorConfig config = topicOperatorConfig(NAMESPACE, kafkaCluster, true, 500);
        kafkaAdminClientOp = new Admin[]{Mockito.spy(Admin.create(config.adminClientConfig()))};

        maybeStartOperator(config);

        KafkaTopic kt = kafkaTopic(NAMESPACE, "bar", SELECTOR, null, null, null, 1, 1,
            Map.of("flush.messages", "1234"));
        var barKt = createTopic(kafkaCluster, kt);
        assertCreateSuccess(kt, barKt, Map.of("flush.messages", "1234"));
        postSyncBarrier();

        // when: resync
        try (var logCaptor = LogCaptor.logMessageMatches(BatchingLoop.LoopRunnable.LOGGER,
            Level.INFO,
            "\\[Batch #[0-9]+\\] Batch reconciliation completed",
            5L,
            TimeUnit.SECONDS)) {
            LOGGER.debug("Waiting for a full resync");
        }

        // then: verify that only the expected methods were called on the admin (e.g. no incrementalAlterConfigs)
        Mockito.verify(kafkaAdminClientOp[0], Mockito.never()).incrementalAlterConfigs(any());
        Mockito.verify(kafkaAdminClientOp[0], Mockito.never()).incrementalAlterConfigs(any(), any());
    }

    private static void postSyncBarrier() throws TimeoutException, InterruptedException {
        var uuid = UUID.randomUUID();
        try (var logCaptor = LogCaptor.logEventMatches(LOGGER,
            Level.DEBUG,
            LogCaptor.messageContainsMatch("Post sync barrier " + uuid),
            5L,
            TimeUnit.SECONDS)) {
            LOGGER.debug("Post sync barrier {}", uuid);
        }
    }

    @ParameterizedTest
    @MethodSource("managedKafkaTopics")
    public void shouldUpdateTopicInKafkaWhenPartitionsIncreasedInKube(
        KafkaTopic kt,
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException, TimeoutException {
        // given
        var expectedTopicName = TopicOperatorUtil.topicName(kt);
        int specPartitions = kt.getSpec().getPartitions();
        createTopicAndAssertSuccess(kafkaCluster, kt);

        // when: partitions is increased
        modifyTopicAndAwait(kt,
            TopicControllerIT::incrementPartitions,
            readyIsTrue());

        // then
        var topicDescription = awaitTopicDescription(expectedTopicName);
        assertEquals(specPartitions + 1, numPartitions(topicDescription));

    }

    @ParameterizedTest
    @MethodSource("managedKafkaTopics")
    public void shouldFailDecreaseInPartitions(
        KafkaTopic kt,
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException, TimeoutException {
        final int specPartitions = kt.getSpec().getPartitions();
        assertEquals(2, specPartitions);
        shouldFailOnModification(kafkaCluster, kt,
            theKt -> {
                theKt.getSpec().setPartitions(1);
                return theKt;
            },
            operated -> {
                assertEquals("Decreasing partitions not supported", assertExactlyOneCondition(operated).getMessage());
                assertEquals(TopicOperatorException.Reason.NOT_SUPPORTED.value, assertExactlyOneCondition(operated).getReason());
            },
            theKt -> {
                theKt.getSpec().setPartitions(specPartitions);
                return theKt;
            });
    }

    @ParameterizedTest
    @MethodSource("managedKafkaTopics")
    public void shouldFailDecreaseInPartitionsWithConfigChange(
        KafkaTopic kt,
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException, TimeoutException {
        final int specPartitions = kt.getSpec().getPartitions();
        assertEquals(2, specPartitions);
        shouldFailOnModification(kafkaCluster, kt,
            theKt ->
                new KafkaTopicBuilder(theKt).editOrNewSpec().withPartitions(1).addToConfig(TopicConfig.COMPRESSION_TYPE_CONFIG, "snappy").endSpec().build(),
            operated -> {
                assertEquals("Decreasing partitions not supported", assertExactlyOneCondition(operated).getMessage());
                assertEquals(TopicOperatorException.Reason.NOT_SUPPORTED.value, assertExactlyOneCondition(operated).getReason());
                try {
                    assertEquals("snappy", topicConfigMap(TopicOperatorUtil.topicName(kt)).get(TopicConfig.COMPRESSION_TYPE_CONFIG),
                        "Expect the config to have been changed even if the #partitions couldn't be decreased");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            },
            theKt -> {
                theKt.getSpec().setPartitions(specPartitions);
                return theKt;
            });
    }

    private static Condition assertExactlyOneCondition(KafkaTopic operated) {
        KafkaTopicStatus status = operated.getStatus();
        assertNotNull(status);
        List<Condition> conditions = status.getConditions();
        assertNotNull(conditions);
        assertEquals(1, conditions.size());
        return conditions.get(0);
    }

    @ParameterizedTest
    @MethodSource("managedKafkaTopics")
    public void shouldNotUpdateTopicInKafkaWhenUnmanagedTopicUpdatedInKube(
        KafkaTopic kt,
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException, TimeoutException {
        // given
        var expectedTopicName = TopicOperatorUtil.topicName(kt);
        createTopicAndAssertSuccess(kafkaCluster, kt);

        // when
        var unmanaged = modifyTopicAndAwait(kt, theKt ->
                new KafkaTopicBuilder(theKt)
                    .editOrNewMetadata()
                        .addToAnnotations(TopicOperatorUtil.MANAGED, "false")
                    .endMetadata()
                    .editOrNewSpec()
                        .withPartitions(3)
                    .endSpec()
                    .build(),
            new Predicate<>() {
                @Override
                public boolean test(KafkaTopic theKt) {
                    return theKt.getStatus().getConditions().get(0).getType().equals("Unmanaged");
                }

                @Override
                public String toString() {
                    return "status=Unmanaged";
                }
            });

        // then
        assertNull(unmanaged.getStatus().getTopicName());
        var topicDescription = awaitTopicDescription(expectedTopicName);
        assertEquals(kt.getSpec().getPartitions(), numPartitions(topicDescription));
        assertEquals(Set.of(kt.getSpec().getReplicas()), replicationFactors(topicDescription));
        assertEquals(Map.of(), topicConfigMap(expectedTopicName));
    }

    @ParameterizedTest
    @MethodSource({"managedKafkaTopics"})
    public void shouldRestoreFinalizerIfRemoved(
        KafkaTopic kt,
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException, TimeoutException {
        // given
        var created = createTopic(kafkaCluster, kt, TopicOperatorUtil.isManaged(kt) ? readyIsTrueOrFalse() : unmanagedIsTrueOrFalse());
        if (TopicOperatorUtil.isManaged(kt)) {
            assertCreateSuccess(kt, created);
        }

        // when: The finalizer is removed
        LOGGER.debug("Removing finalizer");
        var postUpdate = TopicOperatorTestUtil.changeTopic(kubernetesClient, created, theKt1 -> {
            theKt1.getMetadata().getFinalizers().remove(BatchingTopicController.FINALIZER);
            return theKt1;
        });
        var postUpdateGeneration = postUpdate.getMetadata().getGeneration();
        LOGGER.debug("Removed finalizer; generation={}", postUpdateGeneration);

        // then: We expect the operator to revert the finalizer
        waitUntil(postUpdate, theKt ->
            theKt.getStatus().getObservedGeneration() >= postUpdateGeneration
                && theKt.getMetadata().getFinalizers().contains(BatchingTopicController.FINALIZER));
    }

    @ParameterizedTest
    @MethodSource("managedKafkaTopics")
    public void shouldDeleteTopicFromKafkaWhenManagedTopicDeletedFromKube(
        KafkaTopic kt,
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException, TimeoutException {
        // given
        createTopicAndAssertSuccess(kafkaCluster, kt);

        // when
        Crds.topicOperation(kubernetesClient).resource(kt).delete();
        LOGGER.info("Test deleted KafkaTopic {} with resourceVersion {}",
            kt.getMetadata().getName(), TopicOperatorUtil.resourceVersion(kt));
        Resource<KafkaTopic> resource = Crds.topicOperation(kubernetesClient).resource(kt);
        TopicOperatorTestUtil.waitUntilCondition(resource, Objects::isNull);

        // then
        assertNotExistsInKafka(TopicOperatorUtil.topicName(kt));

    }

    @ParameterizedTest
    @MethodSource("managedKafkaTopics")
    public void shouldNotDeleteTopicWhenTopicDeletionDisabledInKafka(
        KafkaTopic kt,
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        @BrokerConfig(name = "delete.topic.enable", value = "false")
        KafkaCluster kafkaCluster
    ) throws ExecutionException, InterruptedException, TimeoutException {
        // given
        createTopicAndAssertSuccess(kafkaCluster, kt);

        // when
        Crds.topicOperation(kubernetesClient).resource(kt).delete();
        LOGGER.info("Test delete KafkaTopic {} with resourceVersion {}",
            kt.getMetadata().getName(), TopicOperatorUtil.resourceVersion(kt));
        Resource<KafkaTopic> resource = Crds.topicOperation(kubernetesClient).resource(kt);
        var unready = TopicOperatorTestUtil.waitUntilCondition(resource, readyIsFalse());

        // then
        Condition condition = assertExactlyOneCondition(unready);
        assertEquals(TopicOperatorException.Reason.KAFKA_ERROR.value, condition.getReason());
        assertEquals("org.apache.kafka.common.errors.TopicDeletionDisabledException: Topic deletion is disabled.",
            condition.getMessage());
    }

    @ParameterizedTest
    @MethodSource("managedKafkaTopics")
    public void shouldDeleteTopicFromKafkaWhenManagedTopicDeletedFromKubeAndFinalizersDisabled(
        KafkaTopic kt,
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException, TimeoutException {

        // given
        maybeStartOperator(topicOperatorConfig(kt.getMetadata().getNamespace(), kafkaCluster, false));
        createTopicAndAssertSuccess(kafkaCluster, kt);

        // when
        Crds.topicOperation(kubernetesClient).resource(kt).delete();
        LOGGER.info("Test deleted KafkaTopic {} with resourceVersion {}",
            kt.getMetadata().getName(), TopicOperatorUtil.resourceVersion(kt));
        Resource<KafkaTopic> resource = Crds.topicOperation(kubernetesClient).resource(kt);
        TopicOperatorTestUtil.waitUntilCondition(resource, Objects::isNull);

        // then
        waitNotExistsInKafka(TopicOperatorUtil.topicName(kt));
    }

    private static TopicOperatorConfig topicOperatorConfig(String ns, KafkaCluster kafkaCluster) {
        return topicOperatorConfig(ns, kafkaCluster, true);
    }

    private static TopicOperatorConfig topicOperatorConfig(String ns, KafkaCluster kafkaCluster, boolean useFinalizer) {
        return topicOperatorConfig(ns, kafkaCluster, useFinalizer, 10_000);
    }

    private static TopicOperatorConfig topicOperatorConfig(String ns, KafkaCluster kafkaCluster, boolean useFinalizer, long fullReconciliationIntervalMs) {
        return new TopicOperatorConfig(ns,
            Labels.fromMap(SELECTOR),
            kafkaCluster.getBootstrapServers(),
            TopicControllerIT.class.getSimpleName(),
            fullReconciliationIntervalMs,
            false, "", "", "", "", "",
            false, "", "", "", "", "",
            useFinalizer,
            100, 100, 10, false, new FeatureGates(""),
            false, false, "", 9090, false, false, "", "", "",
            "all", false);
    }

    @ParameterizedTest
    @MethodSource("managedKafkaTopics")
    public void shouldNotDeleteTopicFromKafkaWhenManagedTopicDeletedFromKubeAndFinalizersDisabledButDeletionDisabledInKafka(
        KafkaTopic kt,
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        @BrokerConfig(name = "delete.topic.enable", value = "false")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException, TimeoutException {

        // given
        maybeStartOperator(topicOperatorConfig(kt.getMetadata().getNamespace(), kafkaCluster, false));
        createTopicAndAssertSuccess(kafkaCluster, kt);

        postSyncBarrier();

        // when
        try (var logCaptor = LogCaptor.logMessageMatches(BatchingLoop.LoopRunnable.LOGGER,
            Level.INFO,
            "\\[Batch #[0-9]+\\] Batch reconciliation completed",
            5L,
            TimeUnit.SECONDS)) {
            try (var logCaptor2 = LogCaptor.logMessageMatches(BatchingTopicController.LOGGER,
                Level.WARN,
                "Unable to delete topic '" + TopicOperatorUtil.topicName(kt) + "' from Kafka because topic deletion is disabled on the Kafka controller.",
                5L,
                TimeUnit.SECONDS)) {

                Crds.topicOperation(kubernetesClient).resource(kt).delete();
                LOGGER.info("Test deleted KafkaTopic {} with resourceVersion {}",
                    kt.getMetadata().getName(), TopicOperatorUtil.resourceVersion(kt));
                Resource<KafkaTopic> resource = Crds.topicOperation(kubernetesClient).resource(kt);
                TopicOperatorTestUtil.waitUntilCondition(resource, Objects::isNull);
            }
        }

        // then
        awaitTopicDescription(TopicOperatorUtil.topicName(kt));
    }

    @ParameterizedTest
    @MethodSource("managedKafkaTopics")
    public void shouldNotDeleteTopicFromKafkaWhenUnmanagedTopicDeletedFromKube(
        KafkaTopic kt,
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException, TimeoutException {
        // given
        var expectedTopicName = TopicOperatorUtil.topicName(kt);
        int specPartitions = kt.getSpec().getPartitions();
        int specReplicas = kt.getSpec().getReplicas();

        createTopicAndAssertSuccess(kafkaCluster, kt);
        TopicOperatorTestUtil.changeTopic(kubernetesClient, kt, theKt -> {
            return new KafkaTopicBuilder(theKt).editOrNewMetadata().addToAnnotations(TopicOperatorUtil.MANAGED, "false").endMetadata().build();
        });

        // when
        Crds.topicOperation(kubernetesClient).resource(kt).delete();
        LOGGER.info("Test created KafkaTopic {} with resourceVersion {}",
            kt.getMetadata().getName(), TopicOperatorUtil.resourceVersion(kt));
        Resource<KafkaTopic> resource = Crds.topicOperation(kubernetesClient).resource(kt);
        TopicOperatorTestUtil.waitUntilCondition(resource, Objects::isNull);

        // then
        var topicDescription = awaitTopicDescription(expectedTopicName);
        assertEquals(specPartitions, numPartitions(topicDescription));
        assertEquals(Set.of(specReplicas), replicationFactors(topicDescription));
        assertEquals(Map.of(), topicConfigMap(expectedTopicName));
    }

    @ParameterizedTest
    @MethodSource("managedKafkaTopics")
    public void shouldNotDeleteTopicWhenUnmanagedTopicDeletedAndFinalizersDisabled(KafkaTopic kt,
                                                                                   @BrokerConfig(name = "auto.create.topics.enable", value = "false")
                                                                                   KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException, TimeoutException {

        // given
        maybeStartOperator(topicOperatorConfig(kt.getMetadata().getNamespace(), kafkaCluster, false));
        var expectedTopicName = TopicOperatorUtil.topicName(kt);
        int specPartitions = kt.getSpec().getPartitions();
        int specReplicas = kt.getSpec().getReplicas();

        createTopicAndAssertSuccess(kafkaCluster, kt);
        TopicOperatorTestUtil.changeTopic(kubernetesClient, kt, theKt ->
            new KafkaTopicBuilder(theKt).editOrNewMetadata().addToAnnotations(TopicOperatorUtil.MANAGED, "false").endMetadata().build());

        Crds.topicOperation(kubernetesClient).resource(kt).delete();
        LOGGER.info("Test deleted KafkaTopic {} with resourceVersion {}", kt.getMetadata().getName(), TopicOperatorUtil.resourceVersion(kt));
        Resource<KafkaTopic> resource = Crds.topicOperation(kubernetesClient).resource(kt);
        TopicOperatorTestUtil.waitUntilCondition(resource, Objects::isNull);

        // then
        var topicDescription = awaitTopicDescription(expectedTopicName);
        assertEquals(specPartitions, numPartitions(topicDescription));
        assertEquals(Set.of(specReplicas), replicationFactors(topicDescription));
        assertEquals(Map.of(), topicConfigMap(expectedTopicName));

    }

    static KafkaTopic[][] collidingManagedTopics(String ns1, String ns2) {
        return new KafkaTopic[][]{
            // both use spec.topicName
            new KafkaTopic[]{kafkaTopic(ns1, "kt1", true, "collide", 1, 1),
                kafkaTopic(ns2, "kt2", true, "collide", 1, 1)},
            // only second uses spec.topicName
            new KafkaTopic[]{kafkaTopic(ns1, "kt1", true, null, 1, 1),
                kafkaTopic(ns2, "kt2", true, "kt1", 1, 1)},
            // only first uses spec.topicName
            new KafkaTopic[]{kafkaTopic(ns1, "kt1", true, "collide", 1, 1),
                kafkaTopic(ns2, "collide", true, null, 1, 1)},
        };
    }

    static KafkaTopic[][] collidingManagedTopics_sameNamespace() {
        return collidingManagedTopics(NAMESPACE, NAMESPACE);
    }

    @ParameterizedTest
    @MethodSource("collidingManagedTopics_sameNamespace")
    public void shouldDetectMultipleResourcesManagingSameTopicInKafka(
        KafkaTopic kt1,
        KafkaTopic kt2,
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException, TimeoutException {
        // given
        assertEquals(TopicOperatorUtil.topicName(kt1), TopicOperatorUtil.topicName(kt2));
        assertNotEquals(kt1.getMetadata().getName(), kt2.getMetadata().getName());

        // when
        createTopicAndAssertSuccess(kafkaCluster, kt1);
        var st1 = waitUntil(kt1, readyIsTrue()).getStatus();
        Thread.sleep(1_000L);
        createTopic(kafkaCluster, kt2);
        var st2 = waitUntil(kt2, readyIsTrueOrFalse()).getStatus();
        waitUntil(kt2, readyIsTrueOrFalse());

        // then
        assertNull(st1.getConditions().get(0).getReason());
        assertEquals(TopicOperatorException.Reason.RESOURCE_CONFLICT.value, st2.getConditions().get(0).getReason());
        assertEquals(format("Managed by Ref{namespace='%s', name='%s'}", NAMESPACE, "kt1"),
            st2.getConditions().get(0).getMessage());
    }

    @Test
    public void shouldFailCreationIfMoreReplicasThanBrokers(
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster
    ) throws ExecutionException, InterruptedException {
        // given
        var topicName = "my-topic";
        var kt = kafkaTopic(NAMESPACE, topicName, true, topicName, 1, (int) Short.MAX_VALUE);
        // and kafkaCluster.numBrokers <= Short.MAX_VALUE

        // when
        var created = createTopic(kafkaCluster, kt);

        // then
        assertTrue(readyIsFalse().test(created));
        Condition condition = assertExactlyOneCondition(created);
        assertEquals(TopicOperatorException.Reason.KAFKA_ERROR.value, condition.getReason());
        assertEquals("org.apache.kafka.common.errors.InvalidReplicationFactorException: Unable to replicate the partition 32767 time(s): The target replication factor of 32767 cannot be reached because only 1 broker(s) are registered.", condition.getMessage());
    }

    @Test
    public void shouldFailCreationIfUnknownConfig(
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster
    ) throws ExecutionException, InterruptedException {
        // given
        var topicName = "my-topic";
        var kt = kafkaTopic(NAMESPACE,
            topicName,
            SELECTOR,
            null,
            true,
            topicName,
            1,
            1,
            Map.of("unknown.config.parameter", "????"));

        // when
        var created = createTopic(kafkaCluster, kt);

        // then
        assertTrue(readyIsFalse().test(created));
        Condition condition = assertExactlyOneCondition(created);
        assertEquals(TopicOperatorException.Reason.KAFKA_ERROR.value, condition.getReason());
        assertEquals("org.apache.kafka.common.errors.InvalidConfigurationException: Unknown topic config name: unknown.config.parameter", condition.getMessage());
    }

    @ParameterizedTest
    @MethodSource("managedKafkaTopicsWithIllegalTopicNames")
    public void shouldFailCreationIfIllegalTopicName(
        KafkaTopic kt,
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException {
        // given

        // when
        var created = createTopic(kafkaCluster, kt);

        // then
        assertTrue(readyIsFalse().test(created));
        Condition condition = assertExactlyOneCondition(created);
        assertEquals(TopicOperatorException.Reason.KAFKA_ERROR.value, condition.getReason());
        assertTrue(condition.getMessage().startsWith("org.apache.kafka.common.errors.InvalidTopicException: Topic name is invalid:"),
            condition.getMessage());
    }


    @ParameterizedTest
    @MethodSource("managedKafkaTopics")
    public void shouldFailChangeToSpecTopicName(
        KafkaTopic kafkaTopic,
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster
    ) throws ExecutionException, InterruptedException, TimeoutException {
        var expectedTopicName = TopicOperatorUtil.topicName(kafkaTopic);
        shouldFailOnModification(kafkaCluster, kafkaTopic,
            theKt -> {
                theKt.getSpec().setTopicName("CHANGED-" + expectedTopicName);
                return theKt;
            },
            operated -> {
                assertEquals("Changing spec.topicName is not supported", assertExactlyOneCondition(operated).getMessage());
                assertEquals(TopicOperatorException.Reason.NOT_SUPPORTED.value, assertExactlyOneCondition(operated).getReason());
            },
            theKt -> {
                theKt.getSpec().setTopicName(expectedTopicName);
                return theKt;
            });
    }

    private void shouldFailOnModification(KafkaCluster kc, KafkaTopic kt,
                                          UnaryOperator<KafkaTopic> changer,
                                          Consumer<KafkaTopic> asserter,
                                          UnaryOperator<KafkaTopic> reverter
    ) throws ExecutionException, InterruptedException, TimeoutException {
        // given
        createTopicAndAssertSuccess(kc, kt);

        // when
        KafkaTopic broken = modifyTopicAndAwait(kt, changer, readyIsFalse());

        // then
        asserter.accept(broken);

        // and when
        var fixed  = modifyTopicAndAwait(kt, reverter, readyIsTrue());

        // then
        assertNull(assertExactlyOneCondition(fixed).getMessage());
        assertNull(assertExactlyOneCondition(fixed).getReason());
    }

    private KafkaTopic modifyTopicAndAwait(KafkaTopic kt, UnaryOperator<KafkaTopic> changer, Predicate<KafkaTopic> predicate) {
        var edited = TopicOperatorTestUtil.changeTopic(kubernetesClient, kt, changer);
        var postUpdateGeneration = edited.getMetadata().getGeneration();
        Predicate<KafkaTopic> topicWasSyncedAndMatchesPredicate = new Predicate<>() {
            @Override
            public boolean test(KafkaTopic theKt) {
                return theKt.getStatus() != null
                    && (theKt.getStatus().getObservedGeneration() >= postUpdateGeneration 
                        || !TopicOperatorUtil.isManaged(theKt) || TopicOperatorUtil.isPaused(theKt))
                    && predicate.test(theKt);
            }

            @Override
            public String toString() {
                return "observedGeneration is correct and " + predicate;
            }
        };
        return waitUntil(edited, topicWasSyncedAndMatchesPredicate);
    }

    @ParameterizedTest
    @MethodSource("managedKafkaTopics")
    public void shouldFailChangeToRf(
        KafkaTopic kt,
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException, TimeoutException {
        int specReplicas = kt.getSpec().getReplicas();
        shouldFailOnModification(kafkaCluster, kt,
            theKt -> {
                theKt.getSpec().setReplicas(specReplicas + 1);
                return theKt;
            },
            operated -> {
                assertEquals("Replication factor change not supported, but required for partitions [0, 1]", assertExactlyOneCondition(operated).getMessage());
                assertEquals(TopicOperatorException.Reason.NOT_SUPPORTED.value, assertExactlyOneCondition(operated).getReason());
            },
            theKt -> {
                theKt.getSpec().setReplicas(specReplicas);
                return theKt;
            });
    }

    @Test
    public void shouldAccountForReassigningPartitionsNoRfChange(
        @BrokerCluster(numBrokers = 3)
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster,
        Producer<String, String> producer)
        throws ExecutionException, InterruptedException, TimeoutException {
        var topicName = "my-topic";
        var kt = kafkaTopic(NAMESPACE, topicName, true, topicName, 1, 1);
        accountForReassigningPartitions(kafkaCluster, producer, kt,
            initialReplicas -> {
                assertEquals(1, initialReplicas.size());
                var replacementReplica = (initialReplicas.iterator().next() + 1) % 3;
                return List.of(replacementReplica);
            },
            readyIsTrue(),
            readyIsTrue());
    }

    @Test
    public void shouldAccountForReassigningPartitionsIncreasingRf(
        @BrokerCluster(numBrokers = 3)
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster,
        Producer<String, String> producer)
        throws ExecutionException, InterruptedException, TimeoutException {
        var topicName = "my-topic";
        var kt = kafkaTopic(NAMESPACE, topicName, true, topicName, 1, 1);
        accountForReassigningPartitions(kafkaCluster, producer, kt,
            initialReplicas -> {
                assertEquals(1, initialReplicas.size());
                Integer initialReplica = initialReplicas.iterator().next();
                var replacementReplica = (initialReplica + 1) % 3;
                return List.of(initialReplica, replacementReplica);
            },
            readyIsFalseAndReasonIs("NotSupported", "Replication factor change not supported, but required for partitions [0]"),
            readyIsFalseAndReasonIs("NotSupported", "Replication factor change not supported, but required for partitions [0]"));
    }

    @Test
    @Disabled("Throttles don't provide a way to ensure that reconciliation happens when the UTO will observe a non-empty removing set")
    public void shouldAccountForReassigningPartitionsDecreasingRf(
        @BrokerCluster(numBrokers = 3)
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster,
        Producer<String, String> producer)
        throws ExecutionException, InterruptedException, TimeoutException {
        var topicName = "my-topic";
        var kt = kafkaTopic(NAMESPACE, topicName, true, topicName, 1, 2);
        accountForReassigningPartitions(kafkaCluster, producer, kt,
            initialReplicas -> {
                assertEquals(2, initialReplicas.size());
                return List.of(initialReplicas.get(0));
            },
            readyIsFalseAndReasonIs("NotSupported", "Replication factor change not supported, but required for partitions [0]"),
            readyIsFalseAndReasonIs("NotSupported", "Replication factor change not supported, but required for partitions [0]"));
    }

    private void accountForReassigningPartitions(
        @BrokerCluster(numBrokers = 3)
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster,
        Producer<String, String> producer,
        KafkaTopic kt,
        Function<List<Integer>, List<Integer>> newReplicasFn,
        Predicate<KafkaTopic> duringReassignmentPredicate,
        Predicate<KafkaTopic> postReassignmentPredicate)
        throws ExecutionException, InterruptedException, TimeoutException {
        // given
        assertEquals(1, kt.getSpec().getPartitions());
        var topicName = TopicOperatorUtil.topicName(kt);
        var tp = new TopicPartition(topicName, 0);
        var created = createTopicAndAssertSuccess(kafkaCluster, kt);

        List<Future<RecordMetadata>> futs = new ArrayList<>(1000);
        for (int i = 0; i < 1000; i++) {
            futs.add(producer.send(new ProducerRecord<>(topicName, "X".repeat(1000), "Y".repeat(1000))));
        }
        for (var f : futs) {
            f.get();
        }

        TopicPartitionInfo pi = awaitTopicDescription(topicName).partitions().get(0);
        var initialReplicas = pi.replicas().stream().map(Node::id).toList();
        var newReplicas = newReplicasFn.apply(initialReplicas);
        var initialLeader = pi.leader().id();
        var addedReplicas = new HashSet<>(newReplicas);
        initialReplicas.forEach(addedReplicas::remove);
        var removingReplicas = new HashSet<>(initialReplicas);
        newReplicas.forEach(removingReplicas::remove);

        var throttledRate = "1";
        Map<ConfigResource, Collection<AlterConfigOp>> throttles = buildThrottles(initialLeader, addedReplicas, throttledRate, tp, AlterConfigOp.OpType.SET);
        Map<ConfigResource, Collection<AlterConfigOp>> removeThrottles = buildThrottles(initialLeader, addedReplicas, throttledRate, tp, AlterConfigOp.OpType.DELETE);
        LOGGER.debug("Initial leader {}", initialLeader);
        LOGGER.debug("Initial replicas {}", initialReplicas);
        LOGGER.debug("New replicas {}", newReplicas);
        LOGGER.debug("Added replicas {}", addedReplicas);
        LOGGER.debug("Removing replicas {}", removingReplicas);
        LOGGER.debug("Throttles {}", throttles);
        LOGGER.debug("Remove throttles {}", removeThrottles);

        // throttle replication to zero. This is to ensure the operator will actually observe the topic state
        // during reassignment
        kafkaAdminClient[0].incrementalAlterConfigs(throttles).all().get();

        // when: reassignment is on-going

        var reassignStartResult = kafkaAdminClient[0].alterPartitionReassignments(
            Map.of(
                tp, Optional.of(new NewPartitionReassignment(newReplicas))
            )
        );
        reassignStartResult.all().get();

        assertFalse(kafkaAdminClient[0].listPartitionReassignments(Set.of(tp)).reassignments().get().isEmpty(),
            "Expect on-going reassignment prior to reconcile");

        // then
        // trigger reconciliation by change a config
        var modified = modifyTopicAndAwait(created,
            TopicControllerIT::setSnappyCompression,
            duringReassignmentPredicate);

        assertFalse(kafkaAdminClient[0].listPartitionReassignments(Set.of(tp)).reassignments().get().isEmpty(),
            "Expect on-going reassignment after reconcile");

        // let reassignment complete normally by removing the throttles
        kafkaAdminClient[0].incrementalAlterConfigs(removeThrottles).all().get();

        long deadline = System.currentTimeMillis() + 30_000;
        while (!kafkaAdminClient[0].listPartitionReassignments(Set.of(tp)).reassignments().get().isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                throw new TimeoutException("Expecting reassignment to complete after removing throttles");
            }
            TimeUnit.MILLISECONDS.sleep(1_000);
        }

        // trigger reconciliation by changing a config again
        modifyTopicAndAwait(modified,
            TopicControllerIT::setGzipCompression,
            postReassignmentPredicate);
    }

    private static HashMap<ConfigResource, Collection<AlterConfigOp>> buildThrottles(
        int initialLeader,
        HashSet<Integer> addedReplicas,
        String throttledRate,
        TopicPartition tp,
        AlterConfigOp.OpType set) {
        var throttles = new LinkedHashMap<ConfigResource, Collection<AlterConfigOp>>();
        throttles.put(new ConfigResource(ConfigResource.Type.BROKER, Integer.toString(initialLeader)),
            List.of(new AlterConfigOp(new ConfigEntry("leader.replication.throttled.rate", throttledRate), set)));
        addedReplicas.forEach(addedReplica ->
            throttles.put(new ConfigResource(ConfigResource.Type.BROKER, Integer.toString(addedReplica)),
                List.of(new AlterConfigOp(new ConfigEntry("follower.replication.throttled.rate", throttledRate), set))));
        throttles.put(new ConfigResource(ConfigResource.Type.TOPIC, tp.topic()),
            List.of(new AlterConfigOp(new ConfigEntry("leader.replication.throttled.replicas", "%d:%d".formatted(tp.partition(), initialLeader)), set)));
        addedReplicas.forEach(addedReplica -> throttles.put(new ConfigResource(ConfigResource.Type.TOPIC, tp.topic()),
            List.of(new AlterConfigOp(new ConfigEntry("follower.replication.throttled.replicas", "%d:%d".formatted(tp.partition(), addedReplica)), set))));
        return throttles;
    }

    @ParameterizedTest
    @MethodSource("managedKafkaTopics")
    public void shouldFailCreationIfNoTopicAuthz(KafkaTopic kt,
                                                 @BrokerConfig(name = "auto.create.topics.enable", value = "false")
                                                 KafkaCluster kafkaCluster)
        throws ExecutionException, InterruptedException {
        topicCreationFailsDueToAdminException(kt, kafkaCluster, new TopicAuthorizationException("not allowed"), "KafkaError");
    }

    @ParameterizedTest
    @MethodSource("managedKafkaTopics")
    public void shouldFailCreationIfNpe(KafkaTopic kt,
                                        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
                                        KafkaCluster kafkaCluster)
        throws ExecutionException, InterruptedException {
        topicCreationFailsDueToAdminException(kt, kafkaCluster, new NullPointerException(), "InternalError");
    }

    private void topicCreationFailsDueToAdminException(KafkaTopic kt,
                                                       KafkaCluster kafkaCluster,
                                                       Throwable exception,
                                                       String expectedReason)
        throws ExecutionException, InterruptedException {
        // given
        var config = topicOperatorConfig(NAMESPACE, kafkaCluster);
        kafkaAdminClientOp = new Admin[]{Mockito.spy(Admin.create(config.adminClientConfig()))};
        var ctr = mock(CreateTopicsResult.class);
        Mockito.doReturn(failedFuture(exception)).when(ctr).all();
        Mockito.doReturn(Map.of(TopicOperatorUtil.topicName(kt), failedFuture(exception))).when(ctr).values();
        Mockito.doReturn(ctr).when(kafkaAdminClientOp[0]).createTopics(any());
        maybeStartOperator(config);

        //when
        var created = createTopic(kafkaCluster, kt);

        // then
        assertTrue(readyIsFalse().test(created));
        var condition = assertExactlyOneCondition(created);
        assertEquals(expectedReason, condition.getReason());
        assertEquals(exception.toString(), condition.getMessage());
    }

    @ParameterizedTest
    @MethodSource("managedKafkaTopics")
    public void shouldFailAlterConfigIfNoTopicAuthz(KafkaTopic kt,
                                                    @BrokerConfig(name = "auto.create.topics.enable", value = "false")
                                                    KafkaCluster kafkaCluster)
        throws ExecutionException, InterruptedException, TimeoutException {
        var config = topicOperatorConfig(NAMESPACE, kafkaCluster);
        kafkaAdminClientOp = new Admin[]{Mockito.spy(Admin.create(config.adminClientConfig()))};
        var ctr = mock(AlterConfigsResult.class);
        Mockito.doReturn(failedFuture(new TopicAuthorizationException("not allowed"))).when(ctr).all();
        Mockito.doReturn(Map.of(new ConfigResource(ConfigResource.Type.TOPIC, TopicOperatorUtil.topicName(kt)), failedFuture(new TopicAuthorizationException("not allowed")))).when(ctr).values();
        Mockito.doReturn(ctr).when(kafkaAdminClientOp[0]).incrementalAlterConfigs(any());

        maybeStartOperator(config);
        createTopicAndAssertSuccess(kafkaCluster, kt);

        var modified = modifyTopicAndAwait(kt,
            TopicControllerIT::setSnappyCompression,
            readyIsFalse());
        var condition = assertExactlyOneCondition(modified);
        assertEquals("KafkaError", condition.getReason());
        assertEquals("org.apache.kafka.common.errors.TopicAuthorizationException: not allowed", condition.getMessage());
    }
    
    @Test
    public void shouldFailTheReconciliationWithNullConfig(
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster
    ) throws ExecutionException, InterruptedException {
        invalidConfigFailsReconciliation(
                kafkaCluster,
                null,
                "KafkaError",
                "org.apache.kafka.common.errors.InvalidConfigurationException: Null value not supported for topic configs: cleanup.policy");
    }

    @Test
    public void shouldFailTheReconciliationWithUnexpectedConfig(
            @BrokerConfig(name = "auto.create.topics.enable", value = "false")
            KafkaCluster kafkaCluster
    ) throws ExecutionException, InterruptedException {
        invalidConfigFailsReconciliation(
                kafkaCluster,
                Map.of("foo", 12),
                "InternalError",
                "io.strimzi.operator.common.model.InvalidResourceException: Invalid value for topic config 'cleanup.policy': {foo=12}");
    }

    private void invalidConfigFailsReconciliation(
            KafkaCluster kafkaCluster,
            Map<String, Integer> policy,
            String expectedReasons,
            String expectedMessage
    ) throws ExecutionException, InterruptedException {
        Map<String, Object> configs = new HashMap<>();
        configs.put("cleanup.policy", policy);
        KafkaTopic kafkaTopic = new KafkaTopicBuilder()
                .withNewMetadata()
                .withNamespace(NAMESPACE)
                .withName("my-topic")
                .withLabels(SELECTOR)
                .endMetadata()
                .withNewSpec()
                .withConfig(configs)
                .withPartitions(1)
                .withReplicas(1)
                .endSpec()
                .build();
        var created = createTopic(kafkaCluster, kafkaTopic);
        var condition = assertExactlyOneCondition(created);
        assertEquals(expectedReasons, condition.getReason());
        assertEquals(expectedMessage, condition.getMessage());
    }

    private static KafkaTopic setGzipCompression(KafkaTopic kt) {
        return setCompression(kt, "gzip");
    }

    private static KafkaTopic setSnappyCompression(KafkaTopic kt) {
        return setCompression(kt, "snappy");
    }

    private static KafkaTopic setCompression(KafkaTopic kt, String gzip) {
        return new KafkaTopicBuilder(kt).editOrNewSpec().addToConfig(TopicConfig.COMPRESSION_TYPE_CONFIG, gzip).endSpec().build();
    }

    @ParameterizedTest
    @MethodSource("managedKafkaTopics")
    public void shouldFailAddPartitionsIfNoTopicAuthz(KafkaTopic kt,
                                                      @BrokerConfig(name = "auto.create.topics.enable", value = "false")
                                                      KafkaCluster kafkaCluster)
        throws ExecutionException, InterruptedException, TimeoutException {
        var config = topicOperatorConfig(NAMESPACE, kafkaCluster);
        kafkaAdminClientOp = new Admin[]{Mockito.spy(Admin.create(config.adminClientConfig()))};
        var ctr = mock(CreatePartitionsResult.class);
        Mockito.doReturn(failedFuture(new TopicAuthorizationException("not allowed"))).when(ctr).all();
        Mockito.doReturn(Map.of(TopicOperatorUtil.topicName(kt), failedFuture(new TopicAuthorizationException("not allowed")))).when(ctr).values();
        Mockito.doReturn(ctr).when(kafkaAdminClientOp[0]).createPartitions(any());

        maybeStartOperator(config);
        createTopicAndAssertSuccess(kafkaCluster, kt);

        var modified = modifyTopicAndAwait(kt,
            TopicControllerIT::incrementPartitions,
            readyIsFalse());
        var condition = assertExactlyOneCondition(modified);
        assertEquals("KafkaError", condition.getReason());
        assertEquals("org.apache.kafka.common.errors.TopicAuthorizationException: not allowed", condition.getMessage());
    }

    private static KafkaTopic incrementPartitions(KafkaTopic theKt) {
        theKt.getSpec().setPartitions(theKt.getSpec().getPartitions() + 1);
        return theKt;
    }

    @ParameterizedTest
    @MethodSource("managedKafkaTopics")
    public void shouldFailDeleteIfNoTopicAuthz(KafkaTopic kt,
                                               @BrokerConfig(name = "auto.create.topics.enable", value = "false")
                                               KafkaCluster kafkaCluster)
        throws ExecutionException, InterruptedException, TimeoutException {

        // given
        var config = topicOperatorConfig(NAMESPACE, kafkaCluster);
        kafkaAdminClientOp = new Admin[]{Mockito.spy(Admin.create(config.adminClientConfig()))};
        var ctr = mock(DeleteTopicsResult.class);
        Mockito.doReturn(failedFuture(new TopicAuthorizationException("not allowed"))).when(ctr).all();
        Mockito.doReturn(Map.of(TopicOperatorUtil.topicName(kt), failedFuture(new TopicAuthorizationException("not allowed")))).when(ctr).topicNameValues();
        Mockito.doReturn(ctr).when(kafkaAdminClientOp[0]).deleteTopics(any(TopicCollection.TopicNameCollection.class));

        maybeStartOperator(config);
        createTopicAndAssertSuccess(kafkaCluster, kt);

        // when
        Crds.topicOperation(kubernetesClient).resource(kt).delete();
        LOGGER.info("Test deleted KafkaTopic {} with resourceVersion {}",
            kt.getMetadata().getName(), TopicOperatorUtil.resourceVersion(kt));
        Resource<KafkaTopic> resource = Crds.topicOperation(kubernetesClient).resource(kt);
        var deleted = TopicOperatorTestUtil.waitUntilCondition(resource, readyIsFalse());

        // then
        var condition = assertExactlyOneCondition(deleted);
        assertEquals("KafkaError", condition.getReason());
        assertEquals("org.apache.kafka.common.errors.TopicAuthorizationException: not allowed", condition.getMessage());
    }

    @Test
    public void shouldFailIfNumPartitionsDivergedWithConfigChange(@BrokerConfig(name = "auto.create.topics.enable", value = "false")
                                                                  KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException, TimeoutException {
        // scenario from https://github.com/strimzi/strimzi-kafka-operator/pull/8627#pullrequestreview-1477513413
        var firstTopicName = "first";
        var secondTopicName = "second";

        // create topic
        LOGGER.info("Create {}", firstTopicName);
        var firstTopic = kafkaTopic(NAMESPACE, firstTopicName, null, null, 1, 1);
        firstTopic = createTopicAndAssertSuccess(kafkaCluster, firstTopic);

        // create conflicting topic
        LOGGER.info("Create conflicting {}", secondTopicName);
        var secondTopic = kafkaTopic(NAMESPACE, secondTopicName, SELECTOR, null, null, firstTopicName,
            1, 1, Map.of(TopicConfig.COMPRESSION_TYPE_CONFIG, "snappy"));
        secondTopic = createTopic(kafkaCluster, secondTopic);
        assertTrue(readyIsFalse().test(secondTopic));
        var condition = assertExactlyOneCondition(secondTopic);
        assertEquals(TopicOperatorException.Reason.RESOURCE_CONFLICT.value, condition.getReason());
        assertEquals(format("Managed by Ref{namespace='%s', name='%s'}", NAMESPACE, firstTopicName), condition.getMessage());

        // increase partitions of topic
        LOGGER.info("Increase partitions of {}", firstTopicName);
        var editedFoo = modifyTopicAndAwait(firstTopic, theKt ->
                new KafkaTopicBuilder(theKt).editSpec().withPartitions(3).endSpec().build(),
            readyIsTrue());

        // unmanage topic
        LOGGER.info("Unmanage {}", firstTopicName);
        var unmanagedFoo = modifyTopicAndAwait(editedFoo, theKt ->
                new KafkaTopicBuilder(theKt).editMetadata().addToAnnotations(TopicOperatorUtil.MANAGED, "false").endMetadata().build(),
            readyIsTrue());

        // when: delete topic
        LOGGER.info("Delete {}", firstTopicName);
        Crds.topicOperation(kubernetesClient).resource(unmanagedFoo).delete();
        LOGGER.info("Test deleted KafkaTopic {} with resourceVersion {}", unmanagedFoo.getMetadata().getName(), TopicOperatorUtil.resourceVersion(unmanagedFoo));
        Resource<KafkaTopic> resource = Crds.topicOperation(kubernetesClient).resource(unmanagedFoo);
        TopicOperatorTestUtil.waitUntilCondition(resource, Objects::isNull);

        // then: expect conflicting topic's unreadiness to be due to mismatching #partitions
        waitUntil(secondTopic, readyIsFalseAndReasonIs(
            TopicOperatorException.Reason.NOT_SUPPORTED.value,
            "Decreasing partitions not supported"));
    }

    @RepeatedTest(10)
    public void shouldDetectConflictingKafkaTopicCreations(
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException {
        var foo = kafkaTopic(NAMESPACE, "foo", null, null, 1, 1);
        var bar = kafkaTopic(NAMESPACE, "bar", SELECTOR, null, null, "foo", 1, 1,
            Map.of(TopicConfig.COMPRESSION_TYPE_CONFIG, "snappy"));

        LOGGER.info("Create conflicting topics: foo and bar");
        var reconciledTopics = createTopicsConcurrently(kafkaCluster, foo, bar);
        var reconciledFoo = TopicOperatorTestUtil.findKafkaTopicByName(reconciledTopics, "foo");
        var reconciledBar = TopicOperatorTestUtil.findKafkaTopicByName(reconciledTopics, "bar");

        // only one resource with the same topicName should be reconciled
        var fooFailed = readyIsFalse().test(reconciledFoo);
        var barFailed = readyIsFalse().test(reconciledBar);
        assertTrue(fooFailed ^ barFailed);

        if (fooFailed) {
            assertKafkaTopicConflict(reconciledFoo, reconciledBar);
        } else {
            assertKafkaTopicConflict(reconciledBar, reconciledFoo);
        }
    }

    private void assertKafkaTopicConflict(KafkaTopic failed, KafkaTopic ready) {
        // the error message should refer to the ready resource name
        var condition = assertExactlyOneCondition(failed);
        assertEquals(TopicOperatorException.Reason.RESOURCE_CONFLICT.value, condition.getReason());
        assertEquals(format("Managed by Ref{namespace='%s', name='%s'}",
            ready.getMetadata().getNamespace(), ready.getMetadata().getName()), condition.getMessage());

        // the failed resource should become ready after we unmanage and delete the other
        LOGGER.info("Unmanage {}", ready.getMetadata().getName());
        var unmanagedBar = modifyTopicAndAwait(ready, theKt ->
                new KafkaTopicBuilder(theKt).editMetadata().addToAnnotations(TopicOperatorUtil.MANAGED, "false").endMetadata().build(),
            readyIsTrue());

        LOGGER.info("Delete {}", ready.getMetadata().getName());
        Crds.topicOperation(kubernetesClient).resource(unmanagedBar).delete();
        Resource<KafkaTopic> resource = Crds.topicOperation(kubernetesClient).resource(unmanagedBar);
        TopicOperatorTestUtil.waitUntilCondition(resource, Objects::isNull);

        waitUntil(failed, readyIsTrue());
    }

    private static <T> KafkaFuture<T> failedFuture(Throwable error) {
        var future = new KafkaFutureImpl<T>();
        future.completeExceptionally(error);
        return future;
    }

    @Test
    public void shouldLogWarningIfAutoCreateTopicsIsEnabled(
        @BrokerConfig(name = BatchingTopicController.AUTO_CREATE_TOPICS_ENABLE, value = "true")
        KafkaCluster kafkaCluster)
        throws Exception {
        try (var logCaptor = LogCaptor.logMessageMatches(BatchingTopicController.LOGGER,
            Level.WARN,
            "It is recommended that " + BatchingTopicController.AUTO_CREATE_TOPICS_ENABLE + " is set to 'false' " +
                "to avoid races between the operator and Kafka applications auto-creating topics",
            5L,
            TimeUnit.SECONDS)) {
            maybeStartOperator(topicOperatorConfig(NAMESPACE, kafkaCluster));
        }
    }

    @Test
    public void shouldTerminateIfQueueFull(
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        @BrokerConfig(name = "num.partitions", value = "4")
        @BrokerConfig(name = "default.replication.factor", value = "1")
        KafkaCluster kafkaCluster)
        throws ExecutionException, InterruptedException, TimeoutException {

        // given
        String ns = createNamespace(NAMESPACE);

        var config = new TopicOperatorConfig(ns, Labels.fromMap(SELECTOR),
            kafkaCluster.getBootstrapServers(), TopicControllerIT.class.getSimpleName(), 10_000,
            false, "", "", "", "", "",
            false, "", "", "", "", "",
            true,
            1, 100, 5_0000, false, new FeatureGates(""),
            false, false, "", 9090, false, false, "", "", "",
            "all", false);

        maybeStartOperator(config);

        assertTrue(operator.isAlive());

        KafkaTopic kt = new KafkaTopicBuilder()
            .withNewMetadata()
            .withNamespace(NAMESPACE)
            .withName("foo")
            .withLabels(SELECTOR)
            .endMetadata()
            .build();

        // when

        // We stop the loop thread, so nothing it taking from the queue, so that the queue length will be exceeded
        operator.queue.stop();

        try (var logCaptor = LogCaptor.logMessageMatches(BatchingLoop.LOGGER,
            Level.ERROR,
            "Queue length 1 exceeded, stopping operator. Please increase STRIMZI_MAX_QUEUE_SIZE environment variable",
            5L,
            TimeUnit.SECONDS)) {

            Crds.topicOperation(kubernetesClient).resource(kt).create();
            Crds.topicOperation(kubernetesClient).resource(new KafkaTopicBuilder(kt)
                .editMetadata().withName("bar").endMetadata().build()).create();
        }

        // then
        assertNull(operator.shutdownHook, "Expect the operator to shutdown");

        // finally, because the @After method of this class asserts that the operator is running
        // we start a new operator
        kafkaAdminClient = null;
        kafkaAdminClientOp = null;
        operator = null;
        maybeStartOperator(topicOperatorConfig(NAMESPACE, kafkaCluster));
    }

    @Test
    public void shouldNotReconcilePausedKafkaTopicOnAdd(
        @BrokerConfig(name = BatchingTopicController.AUTO_CREATE_TOPICS_ENABLE, value = "false")
        KafkaCluster kafkaCluster
    ) throws ExecutionException, InterruptedException {
        var topicName = "my-topic";
        var kafkaTopic = createTopic(
            kafkaCluster,
            kafkaTopic(NAMESPACE, topicName, SELECTOR,
                Map.of(ResourceAnnotations.ANNO_STRIMZI_IO_PAUSE_RECONCILIATION, "true"),
                true, topicName, 1, 1, Map.of()),
            pausedIsTrue()
        );

        assertEquals(1, kafkaTopic.getStatus().getObservedGeneration());
        assertNotExistsInKafka(topicName);
    }

    @Test
    public void shouldNotReconcilePausedKafkaTopicOnUpdate(
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster
    ) throws ExecutionException, InterruptedException {
        var topicName = "my-topic";
        createTopic(kafkaCluster,
            kafkaTopic(NAMESPACE, topicName, SELECTOR, null, true, topicName, 1, 1, Map.of()));
        
        var kafkaTopic = pauseTopic(NAMESPACE, topicName);
        
        TopicOperatorTestUtil.changeTopic(kubernetesClient, kafkaTopic, theKt -> {
            theKt.getSpec().setConfig(Map.of(TopicConfig.FLUSH_MS_CONFIG, "1000"));
            return theKt;
        });

        assertEquals(1, kafkaTopic.getStatus().getObservedGeneration());
        assertEquals(Map.of(), topicConfigMap(topicName));
    }

    @Test
    public void shouldReconcilePausedKafkaTopicOnDelete(
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster
    ) throws ExecutionException, InterruptedException {
        var topicName = "my-topic";
        createTopic(kafkaCluster,
            kafkaTopic(NAMESPACE, topicName, SELECTOR, null, true, topicName, 1, 1, Map.of()));
        
        var kafkaTopic = pauseTopic(NAMESPACE, topicName);

        Crds.topicOperation(kubernetesClient).resource(kafkaTopic).delete();
        LOGGER.info("Test deleted KafkaTopic {} with resourceVersion {}",
            kafkaTopic.getMetadata().getName(), TopicOperatorUtil.resourceVersion(kafkaTopic));
        Resource<KafkaTopic> resource = Crds.topicOperation(kubernetesClient).resource(kafkaTopic);
        TopicOperatorTestUtil.waitUntilCondition(resource, Objects::isNull);

        assertNotExistsInKafka(topicName);
    }

    @Test
    public void topicIdShouldBeEmptyOnPausedKafkaTopic(
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster
    ) throws ExecutionException, InterruptedException {
        var topicName = "my-topic";
        var kafkaTopic = createTopic(kafkaCluster,
            kafkaTopic(NAMESPACE, topicName, SELECTOR, null, true, topicName, 1, 1, Map.of()));

        assertNotNull(kafkaTopic.getStatus().getTopicName());
        assertNotNull(kafkaTopic.getStatus().getTopicId());

        kafkaTopic = pauseTopic(NAMESPACE, topicName);

        assertNotNull(kafkaTopic.getStatus().getTopicName());
        assertNull(kafkaTopic.getStatus().getTopicId());
        
        kafkaTopic = unpauseTopic(NAMESPACE, topicName);

        assertNotNull(kafkaTopic.getStatus().getTopicName());
        assertNotNull(kafkaTopic.getStatus().getTopicId());
    }

    @Test
    public void topicNameAndIdShouldBeEmptyOnUnmanagedKafkaTopic(
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster
    ) throws ExecutionException, InterruptedException {
        var topicName = "my-topic";

        var kafkaTopic = createTopic(kafkaCluster,
            kafkaTopic(NAMESPACE, topicName, SELECTOR, null, true, topicName, 1, 1, Map.of()));

        assertNotNull(kafkaTopic.getStatus().getTopicName());
        assertNotNull(kafkaTopic.getStatus().getTopicId());

        kafkaTopic = unmanageTopic(NAMESPACE, topicName);

        assertNull(kafkaTopic.getStatus().getTopicName());
        assertNull(kafkaTopic.getStatus().getTopicId());

        kafkaTopic = manageTopic(NAMESPACE, topicName);

        assertNotNull(kafkaTopic.getStatus().getTopicName());
        assertNotNull(kafkaTopic.getStatus().getTopicId());
    }

    @Test
    public void shouldReconcileKafkaTopicWithoutPartitions(
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        @BrokerConfig(name = "num.partitions", value = "3")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException, TimeoutException {
        var topicName = "my-topic";

        createTopic(kafkaCluster,
            kafkaTopic(NAMESPACE, topicName, SELECTOR, null, true, topicName, null, 1, Map.of()));

        var topicDescription = awaitTopicDescription(topicName);
        assertEquals(3, numPartitions(topicDescription));
    }

    @Test
    public void shouldReconcileKafkaTopicWithoutReplicas(
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        @BrokerConfig(name = "default.replication.factor", value = "1")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException, TimeoutException {
        var topicName = "my-topic";

        createTopic(kafkaCluster,
            kafkaTopic(NAMESPACE, topicName, SELECTOR, null, true, topicName, 1, null, Map.of()));

        var topicDescription = awaitTopicDescription(topicName);
        assertEquals(Set.of(1), replicationFactors(topicDescription));
    }

    @Test
    public void shouldReconcileKafkaTopicWithEmptySpec(
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        @BrokerConfig(name = "num.partitions", value = "3")
        @BrokerConfig(name = "default.replication.factor", value = "1")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException, TimeoutException {
        var topicName = "my-topic";
        createTopic(kafkaCluster, kafkaTopicWithNoSpec(topicName, true));
        var topicDescription = awaitTopicDescription(topicName);
        assertEquals(3, numPartitions(topicDescription));
        assertEquals(Set.of(1), replicationFactors(topicDescription));
    }

    @Test
    public void shouldNotReconcileKafkaTopicWithMissingSpec(
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        @BrokerConfig(name = "num.partitions", value = "3")
        @BrokerConfig(name = "default.replication.factor", value = "1")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException {
        var topicName = "my-topic";
        maybeStartOperator(topicOperatorConfig(NAMESPACE, kafkaCluster));

        Crds.topicOperation(kubernetesClient)
            .resource(kafkaTopicWithNoSpec(topicName, false))
            .create();

        assertNotExistsInKafka(topicName);
    }

    @Test
    public void shouldReconcileOnTopicExistsException(
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException {
        var topicName = "my-topic";
        var config = topicOperatorConfig(NAMESPACE, kafkaCluster);

        var creteTopicResult = mock(CreateTopicsResult.class);
        var existsException = new TopicExistsException(format("Topic '%s' already exists.", topicName));
        Mockito.doReturn(failedFuture(existsException)).when(creteTopicResult).all();
        Mockito.doReturn(Map.of(topicName, failedFuture(existsException))).when(creteTopicResult).values();
        kafkaAdminClientOp = new Admin[]{Mockito.spy(Admin.create(config.adminClientConfig()))};
        Mockito.doReturn(creteTopicResult).when(kafkaAdminClientOp[0]).createTopics(any());

        KafkaTopic kafkaTopic = createTopic(kafkaCluster, kafkaTopic(NAMESPACE, topicName, true, topicName, 2, 1));
        assertTrue(readyIsTrue().test(kafkaTopic));
    }

    @Test
    public void shouldUpdateAnUnmanagedTopic(
            @BrokerConfig(name = "auto.create.topics.enable", value = "false")
            KafkaCluster kafkaCluster) throws ExecutionException, InterruptedException {
        var topicName = "my-topic";

        // create the topic
        var topic = createTopic(kafkaCluster,
                kafkaTopic(NAMESPACE, topicName, SELECTOR, null, null, topicName, 1, 1,
                        Map.of(TopicConfig.RETENTION_MS_CONFIG, "1000")));
        topic = Crds.topicOperation(kubernetesClient).resource(topic).get();

        TopicOperatorTestUtil.waitUntilCondition(Crds.topicOperation(kubernetesClient).resource(topic), kt ->
                Optional.of(kt)
                    .map(KafkaTopic::getStatus)
                    .map(KafkaTopicStatus::getConditions)
                    .flatMap(c -> Optional.of(c.get(0)))
                    .map(Condition::getType)
                    .filter("Ready"::equals)
                    .isPresent()
        );

        // set unmanaged
        topic = Crds.topicOperation(kubernetesClient).resource(topic).get();
        topic.setStatus(null);
        topic.getMetadata().getAnnotations().put(TopicOperatorUtil.MANAGED, "false");
        topic = Crds.topicOperation(kubernetesClient).resource(topic).update();

        TopicOperatorTestUtil.waitUntilCondition(Crds.topicOperation(kubernetesClient).resource(topic), kt ->
            Optional.of(kt)
                    .map(KafkaTopic::getStatus)
                    .map(KafkaTopicStatus::getConditions)
                    .flatMap(c -> Optional.of(c.get(0)))
                    .map(Condition::getType)
                    .filter("Unmanaged"::equals)
                    .isPresent()
        );

        // apply a change to the unmanaged topic
        topic = Crds.topicOperation(kubernetesClient).resource(topic).get();
        topic.setStatus(null);
        topic.getSpec().getConfig().put(TopicConfig.RETENTION_MS_CONFIG, "1001");
        topic = Crds.topicOperation(kubernetesClient).resource(topic).update();
        var resourceVersionOnUpdate = topic.getMetadata().getResourceVersion();

        TopicOperatorTestUtil.waitUntilCondition(Crds.topicOperation(kubernetesClient).resource(topic), kt ->
                !resourceVersionOnUpdate.equals(kt.getMetadata().getResourceVersion())
        );
        topic = Crds.topicOperation(kubernetesClient).resource(topic).get();
        var resourceVersionAfterUpdate = topic.getMetadata().getResourceVersion();

        // Wait a bit to check the resource is not getting updated continuously
        Thread.sleep(500L);
        TopicOperatorTestUtil.waitUntilCondition(Crds.topicOperation(kubernetesClient).resource(topic), kt ->
                resourceVersionAfterUpdate.equals(kt.getMetadata().getResourceVersion())
        );
    }

    @Test
    public void shouldUpdateTopicIdIfDeletedWhileUnmanaged(
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster
    ) throws ExecutionException, InterruptedException {
        TopicOperatorConfig config = topicOperatorConfig(NAMESPACE, kafkaCluster, true, 500);
        kafkaAdminClientOp = new Admin[]{Mockito.spy(Admin.create(config.adminClientConfig()))};

        var created = createTopic(kafkaCluster,
            kafkaTopic(NAMESPACE, "my-topic", SELECTOR, null, true, "my-topic", 1, 1, Map.of()));

        unmanageTopic(NAMESPACE, "my-topic");

        kafkaAdminClientOp[0].deleteTopics(Set.of("my-topic"));
        kafkaAdminClientOp[0].createTopics(Set.of(new NewTopic("my-topic", 1, (short) 1)));

        var updated = manageTopic(NAMESPACE, "my-topic");

        assertNotEquals(created.getStatus().getTopicId(), updated.getStatus().getTopicId());
    }

    @Test
    public void shouldUpdateTopicIdIfDeletedWhilePaused(
        @BrokerConfig(name = "auto.create.topics.enable", value = "false")
        KafkaCluster kafkaCluster
    ) throws ExecutionException, InterruptedException {
        TopicOperatorConfig config = topicOperatorConfig(NAMESPACE, kafkaCluster, true, 500);
        kafkaAdminClientOp = new Admin[]{Mockito.spy(Admin.create(config.adminClientConfig()))};

        var created = createTopic(kafkaCluster,
            kafkaTopic(NAMESPACE, "my-topic", SELECTOR, null, true, "my-topic", 1, 1, Map.of()));

        pauseTopic(NAMESPACE, "my-topic");

        kafkaAdminClientOp[0].deleteTopics(Set.of("my-topic"));
        kafkaAdminClientOp[0].createTopics(Set.of(new NewTopic("my-topic", 1, (short) 1)));

        var updated = unpauseTopic(NAMESPACE, "my-topic");

        assertNotEquals(created.getStatus().getTopicId(), updated.getStatus().getTopicId());
    }
}
