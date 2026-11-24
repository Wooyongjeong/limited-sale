package com.wooyongjeong.limitedsale.order;

import com.wooyongjeong.limitedsale.LimitedSaleApplication;
import com.wooyongjeong.limitedsale.member.application.MemberService;
import com.wooyongjeong.limitedsale.order.domain.OutboxStore;
import com.wooyongjeong.limitedsale.product.api.ProductCommand;
import com.wooyongjeong.limitedsale.support.FixedPortKafka;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The outbox publisher against real MySQL and a real KRaft broker. One application (publisher off) takes the HTTP
 * acceptances; each test starts the publishing application or applications it needs on the same database, so a test
 * decides exactly when and where publishing happens. Every application loads the shipped application.yml.
 */
class OrderOutboxPublisherIntegrationTest {
    private static final String TOPIC = "limited-sale.order-acceptances.v1";
    private static final String LOCK = "limited-sale:order-outbox-publisher";
    private static final MySQLContainer DB = new MySQLContainer("mysql:8.4.7")
        .withDatabaseName("sales").withUsername("sales_user").withPassword("test_password")
        .withCommand("--log-bin-trust-function-creators=1");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final KafkaContainer KAFKA = FixedPortKafka.create(FixedPortKafka.freePort());
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger MEMBERS = new AtomicInteger();
    /** Counts calls of the injected mark failure in {@link FailFirstMark}. */
    private static final AtomicInteger MARK_FAILURES = new AtomicInteger();
    private static ConfigurableApplicationContext api;
    private static JdbcTemplate jdbc;
    private static Client client;
    private static long product;
    private final List<ConfigurableApplicationContext> started = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        DB.start();
        REDIS.start();
        KAFKA.start();
        api = application(false);
        jdbc = api.getBean(JdbcTemplate.class);
        product = api.getBean(ProductCommand.class).register("outbox product", "", 1000L).productId();
        api.getBean(MemberService.class).register("outbox@example.com", "password", "outbox");
        client = new Client(port(api)).login("outbox@example.com");
    }

    @BeforeEach
    void reset() {
        MARK_FAILURES.set(0);
        for (String table : List.of("order_outbox", "order_acceptances", "sales")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @AfterEach
    void closeApplicationsAndRestoreTheBroker() {
        for (int index = started.size() - 1; index >= 0; index--) {
            started.get(index).close();
        }
        started.clear();
        var docker = DockerClientFactory.instance().client();
        if (!Boolean.TRUE.equals(docker.inspectContainerCmd(KAFKA.getContainerId()).exec().getState().getRunning())) {
            docker.startContainerCmd(KAFKA.getContainerId()).exec();
        }
    }

    @AfterAll
    static void stop() {
        if (api != null) {
            api.close();
        }
        KAFKA.stop();
        REDIS.stop();
        DB.stop();
    }

    private static ConfigurableApplicationContext application(boolean publisher, Class<?>... extraSources) {
        var sources = new ArrayList<Class<?>>(List.of(LimitedSaleApplication.class));
        sources.addAll(List.of(extraSources));
        return new SpringApplicationBuilder(sources.toArray(Class<?>[]::new)).run("--server.port=0",
            "--management.server.port=0", "--limited-sale.order.rate-limit.enabled=false",
            "--spring.config.additional-location=file:src/main/resources/application.yml",
            "--spring.profiles.active=test", "--limited-sale.recovery.enabled=false", "--spring.flyway.enabled=true",
            "--spring.jpa.hibernate.ddl-auto=validate", "--MOCK_PG_API_KEY=test-secret",
            "--spring.flyway.locations=filesystem:src/main/resources/db/migration,classpath:db/nickmigration",
            "--spring.datasource.url=" + DB.getJdbcUrl(), "--spring.datasource.username=" + DB.getUsername(),
            "--spring.datasource.password=" + DB.getPassword(), "--spring.data.redis.host=" + REDIS.getHost(),
            "--spring.data.redis.port=" + REDIS.getMappedPort(6379), "--spring.data.redis.timeout=1s",
            "--limited-sale.order.async.enabled=true", "--limited-sale.order.outbox-publisher.enabled=" + publisher,
            "--limited-sale.kafka.topic-provisioning.enabled=true",
            "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers());
    }

    private ConfigurableApplicationContext publisher(Class<?>... extraSources) {
        ConfigurableApplicationContext context = application(true, extraSources);
        started.add(context);
        return context;
    }

    private static int port(ConfigurableApplicationContext context) {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    private static double meter(ConfigurableApplicationContext context, String name, String result) {
        var registry = context.getBean(MeterRegistry.class);
        var search = registry.find(name);
        if (result != null) {
            search = search.tag("result", result);
        }
        var counter = search.counter();
        return counter == null ? 0 : counter.count();
    }

    private static double leader(ConfigurableApplicationContext context) {
        var gauge = context.getBean(MeterRegistry.class).find("order.outbox.publisher.leader").gauge();
        return gauge == null ? 0 : gauge.value();
    }

    private static String management(ConfigurableApplicationContext context, String path) throws Exception {
        int managementPort = Integer.parseInt(context.getEnvironment().getProperty("local.management.port"));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + path))
            .timeout(Duration.ofSeconds(10)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static long openSale() {
        jdbc.update("INSERT INTO sales(product_id,name,description,product_name_snapshot,unit_price,starts_at,ends_at,"
            + "total_quantity,per_member_limit,is_stopped,created_at,updated_at) VALUES (?, 'sale', '', 'product', "
            + "1000, UTC_TIMESTAMP(6) - INTERVAL 1 HOUR, UTC_TIMESTAMP(6) + INTERVAL 1 HOUR, 5, 5, FALSE, "
            + "UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", product);
        return jdbc.queryForObject("SELECT MAX(id) FROM sales", Long.class);
    }

    /** Accepts an order over HTTP on the application that does not publish, and returns the acceptance id. */
    private static String accept(long saleId) throws Exception {
        var response = client.accept(UUID.randomUUID().toString(), saleId);
        assertThat(response.statusCode()).isEqualTo(202);
        return JSON.readTree(response.body()).path("data").path("acceptanceId").asString();
    }

    private static String payload(String acceptanceId, long saleId) {
        return "{\"schemaVersion\":1,\"acceptanceId\":\"" + acceptanceId + "\",\"saleId\":" + saleId + "}";
    }

    private static int unpublished(long saleId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_outbox WHERE message_key = ? AND published_at IS NULL",
            Integer.class, Long.toString(saleId));
    }

    /** Everything on the topic with this key right now, in partition and offset order. */
    private static List<ConsumerRecord<String, String>> onTopic(long saleId) {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", KAFKA.getBootstrapServers());
        properties.put("key.deserializer", StringDeserializer.class.getName());
        properties.put("value.deserializer", StringDeserializer.class.getName());
        properties.put("enable.auto.commit", "false");
        properties.put("default.api.timeout.ms", "10000");
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(properties)) {
            List<TopicPartition> partitions = consumer.partitionsFor(TOPIC).stream()
                .map(info -> new TopicPartition(TOPIC, info.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            var end = consumer.endOffsets(partitions);
            while (partitions.stream().anyMatch(partition -> consumer.position(partition) < end.get(partition))) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.key().equals(Long.toString(saleId))) {
                        found.add(record);
                    }
                }
            }
        }
        return found;
    }

    private static void awaitTopic(long saleId, int count) {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).ignoreExceptions()
            .untilAsserted(() -> assertThat(onTopic(saleId)).hasSize(count));
    }

    private static final class Client {
        final int port;
        final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String token;

        Client(int port) {
            this.port = port;
        }

        HttpResponse<String> request(String method, String path, String body, String csrf, String key)
                throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(8));
            if (csrf != null) {
                builder.header("X-CSRF-TOKEN", csrf);
            }
            if (key != null) {
                builder.header("Idempotency-Key", key);
            }
            if (body != null) {
                builder.header("Content-Type", "application/json");
            }
            return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }

        Client login(String email) throws Exception {
            token = csrf();
            assertThat(request("POST", "/api/v1/sessions", "{\"email\":\"" + email
                + "\",\"password\":\"password\"}", token, null).statusCode()).isEqualTo(200);
            token = csrf();
            return this;
        }

        private String csrf() throws Exception {
            return JSON.readTree(request("GET", "/api/v1/csrf", null, null, null).body()).path("data").path("token")
                .asString();
        }

        HttpResponse<String> accept(String key, long saleId) throws Exception {
            return request("POST", "/api/v2/orders", "{\"saleId\":" + saleId + ",\"quantity\":1}", token, key);
        }
    }

    /** Makes the first attempt to mark rows published fail after the send succeeded, then lets everything through. */
    @TestConfiguration(proxyBeanMethods = false)
    static class FailFirstMark {
        @Bean
        static BeanPostProcessor failTheFirstMark() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof OutboxStore store)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(OutboxStore.class.getClassLoader(), new Class<?>[] {OutboxStore.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("markPublished") && MARK_FAILURES.getAndIncrement() == 0) {
                                throw new IllegalStateException("injected: the database went away while marking");
                            }
                            try {
                                return method.invoke(store, arguments);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
                }
            };
        }
    }

    @Test
    void anAcceptedOrderArrivesWithTheStoredKeyAndPayloadAndIsMarkedPublished() throws Exception {
        long saleId = openSale();
        var publishing = publisher();

        String acceptanceId = accept(saleId);

        awaitTopic(saleId, 1);
        var record = onTopic(saleId).getFirst();
        var stored = jdbc.queryForMap("SELECT topic, message_key, payload, attempts, "
            + "TIMESTAMPDIFF(SECOND, published_at, UTC_TIMESTAMP(6)) AS age FROM order_outbox");
        assertThat(record.topic()).isEqualTo(TOPIC);
        assertThat(record.key()).isEqualTo(Long.toString(saleId));
        assertThat(record.value()).isEqualTo(payload(acceptanceId, saleId)).isEqualTo(stored.get("payload"));
        assertThat(record.headers().toArray()).isEmpty();
        assertThat(stored).containsEntry("topic", TOPIC).containsEntry("message_key", Long.toString(saleId))
            .containsEntry("attempts", 0);
        assertThat(((Number) stored.get("age")).longValue()).isBetween(0L, 60L);
        assertThat(unpublished(saleId)).isZero();
        assertThat(meter(publishing, "order.outbox.publish", "success")).isEqualTo(1);
        assertThat(meter(publishing, "order.outbox.publish", "failure")).isZero();
        assertThat(leader(publishing)).isEqualTo(1);
    }

    @Test
    void severalAcceptancesOfOneSaleArriveInAcceptanceOrderOnOnePartition() throws Exception {
        long saleId = openSale();
        publisher();
        List<String> accepted = new ArrayList<>();
        for (int count = 0; count < 12; count++) {
            accepted.add(accept(saleId));
        }

        awaitTopic(saleId, 12);

        var records = onTopic(saleId);
        assertThat(records.stream().map(ConsumerRecord::value).toList())
            .containsExactlyElementsOf(accepted.stream().map(id -> payload(id, saleId)).toList());
        assertThat(records.stream().map(ConsumerRecord::partition).distinct()).hasSize(1);
        assertThat(unpublished(saleId)).isZero();
    }

    @Test
    void aRowSavedWhilePublishingWasOffIsSentByANewApplicationThatTurnsItOn() throws Exception {
        long saleId = openSale();
        String acceptanceId = accept(saleId);

        // The application that took the request does not publish, and nothing else is running.
        Thread.sleep(3000);
        assertThat(unpublished(saleId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempts FROM order_outbox", Integer.class)).isZero();

        publisher();

        awaitTopic(saleId, 1);
        assertThat(onTopic(saleId).getFirst().value()).isEqualTo(payload(acceptanceId, saleId));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(unpublished(saleId)).isZero());
    }

    @Test
    void ifMarkingFailsAfterASuccessfulSendTheRowIsSentAgainAndExactlyOneDuplicateAppears() throws Exception {
        long saleId = openSale();
        var publishing = publisher(FailFirstMark.class);

        String acceptanceId = accept(saleId);

        // The send is acknowledged, the mark fails, and the row goes out again after the one second back-off.
        awaitTopic(saleId, 2);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(unpublished(saleId)).isZero());
        Thread.sleep(4000);
        var records = onTopic(saleId);
        assertThat(records).hasSize(2);
        assertThat(records).allSatisfy(record -> assertThat(record.value()).isEqualTo(payload(acceptanceId, saleId)));
        assertThat(MARK_FAILURES.get()).isGreaterThanOrEqualTo(2);
        assertThat(meter(publishing, "order.outbox.publish", "success")).isEqualTo(1);
    }

    @Test
    void whileTheBrokerIsDownRowsStayUnpublishedAttemptsGrowHealthStaysUpAndTheyAreSentAfterItReturns()
            throws Exception {
        long saleId = openSale();
        var publishing = publisher();
        accept(saleId);
        awaitTopic(saleId, 1);
        var docker = DockerClientFactory.instance().client();
        docker.stopContainerCmd(KAFKA.getContainerId()).exec();

        // Acceptance needs no broker, so this still answers 202.
        String waiting = accept(saleId);

        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> assertThat(jdbc.queryForObject(
            "SELECT attempts FROM order_outbox WHERE payload = ?", Integer.class, payload(waiting, saleId)))
            .isGreaterThanOrEqualTo(2));
        assertThat(unpublished(saleId)).isEqualTo(1);
        assertThat(meter(publishing, "order.outbox.publish", "failure")).isGreaterThanOrEqualTo(2);
        assertThat(management(publishing, "/actuator/health")).contains("\"status\":\"UP\"");
        assertThat(leader(publishing)).isEqualTo(1);

        docker.startContainerCmd(KAFKA.getContainerId()).exec();

        // The same application, never restarted, delivers it once the broker answers.
        await().atMost(Duration.ofSeconds(120)).untilAsserted(() -> assertThat(unpublished(saleId)).isZero());
        awaitTopic(saleId, 2);
        assertThat(onTopic(saleId).getLast().value()).isEqualTo(payload(waiting, saleId));
        assertThat(started).containsExactly(publishing);
        assertThat(management(publishing, "/actuator/health")).contains("\"status\":\"UP\"");
    }

    @Test
    void twoApplicationsOnOneDatabaseHaveOneLeaderSendEachRowOnceAndTheOtherTakesOverWhenTheLeaderCloses()
            throws Exception {
        long saleId = openSale();
        var first = publisher();
        var second = publisher();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(leader(first) + leader(second)).isEqualTo(1));
        Thread.sleep(3000);
        assertThat(leader(first) + leader(second)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT IS_FREE_LOCK(?)", Integer.class, LOCK)).isZero();

        List<String> accepted = new ArrayList<>();
        for (int count = 0; count < 15; count++) {
            accepted.add(accept(saleId));
        }
        awaitTopic(saleId, 15);
        Thread.sleep(3000);
        assertThat(onTopic(saleId).stream().map(ConsumerRecord::value).toList())
            .containsExactlyElementsOf(accepted.stream().map(id -> payload(id, saleId)).toList());
        assertThat(meter(first, "order.outbox.publish", "success") + meter(second, "order.outbox.publish", "success"))
            .isEqualTo(15);

        var leaving = leader(first) == 1 ? first : second;
        var staying = leaving == first ? second : first;
        leaving.close();
        started.remove(leaving);
        // These arrive while the leader is gone; only the one that takes over can send them.
        for (int count = 0; count < 5; count++) {
            accepted.add(accept(saleId));
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(leader(staying)).isEqualTo(1));
        awaitTopic(saleId, 20);
        assertThat(onTopic(saleId).stream().map(ConsumerRecord::value).toList())
            .containsExactlyElementsOf(accepted.stream().map(id -> payload(id, saleId)).toList());
        assertThat(unpublished(saleId)).isZero();

        staying.close();
        started.remove(staying);
        assertThat(jdbc.queryForObject("SELECT IS_FREE_LOCK(?)", Integer.class, LOCK)).isEqualTo(1);
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(thread -> thread.getName()
            .equals("order-outbox-publisher") && thread.isAlive());
    }

    @Test
    void aLeaderWhoseLockConnectionIsKilledStopsPublishingAndAsksForTheLockAgain() throws Exception {
        long saleId = openSale();
        var publishing = publisher();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(leader(publishing)).isEqualTo(1));
        long connection = jdbc.queryForObject("SELECT IS_USED_LOCK(?)", Long.class, LOCK);

        jdbc.execute("KILL " + connection);

        // The lock went with the connection. The check before the next batch notices, and a new connection takes it.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Long holder = jdbc.queryForObject("SELECT IS_USED_LOCK(?)", Long.class, LOCK);
            assertThat(holder).isNotNull().isNotEqualTo(connection);
            assertThat(leader(publishing)).isEqualTo(1);
        });
        String acceptanceId = accept(saleId);
        awaitTopic(saleId, 1);
        assertThat(onTopic(saleId).getFirst().value()).isEqualTo(payload(acceptanceId, saleId));
    }
}
