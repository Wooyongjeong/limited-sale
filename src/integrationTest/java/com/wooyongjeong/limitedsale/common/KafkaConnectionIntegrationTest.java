package com.wooyongjeong.limitedsale.common;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wooyongjeong.limitedsale.LimitedSaleApplication;
import com.wooyongjeong.limitedsale.order.infrastructure.messaging.OrderAcceptanceTopicConfiguration;
import com.wooyongjeong.limitedsale.support.FixedPortKafka;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

/**
 * Runs the app with the real application.yml against a real KRaft broker, so the producer, consumer and admin settings
 * asserted here are the ones that ship. The broker is stopped and started with Docker to see what the app does.
 */
class KafkaConnectionIntegrationTest {
    private static final String TOPIC = OrderAcceptanceTopicConfiguration.ORDER_ACCEPTANCES_TOPIC;
    private static final String TOPIC_FOR_PROBES = "test.probe." + UUID.randomUUID();
    private static final MySQLContainer DB = new MySQLContainer("mysql:8.4.7");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final KafkaContainer KAFKA = FixedPortKafka.create(FixedPortKafka.freePort());
    private static ConfigurableApplicationContext context;
    private static int managementPort;
    private static KafkaAdmin admin;
    private static KafkaTemplate<String, String> template;

    @BeforeAll
    static void start() {
        DB.start();
        REDIS.start();
        KAFKA.start();
        // mock-pg's application.yml shadows the sales one on this classpath, so load the real one explicitly.
        context = new SpringApplicationBuilder(LimitedSaleApplication.class).run("--server.port=0",
                "--spring.config.additional-location=file:src/main/resources/application.yml",
                "--limited-sale.recovery.enabled=false",
                "--limited-sale.kafka.topic-provisioning.enabled=true",
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "--spring.flyway.locations=filesystem:src/main/resources/db/migration,classpath:db/nickmigration",
                "--spring.datasource.url=" + DB.getJdbcUrl(), "--spring.datasource.username=" + DB.getUsername(),
                "--spring.datasource.password=" + DB.getPassword(),
                "--spring.data.redis.host=" + REDIS.getHost(), "--spring.data.redis.port=" + REDIS.getMappedPort(6379));
        managementPort = Integer.parseInt(context.getEnvironment().getProperty("local.management.port"));
        admin = context.getBean(KafkaAdmin.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> bean = context.getBean(KafkaTemplate.class);
        template = bean;
        // The broker does not create topics on demand, so the probe topic is created here.
        createTopic(TOPIC_FOR_PROBES, 1);
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
        KAFKA.stop();
        REDIS.stop();
        DB.stop();
    }

    private static String management(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + path))
                .timeout(Duration.ofSeconds(30)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static void awaitHealthUp() {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(management("/actuator/health")).contains("\"status\":\"UP\""));
    }

    private static void awaitPublishWorks() {
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions().untilAsserted(() ->
                assertThat(template.send(TOPIC_FOR_PROBES, "probe", "probe").get(10, TimeUnit.SECONDS)).isNotNull());
    }

    private static void createTopic(String name, int partitions) {
        admin.createOrModifyTopics(new NewTopic(name, partitions, (short) 1));
    }

    @Test
    void operationalTopicIsCreatedWithSixPartitionsAndReplicationOne() {
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() -> {
            TopicDescription description = admin.describeTopics(TOPIC).get(TOPIC);
            assertThat(description.partitions()).hasSize(6);
            assertThat(description.partitions()).allSatisfy(partition -> assertThat(partition.replicas()).hasSize(1));
        });
    }

    @Test
    void keyedMessagesAreConsumedInOrderFromOnePartitionPerKey() throws Exception {
        // The shipped listener settings, not test settings: manual acknowledgement and three consumers per instance.
        @SuppressWarnings("unchecked")
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                context.getBean(ConcurrentKafkaListenerContainerFactory.class);
        assertThat(factory.getConsumerFactory().getConfigurationProperties())
                .containsEntry("group.id", "limited-sale-order-consumer")
                .containsEntry("enable.auto.commit", false)
                .containsEntry("auto.offset.reset", "earliest");
        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL);

        String topic = "test.keyed." + UUID.randomUUID();
        createTopic(topic, 6);
        List<String> keys = List.of("sale-1", "sale-2", "sale-3", "sale-4", "sale-5");
        int perKey = 20;
        // Records are recorded as they arrive, per key, with the partition they came from.
        Map<String, List<String>> values = new HashMap<>();
        Map<String, List<Integer>> partitions = new HashMap<>();
        var container = factory.createContainer(topic);
        container.getContainerProperties().setGroupId("test-" + UUID.randomUUID());
        container.getContainerProperties().setMessageListener((AcknowledgingMessageListener<String, String>)
                (record, acknowledgment) -> {
                    synchronized (values) {
                        values.computeIfAbsent(record.key(), key -> new ArrayList<>()).add(record.value());
                        partitions.computeIfAbsent(record.key(), key -> new ArrayList<>()).add(record.partition());
                    }
                    acknowledgment.acknowledge();
                });
        container.start();
        try {
            assertThat(((ConcurrentMessageListenerContainer<?, ?>) container).getConcurrency()).isEqualTo(3);
            for (int sequence = 0; sequence < perKey; sequence++) {
                for (String key : keys) {
                    template.send(topic, key, String.format("%03d", sequence)).get(30, TimeUnit.SECONDS);
                }
            }
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                synchronized (values) {
                    assertThat(values.values().stream().mapToInt(List::size).sum()).isEqualTo(keys.size() * perKey);
                }
            });
        } finally {
            container.stop();
        }
        List<String> expected = new ArrayList<>();
        for (int sequence = 0; sequence < perKey; sequence++) {
            expected.add(String.format("%03d", sequence));
        }
        for (String key : keys) {
            assertThat(values.get(key)).as("order of %s", key).containsExactlyElementsOf(expected);
            assertThat(partitions.get(key)).as("partitions of %s", key).containsOnly(partitions.get(key).get(0));
        }
    }

    @Test
    void producerSettingsAreTheSafeOnesThatShip() {
        var configuration = template.getProducerFactory().getConfigurationProperties();
        assertThat(configuration).containsEntry("acks", "all").containsEntry("enable.idempotence", "true");
        assertThat(Integer.parseInt(configuration.get("max.in.flight.requests.per.connection").toString()))
                .isLessThanOrEqualTo(5);
        assertThat(Integer.parseInt(configuration.get("max.block.ms").toString())).isEqualTo(5000);
        assertThat(Integer.parseInt(configuration.get("delivery.timeout.ms").toString())).isEqualTo(5000);
    }

    @Test
    void kafkaIsNotPartOfHealth() throws Exception {
        assertThat(context.getBeansOfType(HealthIndicator.class).keySet())
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("kafka"));
        assertThat(management("/actuator/health")).contains("\"status\":\"UP\"");
    }

    @Test
    void clientMetricsAreExposedToPrometheus() throws Exception {
        template.send(TOPIC_FOR_PROBES, "metrics", "metrics").get(30, TimeUnit.SECONDS);
        assertThat(management("/actuator/prometheus")).contains("kafka_producer_record_send_total");
    }

    @Test
    void stoppedBrokerFailsPublishingWithinAboutFiveSecondsAndHealthStaysUp() throws Exception {
        awaitPublishWorks();
        var logs = new ListAppender<ILoggingEvent>();
        Logger producerLog = (Logger) LoggerFactory.getLogger("org.springframework.kafka.support.LoggingProducerListener");
        logs.start();
        producerLog.addAppender(logs);
        var docker = DockerClientFactory.instance().client();
        docker.stopContainerCmd(KAFKA.getContainerId()).exec();
        try {
            long begin = System.nanoTime();
            Throwable failure = catchThrowable(() -> template.send(TOPIC_FOR_PROBES, "down", "down")
                    .get(30, TimeUnit.SECONDS));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - begin);

            // The 60 s default of max.block.ms would hold this thread for a minute; the shipped 5 s bound must not.
            assertThat(elapsed).isLessThan(Duration.ofSeconds(9));
            assertThat(failure).hasRootCauseInstanceOf(TimeoutException.class);
            // The failure is visible in the log and in the producer's own error counter.
            assertThat(logs.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("Exception thrown when sending a message");
            });
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(management("/actuator/prometheus")
                    .lines().filter(line -> line.startsWith("kafka_producer_record_error_total"))
                    .anyMatch(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)) > 0)).isTrue());
            // Neither the stopped broker nor the failed sends change the health status.
            assertThat(management("/actuator/health")).contains("\"status\":\"UP\"");
        } finally {
            producerLog.detachAppender(logs);
            docker.startContainerCmd(KAFKA.getContainerId()).exec();
            awaitPublishWorks();
        }
    }

    @Test
    void publishingWorksAgainAfterBrokerRestartWithoutRestartingTheApp() throws Exception {
        awaitPublishWorks();
        ConfigurableApplicationContext before = context;
        var docker = DockerClientFactory.instance().client();
        docker.stopContainerCmd(KAFKA.getContainerId()).exec();
        try {
            assertThat(catchThrowable(() -> template.send(TOPIC_FOR_PROBES, "down", "down")
                    .get(30, TimeUnit.SECONDS))).isNotNull();
        } finally {
            docker.startContainerCmd(KAFKA.getContainerId()).exec();
        }

        // The producer reconnects to the same advertised address; the app is not restarted or rebuilt.
        awaitPublishWorks();
        var sent = template.send(TOPIC_FOR_PROBES, "after-restart", "after-restart").get(10, TimeUnit.SECONDS);
        assertThat(sent.getRecordMetadata().hasOffset()).isTrue();
        assertThat(context).isSameAs(before);
        assertThat(((ServletWebServerApplicationContext) context).getWebServer()).isNotNull();
        awaitHealthUp();
    }
}
