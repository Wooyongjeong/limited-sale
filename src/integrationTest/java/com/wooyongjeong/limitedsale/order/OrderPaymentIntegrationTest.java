package com.wooyongjeong.limitedsale.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wooyongjeong.limitedsale.LimitedSaleApplication;
import com.wooyongjeong.limitedsale.member.application.MemberService;
import com.wooyongjeong.limitedsale.product.api.ProductCommand;
import com.wooyongjeong.mockpg.MockPgApplication;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class OrderPaymentIntegrationTest {
    private static final MySQLContainer<?> SALES = new MySQLContainer<>("mysql:8.4.7")
            .withCommand("--log-bin-trust-function-creators=1");
    private static final MySQLContainer<?> PG = new MySQLContainer<>("mysql:8.4.7");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static ConfigurableApplicationContext pgContext;
    private static ConfigurableApplicationContext salesContext;
    private static JdbcTemplate sales;
    private static JdbcTemplate pg;
    private static int port;
    private static int pgPort;

    @BeforeAll
    static void start() {
        SALES.start();
        PG.start();
        REDIS.start();
        pgContext = new SpringApplicationBuilder(MockPgApplication.class).run("--server.port=0",
                "--mock-pg.api-key=test-secret", "--spring.config.location=file:mock-pg/src/main/resources/application.yml",
                "--spring.flyway.locations=filesystem:mock-pg/src/main/resources/db/migration",
                "--spring.datasource.url=" + PG.getJdbcUrl(),
                "--spring.datasource.username=" + PG.getUsername(), "--spring.datasource.password=" + PG.getPassword(),
                "--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
                        + "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration");
        pgPort = ((ServletWebServerApplicationContext) pgContext).getWebServer().getPort();
        pg = pgContext.getBean(JdbcTemplate.class);
        salesContext = new SpringApplicationBuilder(LimitedSaleApplication.class).run("--server.port=0",
                "--spring.profiles.active=test", "--spring.flyway.enabled=true", "--spring.jpa.hibernate.ddl-auto=validate",
                "--MOCK_PG_API_KEY=test-secret",
                "--spring.flyway.locations=filesystem:src/main/resources/db/migration,classpath:db/nickmigration",
                "--MOCK_PG_BASE_URL=http://localhost:" + pgPort, "--spring.datasource.url=" + SALES.getJdbcUrl(),
                "--spring.datasource.username=" + SALES.getUsername(), "--spring.datasource.password=" + SALES.getPassword(),
                "--spring.data.redis.host=" + REDIS.getHost(), "--spring.data.redis.port=" + REDIS.getMappedPort(6379));
        port = ((ServletWebServerApplicationContext) salesContext).getWebServer().getPort();
        sales = salesContext.getBean(JdbcTemplate.class);
        var members = salesContext.getBean(MemberService.class);
        members.register("admin-sim@example.com", "password", "adminsim");
        members.register("user-sim@example.com", "password", "usersim");
        sales.update("UPDATE members SET role='ADMIN' WHERE email='admin-sim@example.com'");
        salesContext.getBean(ProductCommand.class).register("simulation product", "", 1000L);
    }

    @AfterAll
    static void stop() {
        if (salesContext != null) {
            salesContext.close();
        }
        if (pgContext != null) {
            pgContext.close();
        }
        REDIS.stop();
        SALES.stop();
        PG.stop();
    }

    private record Fixture(Client client, String order, long sale, String key) {
    }

    private Fixture fixture() throws Exception {
        Client client = new Client();
        client.login("user-sim@example.com");
        long product = sales.queryForObject("SELECT MIN(id) FROM products", Long.class);
        sales.update("INSERT INTO sales(product_id,name,description,product_name_snapshot,unit_price,starts_at,ends_at,"
                + "total_quantity,per_member_limit,created_at,updated_at) VALUES (?, 'sale', '', 'product', 1000, "
                + "UTC_TIMESTAMP(6)-INTERVAL 1 HOUR,UTC_TIMESTAMP(6)+INTERVAL 1 HOUR,10,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", product);
        long sale = sales.queryForObject("SELECT MAX(id) FROM sales", Long.class);
        var created = client.order(sale);
        assertThat(created.statusCode()).isEqualTo(201);
        String order = JSON.readTree(created.body()).path("data").path("orderId").asText();
        String hex = sales.queryForObject("SELECT HEX(p.pg_key) FROM payments p JOIN purchase_orders o ON o.id=p.order_id "
                + "WHERE o.public_id=UNHEX(REPLACE(?, '-', ''))", String.class, order);
        String key = hex.replaceFirst("(........)(....)(....)(....)(............)", "$1-$2-$3-$4-$5").toLowerCase();
        return new Fixture(client, order, sale, key);
    }

    private void scenario(Fixture fixture, String scenario, Integer delay) throws Exception {
        String body = "{\"scenario\":\"" + scenario + "\"" + (delay == null ? "" : ",\"delaySeconds\":" + delay) + "}";
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + pgPort + "/api/v1/simulations/" + fixture.key()))
                .header("X-Mock-PG-Key", "test-secret").header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
        assertThat(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> pay(Fixture fixture) throws Exception {
        return fixture.client().request("POST", "/api/v1/orders/" + fixture.order() + "/payment", null,
                fixture.client().token);
    }

    private void expire(Fixture fixture) {
        sales.update("UPDATE purchase_orders SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND "
                + "WHERE public_id=UNHEX(REPLACE(?, '-', ''))", fixture.order());
    }

    private int pgCount(Fixture fixture) {
        return pg.queryForObject("SELECT COUNT(*) FROM mock_payments WHERE pg_key=UNHEX(REPLACE(?, '-', ''))",
                Integer.class, fixture.key());
    }

    private void quantities(Fixture fixture, int reserved, int sold) {
        assertThat(sales.queryForObject("SELECT reserved_quantity FROM sales WHERE id=?", Integer.class,
                fixture.sale())).isEqualTo(reserved);
        assertThat(sales.queryForObject("SELECT sold_quantity FROM sales WHERE id=?", Integer.class,
                fixture.sale())).isEqualTo(sold);
        assertThat(sales.queryForObject("SELECT reserved_quantity FROM sale_buyer_quantities WHERE sale_id=?",
                Integer.class, fixture.sale())).isEqualTo(reserved);
        assertThat(sales.queryForObject("SELECT purchased_quantity FROM sale_buyer_quantities WHERE sale_id=?",
                Integer.class, fixture.sale())).isEqualTo(sold);
        String status = reserved > 0 ? "RESERVED" : sold > 0 ? "CONFIRMED" : "RELEASED";
        assertThat(sales.queryForObject("SELECT status FROM sale_reservations WHERE sale_id=?", String.class,
                fixture.sale())).isEqualTo(status);
    }

    @Test
    void successFinalizesOnceAndReplaysWithoutCallingPg() throws Exception {
        Fixture f = fixture();
        var first = pay(f);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(first.body()).path("data").path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(JSON.readTree(first.body()).path("data").path("paymentStatus").asText()).isEqualTo("SUCCEEDED");
        quantities(f, 0, 1);
        assertThat(pay(f).statusCode()).isEqualTo(200);
        quantities(f, 0, 1);
        assertThat(pgCount(f)).isEqualTo(1);
    }

    @Test
    void declineAndExpiryReleaseOnlyAfterPgConfirmation() throws Exception {
        Fixture declined = fixture();
        scenario(declined, "DECLINE", null);
        var rejected = pay(declined);
        assertThat(rejected.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(rejected.body()).path("data").isNull()).isTrue();
        assertThat(JSON.readTree(pay(declined).body()).path("code").asText()).isEqualTo("PAYMENT_DECLINED");
        quantities(declined, 0, 0);
        Fixture expired = fixture();
        expire(expired);
        var result = pay(expired);
        assertThat(result.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(result.body()).path("code").asText()).isEqualTo("ORDER_EXPIRED");
        assertThat(JSON.readTree(result.body()).path("data").isNull()).isTrue();
        quantities(expired, 0, 0);
        assertThat(pg.queryForObject("SELECT status FROM mock_payments WHERE pg_key=UNHEX(REPLACE(?, '-', ''))", String.class, expired.key())).isEqualTo("CLOSED");
    }

    @Test
    void delayedResultRetainsStockAndRetryIntervalUntilExpiry() throws Exception {
        Fixture f = fixture();
        scenario(f, "DELAY", 60);
        assertThat(pay(f).statusCode()).isEqualTo(202);
        quantities(f, 1, 0);
        assertThat(pay(f).statusCode()).isEqualTo(202);
        quantities(f, 1, 0);
        expire(f);
        assertThat(pay(f).statusCode()).isEqualTo(409);
        quantities(f, 0, 0);
    }

    @Test
    void savedSuccessIsReappliedAfterLocalFinalizationFailure() throws Exception {
        Fixture f = fixture();
        sales.execute("CREATE TRIGGER fail_payment_finish BEFORE UPDATE ON sale_reservations FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test failure'");
        try {
            assertThat(pay(f).statusCode()).isEqualTo(500);
            quantities(f, 1, 0);
        } finally {
            sales.execute("DROP TRIGGER fail_payment_finish");
        }
        assertThat(pay(f).statusCode()).isEqualTo(200);
        quantities(f, 0, 1);
    }

    @Test
    void responseLossPreservesReservationUntilQueryAfterRetryInterval() throws Exception {
        Fixture f = fixture();
        scenario(f, "RESPONSE_LOSS", 4);
        assertThat(pay(f).statusCode()).isEqualTo(202);
        quantities(f, 1, 0);
        assertThat(pay(f).statusCode()).isEqualTo(202);
        quantities(f, 1, 0);
        sales.update("UPDATE purchase_orders SET next_recovery_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND "
                + "WHERE public_id=UNHEX(REPLACE(?, '-', ''))", f.order());
        assertThat(pay(f).statusCode()).isEqualTo(200);
        quantities(f, 0, 1);
    }

    @Test
    void expiredOrderIsConfirmedWhenPgAlreadySucceeded() throws Exception {
        Fixture f = fixture();
        scenario(f, "RESPONSE_LOSS", 4);
        assertThat(pay(f).statusCode()).isEqualTo(202);
        quantities(f, 1, 0);
        expire(f);
        assertThat(pay(f).statusCode()).isEqualTo(200);
        quantities(f, 0, 1);
    }

    @Test
    void query404BeforeExpiryReusesTheSameKeyForCharge() throws Exception {
        Fixture f = fixture();
        sales.update("UPDATE purchase_orders SET status='PAYMENT_PENDING',next_recovery_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND "
                + "WHERE public_id=UNHEX(REPLACE(?, '-', ''))", f.order());
        sales.update("UPDATE payments p JOIN purchase_orders o ON p.order_id=o.id SET p.status='UNKNOWN' "
                + "WHERE o.public_id=UNHEX(REPLACE(?, '-', ''))", f.order());
        assertThat(pay(f).statusCode()).isEqualTo(200);
        quantities(f, 0, 1);
        assertThat(pgCount(f)).isEqualTo(1);
    }

    @Test
    void parallelCallsFinalizeExactlyOnceAndLateUnknownCannotReverseIt() throws Exception {
        Fixture f = fixture();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var latch = new java.util.concurrent.CountDownLatch(1);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    latch.await();
                    return pay(f).statusCode();
                }));
            }
            latch.countDown();
            for (var future : futures) {
                assertThat(future.get()).isIn(200, 202);
            }
        }
        assertThat(pay(f).statusCode()).isEqualTo(200);
        quantities(f, 0, 1);
        long id = sales.queryForObject("SELECT id FROM purchase_orders WHERE public_id=UNHEX(REPLACE(?, '-', ''))", Long.class, f.order());
        salesContext.getBean(com.wooyongjeong.limitedsale.order.application.OrderPaymentTransaction.class)
                .record(id, com.wooyongjeong.limitedsale.payment.api.GatewayResult.UNKNOWN);
        assertThat(pay(f).statusCode()).isEqualTo(200);
        quantities(f, 0, 1);
        assertThat(sales.queryForObject("SELECT status FROM payments WHERE order_id=?", String.class, id))
                .isEqualTo("SUCCEEDED");
        assertThat(pgCount(f)).isEqualTo(1);
    }

    @Test
    void failedCommunicationDoesNotReleaseAnExpiredOrder() throws Exception {
        Fixture f = fixture();
        var environment = (org.springframework.core.env.ConfigurableEnvironment) salesContext.getEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("unreachablePg",
                java.util.Map.of("MOCK_PG_BASE_URL", "http://127.0.0.1:1")));
        try {
            assertThat(pay(f).statusCode()).isEqualTo(202);
            quantities(f, 1, 0);
            expire(f);
            assertThat(pay(f).statusCode()).isEqualTo(202);
            quantities(f, 1, 0);
        } finally {
            environment.getPropertySources().remove("unreachablePg");
        }
    }

    @Test
    void lateProcessingResponseCannotReverseAConfirmedClose() throws Exception {
        Fixture f = fixture();
        var receivedCharge = new java.util.concurrent.CountDownLatch(1);
        var releaseCharge = new java.util.concurrent.CountDownLatch(1);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var httpWorkers = java.util.concurrent.Executors.newFixedThreadPool(2);
        server.setExecutor(httpWorkers);
        server.createContext("/api/v1/payments", exchange -> {
            boolean close = exchange.getRequestURI().getPath().endsWith("/close");
            if (!close) {
                receivedCharge.countDown();
                try {
                    releaseCharge.await(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            String state = close ? "CLOSED" : "PROCESSING";
            int code = close ? 200 : 202;
            String finalized = close ? "\"2026-01-01T00:00:00Z\"" : "null";
            String body = "{\"httpStatus\":" + code + ",\"code\":\"" + (close ? "OK" : "PROCESSING")
                    + "\",\"data\":{\"pgKey\":\"" + f.key() + "\",\"amount\":1000,\"status\":\"" + state
                    + "\",\"createdAt\":\"2026-01-01T00:00:00Z\",\"updatedAt\":\"2026-01-01T00:00:00Z\",\"finalizedAt\":"
                    + finalized + "},\"message\":\"ok\"}";
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var environment = (org.springframework.core.env.ConfigurableEnvironment) salesContext.getEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("delayedPg",
                java.util.Map.of("MOCK_PG_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort())));
        try (var requests = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var late = requests.submit(() -> pay(f).statusCode());
            assertThat(receivedCharge.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            expire(f);
            assertThat(pay(f).statusCode()).isEqualTo(409);
            releaseCharge.countDown();
            assertThat(late.get()).isEqualTo(409);
            quantities(f, 0, 0);
            assertThat(pay(f).statusCode()).isEqualTo(409);
            assertThat(sales.queryForObject("SELECT p.status FROM payments p JOIN purchase_orders o ON o.id=p.order_id WHERE o.public_id=UNHEX(REPLACE(?, '-', ''))", String.class, f.order())).isEqualTo("CLOSED");
        } finally {
            releaseCharge.countDown();
            environment.getPropertySources().remove("delayedPg");
            server.stop(0);
            httpWorkers.shutdownNow();
        }
    }

    @Test
    void retryIntervalIsSharedByAnotherApplicationInstance() throws Exception {
        Fixture f = fixture();
        scenario(f, "RESPONSE_LOSS", 4);
        assertThat(pay(f).statusCode()).isEqualTo(202);
        assertThat(sales.queryForObject("SELECT TIMESTAMPDIFF(MICROSECOND, p.requested_at, o.next_recovery_at) "
                + "FROM payments p JOIN purchase_orders o ON o.id=p.order_id "
                + "WHERE o.public_id=UNHEX(REPLACE(?, '-', ''))", Long.class, f.order())).isEqualTo(10_000_000L);
        sales.update("UPDATE purchase_orders SET next_recovery_at=UTC_TIMESTAMP(6)+INTERVAL 60 SECOND "
                + "WHERE public_id=UNHEX(REPLACE(?, '-', ''))", f.order());
        var before = sales.queryForObject("SELECT next_recovery_at FROM purchase_orders WHERE public_id=UNHEX(REPLACE(?, '-', ''))", java.sql.Timestamp.class, f.order());
        int originalPort = port;
        try (var second = new SpringApplicationBuilder(LimitedSaleApplication.class).run("--server.port=0",
                "--spring.profiles.active=test", "--spring.flyway.enabled=true", "--spring.jpa.hibernate.ddl-auto=validate",
                "--MOCK_PG_API_KEY=test-secret", "--MOCK_PG_BASE_URL=http://localhost:" + pgPort,
                "--spring.flyway.locations=filesystem:src/main/resources/db/migration,classpath:db/nickmigration",
                "--spring.datasource.url=" + SALES.getJdbcUrl(), "--spring.datasource.username=" + SALES.getUsername(),
                "--spring.datasource.password=" + SALES.getPassword(), "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379))) {
            port = ((ServletWebServerApplicationContext) second).getWebServer().getPort();
            assertThat(pay(f).statusCode()).isEqualTo(202);
            assertThat(sales.queryForObject("SELECT next_recovery_at FROM purchase_orders WHERE public_id=UNHEX(REPLACE(?, '-', ''))", java.sql.Timestamp.class, f.order())).isEqualTo(before);
            quantities(f, 1, 0);
        } finally {
            port = originalPort;
        }
    }

    @Test
    void pgCommunicationRefusesAnEnclosingTransaction() {
        var gateway = salesContext.getBean(com.wooyongjeong.limitedsale.payment.api.PaymentGateway.class);
        var transactions = salesContext.getBean(org.springframework.transaction.support.TransactionTemplate.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transactions.execute(status -> {
            gateway.query(new com.wooyongjeong.limitedsale.payment.api.PaymentDetails(UUID.randomUUID(), 1000, "PROCESSING"),
                    java.time.Duration.ofSeconds(1));
            return null;
        })).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void endpointRequiresOwnershipAndCsrfAndReadDoesNotCharge() throws Exception {
        Fixture f = fixture();
        String path = "/api/v1/orders/" + f.order() + "/payment";
        assertThat(new Client().request("POST", path, null, null).statusCode()).isIn(401, 403);
        assertThat(f.client().request("POST", path, null, null).statusCode()).isEqualTo(403);
        assertThat(f.client().request("POST", path, "{\"amount\":1}", f.client().token).statusCode()).isEqualTo(400);
        Client admin = new Client();
        admin.login("admin-sim@example.com");
        assertThat(admin.request("POST", path, null, admin.token).statusCode()).isEqualTo(404);
        var get = f.client().request("GET", "/api/v1/orders/" + f.order(), null, null);
        assertThat(JSON.readTree(get.body()).path("data").path("paymentStatus").asText()).isEqualTo("READY");
        assertThat(pgCount(f)).isZero();
    }

    private static final class Client {
        private final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        private String token;

        HttpResponse<String> request(String method, String path, String body, String csrf) throws Exception {
            return request(method, path, body, csrf, "application/json");
        }

        HttpResponse<String> request(String method, String path, String body, String csrf, String contentType)
                throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (csrf != null) {
                builder.header("X-CSRF-TOKEN", csrf);
            }
            if (body != null) {
                builder.header("Content-Type", contentType);
            }
            return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> order(long saleId) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/orders"))
                    .header("X-CSRF-TOKEN", token).header("Content-Type", "application/json")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofString("{\"saleId\":" + saleId + ",\"quantity\":1}"))
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        void login(String email) throws Exception {
            token = JSON.readTree(request("GET", "/api/v1/csrf", null, null).body()).path("data").path("token").asText();
            assertThat(request("POST", "/api/v1/sessions", "{\"email\":\"" + email
                    + "\",\"password\":\"password\"}", token).statusCode()).isEqualTo(200);
            token = JSON.readTree(request("GET", "/api/v1/csrf", null, null).body()).path("data").path("token").asText();
        }
    }
}
