package com.wooyongjeong.limitedsale.order;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wooyongjeong.limitedsale.LimitedSaleApplication;
import com.wooyongjeong.limitedsale.member.application.MemberService;
import com.wooyongjeong.limitedsale.payment.api.PaymentPreparation;
import com.wooyongjeong.limitedsale.payment.infrastructure.persistence.JpaPaymentPreparation;
import com.wooyongjeong.limitedsale.product.api.ProductCommand;
import com.wooyongjeong.limitedsale.support.FixedPortKafka;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Retry, failure and stall recovery of the v2 path (3-6) against real MySQL and a real broker. One application accepts,
 * publishes, checks for stalls and consumes, with the long limits shortened: a 6 s retry budget, a 1 s interval while a
 * failure cannot be stored, stalls after 3 s once passed (20 s regardless), a stall check every second. MySQL answers a
 * lock wait after 2 s, so a row another connection holds is the transient error. Every test ends with the
 * reconciliation SQL.
 */
class OrderAcceptanceRecoveryIntegrationTest {
    private static final String TOPIC = "limited-sale.order-acceptances.v1";
    private static final MySQLContainer SALES = new MySQLContainer("mysql:8.4.7")
        .withCommand("--log-bin-trust-function-creators=1", "--innodb-lock-wait-timeout=2");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final KafkaContainer KAFKA = FixedPortKafka.create(FixedPortKafka.freePort());
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger MEMBERS = new AtomicInteger();
    /** While set, preparing the payment throws an error that no retry would change. */
    private static final AtomicBoolean FAIL_PAYMENT = new AtomicBoolean();
    private static final ListAppender<ILoggingEvent> HANDLER_LOG = new ListAppender<>();
    private static ConfigurableApplicationContext app;
    private static JdbcTemplate jdbc;
    private static KafkaProducer<String, String> producer;
    private static long product;

    @BeforeAll
    static void start() {
        SALES.start();
        REDIS.start();
        KAFKA.start();
        app = new SpringApplicationBuilder(LimitedSaleApplication.class, FailingPayment.class).run("--server.port=0",
            "--management.server.port=0", "--limited-sale.order.rate-limit.enabled=false",
            "--spring.config.additional-location=file:src/main/resources/application.yml",
            "--spring.profiles.active=test", "--limited-sale.recovery.enabled=false", "--spring.flyway.enabled=true",
            "--spring.jpa.hibernate.ddl-auto=validate", "--MOCK_PG_API_KEY=test-secret",
            "--spring.flyway.locations=filesystem:src/main/resources/db/migration,classpath:db/nickmigration",
            "--spring.datasource.url=" + SALES.getJdbcUrl(), "--spring.datasource.username=" + SALES.getUsername(),
            "--spring.datasource.password=" + SALES.getPassword(), "--spring.data.redis.host=" + REDIS.getHost(),
            "--spring.data.redis.port=" + REDIS.getMappedPort(6379), "--spring.data.redis.timeout=1s",
            "--limited-sale.order.async.enabled=true", "--limited-sale.order.outbox-publisher.enabled=true",
            "--limited-sale.order.consumer.enabled=true", "--limited-sale.kafka.topic-provisioning.enabled=true",
            "--limited-sale.order.consumer.retry-budget=PT6S",
            "--limited-sale.order.consumer.outage-retry-interval=PT1S",
            "--limited-sale.order.stall.after-publish=PT3S", "--limited-sale.order.stall.deadline=PT20S",
            "--limited-sale.order.stall.scan-interval=PT1S",
            "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers());
        jdbc = app.getBean(JdbcTemplate.class);
        // A rebalance starts a message's retries over on another consumer, so let the three consumers settle first.
        var container = app.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("order-acceptance-consumer");
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
            assertThat(container.getAssignedPartitions()).hasSize(6));
        Logger handlerLog = (Logger) LoggerFactory.getLogger(
            "com.wooyongjeong.limitedsale.order.infrastructure.messaging.OrderAcceptanceConsumerConfiguration");
        HANDLER_LOG.start();
        handlerLog.addAppender(HANDLER_LOG);
        product = app.getBean(ProductCommand.class).register("recovery product", "", 1000L).productId();
        Properties properties = new Properties();
        properties.put("bootstrap.servers", KAFKA.getBootstrapServers());
        properties.put("key.serializer", StringSerializer.class.getName());
        properties.put("value.serializer", StringSerializer.class.getName());
        producer = new KafkaProducer<>(properties);
    }

    @AfterEach
    void reconcile() throws Exception {
        FAIL_PAYMENT.set(false);
        String sql = Files.readString(Path.of("tools/reconciliation/reconcile.sql")).trim();
        assertThat(jdbc.queryForList(sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql)).isEmpty();
    }

    @AfterAll
    static void stop() {
        if (producer != null) {
            producer.close();
        }
        if (app != null) {
            app.close();
        }
        KAFKA.stop();
        REDIS.stop();
        SALES.stop();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingPayment {
        @Bean
        @Primary
        PaymentPreparation failingPaymentPreparation(JpaPaymentPreparation real) {
            return (orderId, amount, time) -> {
                if (FAIL_PAYMENT.get()) {
                    throw new IllegalStateException("injected: a defect no retry can fix");
                }
                real.prepare(orderId, amount, time);
            };
        }
    }

    private static double meter(String name, String tag, String value) {
        var search = app.getBean(MeterRegistry.class).find(name);
        if (tag != null) {
            search = search.tag(tag, value);
        }
        var counter = search.counter();
        return counter == null ? 0 : counter.count();
    }

    private static double failed(String reason) {
        return meter("order.acceptance.failed", "reason", reason);
    }

    private static long openSale() {
        jdbc.update("INSERT INTO sales(product_id,name,description,product_name_snapshot,unit_price,starts_at,ends_at,"
            + "total_quantity,per_member_limit,is_stopped,created_at,updated_at) VALUES (?, 'sale', '', 'product', "
            + "1000, UTC_TIMESTAMP(6) - INTERVAL 1 HOUR, UTC_TIMESTAMP(6) + INTERVAL 1 HOUR, 5, 5, FALSE, "
            + "UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", product);
        return jdbc.queryForObject("SELECT MAX(id) FROM sales", Long.class);
    }

    private static int partition(long saleId) {
        return Utils.toPositive(Utils.murmur2(Long.toString(saleId).getBytes(StandardCharsets.UTF_8))) % 6;
    }

    private static Client member() throws Exception {
        String email = "recovery" + MEMBERS.incrementAndGet() + "@example.com";
        app.getBean(MemberService.class).register(email, "password", "recovery" + MEMBERS.get());
        return new Client(((ServletWebServerApplicationContext) app).getWebServer().getPort(), email).login();
    }

    private static Map<String, Object> acceptance(String acceptanceId) {
        return jdbc.queryForMap("SELECT id, status, rejection_reason, failure_reason, order_id, finished_at "
            + "FROM order_acceptances WHERE public_id = UNHEX(REPLACE(?, '-', ''))", acceptanceId);
    }

    private static Map<String, Object> awaitFinished(String acceptanceId, Duration within) {
        await().atMost(within).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
            assertThat(acceptance(acceptanceId).get("status")).isNotEqualTo("ACCEPTED"));
        return acceptance(acceptanceId);
    }

    private static int ordersFor(String acceptanceId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE request_key = UNHEX(REPLACE(?, '-', ''))",
            Integer.class, acceptanceId);
    }

    private static int outboxRows(String acceptanceId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_outbox o JOIN order_acceptances a ON a.id = o.acceptance_id "
            + "WHERE a.public_id = UNHEX(REPLACE(?, '-', ''))", Integer.class, acceptanceId);
    }

    private static int reserved(long saleId) {
        return jdbc.queryForObject("SELECT reserved_quantity FROM sales WHERE id = ?", Integer.class, saleId);
    }

    /** An ACCEPTED acceptance stored directly, with no outbox row: the test decides what is sent for it. */
    private static String storedAcceptance(Client client, long saleId) {
        String acceptanceId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO order_acceptances(public_id, member_id, request_key, sale_id, quantity, status, "
            + "accepted_at) VALUES (UNHEX(REPLACE(?, '-', '')), (SELECT id FROM members WHERE email = ?), "
            + "UNHEX(REPLACE(?, '-', '')), ?, 1, 'ACCEPTED', UTC_TIMESTAMP(6))",
            acceptanceId, client.email, UUID.randomUUID().toString(), saleId);
        return acceptanceId;
    }

    /** An outbox row for the acceptance as if it had been published {@code secondsAgo} at that partition and offset. */
    private static void publishedRow(String acceptanceId, long saleId, int partition, long offset, int secondsAgo) {
        jdbc.update("INSERT INTO order_outbox(topic, message_key, payload, created_at, published_at, attempts, "
            + "acceptance_id, partition_no, record_offset) VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) - "
            + "INTERVAL ? SECOND, 0, ?, ?, ?)", TOPIC, Long.toString(saleId), payload(acceptanceId, saleId),
            secondsAgo, acceptance(acceptanceId).get("id"), partition, offset);
    }

    private static void send(long saleId, String value) throws Exception {
        producer.send(new ProducerRecord<>(TOPIC, Long.toString(saleId), value)).get(10, TimeUnit.SECONDS);
    }

    private static String payload(String acceptanceId, long saleId) {
        return "{\"schemaVersion\":1,\"acceptanceId\":\"" + acceptanceId + "\",\"saleId\":" + saleId + "}";
    }

    /** A connection that holds row locks until it is closed. */
    private static Connection holding(String sql, Object parameter) throws Exception {
        Connection holder = DriverManager.getConnection(SALES.getJdbcUrl(), SALES.getUsername(), SALES.getPassword());
        holder.setAutoCommit(false);
        try (var statement = holder.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            statement.executeQuery().close();
        }
        return holder;
    }

    private static final class Client {
        final int port;
        final String email;
        final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String token;

        Client(int port, String email) {
            this.port = port;
            this.email = email;
        }

        HttpResponse<String> request(String method, String path, String body, String key) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15));
            if (token != null) {
                builder.header("X-CSRF-TOKEN", token);
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

        Client login() throws Exception {
            token = csrf();
            assertThat(request("POST", "/api/v1/sessions", "{\"email\":\"" + email
                + "\",\"password\":\"password\"}", null).statusCode()).isEqualTo(200);
            token = csrf();
            return this;
        }

        private String csrf() throws Exception {
            String saved = token;
            token = null;
            String fresh = JSON.readTree(request("GET", "/api/v1/csrf", null, null).body()).path("data")
                .path("token").asString();
            token = saved;
            return fresh;
        }

        String accept(long saleId) throws Exception {
            var response = request("POST", "/api/v2/orders", "{\"saleId\":" + saleId + ",\"quantity\":1}",
                UUID.randomUUID().toString());
            assertThat(response.statusCode()).isEqualTo(202);
            return JSON.readTree(response.body()).path("data").path("acceptanceId").asString();
        }

        String failureReason(String acceptanceId) throws Exception {
            var response = request("GET", "/api/v2/order-requests/" + acceptanceId, null, null);
            assertThat(response.statusCode()).isEqualTo(200);
            return JSON.readTree(response.body()).path("data").path("failureReason").asString();
        }
    }

    @Test
    void aTransientErrorThatClearsWithinTheBudgetIsProcessedNormally() throws Exception {
        long saleId = openSale();
        Client client = member();
        double retries = meter("order.acceptance.retries", null, null);
        String acceptanceId;
        try (Connection holder = holding("SELECT id FROM sales WHERE id = ? FOR UPDATE", saleId)) {
            acceptanceId = client.accept(saleId);
            // One 2 s lock wait times out; the retry after it finds the row free.
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(meter("order.acceptance.retries", null, null)).isGreaterThan(retries));
            holder.rollback();
        }

        assertThat(awaitFinished(acceptanceId, Duration.ofSeconds(20)).get("status")).isEqualTo("ORDER_CREATED");
        assertThat(ordersFor(acceptanceId)).isEqualTo(1);
    }

    @Test
    void aTransientErrorPastTheBudgetFailsTheAcceptanceAndTheNextMessageOfThePartitionGoesOn() throws Exception {
        long blocked = openSale();
        long next = openSale();
        while (partition(next) != partition(blocked)) {
            next = openSale();
        }
        Client client = member();
        double exhausted = failed("TRANSIENT_RETRIES_EXHAUSTED");
        String stuck;
        String behind;
        try (Connection holder = holding("SELECT id FROM sales WHERE id = ? FOR UPDATE", blocked)) {
            stuck = client.accept(blocked);
            behind = client.accept(next);

            var failure = awaitFinished(stuck, Duration.ofSeconds(30));
            assertThat(failure.get("status")).isEqualTo("PROCESSING_FAILED");
            assertThat(failure.get("failure_reason")).isEqualTo("TRANSIENT_RETRIES_EXHAUSTED");
            // The lock is still held; the message behind it in the same partition is not held up any longer.
            var served = awaitFinished(behind, Duration.ofSeconds(15));
            assertThat(served.get("status")).isEqualTo("ORDER_CREATED");
            assertThat(holder.isValid(1)).isTrue();
            assertThat(((java.time.LocalDateTime) served.get("finished_at")))
                .isAfterOrEqualTo((java.time.LocalDateTime) failure.get("finished_at"));
            holder.rollback();
        }
        assertThat(client.failureReason(stuck)).isEqualTo("TRANSIENT_RETRIES_EXHAUSTED");
        assertThat(ordersFor(stuck)).isZero();
        assertThat(reserved(blocked)).isZero();
        assertThat(failed("TRANSIENT_RETRIES_EXHAUSTED")).isEqualTo(exhausted + 1);
    }

    @Test
    void anErrorNoRetryWouldChangeFailsAtOnceAndLeavesNoStockOrOrder() throws Exception {
        long saleId = openSale();
        Client client = member();
        double retries = meter("order.acceptance.retries", null, null);
        FAIL_PAYMENT.set(true);

        long started = System.nanoTime();
        String acceptanceId = client.accept(saleId);
        var failure = awaitFinished(acceptanceId, Duration.ofSeconds(20));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(failure.get("status")).isEqualTo("PROCESSING_FAILED");
        assertThat(failure.get("failure_reason")).isEqualTo("PROCESSING_ERROR");
        assertThat(meter("order.acceptance.retries", null, null)).isEqualTo(retries + 1);
        assertThat(ordersFor(acceptanceId)).isZero();
        assertThat(reserved(saleId)).isZero();
    }

    @Test
    void whenEvenTheFailureCannotBeStoredTheMessageWaitsAndIsProcessedOnceTheDatabaseAnswers() throws Exception {
        long saleId = openSale();
        Client client = member();
        String acceptanceId = storedAcceptance(client, saleId);
        double failures = meter("order.acceptance.failed", null, null);
        try (Connection holder = holding("SELECT id FROM order_acceptances WHERE public_id = UNHEX(REPLACE(?, '-', '')) "
                + "FOR UPDATE", acceptanceId)) {
            // Processing cannot lock the acceptance; after the 6 s budget, storing the failure cannot lock it either.
            send(saleId, payload(acceptanceId, saleId));
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(HANDLER_LOG.list)
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("Could not store the failure of order acceptance " + acceptanceId)));
            // From now on it is retried every second, without spending a new budget or failing it.
            Thread.sleep(4_000);
            assertThat(acceptance(acceptanceId).get("status")).isEqualTo("ACCEPTED");
            holder.rollback();
        }

        assertThat(awaitFinished(acceptanceId, Duration.ofSeconds(20)).get("status")).isEqualTo("ORDER_CREATED");
        assertThat(meter("order.acceptance.failed", null, null)).isEqualTo(failures);
        assertThat(ordersFor(acceptanceId)).isEqualTo(1);
    }

    @Test
    void anAcceptanceTheConsumerHasPassedIsRepublishedAndBecomesOneOrder() throws Exception {
        long saleId = openSale();
        Client client = member();
        String earlier = client.accept(saleId);
        assertThat(awaitFinished(earlier, Duration.ofSeconds(20)).get("status")).isEqualTo("ORDER_CREATED");
        var position = jdbc.queryForMap("SELECT o.partition_no, o.record_offset FROM order_outbox o JOIN "
            + "order_acceptances a ON a.id = o.acceptance_id WHERE a.public_id = UNHEX(REPLACE(?, '-', ''))", earlier);
        assertThat(position.get("partition_no")).isEqualTo(partition(saleId));
        double republished = meter("order.acceptance.republished", null, null);

        // As if its message had been lost on the way: published where the consumer has already read past.
        String lost = storedAcceptance(client, saleId);
        publishedRow(lost, saleId, partition(saleId), ((Number) position.get("record_offset")).longValue(), 5);

        assertThat(awaitFinished(lost, Duration.ofSeconds(30)).get("status")).isEqualTo("ORDER_CREATED");
        assertThat(outboxRows(lost)).isEqualTo(2);
        assertThat(ordersFor(lost)).isEqualTo(1);
        assertThat(meter("order.acceptance.republished", null, null)).isEqualTo(republished + 1);
    }

    @Test
    void anAcceptanceStillWaitingForItsTurnIsLeftAloneUntilTheDeadline() throws Exception {
        long saleId = openSale();
        Client client = member();
        String waiting = storedAcceptance(client, saleId);
        // Published 10 s ago at an offset the consumer has not reached: past the 3 s stall time, not past the 20 s one.
        publishedRow(waiting, saleId, partition(saleId), 1_000_000_000L, 10);

        Thread.sleep(5_000);
        assertThat(outboxRows(waiting)).isEqualTo(1);
        assertThat(acceptance(waiting).get("status")).isEqualTo("ACCEPTED");

        // At 20 s it is sent again whatever the offsets say.
        assertThat(awaitFinished(waiting, Duration.ofSeconds(30)).get("status")).isEqualTo("ORDER_CREATED");
        assertThat(outboxRows(waiting)).isEqualTo(2);
    }

    @Test
    void anAcceptanceRepublishedThreeTimesWithoutResultEndsAsStalled() throws Exception {
        long saleId = openSale();
        long otherSale = openSale();
        Client client = member();
        String unprocessable = storedAcceptance(client, saleId);
        double republished = meter("order.acceptance.republished", null, null);
        // Its only message names another sale, so the consumer skips it every time it arrives.
        jdbc.update("INSERT INTO order_outbox(topic, message_key, payload, created_at, attempts, acceptance_id) "
            + "VALUES (?, ?, ?, UTC_TIMESTAMP(6), 0, ?)", TOPIC, Long.toString(otherSale),
            payload(unprocessable, otherSale), acceptance(unprocessable).get("id"));

        var failure = awaitFinished(unprocessable, Duration.ofSeconds(60));

        assertThat(failure.get("status")).isEqualTo("PROCESSING_FAILED");
        assertThat(failure.get("failure_reason")).isEqualTo("STALLED");
        assertThat(outboxRows(unprocessable)).isEqualTo(4);
        assertThat(meter("order.acceptance.republished", null, null)).isEqualTo(republished + 3);
        assertThat(client.failureReason(unprocessable)).isEqualTo("STALLED");
    }
}
