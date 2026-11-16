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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderPaymentIntegrationTest {
    private static final MySQLContainer<?> SALES = new MySQLContainer<>("mysql:8.4.7")
            .withCommand("--log-bin-trust-function-creators=1");
    private static final MySQLContainer<?> PG = new MySQLContainer<>("mysql:8.4.7")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");
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
                "--spring.profiles.active=test", "--limited-sale.recovery.enabled=false", "--spring.flyway.enabled=true", "--spring.jpa.hibernate.ddl-auto=validate",
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
        assertThat(pay(f).statusCode()).isEqualTo(202);
        sales.update("UPDATE purchase_orders SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND "
                + "WHERE public_id=UNHEX(REPLACE(?, '-', ''))", f.order());
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
        assertThat(salesContext.getBean(com.wooyongjeong.limitedsale.order.application.OrderPaymentTransaction.class)
                .record(id, UUID.randomUUID(), com.wooyongjeong.limitedsale.payment.api.GatewayResult.UNKNOWN)).isFalse();
        assertThat(pay(f).statusCode()).isEqualTo(200);
        quantities(f, 0, 1);
        assertThat(sales.queryForObject("SELECT status FROM payments WHERE order_id=?", String.class, id))
                .isEqualTo("SUCCEEDED");
        assertThat(pgCount(f)).isEqualTo(1);
    }

    @Test
    void scheduledExpiryClosesAnUnrequestedOrder() throws Exception {
        Fixture f = fixture();
        expire(f);
        sales.update("UPDATE purchase_orders SET next_recovery_at=UTC_TIMESTAMP(6)+INTERVAL 60 SECOND "
                + "WHERE public_id=UNHEX(REPLACE(?, '-', ''))", f.order());
        var scheduler = new com.wooyongjeong.limitedsale.order.application.OrderRecoveryScheduler(
                salesContext.getBean(com.wooyongjeong.limitedsale.order.application.OrderPaymentTransaction.class),
                salesContext.getBean(com.wooyongjeong.limitedsale.order.application.OrderPaymentService.class), 20, 4);
        try {
            scheduler.poll();
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
            String status;
            do {
                status = sales.queryForObject("SELECT status FROM purchase_orders "
                        + "WHERE public_id=UNHEX(REPLACE(?, '-', ''))", String.class, f.order());
                if (!"EXPIRED".equals(status)) {
                    Thread.sleep(50);
                }
            } while (!"EXPIRED".equals(status) && System.nanoTime() < deadline);
            assertThat(status).isEqualTo("EXPIRED");
            quantities(f, 0, 0);
            assertThat(pgCount(f)).isEqualTo(1);
            assertThat(pg.queryForObject("SELECT status FROM mock_payments WHERE pg_key=UNHEX(REPLACE(?, '-', ''))",
                    String.class, f.key())).isEqualTo("CLOSED");
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void recoveryRetainsStockDuringOutageAndConvergesAfterPgReturns() throws Exception {
        Fixture f = fixture();
        expire(f);
        long id = sales.queryForObject("SELECT id FROM purchase_orders WHERE public_id=UNHEX(REPLACE(?, '-', ''))",
                Long.class, f.order());
        sales.update("UPDATE purchase_orders SET next_recovery_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", id);
        var environment = (org.springframework.core.env.ConfigurableEnvironment) salesContext.getEnvironment();
        var service = salesContext.getBean(com.wooyongjeong.limitedsale.order.application.OrderPaymentService.class);
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("recoveryOutage",
                java.util.Map.of("MOCK_PG_BASE_URL", "http://127.0.0.1:1")));
        try {
            service.recover(id);
            quantities(f, 1, 0);
            assertThat(sales.queryForObject("SELECT requires_review FROM purchase_orders WHERE id=?", Boolean.class, id))
                    .isTrue();
            var next = sales.queryForObject("SELECT next_recovery_at FROM purchase_orders WHERE id=?", java.sql.Timestamp.class, id);
            var warning = sales.queryForObject("SELECT next_review_warning_at FROM purchase_orders WHERE id=?", java.sql.Timestamp.class, id);
            service.recover(id);
            assertThat(sales.queryForObject("SELECT next_recovery_at FROM purchase_orders WHERE id=?", java.sql.Timestamp.class, id))
                    .isEqualTo(next);
            sales.update("UPDATE purchase_orders SET next_recovery_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", id);
            service.recover(id);
            assertThat(sales.queryForObject("SELECT next_review_warning_at FROM purchase_orders WHERE id=?", java.sql.Timestamp.class, id))
                    .isEqualTo(warning);
            sales.update("UPDATE purchase_orders SET next_recovery_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND, "
                    + "next_review_warning_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", id);
            service.recover(id);
            assertThat(sales.queryForObject("SELECT next_review_warning_at > UTC_TIMESTAMP(6) + INTERVAL 4 MINUTE "
                    + "FROM purchase_orders WHERE id=?", Boolean.class, id)).isTrue();
            assertThat(sales.queryForObject("SELECT next_recovery_at > UTC_TIMESTAMP(6) + INTERVAL 8 SECOND "
                    + "FROM purchase_orders WHERE id=?", Boolean.class, id)).isTrue();
            quantities(f, 1, 0);
        } finally {
            environment.getPropertySources().remove("recoveryOutage");
        }
        sales.update("UPDATE purchase_orders SET next_recovery_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", id);
        service.recover(id);
        quantities(f, 0, 0);
        assertThat(sales.queryForObject("SELECT status FROM purchase_orders WHERE id=?", String.class, id)).isEqualTo("EXPIRED");
        assertThat(sales.queryForObject("SELECT requires_review FROM purchase_orders WHERE id=?", Boolean.class, id)).isFalse();
        assertThat(sales.queryForObject("SELECT next_review_warning_at FROM purchase_orders WHERE id=?", java.sql.Timestamp.class, id))
                .isNull();
    }

    @Test
    void recoveryAppliesADeclineWithoutAnotherUserRequest() throws Exception {
        Fixture f = fixture();
        scenario(f, "DECLINE", null);
        var gateway = salesContext.getBean(com.wooyongjeong.limitedsale.payment.api.PaymentGateway.class);
        var payment = new com.wooyongjeong.limitedsale.payment.api.PaymentDetails(UUID.fromString(f.key()), 1000, "PROCESSING");
        assertThat(gateway.charge(payment, java.time.Duration.ofSeconds(3)))
                .isEqualTo(com.wooyongjeong.limitedsale.payment.api.GatewayResult.FAILED);
        sales.update("UPDATE purchase_orders SET status='PAYMENT_PENDING', next_recovery_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND "
                + "WHERE public_id=UNHEX(REPLACE(?, '-', ''))", f.order());
        sales.update("UPDATE payments p JOIN purchase_orders o ON p.order_id=o.id SET p.status='PROCESSING', "
                + "p.requested_at=UTC_TIMESTAMP(6) WHERE o.public_id=UNHEX(REPLACE(?, '-', ''))", f.order());
        long id = sales.queryForObject("SELECT id FROM purchase_orders WHERE public_id=UNHEX(REPLACE(?, '-', ''))",
                Long.class, f.order());
        salesContext.getBean(com.wooyongjeong.limitedsale.order.application.OrderPaymentService.class).recover(id);
        assertThat(sales.queryForObject("SELECT status FROM purchase_orders WHERE id=?", String.class, id))
                .isEqualTo("PAYMENT_FAILED");
        quantities(f, 0, 0);
        assertThat(pgCount(f)).isEqualTo(1);
    }

    @Test
    void recoveryContinuesDelayedPaymentUntilItsFinalResult() throws Exception {
        Fixture f = fixture();
        scenario(f, "DELAY", 60);
        assertThat(pay(f).statusCode()).isEqualTo(202);
        long id = sales.queryForObject("SELECT id FROM purchase_orders WHERE public_id=UNHEX(REPLACE(?, '-', ''))",
                Long.class, f.order());
        sales.update("UPDATE purchase_orders SET next_recovery_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", id);
        var service = salesContext.getBean(com.wooyongjeong.limitedsale.order.application.OrderPaymentService.class);
        service.recover(id);
        quantities(f, 1, 0);
        assertThat(sales.queryForObject("SELECT next_recovery_at > UTC_TIMESTAMP(6) + INTERVAL 8 SECOND "
                + "FROM purchase_orders WHERE id=?", Boolean.class, id)).isTrue();
        pg.update("UPDATE mock_payments SET completion_due_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND "
                + "WHERE pg_key=UNHEX(REPLACE(?, '-', ''))", f.key());
        var nowFromJdbc = pg.queryForObject("SELECT UTC_TIMESTAMP(6)", java.sql.Timestamp.class).toInstant();
        var dueAsUtc = java.time.Instant.parse(pg.queryForObject("SELECT DATE_FORMAT(completion_due_at, "
                + "'%Y-%m-%dT%H:%i:%s.%fZ') FROM mock_payments WHERE pg_key=UNHEX(REPLACE(?, '-', ''))",
                String.class, f.key()));
        assertThat(nowFromJdbc).isAfter(dueAsUtc);
        sales.update("UPDATE purchase_orders SET next_recovery_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", id);
        service.recover(id);
        assertThat(pg.queryForObject("SELECT status FROM mock_payments WHERE pg_key=UNHEX(REPLACE(?, '-', ''))",
                String.class, f.key())).isEqualTo("SUCCEEDED");
        assertThat(sales.queryForObject("SELECT status FROM payments WHERE order_id=?", String.class, id))
                .isEqualTo("SUCCEEDED");
        quantities(f, 0, 1);
        assertThat(pgCount(f)).isEqualTo(1);
    }

    @Test
    void interruptedAttemptsConvergeAfterLeaseAndPgSuccessBoundaries() throws Exception {
        Fixture afterLease = fixture();
        Fixture afterPgSuccess = fixture();
        var transaction = salesContext.getBean(com.wooyongjeong.limitedsale.order.application.OrderPaymentTransaction.class);
        var gateway = salesContext.getBean(com.wooyongjeong.limitedsale.payment.api.PaymentGateway.class);
        for (Fixture f : java.util.List.of(afterLease, afterPgSuccess)) {
            boolean sendCharge = f == afterPgSuccess;
            var interrupted = new com.wooyongjeong.limitedsale.payment.api.PaymentGateway() {
                @Override
                public com.wooyongjeong.limitedsale.payment.api.GatewayResult charge(
                        com.wooyongjeong.limitedsale.payment.api.PaymentDetails payment, java.time.Duration timeout) {
                    if (sendCharge) {
                        assertThat(gateway.charge(payment, timeout))
                                .isEqualTo(com.wooyongjeong.limitedsale.payment.api.GatewayResult.SUCCEEDED);
                    }
                    throw new IllegalStateException("simulated interruption");
                }

                @Override
                public com.wooyongjeong.limitedsale.payment.api.GatewayResult query(
                        com.wooyongjeong.limitedsale.payment.api.PaymentDetails payment, java.time.Duration timeout) {
                    return gateway.query(payment, timeout);
                }

                @Override
                public com.wooyongjeong.limitedsale.payment.api.GatewayResult close(
                        com.wooyongjeong.limitedsale.payment.api.PaymentDetails payment, java.time.Duration timeout) {
                    return gateway.close(payment, timeout);
                }
            };
            var service = new com.wooyongjeong.limitedsale.order.application.OrderPaymentService(
                    transaction, interrupted, java.time.Duration.ofSeconds(3));
            long member = sales.queryForObject("SELECT member_id FROM purchase_orders "
                    + "WHERE public_id=UNHEX(REPLACE(?, '-', ''))", Long.class, f.order());
            assertThatThrownBy(() -> service.pay(member, UUID.fromString(f.order())))
                    .isInstanceOf(IllegalStateException.class);
            quantities(f, 1, 0);
            assertThat(pgCount(f)).isEqualTo(sendCharge ? 1 : 0);
            sales.update("UPDATE purchase_orders SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND, "
                    + "next_recovery_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND "
                    + "WHERE public_id=UNHEX(REPLACE(?, '-', ''))", f.order());
            long id = sales.queryForObject("SELECT id FROM purchase_orders WHERE public_id=UNHEX(REPLACE(?, '-', ''))",
                    Long.class, f.order());
            salesContext.getBean(com.wooyongjeong.limitedsale.order.application.OrderPaymentService.class).recover(id);
            quantities(f, 0, 1);
            assertThat(pgCount(f)).isEqualTo(1);
        }
    }

    @Test
    void restartedNodeRecoversPersistedLeaseAndPaymentBoundaries() throws Exception {
        Fixture afterLease = fixture();
        Fixture afterPgSuccess = fixture();
        Fixture afterPaymentSaved = fixture();
        Fixture underReview = fixture();
        expire(underReview);
        sales.update("UPDATE purchase_orders SET status='PAYMENT_PENDING', requires_review=true, "
                + "next_review_warning_at=UTC_TIMESTAMP(6)+INTERVAL 5 MINUTE, "
                + "next_recovery_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND "
                + "WHERE public_id=UNHEX(REPLACE(?, '-', ''))", underReview.order());
        var warning = sales.queryForObject("SELECT next_review_warning_at FROM purchase_orders "
                + "WHERE public_id=UNHEX(REPLACE(?, '-', ''))", java.sql.Timestamp.class, underReview.order());
        var gateway = salesContext.getBean(com.wooyongjeong.limitedsale.payment.api.PaymentGateway.class);
        for (Fixture f : java.util.List.of(afterPgSuccess, afterPaymentSaved)) {
            var details = new com.wooyongjeong.limitedsale.payment.api.PaymentDetails(UUID.fromString(f.key()), 1000, "PROCESSING");
            assertThat(gateway.charge(details, java.time.Duration.ofSeconds(3)))
                    .isEqualTo(com.wooyongjeong.limitedsale.payment.api.GatewayResult.SUCCEEDED);
        }
        for (Fixture f : java.util.List.of(afterLease, afterPgSuccess, afterPaymentSaved)) {
            sales.update("UPDATE purchase_orders SET status='PAYMENT_PENDING', next_recovery_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND, "
                    + "lease_token=UUID_TO_BIN(UUID()), lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND "
                    + "WHERE public_id=UNHEX(REPLACE(?, '-', ''))", f.order());
            sales.update("UPDATE payments p JOIN purchase_orders o ON o.id=p.order_id SET p.status=?, "
                    + "p.requested_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND "
                    + "WHERE o.public_id=UNHEX(REPLACE(?, '-', ''))", f == afterPaymentSaved ? "SUCCEEDED" : "PROCESSING", f.order());
        }
        String[] arguments = {"--server.port=0", "--spring.profiles.active=test", "--limited-sale.recovery.enabled=false",
                "--spring.flyway.enabled=true", "--spring.jpa.hibernate.ddl-auto=validate", "--MOCK_PG_API_KEY=test-secret",
                "--MOCK_PG_BASE_URL=http://localhost:" + pgPort,
                "--spring.flyway.locations=filesystem:src/main/resources/db/migration,classpath:db/nickmigration",
                "--spring.datasource.url=" + SALES.getJdbcUrl(), "--spring.datasource.username=" + SALES.getUsername(),
                "--spring.datasource.password=" + SALES.getPassword(), "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379)};
        try (var stopped = new SpringApplicationBuilder(LimitedSaleApplication.class).run(arguments)) {
            assertThat(stopped.isActive()).isTrue();
        }
        try (var restarted = new SpringApplicationBuilder(LimitedSaleApplication.class).run(arguments)) {
            var service = restarted.getBean(com.wooyongjeong.limitedsale.order.application.OrderPaymentService.class);
            for (Fixture f : java.util.List.of(afterLease, afterPgSuccess, afterPaymentSaved)) {
                long id = sales.queryForObject("SELECT id FROM purchase_orders WHERE public_id=UNHEX(REPLACE(?, '-', ''))",
                        Long.class, f.order());
                service.recover(id);
                quantities(f, 0, 1);
                assertThat(pgCount(f)).isEqualTo(1);
            }
            var environment = (org.springframework.core.env.ConfigurableEnvironment) restarted.getEnvironment();
            environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("restartOutage",
                    java.util.Map.of("MOCK_PG_BASE_URL", "http://127.0.0.1:1")));
            long reviewId = sales.queryForObject("SELECT id FROM purchase_orders WHERE public_id=UNHEX(REPLACE(?, '-', ''))",
                    Long.class, underReview.order());
            try {
                service.recover(reviewId);
                quantities(underReview, 1, 0);
                assertThat(sales.queryForObject("SELECT next_review_warning_at FROM purchase_orders WHERE id=?",
                        java.sql.Timestamp.class, reviewId)).isEqualTo(warning);
            } finally {
                environment.getPropertySources().remove("restartOutage");
            }
            sales.update("UPDATE purchase_orders SET next_recovery_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", reviewId);
            service.recover(reviewId);
            quantities(underReview, 0, 0);
        }
    }

    @Test
    void twoNodesClaimOnceAndRunDifferentOrdersInParallel() throws Exception {
        sales.update("UPDATE purchase_orders SET lease_token=UUID_TO_BIN(UUID()), "
                + "lease_until=UTC_TIMESTAMP(6)+INTERVAL 1 HOUR "
                + "WHERE status IN ('AWAITING_PAYMENT', 'PAYMENT_PENDING')");
        Fixture first = fixture();
        Fixture second = fixture();
        expire(first);
        expire(second);
        var entered = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.ConcurrentHashMap<String, Integer>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var httpWorkers = java.util.concurrent.Executors.newFixedThreadPool(4);
        server.setExecutor(httpWorkers);
        server.createContext("/api/v1/payments", exchange -> {
            String[] parts = exchange.getRequestURI().getPath().split("/");
            String key = parts[4];
            calls.merge(key, 1, Integer::sum);
            entered.countDown();
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            String body = "{\"httpStatus\":200,\"code\":\"OK\",\"data\":{\"pgKey\":\"" + key
                    + "\",\"amount\":1000,\"status\":\"CLOSED\",\"createdAt\":\"2026-01-01T00:00:00Z\","
                    + "\"updatedAt\":\"2026-01-01T00:00:00Z\",\"finalizedAt\":\"2026-01-01T00:00:00Z\"},\"message\":\"ok\"}";
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var environment = (org.springframework.core.env.ConfigurableEnvironment) salesContext.getEnvironment();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("parallelPg",
                java.util.Map.of("MOCK_PG_BASE_URL", url)));
        try (var node = new SpringApplicationBuilder(LimitedSaleApplication.class).run("--server.port=0",
                "--spring.profiles.active=test", "--limited-sale.recovery.enabled=false", "--spring.flyway.enabled=true",
                "--spring.jpa.hibernate.ddl-auto=validate", "--MOCK_PG_API_KEY=test-secret", "--MOCK_PG_BASE_URL=" + url,
                "--spring.flyway.locations=filesystem:src/main/resources/db/migration,classpath:db/nickmigration",
                "--spring.datasource.url=" + SALES.getJdbcUrl(), "--spring.datasource.username=" + SALES.getUsername(),
                "--spring.datasource.password=" + SALES.getPassword(), "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379))) {
            var scheduler1 = new com.wooyongjeong.limitedsale.order.application.OrderRecoveryScheduler(
                    salesContext.getBean(com.wooyongjeong.limitedsale.order.application.OrderPaymentTransaction.class),
                    salesContext.getBean(com.wooyongjeong.limitedsale.order.application.OrderPaymentService.class), 20, 2);
            var scheduler2 = new com.wooyongjeong.limitedsale.order.application.OrderRecoveryScheduler(
                    node.getBean(com.wooyongjeong.limitedsale.order.application.OrderPaymentTransaction.class),
                    node.getBean(com.wooyongjeong.limitedsale.order.application.OrderPaymentService.class), 20, 2);
            try {
                scheduler1.poll();
                scheduler2.poll();
                assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(pay(first).statusCode()).isEqualTo(202);
                assertThat(calls).containsEntry(first.key(), 1).containsEntry(second.key(), 1).hasSize(2);
            } finally {
                release.countDown();
                scheduler1.shutdown();
                scheduler2.shutdown();
            }
            quantities(first, 0, 0);
            quantities(second, 0, 0);
        } finally {
            release.countDown();
            environment.getPropertySources().remove("parallelPg");
            server.stop(0);
            httpWorkers.shutdownNow();
        }
    }

    @Test
    void recoveryCandidateQueryHasItsSupportingIndex() {
        var columns = sales.queryForList("SELECT COLUMN_NAME FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='purchase_orders' AND index_name='ix_orders_recovery' "
                + "ORDER BY seq_in_index", String.class);
        assertThat(columns).containsExactly("status", "next_recovery_at", "id");
        var plan = sales.queryForList("EXPLAIN SELECT id FROM purchase_orders "
                + "WHERE status IN ('AWAITING_PAYMENT', 'PAYMENT_PENDING') "
                + "AND (next_recovery_at <= UTC_TIMESTAMP(6) OR "
                + "(expires_at <= UTC_TIMESTAMP(6) AND requires_review = false)) "
                + "AND (lease_until IS NULL OR lease_until <= UTC_TIMESTAMP(6)) "
                + "ORDER BY next_recovery_at, id LIMIT 20 FOR UPDATE SKIP LOCKED");
        assertThat(plan).hasSize(1);
        assertThat(String.valueOf(plan.getFirst().get("possible_keys"))).contains("ix_orders_recovery");
    }

    @Test
    void lockedCandidateDoesNotBlockAnotherDueOrder() throws Exception {
        sales.update("UPDATE purchase_orders SET lease_token=UUID_TO_BIN(UUID()), "
                + "lease_until=UTC_TIMESTAMP(6)+INTERVAL 1 HOUR "
                + "WHERE status IN ('AWAITING_PAYMENT', 'PAYMENT_PENDING')");
        Fixture blocked = fixture();
        Fixture available = fixture();
        expire(blocked);
        expire(available);
        var scheduler = new com.wooyongjeong.limitedsale.order.application.OrderRecoveryScheduler(
                salesContext.getBean(com.wooyongjeong.limitedsale.order.application.OrderPaymentTransaction.class),
                salesContext.getBean(com.wooyongjeong.limitedsale.order.application.OrderPaymentService.class), 20, 1);
        try (var connection = sales.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT id FROM purchase_orders "
                    + "WHERE public_id=UNHEX(REPLACE(?, '-', '')) FOR UPDATE")) {
                lock.setString(1, blocked.order());
                lock.executeQuery().close();
            }
            scheduler.poll();
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
            String status;
            do {
                status = sales.queryForObject("SELECT status FROM purchase_orders "
                        + "WHERE public_id=UNHEX(REPLACE(?, '-', ''))", String.class, available.order());
                if (!"EXPIRED".equals(status)) {
                    Thread.sleep(25);
                }
            } while (!"EXPIRED".equals(status) && System.nanoTime() < deadline);
            assertThat(status).isEqualTo("EXPIRED");
            quantities(available, 0, 0);
            quantities(blocked, 1, 0);
            connection.commit();
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void databaseTimeIsReadAfterPaymentRowLockWait() throws Exception {
        Fixture f = fixture();
        sales.update("UPDATE purchase_orders SET expires_at=UTC_TIMESTAMP(6)+INTERVAL 2 SECOND "
                + "WHERE public_id=UNHEX(REPLACE(?, '-', ''))", f.order());
        long paymentId = sales.queryForObject("SELECT p.id FROM payments p JOIN purchase_orders o ON p.order_id=o.id "
                + "WHERE o.public_id=UNHEX(REPLACE(?, '-', ''))", Long.class, f.order());
        try (var connection = sales.getDataSource().getConnection();
                var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT id FROM payments WHERE id=? FOR UPDATE")) {
                lock.setLong(1, paymentId);
                lock.executeQuery().close();
            }
            var result = pool.submit(() -> pay(f).statusCode());
            Thread.sleep(2500);
            connection.commit();
            assertThat(result.get()).isEqualTo(409);
        }
        assertThat(pg.queryForObject("SELECT status FROM mock_payments WHERE pg_key=UNHEX(REPLACE(?, '-', ''))",
                String.class, f.key())).isEqualTo("CLOSED");
        quantities(f, 0, 0);
    }

    @Test
    void finalizationRollsBackWhenLeaseExpiresDuringSaleLockWait() throws Exception {
        Fixture f = fixture();
        long id = sales.queryForObject("SELECT id FROM purchase_orders WHERE public_id=UNHEX(REPLACE(?, '-', ''))",
                Long.class, f.order());
        UUID token = UUID.randomUUID();
        sales.update("UPDATE purchase_orders SET status='PAYMENT_PENDING', lease_token=UNHEX(REPLACE(?, '-', '')), "
                + "lease_until=UTC_TIMESTAMP(6)+INTERVAL 2 SECOND WHERE id=?", token.toString(), id);
        sales.update("UPDATE payments SET status='SUCCEEDED' WHERE order_id=?", id);
        var transaction = salesContext.getBean(com.wooyongjeong.limitedsale.order.application.OrderPaymentTransaction.class);
        try (var connection = sales.getDataSource().getConnection();
                var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT id FROM sales WHERE id=? FOR UPDATE")) {
                lock.setLong(1, f.sale());
                lock.executeQuery().close();
            }
            var result = pool.submit(() -> transaction.finish(id, token, false));
            Thread.sleep(2500);
            connection.commit();
            assertThatThrownBy(result::get).hasCauseInstanceOf(IllegalStateException.class);
        }
        assertThat(sales.queryForObject("SELECT status FROM purchase_orders WHERE id=?", String.class, id))
                .isEqualTo("PAYMENT_PENDING");
        quantities(f, 1, 0);
    }

    @Test
    void staleTokenCannotChangePaymentOrTheNewLeaseInMysql() throws Exception {
        Fixture f = fixture();
        UUID old = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        sales.update("UPDATE purchase_orders SET lease_token=UNHEX(REPLACE(?, '-', '')), "
                + "lease_until=UTC_TIMESTAMP(6)+INTERVAL 30 SECOND WHERE public_id=UNHEX(REPLACE(?, '-', ''))",
                current.toString(), f.order());
        long id = sales.queryForObject("SELECT id FROM purchase_orders WHERE public_id=UNHEX(REPLACE(?, '-', ''))",
                Long.class, f.order());
        var transaction = salesContext.getBean(com.wooyongjeong.limitedsale.order.application.OrderPaymentTransaction.class);
        assertThat(transaction.record(id, old, com.wooyongjeong.limitedsale.payment.api.GatewayResult.SUCCEEDED)).isFalse();
        transaction.finish(id, old, false);
        assertThat(sales.queryForObject("SELECT HEX(lease_token) FROM purchase_orders WHERE id=?", String.class, id))
                .isEqualTo(current.toString().replace("-", "").toUpperCase());
        assertThat(sales.queryForObject("SELECT status FROM payments WHERE order_id=?", String.class, id)).isEqualTo("READY");
        quantities(f, 1, 0);
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
    void activeLeasePreventsExpiryFromStealingAnInFlightRequest() throws Exception {
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
            assertThat(pay(f).statusCode()).isEqualTo(202);
            releaseCharge.countDown();
            assertThat(late.get()).isEqualTo(202);
            quantities(f, 1, 0);
            assertThat(pay(f).statusCode()).isEqualTo(409);
            quantities(f, 0, 0);
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
                + "WHERE o.public_id=UNHEX(REPLACE(?, '-', ''))", Long.class, f.order())).isBetween(10_000_000L, 15_000_000L);
        sales.update("UPDATE purchase_orders SET next_recovery_at=UTC_TIMESTAMP(6)+INTERVAL 60 SECOND "
                + "WHERE public_id=UNHEX(REPLACE(?, '-', ''))", f.order());
        var before = sales.queryForObject("SELECT next_recovery_at FROM purchase_orders WHERE public_id=UNHEX(REPLACE(?, '-', ''))", java.sql.Timestamp.class, f.order());
        int originalPort = port;
        try (var second = new SpringApplicationBuilder(LimitedSaleApplication.class).run("--server.port=0",
                "--spring.profiles.active=test", "--limited-sale.recovery.enabled=false", "--spring.flyway.enabled=true", "--spring.jpa.hibernate.ddl-auto=validate",
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
