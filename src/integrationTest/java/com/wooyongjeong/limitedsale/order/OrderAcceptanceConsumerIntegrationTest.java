package com.wooyongjeong.limitedsale.order;

import com.wooyongjeong.limitedsale.LimitedSaleApplication;
import com.wooyongjeong.limitedsale.member.application.MemberService;
import com.wooyongjeong.limitedsale.order.application.AcceptanceProcessingMetrics;
import com.wooyongjeong.limitedsale.order.application.OrderAcceptanceProcessor.Outcome;
import com.wooyongjeong.limitedsale.order.presentation.code.OrderResponseCode;
import com.wooyongjeong.limitedsale.product.api.ProductCommand;
import com.wooyongjeong.limitedsale.support.FixedPortKafka;
import com.wooyongjeong.mockpg.MockPgApplication;
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
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static com.wooyongjeong.limitedsale.support.ResponseEnvelope.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The order consumer against real MySQL, a real KRaft broker and the Mock PG. One application accepts over HTTP,
 * publishes and consumes, so the whole v2 path runs as shipped; tests that need a message the outbox would never send
 * (a duplicate, a broken one) put it on the topic themselves. The sales database answers a lock wait after 2 s, so a
 * held sale row turns into the transient failure the consumer must retry. Every test ends with the reconciliation SQL.
 */
class OrderAcceptanceConsumerIntegrationTest {
    private static final String TOPIC = "limited-sale.order-acceptances.v1";
    private static final String LISTENER = "order-acceptance-consumer";
    private static final MySQLContainer SALES = new MySQLContainer("mysql:8.4.7")
        .withCommand("--log-bin-trust-function-creators=1", "--innodb-lock-wait-timeout=2");
    private static final MySQLContainer PG = new MySQLContainer("mysql:8.4.7")
        .withUrlParam("connectionTimeZone", "UTC").withUrlParam("forceConnectionTimeZoneToSession", "true");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final KafkaContainer KAFKA = FixedPortKafka.create(FixedPortKafka.freePort());
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger MEMBERS = new AtomicInteger();
    /** When set, the first ORDER_CREATED fails after its transaction committed and before the offset is acknowledged. */
    private static final AtomicBoolean FAIL_AFTER_COMMIT = new AtomicBoolean();
    private static ConfigurableApplicationContext pgContext;
    private static ConfigurableApplicationContext app;
    private static JdbcTemplate jdbc;
    private static KafkaProducer<String, String> producer;
    private static long product;
    private static int pgPort;
    private final List<ConfigurableApplicationContext> started = new ArrayList<>();

    @BeforeAll
    static void start() {
        SALES.start();
        PG.start();
        REDIS.start();
        KAFKA.start();
        pgContext = new SpringApplicationBuilder(MockPgApplication.class).run("--server.port=0",
            "--mock-pg.api-key=test-secret", "--spring.config.location=file:mock-pg/src/main/resources/application.yml",
            "--spring.flyway.locations=filesystem:mock-pg/src/main/resources/db/migration",
            "--spring.datasource.url=" + PG.getJdbcUrl(),
            "--spring.datasource.username=" + PG.getUsername(), "--spring.datasource.password=" + PG.getPassword(),
            "--spring.autoconfigure.exclude=org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration,"
                + "org.springframework.boot.session.autoconfigure.SessionAutoConfiguration,"
                + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration");
        pgPort = port(pgContext);
        app = application(true, FailAfterCommit.class);
        jdbc = app.getBean(JdbcTemplate.class);
        product = app.getBean(ProductCommand.class).register("consumer product", "", 1000L).productId();
        Properties properties = new Properties();
        properties.put("bootstrap.servers", KAFKA.getBootstrapServers());
        properties.put("key.serializer", StringSerializer.class.getName());
        properties.put("value.serializer", StringSerializer.class.getName());
        producer = new KafkaProducer<>(properties);
    }

    @AfterEach
    void closeExtraApplicationsAndReconcile() throws Exception {
        FAIL_AFTER_COMMIT.set(false);
        for (int index = started.size() - 1; index >= 0; index--) {
            started.get(index).close();
        }
        started.clear();
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
        if (pgContext != null) {
            pgContext.close();
        }
        KAFKA.stop();
        REDIS.stop();
        PG.stop();
        SALES.stop();
    }

    private static ConfigurableApplicationContext application(boolean publisher, Class<?>... extraSources) {
        var sources = new ArrayList<Class<?>>(List.of(LimitedSaleApplication.class));
        sources.addAll(List.of(extraSources));
        return new SpringApplicationBuilder(sources.toArray(Class<?>[]::new)).run("--server.port=0",
            "--management.server.port=0", "--limited-sale.order.rate-limit.enabled=false",
            "--spring.config.additional-location=file:src/main/resources/application.yml",
            "--spring.profiles.active=test", "--limited-sale.recovery.enabled=false", "--spring.flyway.enabled=true",
            "--spring.jpa.hibernate.ddl-auto=validate", "--MOCK_PG_API_KEY=test-secret",
            "--MOCK_PG_BASE_URL=http://localhost:" + pgPort,
            "--spring.flyway.locations=filesystem:src/main/resources/db/migration,classpath:db/nickmigration",
            "--spring.datasource.url=" + SALES.getJdbcUrl(), "--spring.datasource.username=" + SALES.getUsername(),
            "--spring.datasource.password=" + SALES.getPassword(), "--spring.data.redis.host=" + REDIS.getHost(),
            "--spring.data.redis.port=" + REDIS.getMappedPort(6379), "--spring.data.redis.timeout=1s",
            "--limited-sale.order.async.enabled=true", "--limited-sale.order.outbox-publisher.enabled=" + publisher,
            "--limited-sale.order.consumer.enabled=true", "--limited-sale.kafka.topic-provisioning.enabled=true",
            "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers());
    }

    private static int port(ConfigurableApplicationContext context) {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    private static double processed(ConfigurableApplicationContext context, Outcome outcome) {
        var counter = context.getBean(MeterRegistry.class).find("order.acceptance.processed")
            .tag("result", outcome.name().toLowerCase()).counter();
        return counter == null ? 0 : counter.count();
    }

    /** A sale whose period and stock are given as SQL; the product, price 1000 and timestamps are fixed. */
    private static long sale(String startsAt, String endsAt, int total, int perMember, boolean stopped) {
        jdbc.update("INSERT INTO sales(product_id,name,description,product_name_snapshot,unit_price,starts_at,ends_at,"
            + "total_quantity,per_member_limit,is_stopped,created_at,updated_at) VALUES (?, 'sale', '', 'product', "
            + "1000, " + startsAt + ", " + endsAt + ", ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
            product, total, perMember, stopped);
        return jdbc.queryForObject("SELECT MAX(id) FROM sales", Long.class);
    }

    private static long openSale(int total, int perMember) {
        return sale("UTC_TIMESTAMP(6) - INTERVAL 1 HOUR", "UTC_TIMESTAMP(6) + INTERVAL 1 HOUR", total, perMember, false);
    }

    private static Client member() throws Exception {
        String email = "consumer" + MEMBERS.incrementAndGet() + "@example.com";
        app.getBean(MemberService.class).register(email, "password", "consumer" + MEMBERS.get());
        return new Client(port(app), email).login();
    }

    private static Map<String, Object> acceptance(String acceptanceId) {
        return jdbc.queryForMap("SELECT status, rejection_reason, order_id, member_id, accepted_at, finished_at "
            + "FROM order_acceptances WHERE public_id = UNHEX(REPLACE(?, '-', ''))", acceptanceId);
    }

    private static Map<String, Object> awaitFinished(String acceptanceId) {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
            assertThat(acceptance(acceptanceId).get("status")).isNotEqualTo("ACCEPTED"));
        return acceptance(acceptanceId);
    }

    private static int ordersFor(String acceptanceId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE request_key = UNHEX(REPLACE(?, '-', ''))",
            Integer.class, acceptanceId);
    }

    /** Stores an ACCEPTED acceptance directly (no outbox row) at the given SQL time, for a message sent by the test. */
    private static String storedAcceptance(Client client, long saleId, String acceptedAt) {
        String acceptanceId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO order_acceptances(public_id, member_id, request_key, sale_id, quantity, status, "
            + "accepted_at) VALUES (UNHEX(REPLACE(?, '-', '')), (SELECT id FROM members WHERE email = ?), "
            + "UNHEX(REPLACE(?, '-', '')), ?, 1, 'ACCEPTED', " + acceptedAt + ")",
            acceptanceId, client.email, UUID.randomUUID().toString(), saleId);
        return acceptanceId;
    }

    private static void send(long saleId, String value) throws Exception {
        producer.send(new ProducerRecord<>(TOPIC, Long.toString(saleId), value)).get(10, TimeUnit.SECONDS);
    }

    /** The partition the producer's default partitioner picks for a sale's key. */
    private static int partition(long saleId) {
        return Utils.toPositive(Utils.murmur2(Long.toString(saleId).getBytes(StandardCharsets.UTF_8))) % 6;
    }

    private static String payload(String acceptanceId, long saleId) {
        return "{\"schemaVersion\":1,\"acceptanceId\":\"" + acceptanceId + "\",\"saleId\":" + saleId + "}";
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

        /** Accepts one unit over v2 and returns the acceptance id. */
        String accept(long saleId) throws Exception {
            var response = request("POST", "/api/v2/orders", "{\"saleId\":" + saleId + ",\"quantity\":1}",
                UUID.randomUUID().toString());
            assertThat(response.statusCode()).isEqualTo(202);
            return JSON.readTree(response.body()).path("data").path("acceptanceId").asString();
        }

        JsonNode status(String acceptanceId) throws Exception {
            var response = request("GET", "/api/v2/order-requests/" + acceptanceId, null, null);
            assertThat(response.statusCode()).isEqualTo(200);
            return JSON.readTree(response.body()).path("data");
        }
    }

    /** Replaces the processing metrics with ones that can fail once right after a committed ORDER_CREATED. */
    @TestConfiguration(proxyBeanMethods = false)
    static class FailAfterCommit {
        @Bean
        @Primary
        AcceptanceProcessingMetrics failingAfterCommit(MeterRegistry registry) {
            return new AcceptanceProcessingMetrics(registry) {
                @Override
                public void record(Outcome outcome) {
                    if (outcome == Outcome.ORDER_CREATED && FAIL_AFTER_COMMIT.compareAndSet(true, false)) {
                        // Transient, like a connection lost before the acknowledgement: the message comes again.
                        throw new TransientDataAccessResourceException(
                            "injected: the consumer stopped before acknowledging");
                    }
                    super.record(outcome);
                }
            };
        }
    }

    @Test
    void anAcceptedRequestBecomesAnOrderWithAReadyPaymentThatTheV1PaymentApiPays() throws Exception {
        long saleId = openSale(5, 2);
        Client client = member();

        String acceptanceId = client.accept(saleId);

        var finished = awaitFinished(acceptanceId);
        assertThat(finished.get("status")).isEqualTo("ORDER_CREATED");
        JsonNode status = client.status(acceptanceId);
        assertThat(status.path("status").asString()).isEqualTo("ORDER_CREATED");
        String orderId = status.path("orderId").asString();
        assertThat(orderId).isNotBlank();
        assertThat(status.path("finishedAt").isNull()).isFalse();
        var order = jdbc.queryForMap("SELECT o.id, o.status, p.status AS payment, "
            + "TIMESTAMPDIFF(MICROSECOND, o.created_at, o.expires_at) AS window_us FROM purchase_orders o "
            + "JOIN payments p ON p.order_id = o.id WHERE o.public_id = UNHEX(REPLACE(?, '-', ''))", orderId);
        assertThat(order.get("payment")).isEqualTo("READY");
        // Five minutes from the moment the consumer made the order, not from the acceptance.
        assertThat(((Number) order.get("window_us")).longValue()).isEqualTo(Duration.ofMinutes(5).toNanos() / 1000);
        assertThat(((Number) order.get("id")).longValue()).isEqualTo(((Number) finished.get("order_id")).longValue());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_request_results", Integer.class)).isZero();

        var paid = client.request("POST", "/api/v1/orders/" + orderId + "/payment", null, null);
        assertThat(paid.statusCode()).isEqualTo(200);
        assertCode(paid, OrderResponseCode.PAYMENT_SUCCEEDED);
        assertThat(jdbc.queryForObject("SELECT sold_quantity FROM sales WHERE id = ?", Integer.class, saleId))
            .isEqualTo(1);
    }

    @Test
    void theSameMessageDeliveredAgainMakesNoSecondOrder() throws Exception {
        long saleId = openSale(5, 5);
        Client client = member();
        String acceptanceId = client.accept(saleId);
        assertThat(awaitFinished(acceptanceId).get("status")).isEqualTo("ORDER_CREATED");
        double duplicates = processed(app, Outcome.DUPLICATE);

        send(saleId, payload(acceptanceId, saleId));
        send(saleId, payload(acceptanceId, saleId));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(processed(app, Outcome.DUPLICATE)).isEqualTo(duplicates + 2));
        assertThat(ordersFor(acceptanceId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reserved_quantity FROM sales WHERE id = ?", Integer.class, saleId))
            .isEqualTo(1);
    }

    @Test
    void aConsumerThatFailsAfterTheCommitAndBeforeTheAcknowledgementGetsTheMessageAgainAndMakesOneOrder()
            throws Exception {
        long saleId = openSale(5, 5);
        Client client = member();
        double duplicates = processed(app, Outcome.DUPLICATE);
        FAIL_AFTER_COMMIT.set(true);

        String acceptanceId = client.accept(saleId);

        // The first delivery committed the order and then failed; the redelivery finds the acceptance finished.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(processed(app, Outcome.DUPLICATE)).isEqualTo(duplicates + 1));
        assertThat(FAIL_AFTER_COMMIT.get()).isFalse();
        assertThat(acceptance(acceptanceId).get("status")).isEqualTo("ORDER_CREATED");
        assertThat(ordersFor(acceptanceId)).isEqualTo(1);
    }

    @Test
    void aRequestAcceptedBeforeTheEndIsServedAfterTheEnd() throws Exception {
        long saleId = sale("UTC_TIMESTAMP(6) - INTERVAL 1 HOUR", "UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE", 5, 5, false);
        Client client = member();
        String acceptanceId = storedAcceptance(client, saleId, "UTC_TIMESTAMP(6) - INTERVAL 10 MINUTE");

        send(saleId, payload(acceptanceId, saleId));

        assertThat(awaitFinished(acceptanceId).get("status")).isEqualTo("ORDER_CREATED");
        assertThat(ordersFor(acceptanceId)).isEqualTo(1);
    }

    @Test
    void aStopAfterAcceptanceRejectsTheWaitingRequest() throws Exception {
        long saleId = sale("UTC_TIMESTAMP(6) - INTERVAL 1 HOUR", "UTC_TIMESTAMP(6) + INTERVAL 1 HOUR", 5, 5, true);
        Client client = member();
        String acceptanceId = storedAcceptance(client, saleId, "UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE");

        send(saleId, payload(acceptanceId, saleId));

        var finished = awaitFinished(acceptanceId);
        assertThat(finished.get("status")).isEqualTo("REJECTED");
        assertThat(finished.get("rejection_reason")).isEqualTo("SALE_STOPPED");
        assertThat(finished.get("finished_at")).isNotNull();
        assertThat(ordersFor(acceptanceId)).isZero();
        assertThat(client.status(acceptanceId).path("rejectionReason").asString()).isEqualTo("SALE_STOPPED");
    }

    @Test
    void theStockRunsOutInPublishOrder() throws Exception {
        long saleId = openSale(2, 2);
        Client first = member();
        Client second = member();
        List<String> accepted = new ArrayList<>(List.of(first.accept(saleId), first.accept(saleId)));
        accepted.add(second.accept(saleId));

        assertThat(awaitFinished(accepted.get(0)).get("status")).isEqualTo("ORDER_CREATED");
        assertThat(awaitFinished(accepted.get(1)).get("status")).isEqualTo("ORDER_CREATED");
        var last = awaitFinished(accepted.get(2));
        assertThat(last.get("status")).isEqualTo("REJECTED");
        assertThat(last.get("rejection_reason")).isEqualTo("SALE_SOLD_OUT");
        assertThat(jdbc.queryForObject("SELECT reserved_quantity FROM sales WHERE id = ?", Integer.class, saleId))
            .isEqualTo(2);
    }

    @Test
    void theMemberLimitCountsV1AndV2OrdersTogether() throws Exception {
        long saleId = openSale(5, 1);
        Client client = member();
        var v1 = client.request("POST", "/api/v1/orders", "{\"saleId\":" + saleId + ",\"quantity\":1}",
            UUID.randomUUID().toString());
        assertThat(v1.statusCode()).isEqualTo(201);

        String acceptanceId = client.accept(saleId);

        var finished = awaitFinished(acceptanceId);
        assertThat(finished.get("status")).isEqualTo("REJECTED");
        assertThat(finished.get("rejection_reason")).isEqualTo("PURCHASE_LIMIT_EXCEEDED");
    }

    @Test
    void messagesThatCanNeverBeProcessedAreSkippedAndTheNextMessageOfThePartitionIsProcessed() throws Exception {
        long saleId = openSale(5, 5);
        long otherSale = openSale(5, 5);
        Client client = member();
        String mismatched = storedAcceptance(client, otherSale, "UTC_TIMESTAMP(6)");
        String valid = storedAcceptance(client, saleId, "UTC_TIMESTAMP(6)");
        double skipped = processed(app, Outcome.SKIPPED);

        // All under the same key, so they sit in one partition in this order, ahead of the valid one.
        send(saleId, "not json");
        send(saleId, "{\"schemaVersion\":2,\"acceptanceId\":\"" + valid + "\",\"saleId\":" + saleId + "}");
        send(saleId, payload(UUID.randomUUID().toString(), saleId));
        send(saleId, payload(mismatched, saleId));
        send(saleId, payload(valid, saleId));

        assertThat(awaitFinished(valid).get("status")).isEqualTo("ORDER_CREATED");
        assertThat(processed(app, Outcome.SKIPPED)).isEqualTo(skipped + 4);
        assertThat(acceptance(mismatched).get("status")).isEqualTo("ACCEPTED");
        assertThat(ordersFor(valid)).isEqualTo(1);
        // The acceptance for the other sale is left for its own message; finish it so reconciliation sees no gap.
        send(otherSale, payload(mismatched, otherSale));
        assertThat(awaitFinished(mismatched).get("status")).isEqualTo("ORDER_CREATED");
    }

    @Test
    void aLockWaitTimeoutIsRetriedUntilTheSaleRowIsFree() throws Exception {
        long saleId = openSale(5, 5);
        Client client = member();
        String acceptanceId;
        try (Connection holder = DriverManager.getConnection(SALES.getJdbcUrl(), SALES.getUsername(),
                SALES.getPassword())) {
            holder.setAutoCommit(false);
            try (var statement = holder.prepareStatement("SELECT id FROM sales WHERE id = ? FOR UPDATE")) {
                statement.setLong(1, saleId);
                statement.executeQuery().close();
            }
            acceptanceId = client.accept(saleId);
            // Several 2 s lock waits time out meanwhile; each one is retried, none is turned into a result.
            Thread.sleep(7000);
            assertThat(acceptance(acceptanceId).get("status")).isEqualTo("ACCEPTED");
            holder.rollback();
        }

        assertThat(awaitFinished(acceptanceId).get("status")).isEqualTo("ORDER_CREATED");
        assertThat(ordersFor(acceptanceId)).isEqualTo(1);
    }

    @Test
    void twoApplicationsShareThePartitionsAndEachAcceptanceBecomesExactlyOneOrder() throws Exception {
        var second = application(false);
        started.add(second);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            for (var context : List.of(app, second)) {
                var container = context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer(LISTENER);
                assertThat(container.getAssignedPartitions()).isNotEmpty();
                assertThat(container.getGroupId()).isEqualTo("limited-sale-order-consumer");
            }
        });
        var secondPartitions = second.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer(LISTENER)
            .getAssignedPartitions().stream().map(TopicPartition::partition).toList();
        double createdBefore = processed(app, Outcome.ORDER_CREATED);
        // Three sales whose partition the second application owns and three whose partition it does not.
        int toSecond = 0;
        int toFirst = 0;
        List<String> accepted = new ArrayList<>();
        while (toSecond < 3 || toFirst < 3) {
            long saleId = openSale(5, 5);
            boolean onSecond = secondPartitions.contains(partition(saleId));
            if (onSecond ? toSecond++ >= 3 : toFirst++ >= 3) {
                continue;
            }
            Client client = member();
            accepted.add(client.accept(saleId));
            accepted.add(client.accept(saleId));
        }

        for (String acceptanceId : accepted) {
            assertThat(awaitFinished(acceptanceId).get("status")).isEqualTo("ORDER_CREATED");
            assertThat(ordersFor(acceptanceId)).isEqualTo(1);
        }
        assertThat(processed(app, Outcome.ORDER_CREATED) - createdBefore + processed(second, Outcome.ORDER_CREATED))
            .isEqualTo(12);
        assertThat(processed(second, Outcome.ORDER_CREATED)).isGreaterThanOrEqualTo(6);
    }
}
