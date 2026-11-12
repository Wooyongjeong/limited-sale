package com.wooyongjeong.limitedsale.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wooyongjeong.limitedsale.LimitedSaleApplication;
import com.wooyongjeong.limitedsale.member.application.MemberService;
import com.wooyongjeong.limitedsale.product.api.ProductCommand;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class OrderIntegrationTest {
    private static final MySQLContainer<?> DB = new MySQLContainer<>("mysql:8.4.7")
        .withDatabaseName("sales").withUsername("sales_user").withPassword("test_password");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static int port;
    private static long product;
    private static long user1;

    @BeforeAll
    static void start() {
        DB.start();
        REDIS.start();
        context = new SpringApplicationBuilder(LimitedSaleApplication.class).run("--server.port=0",
            "--spring.datasource.url=" + DB.getJdbcUrl(), "--spring.datasource.username=" + DB.getUsername(),
            "--spring.datasource.password=" + DB.getPassword(), "--spring.data.redis.host=" + REDIS.getHost(),
            "--spring.data.redis.port=" + REDIS.getMappedPort(6379), "--spring.data.redis.timeout=1s",
            "--spring.flyway.locations=filesystem:src/main/resources/db/migration,classpath:db/nickmigration");
        port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        jdbc = context.getBean(JdbcTemplate.class);
        product = context.getBean(ProductCommand.class).register("product", "", 1000L).productId();
        var members = context.getBean(MemberService.class);
        members.register("order1@example.com", "password", "orderone");
        members.register("order2@example.com", "password", "ordertwo");
        user1 = jdbc.queryForObject("SELECT id FROM members WHERE email='order1@example.com'", Long.class);
    }

    @BeforeEach
    void reset() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_payment");
        for (String table : List.of("order_request_results", "payments", "sale_reservations", "purchase_orders",
                "sale_buyer_quantities", "sales")) {
            jdbc.update("DELETE FROM " + table);
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

    private static long sale(int total, int limit) {
        jdbc.update("INSERT INTO sales(product_id,name,description,product_name_snapshot,unit_price,starts_at,ends_at,"
            + "total_quantity,per_member_limit,created_at,updated_at) VALUES (?, 'sale', '', 'product', 1000, "
            + "UTC_TIMESTAMP(6)-INTERVAL 1 HOUR, UTC_TIMESTAMP(6)+INTERVAL 1 HOUR, ?, ?, "
            + "UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", product, total, limit);
        return jdbc.queryForObject("SELECT MAX(id) FROM sales", Long.class);
    }

    private static int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static Instant databaseTime() {
        return Instant.parse(jdbc.queryForObject(
            "SELECT DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%fZ')", String.class));
    }

    private static void awaitSaleLockWaiters(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        long waiters = 0;
        while (System.nanoTime() < deadline) {
            waiters = jdbc.queryForList("SHOW FULL PROCESSLIST").stream().filter(row -> {
                Object info = row.get("Info");
                if (info == null) {
                    return false;
                }
                String sql = info.toString().toLowerCase(java.util.Locale.ROOT);
                return sql.contains("from sales ") && sql.contains("for update");
            }).count();
            if (waiters >= expected) {
                break;
            }
            Thread.sleep(10);
        }
        assertThat(waiters).as("MySQL sale row lock waiters").isGreaterThanOrEqualTo(expected);
    }

    private static final class Client {
        final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String token;

        HttpResponse<String> request(String method, String path, String body, String csrf, String key)
                throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
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

        void login(String email) throws Exception {
            token = data(request("GET", "/api/v1/csrf", null, null, null)).path("token").asText();
            assertThat(request("POST", "/api/v1/sessions", "{\"email\":\"" + email
                + "\",\"password\":\"password\"}", token, null).statusCode()).isEqualTo(200);
            token = data(request("GET", "/api/v1/csrf", null, null, null)).path("token").asText();
        }

        HttpResponse<String> order(String key, long saleId, int quantity) throws Exception {
            return request("POST", "/api/v1/orders", "{\"saleId\":" + saleId + ",\"quantity\":" + quantity + "}",
                token, key);
        }
    }

    private static JsonNode data(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).path("data");
    }

    private static String code(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).path("code").asText();
    }

    private static Client client(String email) throws Exception {
        Client client = new Client();
        client.login(email);
        return client;
    }

    @Test
    void createsReservationsExhaustsStockAndReplaysCurrentOrder() throws Exception {
        long id = sale(5, 4);
        Client one = client("order1@example.com");
        Client two = client("order2@example.com");
        String key = UUID.randomUUID().toString();
        Instant before = Instant.parse(jdbc.queryForObject(
            "SELECT DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%fZ')", String.class));
        var first = one.order(key, id, 2);
        Instant after = Instant.parse(jdbc.queryForObject(
            "SELECT DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%fZ')", String.class));
        assertThat(first.statusCode()).isEqualTo(201);
        var view = data(first);
        Instant created = Instant.parse(view.path("createdAt").asText());
        assertThat(created).isBetween(before, after);
        assertThat(Instant.parse(jdbc.queryForObject("SELECT DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') "
            + "FROM purchase_orders WHERE public_id=UNHEX(REPLACE(?, '-', ''))", String.class,
            view.path("orderId").asText()))).isEqualTo(created);
        assertThat(view.path("quantity").asInt()).isEqualTo(2);
        assertThat(view.path("totalAmount").asLong()).isEqualTo(2000);
        assertThat(view.path("status").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(Instant.parse(view.path("expiresAt").asText()))
            .isEqualTo(Instant.parse(view.path("createdAt").asText()).plusSeconds(300));
        Set<String> fields = java.util.stream.StreamSupport.stream(
            java.util.Spliterators.spliteratorUnknownSize(view.fieldNames(), 0), false).collect(Collectors.toSet());
        assertThat(fields).containsExactlyInAnyOrder("orderId", "saleId", "productId", "productNameSnapshot",
            "quantity", "unitPrice", "totalAmount", "status", "createdAt", "updatedAt", "expiresAt");
        assertThat(one.order(UUID.randomUUID().toString(), id, 2).statusCode()).isEqualTo(201);
        assertThat(code(one.order(UUID.randomUUID().toString(), id, 1))).isEqualTo("PURCHASE_LIMIT_EXCEEDED");
        assertThat(two.order(UUID.randomUUID().toString(), id, 1).statusCode()).isEqualTo(201);
        assertThat(code(two.order(UUID.randomUUID().toString(), id, 1))).isEqualTo("SALE_SOLD_OUT");
        assertThat(jdbc.queryForObject("SELECT reserved_quantity FROM sales WHERE id=?", Integer.class, id))
            .isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT reserved_quantity FROM sale_buyer_quantities "
            + "WHERE sale_id=? AND member_id=?", Integer.class, id, user1)).isEqualTo(4);
        assertThat(count("purchase_orders")).isEqualTo(3);
        assertThat(count("sale_reservations")).isEqualTo(3);
        assertThat(count("payments")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE status='READY' AND pg_key IS NOT NULL",
            Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE next_recovery_at=expires_at",
            Integer.class)).isEqualTo(3);
        jdbc.update("UPDATE purchase_orders SET status='PAYMENT_PENDING' WHERE public_id=UNHEX(REPLACE(?, '-', ''))",
            view.path("orderId").asText());
        var replay = one.order(key, id, 2);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(data(replay).path("status").asText()).isEqualTo("PAYMENT_PENDING");
        assertThat(code(one.order(key, id, 3))).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(data(one.order(key, id, 2)).path("orderId").asText()).isEqualTo(view.path("orderId").asText());
        assertThat(one.request("GET", "/api/v1/orders/" + view.path("orderId").asText(), null, null, null).statusCode())
            .isEqualTo(200);
        assertThat(two.request("GET", "/api/v1/orders/" + view.path("orderId").asText(), null, null, null).statusCode())
            .isEqualTo(404);
    }

    @Test
    void maximumPriceAndQuantityKeepExactLongAmountAcrossOrderAndPayment() throws Exception {
        long id = sale(1_000_000, 1_000_000);
        jdbc.update("UPDATE sales SET unit_price=1000000000 WHERE id=?", id);
        Client c = client("order1@example.com");
        var response = c.order(UUID.randomUUID().toString(), id, 1_000_000);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(data(response).path("totalAmount").asLong()).isEqualTo(1_000_000_000_000_000L);
        assertThat(jdbc.queryForObject("SELECT total_amount FROM purchase_orders", Long.class))
            .isEqualTo(1_000_000_000_000_000L);
        assertThat(jdbc.queryForObject("SELECT amount FROM payments", Long.class))
            .isEqualTo(1_000_000_000_000_000L);
        assertThat(jdbc.queryForObject("SELECT reserved_quantity FROM sales WHERE id=?", Integer.class, id))
            .isEqualTo(1_000_000);
    }

    @Test
    void retriesAfterResponseLossWithoutCreatingAnotherOrder() throws Exception {
        long id = sale(3, 3);
        Client c = client("order1@example.com");
        String key = UUID.randomUUID().toString();
        c.order(key, id, 2);
        var replay = c.order(key, id, 2);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(data(replay).path("quantity").asInt()).isEqualTo(2);
        assertThat(count("purchase_orders")).isEqualTo(1);
        assertThat(count("sale_reservations")).isEqualTo(1);
        assertThat(count("payments")).isEqualTo(1);
    }

    @Test
    void storesBusinessFailuresAndUsesApprovedPrecedence() throws Exception {
        Client c = client("order1@example.com");
        long id = sale(2, 1);
        jdbc.update("UPDATE sales SET is_stopped=1, starts_at=UTC_TIMESTAMP(6)+INTERVAL 1 HOUR, "
            + "ends_at=UTC_TIMESTAMP(6)+INTERVAL 2 HOUR WHERE id=?", id);
        String periodKey = UUID.randomUUID().toString();
        assertThat(code(c.order(periodKey, id, 2))).isEqualTo("SALE_OUTSIDE_PERIOD");
        jdbc.update("UPDATE sales SET starts_at=UTC_TIMESTAMP(6)-INTERVAL 1 HOUR WHERE id=?", id);
        assertThat(code(c.order(periodKey, id, 2))).isEqualTo("SALE_OUTSIDE_PERIOD");
        assertThat(code(c.order(UUID.randomUUID().toString(), id, 2))).isEqualTo("SALE_STOPPED");
        jdbc.update("UPDATE sales SET is_stopped=0 WHERE id=?", id);
        assertThat(code(c.order(UUID.randomUUID().toString(), id, 2))).isEqualTo("PURCHASE_LIMIT_EXCEEDED");
        assertThat(c.order(UUID.randomUUID().toString(), id, 1).statusCode()).isEqualTo(201);
        assertThat(code(c.order(UUID.randomUUID().toString(), id, 1))).isEqualTo("PURCHASE_LIMIT_EXCEEDED");
        String missingKey = UUID.randomUUID().toString();
        long missing = 999999;
        assertThat(code(c.order(missingKey, missing, 1))).isEqualTo("SALE_NOT_FOUND");
        jdbc.update("INSERT INTO sales(id,product_id,name,description,product_name_snapshot,unit_price,"
            + "starts_at,ends_at,"
            + "total_quantity,per_member_limit,created_at,updated_at) VALUES (?,?,'new','','product',1000,"
            + "UTC_TIMESTAMP(6)-INTERVAL 1 HOUR,UTC_TIMESTAMP(6)+INTERVAL 1 HOUR,1,1,UTC_TIMESTAMP(6),"
            + "UTC_TIMESTAMP(6))", missing, product);
        assertThat(code(c.order(missingKey, missing, 1))).isEqualTo("SALE_NOT_FOUND");
        assertThat(code(c.order(missingKey, missing, 2))).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(c.order(UUID.randomUUID().toString(), missing, 1).statusCode()).isEqualTo(201);
    }

    @Test
    void rejectsUnauthenticatedCsrfAndStrictInputWithoutRecordingKey() throws Exception {
        long id = sale(5, 5);
        Client anonymous = new Client();
        var anon = anonymous.request("GET", "/api/v1/orders/" + UUID.randomUUID(), null, null, null);
        assertThat(anon.statusCode()).isEqualTo(401);
        Client c = client("order1@example.com");
        String key = UUID.randomUUID().toString();
        assertThat(c.request("POST", "/api/v1/orders", "{\"saleId\":" + id + ",\"quantity\":1}", null, key)
            .statusCode()).isEqualTo(403);
        for (String body : List.of("{}", "null", "[]", "{\"saleId\":1,\"quantity\":1,\"extra\":2}",
                "{\"saleId\":1,\"saleId\":1,\"quantity\":1}", "{\"saleId\":\"1\",\"quantity\":1}",
                "{\"saleId\":1,\"quantity\":1.0}", "{\"saleId\":1,\"quantity\":0}",
                "{\"saleId\":1,\"quantity\":1000001}", "{\"saleId\":1,\"quantity\":1} {}")) {
            assertThat(c.request("POST", "/api/v1/orders", body, c.token, key).statusCode()).as(body).isEqualTo(400);
        }
        assertThat(c.request("POST", "/api/v1/orders", "{\"saleId\":1,\"quantity\":1}", c.token, "1-1-1-1-1")
            .statusCode()).isEqualTo(400);
        assertThat(c.request("GET", "/api/v1/orders/invalid", null, null, null).statusCode()).isEqualTo(400);
        assertThat(count("order_request_results")).isZero();
    }

    @Test
    void sameKeyRacesReplayOrConflictAndAreScopedToMember() throws Exception {
        long firstSale = sale(20, 20);
        long secondSale = sale(20, 20);
        Client one = client("order1@example.com");
        Client two = client("order2@example.com");
        try (var executor = Executors.newFixedThreadPool(2)) {
            String key = UUID.randomUUID().toString();
            var gate = new CountDownLatch(1);
            HttpResponse<String> first;
            HttpResponse<String> second;
            try (Connection lock = context.getBean(DataSource.class).getConnection()) {
                lock.setAutoCommit(false);
                try (var statement = lock.prepareStatement("SELECT id FROM sales WHERE id=? FOR UPDATE")) {
                    statement.setLong(1, firstSale);
                    statement.executeQuery().close();
                }
                var a = executor.submit(() -> {
                    gate.await();
                    return one.order(key, firstSale, 2);
                });
                var b = executor.submit(() -> {
                    gate.await();
                    return one.order(key, firstSale, 2);
                });
                gate.countDown();
                awaitSaleLockWaiters(2);
                assertThat(a.isDone()).isFalse();
                assertThat(b.isDone()).isFalse();
                lock.commit();
                first = a.get(15, TimeUnit.SECONDS);
                second = b.get(15, TimeUnit.SECONDS);
            }
            assertThat(List.of(first.statusCode(), second.statusCode())).containsExactlyInAnyOrder(201, 200);
            assertThat(data(first).path("orderId").asText()).isEqualTo(data(second).path("orderId").asText());
            assertThat(two.order(key, firstSale, 2).statusCode()).isEqualTo(201);
            String competing = UUID.randomUUID().toString();
            var gate2 = new CountDownLatch(1);
            var c = executor.submit(() -> {
                gate2.await();
                return one.order(competing, firstSale, 1);
            });
            var d = executor.submit(() -> {
                gate2.await();
                return one.order(competing, secondSale, 3);
            });
            gate2.countDown();
            var result1 = c.get(15, TimeUnit.SECONDS);
            var result2 = d.get(15, TimeUnit.SECONDS);
            assertThat(List.of(result1.statusCode(), result2.statusCode())).containsExactlyInAnyOrder(201, 409);
            assertThat(count("purchase_orders")).isEqualTo(3);
            assertThat(count("order_request_results")).isEqualTo(3);
            assertThat(count("sale_reservations")).isEqualTo(3);
            assertThat(count("payments")).isEqualTo(3);
        }
    }

    @Test
    void businessRejectionsReplayAfterStopLimitAndStockChange() throws Exception {
        long id = sale(2, 1);
        Client one = client("order1@example.com");
        Client two = client("order2@example.com");
        jdbc.update("UPDATE sales SET is_stopped=1 WHERE id=?", id);
        String stopped = UUID.randomUUID().toString();
        assertThat(code(one.order(stopped, id, 1))).isEqualTo("SALE_STOPPED");
        jdbc.update("UPDATE sales SET is_stopped=0 WHERE id=?", id);
        assertThat(code(one.order(stopped, id, 1))).isEqualTo("SALE_STOPPED");
        String limit = UUID.randomUUID().toString();
        assertThat(code(one.order(limit, id, 2))).isEqualTo("PURCHASE_LIMIT_EXCEEDED");
        jdbc.update("UPDATE sales SET per_member_limit=2 WHERE id=?", id);
        assertThat(code(one.order(limit, id, 2))).isEqualTo("PURCHASE_LIMIT_EXCEEDED");
        assertThat(one.order(UUID.randomUUID().toString(), id, 2).statusCode()).isEqualTo(201);
        String stock = UUID.randomUUID().toString();
        assertThat(code(two.order(stock, id, 1))).isEqualTo("SALE_SOLD_OUT");
        jdbc.update("UPDATE sales SET total_quantity=3 WHERE id=?", id);
        assertThat(code(two.order(stock, id, 1))).isEqualTo("SALE_SOLD_OUT");
        assertThat(two.order(UUID.randomUUID().toString(), id, 1).statusCode()).isEqualTo(201);
    }

    @Test
    void sameKeyConcurrentlyBelongsIndependentlyToDifferentMembers() throws Exception {
        long id = sale(2, 1);
        Client one = client("order1@example.com");
        Client two = client("order2@example.com");
        String key = UUID.randomUUID().toString();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var a = executor.submit(() -> {
                gate.await();
                return one.order(key, id, 1);
            });
            var b = executor.submit(() -> {
                gate.await();
                return two.order(key, id, 1);
            });
            gate.countDown();
            assertThat(a.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
            assertThat(b.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
        }
        assertThat(count("purchase_orders")).isEqualTo(2);
        assertThat(count("order_request_results")).isEqualTo(2);
    }

    @Test
    void concurrentDistinctKeysRespectOneMembersQuantityLimit() throws Exception {
        long id = sale(50, 3);
        Client client = client("order1@example.com");
        try (var executor = Executors.newFixedThreadPool(10)) {
            var gate = new CountDownLatch(1);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
            for (int i = 0; i < 10; i++) {
                futures.add(executor.submit(() -> {
                    gate.await();
                    return client.order(UUID.randomUUID().toString(), id, 1);
                }));
            }
            gate.countDown();
            int created = 0;
            int rejected = 0;
            for (var future : futures) {
                var response = future.get(20, TimeUnit.SECONDS);
                if (response.statusCode() == 201) {
                    created++;
                } else {
                    assertThat(response.statusCode()).isEqualTo(409);
                    assertThat(code(response)).isEqualTo("PURCHASE_LIMIT_EXCEEDED");
                    rejected++;
                }
            }
            assertThat(created).isEqualTo(3);
            assertThat(rejected).isEqualTo(7);
        }
        assertThat(jdbc.queryForObject("SELECT reserved_quantity FROM sales WHERE id=?", Integer.class, id))
            .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT reserved_quantity FROM sale_buyer_quantities "
            + "WHERE sale_id=? AND member_id=?", Integer.class, id, user1)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT SUM(quantity) FROM sale_reservations WHERE sale_id=?",
            Integer.class, id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT SUM(quantity) FROM purchase_orders WHERE sale_id=?",
            Integer.class, id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM payments", Long.class)).isEqualTo(3000);
        assertThat(count("purchase_orders")).isEqualTo(3);
        assertThat(count("sale_reservations")).isEqualTo(3);
        assertThat(count("payments")).isEqualTo(3);
        assertThat(count("order_request_results")).isEqualTo(10);
    }

    @Test
    void concurrentDistinctRequestsCanExhaustStockWithoutOverselling() throws Exception {
        long id = sale(7, 7);
        Client one = client("order1@example.com");
        Client two = client("order2@example.com");
        try (var executor = Executors.newFixedThreadPool(10)) {
            var gate = new CountDownLatch(1);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
            for (int i = 0; i < 10; i++) {
                Client c = i % 2 == 0 ? one : two;
                futures.add(executor.submit(() -> {
                    gate.await();
                    return c.order(UUID.randomUUID().toString(), id, 1);
                }));
            }
            gate.countDown();
            int success = 0;
            for (var future : futures) {
                var result = future.get(20, TimeUnit.SECONDS);
                if (result.statusCode() == 201) {
                    success++;
                } else {
                    assertThat(code(result)).isEqualTo("SALE_SOLD_OUT");
                }
            }
            assertThat(success).isEqualTo(7);
        }
        assertThat(jdbc.queryForObject("SELECT reserved_quantity FROM sales WHERE id=?", Integer.class, id))
            .isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT SUM(reserved_quantity) FROM sale_buyer_quantities WHERE sale_id=?",
            Integer.class, id)).isEqualTo(7);
        assertThat(count("purchase_orders")).isEqualTo(7);
        assertThat(count("sale_reservations")).isEqualTo(7);
        assertThat(count("payments")).isEqualTo(7);
    }

    @Test
    void sameKeyDifferentQuantitiesAndRejectedVersusCreatedRacePreserveWinner() throws Exception {
        long id = sale(20, 20);
        Client c = client("order1@example.com");
        try (var executor = Executors.newFixedThreadPool(2)) {
            String key = UUID.randomUUID().toString();
            var gate = new CountDownLatch(1);
            var a = executor.submit(() -> {
                gate.await();
                return c.order(key, id, 1);
            });
            var b = executor.submit(() -> {
                gate.await();
                return c.order(key, id, 2);
            });
            gate.countDown();
            var first = a.get(15, TimeUnit.SECONDS);
            var second = b.get(15, TimeUnit.SECONDS);
            assertThat(List.of(first.statusCode(), second.statusCode())).containsExactlyInAnyOrder(201, 409);
            String winner = first.statusCode() == 201 ? data(first).path("orderId").asText()
                : data(second).path("orderId").asText();
            int quantity = first.statusCode() == 201 ? 1 : 2;
            assertThat(data(c.order(key, id, quantity)).path("orderId").asText()).isEqualTo(winner);
            String next = UUID.randomUUID().toString();
            var gate2 = new CountDownLatch(1);
            var valid = executor.submit(() -> {
                gate2.await();
                return c.order(next, id, 1);
            });
            var missing = executor.submit(() -> {
                gate2.await();
                return c.order(next, 88888888, 1);
            });
            gate2.countDown();
            var validResult = valid.get(15, TimeUnit.SECONDS);
            var missingResult = missing.get(15, TimeUnit.SECONDS);
            assertThat(List.of(validResult.statusCode(), missingResult.statusCode()))
                .isIn(List.of(201, 409), List.of(409, 404));
            assertThat(c.order(next, id, 1).statusCode()).isEqualTo(validResult.statusCode() == 201 ? 200 : 409);
            assertThat(c.order(next, 88888888, 1).statusCode()).isEqualTo(missingResult.statusCode());
        }
        assertThat(count("order_request_results")).isEqualTo(2);
    }

    @Test
    void otherUniqueConstraintFailuresAreNotTreatedAsRequestConflicts() throws Exception {
        long id = sale(5, 5);
        Client c = client("order1@example.com");
        assertThat(c.order(UUID.randomUUID().toString(), id, 1).statusCode()).isEqualTo(201);
        jdbc.update("UPDATE payments SET pg_key=UNHEX('AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA')");
        try (Connection root = java.sql.DriverManager.getConnection(DB.getJdbcUrl(), "root", DB.getPassword());
                var statement = root.createStatement()) {
            statement.execute("SET GLOBAL log_bin_trust_function_creators=1");
        }
        jdbc.execute("CREATE TRIGGER fail_payment BEFORE INSERT ON payments FOR EACH ROW "
            + "SET NEW.pg_key=UNHEX('AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA')");
        String key = UUID.randomUUID().toString();
        try {
            assertThat(c.order(key, id, 2).statusCode()).isEqualTo(500);
        } finally {
            jdbc.execute("DROP TRIGGER fail_payment");
        }
        assertThat(count("purchase_orders")).isEqualTo(1);
        assertThat(count("order_request_results")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reserved_quantity FROM sales WHERE id=?", Integer.class, id))
            .isEqualTo(1);
        assertThat(c.order(key, id, 2).statusCode()).isEqualTo(201);
    }

    @Test
    void failureAfterQuantityWritesRollsBackEverythingAndAllowsRetry() throws Exception {
        long id = sale(5, 5);
        Client c = client("order1@example.com");
        String key = UUID.randomUUID().toString();
        try (Connection root = java.sql.DriverManager.getConnection(DB.getJdbcUrl(), "root", DB.getPassword());
                var statement = root.createStatement()) {
            statement.execute("SET GLOBAL log_bin_trust_function_creators=1");
        }
        jdbc.execute("CREATE TRIGGER fail_payment BEFORE INSERT ON payments FOR EACH ROW "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected payment failure'");
        try {
            assertThat(c.order(key, id, 3).statusCode()).isEqualTo(500);
        } finally {
            jdbc.execute("DROP TRIGGER fail_payment");
        }
        assertThat(jdbc.queryForObject("SELECT reserved_quantity FROM sales WHERE id=?", Integer.class, id)).isZero();
        assertThat(count("sale_buyer_quantities")).isZero();
        assertThat(count("purchase_orders")).isZero();
        assertThat(count("sale_reservations")).isZero();
        assertThat(count("payments")).isZero();
        assertThat(count("order_request_results")).isZero();
        assertThat(c.order(key, id, 3).statusCode()).isEqualTo(201);
    }

    @Test
    void locksBeforeReadingDatabaseTimeAndObservesConcurrentStop() throws Exception {
        long expired = sale(5, 5);
        long stopped = sale(5, 5);
        Client c = client("order1@example.com");
        jdbc.update("UPDATE sales SET ends_at=UTC_TIMESTAMP(6)+INTERVAL 8 SECOND WHERE id=?", expired);
        Instant endsAt = Instant.parse(jdbc.queryForObject(
            "SELECT DATE_FORMAT(ends_at, '%Y-%m-%dT%H:%i:%s.%fZ') FROM sales WHERE id=?", String.class, expired));
        try (var executor = Executors.newSingleThreadExecutor();
                Connection connection = context.getBean(DataSource.class).getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM sales WHERE id=? FOR UPDATE")) {
                statement.setLong(1, expired);
                statement.executeQuery().close();
            }
            var result = executor.submit(() -> c.order(UUID.randomUUID().toString(), expired, 1));
            awaitSaleLockWaiters(1);
            assertThat(result.isDone()).isFalse();
            assertThat(databaseTime()).isBefore(endsAt);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
            while (databaseTime().isBefore(endsAt.plusMillis(50)) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(databaseTime()).isAfterOrEqualTo(endsAt);
            connection.commit();
            assertThat(code(result.get(15, TimeUnit.SECONDS))).isEqualTo("SALE_OUTSIDE_PERIOD");
            try (var statement = connection.prepareStatement("SELECT id FROM sales WHERE id=? FOR UPDATE")) {
                statement.setLong(1, stopped);
                statement.executeQuery().close();
            }
            var stopResult = executor.submit(() -> c.order(UUID.randomUUID().toString(), stopped, 1));
            awaitSaleLockWaiters(1);
            assertThat(stopResult.isDone()).isFalse();
            try (var statement = connection.prepareStatement("UPDATE sales SET is_stopped=1 WHERE id=?")) {
                statement.setLong(1, stopped);
                statement.executeUpdate();
            }
            connection.commit();
            assertThat(code(stopResult.get(15, TimeUnit.SECONDS))).isEqualTo("SALE_STOPPED");
        }
        assertThat(count("purchase_orders")).isZero();
        assertThat(count("sale_buyer_quantities")).isZero();
    }
}
