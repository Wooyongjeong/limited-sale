package com.wooyongjeong.limitedsale.order;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.wooyongjeong.limitedsale.LimitedSaleApplication;
import com.wooyongjeong.limitedsale.common.web.CommonResponseCode;
import com.wooyongjeong.limitedsale.member.application.MemberService;
import com.wooyongjeong.limitedsale.member.presentation.code.AuthenticationResponseCode;
import com.wooyongjeong.limitedsale.order.presentation.code.OrderResponseCode;
import com.wooyongjeong.limitedsale.product.api.ProductCommand;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;

import static com.wooyongjeong.limitedsale.support.ResponseEnvelope.assertCode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The asynchronous accept and status APIs against real MySQL and Redis. The shared application has the switch on and
 * the request limit off; tests that need another configuration start their own application on the same database.
 * Nothing here publishes to Kafka: acceptances stay ACCEPTED.
 */
class OrderAcceptanceIntegrationTest {
    private static final String TOPIC = "limited-sale.order-acceptances.v1";
    private static final MySQLContainer DB = new MySQLContainer("mysql:8.4.7")
        .withDatabaseName("sales").withUsername("sales_user").withPassword("test_password")
        .withCommand("--log-bin-trust-function-creators=1");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger MEMBERS = new AtomicInteger();
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static StringRedisTemplate redis;
    private static int port;
    private static long product;

    @BeforeAll
    static void start() {
        DB.start();
        REDIS.start();
        context = application(true, false);
        port = port(context);
        jdbc = context.getBean(JdbcTemplate.class);
        redis = context.getBean(StringRedisTemplate.class);
        product = context.getBean(ProductCommand.class).register("acceptance product", "", 1000L).productId();
    }

    @BeforeEach
    void reset() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_outbox");
        for (String table : List.of("order_outbox", "order_acceptances", "order_request_results", "payments",
                "sale_reservations", "purchase_orders", "sale_buyer_quantities", "sales")) {
            jdbc.update("DELETE FROM " + table);
        }
        var markers = redis.keys("limited-sale:sale:sold-out:v1:*");
        if (markers != null && !markers.isEmpty()) {
            redis.delete(markers);
        }
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
        REDIS.stop();
        DB.stop();
    }

    /** Uses the shipped application.yml, as the request limit test does; the switch is only set when asked. */
    private static ConfigurableApplicationContext application(boolean switchOn, boolean limitOn) {
        List<String> arguments = new ArrayList<>(List.of("--server.port=0", "--management.server.port=0",
            "--limited-sale.order.rate-limit.enabled=" + limitOn,
            "--limited-sale.order.rate-limit.max-requests=1", "--limited-sale.order.rate-limit.window=PT30S",
            "--spring.config.additional-location=file:src/main/resources/application.yml",
            "--spring.profiles.active=test", "--limited-sale.recovery.enabled=false", "--spring.flyway.enabled=true",
            "--spring.jpa.hibernate.ddl-auto=validate", "--MOCK_PG_API_KEY=test-secret",
            "--spring.flyway.locations=filesystem:src/main/resources/db/migration,classpath:db/nickmigration",
            "--spring.datasource.url=" + DB.getJdbcUrl(), "--spring.datasource.username=" + DB.getUsername(),
            "--spring.datasource.password=" + DB.getPassword(), "--spring.data.redis.host=" + REDIS.getHost(),
            "--spring.data.redis.port=" + REDIS.getMappedPort(6379), "--spring.data.redis.timeout=1s"));
        if (switchOn) {
            arguments.add("--limited-sale.order.async.enabled=true");
        }
        return new SpringApplicationBuilder(LimitedSaleApplication.class).run(arguments.toArray(String[]::new));
    }

    private static int port(ConfigurableApplicationContext application) {
        return ((ServletWebServerApplicationContext) application).getWebServer().getPort();
    }

    private static long sale(String starts, String ends, boolean stopped) {
        jdbc.update("INSERT INTO sales(product_id,name,description,product_name_snapshot,unit_price,starts_at,ends_at,"
            + "total_quantity,per_member_limit,is_stopped,created_at,updated_at) VALUES (?, 'sale', '', 'product', "
            + "1000, UTC_TIMESTAMP(6)" + starts + ", UTC_TIMESTAMP(6)" + ends + ", 5, 5, ?, UTC_TIMESTAMP(6), "
            + "UTC_TIMESTAMP(6))", product, stopped);
        return jdbc.queryForObject("SELECT MAX(id) FROM sales", Long.class);
    }

    private static long openSale() {
        return sale("-INTERVAL 1 HOUR", "+INTERVAL 1 HOUR", false);
    }

    private static int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static String code(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).path("code").asString();
    }

    private static JsonNode data(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).path("data");
    }

    private static int sqlErrorCode(Runnable statement) {
        try {
            statement.run();
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql) {
                    return sql.getErrorCode();
                }
            }
            throw failure;
        }
        return 0;
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
            token = data(request("GET", "/api/v1/csrf", null, null, null)).path("token").asString();
            assertThat(request("POST", "/api/v1/sessions", "{\"email\":\"" + email
                + "\",\"password\":\"password\"}", token, null).statusCode()).isEqualTo(200);
            token = data(request("GET", "/api/v1/csrf", null, null, null)).path("token").asString();
            return this;
        }

        HttpResponse<String> accept(String key, long saleId, int quantity) throws Exception {
            return request("POST", "/api/v2/orders", "{\"saleId\":" + saleId + ",\"quantity\":" + quantity + "}",
                token, key);
        }

        HttpResponse<String> order(String key, long saleId, int quantity) throws Exception {
            return request("POST", "/api/v1/orders", "{\"saleId\":" + saleId + ",\"quantity\":" + quantity + "}",
                token, key);
        }

        HttpResponse<String> status(String acceptanceId) throws Exception {
            return request("GET", "/api/v2/order-requests/" + acceptanceId, null, null, null);
        }
    }

    /** Every test member is new, so the request limit counters of one test never reach another. */
    private static Client member(int port, ConfigurableApplicationContext application) throws Exception {
        int number = MEMBERS.incrementAndGet();
        String email = "accept" + number + "@example.com";
        application.getBean(MemberService.class).register(email, "password", "accept" + number);
        return new Client(port).login(email);
    }

    private static Client member() throws Exception {
        return member(port, context);
    }

    @Test
    void firstAcceptanceIs202StoresTheAcceptanceAndTheExactOutboxMessageAndNothingElse() throws Exception {
        long saleId = openSale();
        Client c = member();
        String key = newKey();

        var response = c.accept(key, saleId, 2);

        assertCode(response, OrderResponseCode.ORDER_ACCEPTED);
        String id = data(response).path("acceptanceId").asString();
        assertThat(data(response).path("status").asString()).isEqualTo("ACCEPTED");
        assertThat(data(response).path("statusUrl").asString()).isEqualTo("/api/v2/order-requests/" + id);
        assertThat(response.headers().firstValue("Location")).contains("/api/v2/order-requests/" + id);

        var row = jdbc.queryForMap("SELECT BIN_TO_UUID(public_id) AS id, BIN_TO_UUID(request_key) AS request_key, "
            + "sale_id, quantity, status, rejection_reason, failure_reason, order_id, finished_at, "
            + "TIMESTAMPDIFF(SECOND, accepted_at, UTC_TIMESTAMP(6)) AS age FROM order_acceptances");
        assertThat(row).containsEntry("id", id).containsEntry("request_key", key).containsEntry("sale_id", saleId)
            .containsEntry("quantity", 2).containsEntry("status", "ACCEPTED")
            .containsEntry("rejection_reason", null).containsEntry("failure_reason", null)
            .containsEntry("order_id", null).containsEntry("finished_at", null);
        assertThat(((Number) row.get("age")).longValue()).isBetween(0L, 30L);

        var outbox = jdbc.queryForMap("SELECT topic, message_key, payload, published_at, attempts FROM order_outbox");
        assertThat(outbox).containsEntry("topic", TOPIC).containsEntry("message_key", Long.toString(saleId))
            .containsEntry("payload", "{\"schemaVersion\":1,\"acceptanceId\":\"" + id + "\",\"saleId\":"
                + saleId + "}")
            .containsEntry("published_at", null).containsEntry("attempts", 0);
        assertThat(count("order_acceptances")).isEqualTo(1);
        assertThat(count("order_outbox")).isEqualTo(1);
        // Stock is the consumer's business: acceptance reserves nothing and creates no order.
        assertThat(count("purchase_orders")).isZero();
        assertThat(count("order_request_results")).isZero();
        assertThat(jdbc.queryForObject("SELECT reserved_quantity FROM sales WHERE id=?", Integer.class, saleId))
            .isZero();
    }

    @Test
    void theOwnerReadsEveryFieldOfTheAcceptanceWithoutACsrfToken() throws Exception {
        long saleId = openSale();
        Client c = member();
        String id = data(c.accept(newKey(), saleId, 3)).path("acceptanceId").asString();

        var response = c.status(id);

        assertCode(response, OrderResponseCode.ORDER_ACCEPTANCE_FOUND);
        JsonNode body = data(response);
        assertThat(body.path("acceptanceId").asString()).isEqualTo(id);
        assertThat(body.path("saleId").asLong()).isEqualTo(saleId);
        assertThat(body.path("quantity").asInt()).isEqualTo(3);
        assertThat(body.path("status").asString()).isEqualTo("ACCEPTED");
        assertThat(body.path("rejectionReason").isNull()).isTrue();
        assertThat(body.path("failureReason").isNull()).isTrue();
        assertThat(body.path("orderId").isNull()).isTrue();
        assertThat(body.path("acceptedAt").asString()).isNotBlank();
        assertThat(body.path("finishedAt").isNull()).isTrue();
    }

    @Test
    void anOrderCreatedAcceptanceShowsThePublicOrderId() throws Exception {
        long saleId = openSale();
        Client c = member();
        String orderId = data(c.order(newKey(), saleId, 1)).path("orderId").asString();
        String id = data(c.accept(newKey(), saleId, 1)).path("acceptanceId").asString();
        // The consumer is not built yet (3-5); this is the row it will write.
        jdbc.update("UPDATE order_acceptances SET status='ORDER_CREATED', finished_at=UTC_TIMESTAMP(6), "
            + "order_id=(SELECT id FROM purchase_orders WHERE public_id=UUID_TO_BIN(?)) WHERE public_id=UUID_TO_BIN(?)",
            orderId, id);

        JsonNode body = data(c.status(id));

        assertThat(body.path("status").asString()).isEqualTo("ORDER_CREATED");
        assertThat(body.path("orderId").asString()).isEqualTo(orderId);
        assertThat(body.path("finishedAt").asString()).isNotBlank();
    }

    @Test
    void aFailedAcceptanceShowsItsFailureReason() throws Exception {
        Client c = member();
        String id = data(c.accept(newKey(), openSale(), 1)).path("acceptanceId").asString();
        jdbc.update("UPDATE order_acceptances SET status='PROCESSING_FAILED', failure_reason='TEST_FAILURE', "
            + "finished_at=UTC_TIMESTAMP(6) WHERE public_id=UUID_TO_BIN(?)", id);

        JsonNode body = data(c.status(id));

        assertThat(body.path("status").asString()).isEqualTo("PROCESSING_FAILED");
        assertThat(body.path("failureReason").asString()).isEqualTo("TEST_FAILURE");
        assertThat(body.path("orderId").isNull()).isTrue();
    }

    @Test
    void acceptanceSurvivesARestartOfTheApplication() throws Exception {
        long saleId = openSale();
        String id;
        String key = newKey();
        int number = MEMBERS.incrementAndGet();
        try (var first = application(true, false)) {
            first.getBean(MemberService.class).register("restart" + number + "@example.com", "password",
                "restart" + number);
            Client before = new Client(port(first)).login("restart" + number + "@example.com");
            var response = before.accept(key, saleId, 1);
            assertCode(response, OrderResponseCode.ORDER_ACCEPTED);
            id = data(response).path("acceptanceId").asString();
        }
        try (var second = application(true, false)) {
            Client after = new Client(port(second)).login("restart" + number + "@example.com");

            var response = after.status(id);

            assertCode(response, OrderResponseCode.ORDER_ACCEPTANCE_FOUND);
            assertThat(data(response).path("status").asString()).isEqualTo("ACCEPTED");
            assertThat(data(response).path("saleId").asLong()).isEqualTo(saleId);
            // The same key still replays the stored acceptance instead of creating another.
            var replay = after.accept(key, saleId, 1);
            assertCode(replay, OrderResponseCode.ORDER_ACCEPTANCE_FOUND);
            assertThat(data(replay).path("acceptanceId").asString()).isEqualTo(id);
        }
        assertThat(count("order_acceptances")).isEqualTo(1);
        assertThat(count("order_outbox")).isEqualTo(1);
    }

    @Test
    void anOutboxInsertFailureStoresNoAcceptanceAndAnswers500ThenTheRetryWorks() throws Exception {
        long saleId = openSale();
        Client c = member();
        String key = newKey();
        jdbc.execute("CREATE TRIGGER fail_outbox BEFORE INSERT ON order_outbox FOR EACH ROW "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected outbox failure'");
        try {
            assertCode(c.accept(key, saleId, 1), CommonResponseCode.INTERNAL_ERROR);
        } finally {
            jdbc.execute("DROP TRIGGER fail_outbox");
        }
        assertThat(count("order_acceptances")).isZero();
        assertThat(count("order_outbox")).isZero();

        assertCode(c.accept(key, saleId, 1), OrderResponseCode.ORDER_ACCEPTED);
        assertThat(count("order_acceptances")).isEqualTo(1);
        assertThat(count("order_outbox")).isEqualTo(1);
    }

    @Test
    void anUnacceptableSaleStoresARejectionWithoutAnOutboxRowAndRepeatsTheSameAnswer() throws Exception {
        long ended = sale("-INTERVAL 2 HOUR", "-INTERVAL 1 HOUR", false);
        long scheduled = sale("+INTERVAL 1 HOUR", "+INTERVAL 2 HOUR", false);
        long stopped = sale("-INTERVAL 1 HOUR", "+INTERVAL 1 HOUR", true);
        long unknown = 987_654_321L;
        long marked = openSale();
        redis.opsForValue().set("limited-sale:sale:sold-out:v1:" + marked, "1", Duration.ofSeconds(30));
        Client c = member();
        record Case(long saleId, OrderResponseCode expected, String reason) {
        }

        for (var row : List.of(new Case(ended, OrderResponseCode.SALE_OUTSIDE_PERIOD, "SALE_OUTSIDE_PERIOD"),
                new Case(scheduled, OrderResponseCode.SALE_OUTSIDE_PERIOD, "SALE_OUTSIDE_PERIOD"),
                new Case(stopped, OrderResponseCode.SALE_STOPPED, "SALE_STOPPED"),
                new Case(unknown, OrderResponseCode.SALE_NOT_FOUND, "SALE_NOT_FOUND"),
                new Case(marked, OrderResponseCode.SALE_SOLD_OUT, "SALE_SOLD_OUT"))) {
            String key = newKey();

            var first = c.accept(key, row.saleId(), 1);

            assertCode(first, row.expected());
            assertThat(data(first).isNull()).isTrue();
            var stored = jdbc.queryForMap("SELECT status, rejection_reason, failure_reason, order_id, "
                + "finished_at IS NOT NULL AS finished FROM order_acceptances WHERE request_key=UUID_TO_BIN(?)", key);
            assertThat(stored).containsEntry("status", "REJECTED").containsEntry("rejection_reason", row.reason())
                .containsEntry("failure_reason", null).containsEntry("order_id", null).containsEntry("finished", 1L);
            // The same key answers the same way, including after the situation changed.
            assertCode(c.accept(key, row.saleId(), 1), row.expected());
            assertCode(c.accept(key, row.saleId(), 2), OrderResponseCode.IDEMPOTENCY_CONFLICT);
        }
        assertThat(count("order_acceptances")).isEqualTo(5);
        assertThat(count("order_outbox")).isZero();
        assertThat(jdbc.queryForObject("SELECT reserved_quantity FROM sales WHERE id=?", Integer.class, marked))
            .isZero();
    }

    @Test
    void aMarkedSaleStillReplaysAnExistingAcceptanceFirst() throws Exception {
        long saleId = openSale();
        Client c = member();
        String key = newKey();
        String id = data(c.accept(key, saleId, 1)).path("acceptanceId").asString();
        redis.opsForValue().set("limited-sale:sale:sold-out:v1:" + saleId, "1", Duration.ofSeconds(30));

        var replay = c.accept(key, saleId, 1);

        assertCode(replay, OrderResponseCode.ORDER_ACCEPTANCE_FOUND);
        assertThat(data(replay).path("acceptanceId").asString()).isEqualTo(id);
        assertThat(count("order_acceptances")).isEqualTo(1);
    }

    @Test
    void aSameKeyRepeatIs200WithTheCurrentStatusAndStoresNothingNew() throws Exception {
        long saleId = openSale();
        Client c = member();
        String key = newKey();
        String id = data(c.accept(key, saleId, 1)).path("acceptanceId").asString();

        var replay = c.accept(key, saleId, 1);

        assertCode(replay, OrderResponseCode.ORDER_ACCEPTANCE_FOUND);
        assertThat(data(replay).path("acceptanceId").asString()).isEqualTo(id);
        assertThat(data(replay).path("status").asString()).isEqualTo("ACCEPTED");
        assertThat(data(replay).path("statusUrl").asString()).isEqualTo("/api/v2/order-requests/" + id);
        // A later status shows through the replay too.
        jdbc.update("UPDATE order_acceptances SET status='PROCESSING_FAILED', failure_reason='X', "
            + "finished_at=UTC_TIMESTAMP(6)");
        assertThat(data(c.accept(key, saleId, 1)).path("status").asString()).isEqualTo("PROCESSING_FAILED");
        assertThat(count("order_acceptances")).isEqualTo(1);
        assertThat(count("order_outbox")).isEqualTo(1);
    }

    @Test
    void aDifferentInputOnTheSameKeyIsAConflictAndStoresNothingNew() throws Exception {
        long saleId = openSale();
        long other = openSale();
        Client c = member();
        String key = newKey();
        assertCode(c.accept(key, saleId, 2), OrderResponseCode.ORDER_ACCEPTED);

        assertCode(c.accept(key, saleId, 3), OrderResponseCode.IDEMPOTENCY_CONFLICT);
        assertCode(c.accept(key, other, 2), OrderResponseCode.IDEMPOTENCY_CONFLICT);

        assertThat(count("order_acceptances")).isEqualTo(1);
        assertThat(count("order_outbox")).isEqualTo(1);
    }

    @Test
    void theSameKeyFromTwoMembersIsTwoAcceptances() throws Exception {
        long saleId = openSale();
        String key = newKey();

        assertCode(member().accept(key, saleId, 1), OrderResponseCode.ORDER_ACCEPTED);
        assertCode(member().accept(key, saleId, 1), OrderResponseCode.ORDER_ACCEPTED);

        assertThat(count("order_acceptances")).isEqualTo(2);
        assertThat(count("order_outbox")).isEqualTo(2);
    }

    @Test
    void concurrentRequestsWithOneKeyCreateExactlyOneAcceptance() throws Exception {
        long saleId = openSale();
        Client c = member();
        String key = newKey();
        int threads = 12;
        List<HttpResponse<String>> responses = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(threads)) {
            var gate = new CountDownLatch(1);
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    gate.await();
                    return c.accept(key, saleId, 1);
                }));
            }
            gate.countDown();
            for (var future : futures) {
                responses.add(future.get());
            }
        }

        assertThat(responses).extracting(HttpResponse::statusCode).containsOnly(202, 200);
        assertThat(responses.stream().filter(response -> response.statusCode() == 202)).hasSize(1);
        assertThat(responses.stream().map(response -> {
            try {
                return data(response).path("acceptanceId").asString();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }).distinct()).hasSize(1);
        assertThat(count("order_acceptances")).isEqualTo(1);
        assertThat(count("order_outbox")).isEqualTo(1);
    }

    @Test
    void anotherMembersAndAnUnknownAcceptanceAre404() throws Exception {
        long saleId = openSale();
        Client owner = member();
        Client other = member();
        String id = data(owner.accept(newKey(), saleId, 1)).path("acceptanceId").asString();

        assertCode(other.status(id), OrderResponseCode.ORDER_ACCEPTANCE_NOT_FOUND);
        assertCode(owner.status(UUID.randomUUID().toString()), OrderResponseCode.ORDER_ACCEPTANCE_NOT_FOUND);
        assertCode(owner.status(id), OrderResponseCode.ORDER_ACCEPTANCE_FOUND);
    }

    @Test
    void sessionCsrfAndInputRulesMatchVersionOne() throws Exception {
        long saleId = openSale();
        Client c = member();
        Client anonymous = new Client(port);
        String body = "{\"saleId\":" + saleId + ",\"quantity\":1}";

        // As in version one, the CSRF check comes before the login check.
        assertCode(anonymous.request("POST", "/api/v2/orders", body, null, newKey()),
            AuthenticationResponseCode.CSRF_INVALID);
        String anonymousToken = data(anonymous.request("GET", "/api/v1/csrf", null, null, null)).path("token")
            .asString();
        assertCode(anonymous.request("POST", "/api/v2/orders", body, anonymousToken, newKey()),
            AuthenticationResponseCode.AUTHENTICATION_REQUIRED);
        assertCode(anonymous.status(UUID.randomUUID().toString()), AuthenticationResponseCode.AUTHENTICATION_REQUIRED);
        assertCode(c.request("POST", "/api/v2/orders", body, null, newKey()), AuthenticationResponseCode.CSRF_INVALID);
        assertThat(c.request("POST", "/api/v2/orders", body, "wrong", newKey()).statusCode()).isEqualTo(403);
        assertCode(c.request("POST", "/api/v2/orders", body, c.token, null), CommonResponseCode.INVALID_INPUT);
        assertCode(c.request("POST", "/api/v2/orders", body, c.token, "not-a-uuid"), CommonResponseCode.INVALID_INPUT);
        assertCode(c.request("POST", "/api/v2/orders", "{\"saleId\":" + saleId + ",\"quantity\":0}", c.token,
            newKey()), CommonResponseCode.INVALID_INPUT);
        assertCode(c.request("POST", "/api/v2/orders", "{\"saleId\":" + saleId + ",\"quantity\":1,\"x\":1}",
            c.token, newKey()), CommonResponseCode.INVALID_INPUT);
        assertCode(c.status("not-a-uuid"), CommonResponseCode.INVALID_INPUT);
        assertThat(count("order_acceptances")).isZero();
        assertThat(count("order_outbox")).isZero();
    }

    @Test
    void theRequestLimitIsSharedWithVersionOneAndStoresNothingWhenItRejects() throws Exception {
        long saleId = openSale();
        try (var limited = application(true, true)) {
            int limitedPort = port(limited);
            Client acceptedFirst = member(limitedPort, limited);
            var first = acceptedFirst.accept(newKey(), saleId, 1);
            assertCode(first, OrderResponseCode.ORDER_ACCEPTED);
            String firstId = data(first).path("acceptanceId").asString();

            var second = acceptedFirst.accept(newKey(), saleId, 1);

            assertCode(second, OrderResponseCode.ORDER_RATE_LIMITED);
            assertThat(Long.parseLong(second.headers().firstValue("Retry-After").orElseThrow())).isPositive();
            assertThat(count("order_acceptances")).isEqualTo(1);
            assertThat(count("order_outbox")).isEqualTo(1);

            // A synchronous order spends the same per-member budget, so the asynchronous request is turned away.
            Client orderedFirst = member(limitedPort, limited);
            assertCode(orderedFirst.order(newKey(), saleId, 1), OrderResponseCode.ORDER_CREATED);
            assertCode(orderedFirst.accept(newKey(), saleId, 1), OrderResponseCode.ORDER_RATE_LIMITED);
            assertThat(count("order_acceptances")).isEqualTo(1);
            assertThat(count("order_outbox")).isEqualTo(1);

            // Reading the status does not spend the budget.
            assertCode(acceptedFirst.status(firstId), OrderResponseCode.ORDER_ACCEPTANCE_FOUND);
        }
    }

    @Test
    void withTheSwitchOffBothPathsAre404AndNothingIsStored() throws Exception {
        long saleId = openSale();
        // No switch argument at all: the default is off.
        try (var off = application(false, false)) {
            Client c = member(port(off), off);

            var post = c.accept(newKey(), saleId, 1);
            var get = c.status(UUID.randomUUID().toString());

            assertThat(post.statusCode()).isEqualTo(404);
            assertThat(get.statusCode()).isEqualTo(404);
            assertThat(count("order_acceptances")).isZero();
            assertThat(count("order_outbox")).isZero();
            // Version one is not affected.
            assertCode(c.order(newKey(), saleId, 1), OrderResponseCode.ORDER_CREATED);
        }
    }

    @Test
    void versionOneIsUnchangedAndItsKeysAreSeparateFromVersionTwo() throws Exception {
        long saleId = openSale();
        Client c = member();
        String key = newKey();

        var created = c.order(key, saleId, 2);
        assertCode(created, OrderResponseCode.ORDER_CREATED);
        assertCode(c.order(key, saleId, 2), OrderResponseCode.ORDER_FOUND);
        assertCode(c.order(key, saleId, 3), OrderResponseCode.IDEMPOTENCY_CONFLICT);
        // The same UUID on v2 is a separate attempt: it neither replays nor conflicts with the v1 order.
        assertCode(c.accept(key, saleId, 1), OrderResponseCode.ORDER_ACCEPTED);
        assertCode(c.accept(key, saleId, 1), OrderResponseCode.ORDER_ACCEPTANCE_FOUND);
        assertCode(c.order(key, saleId, 2), OrderResponseCode.ORDER_FOUND);

        assertThat(count("purchase_orders")).isEqualTo(1);
        assertThat(count("order_request_results")).isEqualTo(1);
        assertThat(count("order_acceptances")).isEqualTo(1);
        assertThat(count("order_outbox")).isEqualTo(1);
    }

    @Test
    void acceptingNeverWaitsForTheSaleRowLock() throws Exception {
        long saleId = openSale();
        Client c = member();
        String earlier = data(c.accept(newKey(), saleId, 1)).path("acceptanceId").asString();
        try (Connection holder = context.getBean(DataSource.class).getConnection()) {
            holder.setAutoCommit(false);
            try (var statement = holder.prepareStatement("SELECT id FROM sales WHERE id=? FOR UPDATE")) {
                statement.setLong(1, saleId);
                statement.executeQuery().close();
            }
            try {
                long started = System.nanoTime();
                // The client times out at 8 s; blocking on the lock would end in a timeout, not a 202.
                assertCode(c.accept(newKey(), saleId, 1), OrderResponseCode.ORDER_ACCEPTED);
                assertCode(c.accept(newKey(), saleId, 1), OrderResponseCode.ORDER_ACCEPTED);
                assertCode(c.status(earlier), OrderResponseCode.ORDER_ACCEPTANCE_FOUND);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
            } finally {
                holder.rollback();
            }
        }
        assertThat(count("order_acceptances")).isEqualTo(3);
    }

    @Test
    void theStatesAndTheirRequiredColumnsAreEnforcedByTheDatabase() throws Exception {
        long saleId = openSale();
        Client c = member();
        data(c.accept(newKey(), saleId, 1));
        long memberId = jdbc.queryForObject("SELECT member_id FROM order_acceptances", Long.class);
        long orderId;
        data(c.order(newKey(), saleId, 1));
        orderId = jdbc.queryForObject("SELECT id FROM purchase_orders", Long.class);
        String base = "INSERT INTO order_acceptances(public_id, member_id, request_key, sale_id, quantity, status, "
            + "rejection_reason, failure_reason, order_id, accepted_at, finished_at) VALUES (RANDOM_BYTES(16), "
            + memberId + ", RANDOM_BYTES(16), 1, 1, ";
        String now = "UTC_TIMESTAMP(6)";
        // Valid rows of every state.
        jdbc.update(base + "'ACCEPTED', NULL, NULL, NULL, " + now + ", NULL)");
        jdbc.update(base + "'REJECTED', 'SALE_STOPPED', NULL, NULL, " + now + ", " + now + ")");
        jdbc.update(base + "'ORDER_CREATED', NULL, NULL, " + orderId + ", " + now + ", " + now + ")");
        jdbc.update(base + "'PROCESSING_FAILED', NULL, 'SOME_REASON', NULL, " + now + ", " + now + ")");
        // Each state with a missing or foreign column is a CHECK violation (3819).
        for (String invalid : List.of(
                "'ACCEPTED', NULL, NULL, " + orderId + ", " + now + ", NULL)",
                "'ACCEPTED', NULL, NULL, NULL, " + now + ", " + now + ")",
                "'ACCEPTED', 'SALE_STOPPED', NULL, NULL, " + now + ", NULL)",
                "'ACCEPTED', NULL, 'X', NULL, " + now + ", NULL)",
                "'REJECTED', NULL, NULL, NULL, " + now + ", " + now + ")",
                "'REJECTED', 'SALE_STOPPED', NULL, NULL, " + now + ", NULL)",
                "'REJECTED', 'NOT_A_REASON', NULL, NULL, " + now + ", " + now + ")",
                "'REJECTED', 'sale_stopped', NULL, NULL, " + now + ", " + now + ")",
                "'REJECTED', 'SALE_STOPPED', NULL, " + orderId + ", " + now + ", " + now + ")",
                "'ORDER_CREATED', NULL, NULL, NULL, " + now + ", " + now + ")",
                "'ORDER_CREATED', NULL, NULL, " + orderId + ", " + now + ", NULL)",
                "'PROCESSING_FAILED', NULL, NULL, NULL, " + now + ", " + now + ")",
                "'PROCESSING_FAILED', NULL, 'X', NULL, " + now + ", NULL)",
                "'accepted', NULL, NULL, NULL, " + now + ", NULL)",
                "'ACCEPTED ', NULL, NULL, NULL, " + now + ", NULL)",
                "'UNKNOWN', NULL, NULL, NULL, " + now + ", NULL)")) {
            assertThat(sqlErrorCode(() -> jdbc.update(base + invalid))).as(invalid).isEqualTo(3819);
        }
        assertThat(sqlErrorCode(() -> jdbc.update("INSERT INTO order_acceptances(public_id, member_id, request_key, "
            + "sale_id, quantity, status, accepted_at) VALUES (RANDOM_BYTES(16), 999999999, RANDOM_BYTES(16), 1, 1, "
            + "'ACCEPTED', UTC_TIMESTAMP(6))"))).isEqualTo(1452);
        assertThat(sqlErrorCode(() -> jdbc.update("INSERT INTO order_acceptances(public_id, member_id, request_key, "
            + "sale_id, quantity, status, order_id, finished_at, accepted_at) VALUES (RANDOM_BYTES(16), " + memberId
            + ", RANDOM_BYTES(16), 1, 1, 'ORDER_CREATED', 999999999, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))")))
            .isEqualTo(1452);
        assertThat(sqlErrorCode(() -> jdbc.update("INSERT INTO order_acceptances(public_id, member_id, request_key, "
            + "sale_id, quantity, status, accepted_at) SELECT RANDOM_BYTES(16), member_id, request_key, sale_id, "
            + "quantity, 'ACCEPTED', accepted_at FROM order_acceptances LIMIT 1"))).isEqualTo(1062);
        assertThat(sqlErrorCode(() -> jdbc.update("INSERT INTO order_outbox(topic, message_key, payload, created_at) "
            + "VALUES ('t', 'k', 'not json', UTC_TIMESTAMP(6))"))).isEqualTo(3819);
        assertThat(sqlErrorCode(() -> jdbc.update("INSERT INTO order_outbox(topic, message_key, payload, created_at, "
            + "attempts) VALUES ('t', 'k', '{}', UTC_TIMESTAMP(6), -1)"))).isEqualTo(3819);
        // Unpublished rows are read in id order through one index on (published_at, id).
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.statistics WHERE table_schema = "
            + "DATABASE() AND table_name = 'order_outbox' AND index_name = 'ix_order_outbox_unpublished' "
            + "ORDER BY seq_in_index", String.class)).containsExactly("published_at", "id");
    }
}
