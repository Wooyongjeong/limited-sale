package com.wooyongjeong.limitedsale.sale;

import com.wooyongjeong.limitedsale.LimitedSaleApplication;
import com.wooyongjeong.limitedsale.member.application.MemberService;
import com.wooyongjeong.limitedsale.order.application.OrderResult;
import com.wooyongjeong.limitedsale.order.application.OrderService;
import com.wooyongjeong.limitedsale.order.presentation.code.OrderResponseCode;
import com.wooyongjeong.limitedsale.product.api.ProductCommand;
import com.wooyongjeong.limitedsale.sale.api.SaleCommand;
import com.wooyongjeong.limitedsale.sale.api.SaleCreation;
import com.wooyongjeong.limitedsale.sale.api.SaleDetails;
import com.wooyongjeong.limitedsale.sale.api.SalePatch;
import com.wooyongjeong.limitedsale.sale.api.SaleAllocation;
import com.wooyongjeong.limitedsale.sale.api.SaleReservation;
import com.wooyongjeong.mockpg.MockPgApplication;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static com.wooyongjeong.limitedsale.support.ResponseEnvelope.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Drives real orders through the Redis sold-out pre-check against MySQL and Redis: what the marker may and may not
 * decide, when it is written and cleared, and that nothing it does can change what MySQL sells.
 */
class SoldOutGateIntegrationTest {
    private static final MySQLContainer SALES = new MySQLContainer("mysql:8.4.7");
    private static final MySQLContainer PG = new MySQLContainer("mysql:8.4.7")
        .withUrlParam("connectionTimeZone", "UTC").withUrlParam("forceConnectionTimeZoneToSession", "true");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final HttpClient ANONYMOUS = HttpClient.newHttpClient();
    private static final String EMAIL = "sold-out-gate@example.com";
    private static ConfigurableApplicationContext pgContext;
    private static ConfigurableApplicationContext salesContext;
    private static JdbcTemplate sales;
    private static StringRedisTemplate redis;
    private static MeterRegistry meters;
    private static OrderService orders;
    private static int port;
    private static int pgPort;
    private static int managementPort;
    private static long product;
    private static long memberId;

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ConfigurableApplicationContext application(String... extra) {
        List<String> arguments = new ArrayList<>(List.of("--server.port=0", "--management.server.port=" + freePort(),
            "--spring.config.additional-location=file:src/main/resources/application.yml",
            "--limited-sale.order.rate-limit.enabled=false", "--spring.profiles.active=test",
            "--limited-sale.recovery.enabled=false", "--spring.flyway.enabled=true",
            "--spring.jpa.hibernate.ddl-auto=validate", "--MOCK_PG_API_KEY=test-secret",
            "--spring.flyway.locations=filesystem:src/main/resources/db/migration,classpath:db/nickmigration",
            "--MOCK_PG_BASE_URL=http://localhost:" + pgPort, "--spring.datasource.url=" + SALES.getJdbcUrl(),
            "--spring.datasource.username=" + SALES.getUsername(),
            "--spring.datasource.password=" + SALES.getPassword(), "--spring.data.redis.host=" + REDIS.getHost(),
            "--spring.data.redis.port=" + REDIS.getMappedPort(6379), "--spring.data.redis.timeout=1s",
            "--spring.data.redis.connect-timeout=1s"));
        arguments.addAll(List.of(extra));
        return new SpringApplicationBuilder(LimitedSaleApplication.class).run(arguments.toArray(String[]::new));
    }

    @BeforeAll
    static void start() {
        SALES.start();
        PG.start();
        REDIS.start();
        pgContext = new SpringApplicationBuilder(MockPgApplication.class).run("--server.port=0",
            "--mock-pg.api-key=test-secret", "--spring.config.location=file:mock-pg/src/main/resources/mock-pg.yml",
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
        pgPort = ((ServletWebServerApplicationContext) pgContext).getWebServer().getPort();
        salesContext = application("--limited-sale.sale.sold-out-gate.ttl=PT30S");
        port = ((ServletWebServerApplicationContext) salesContext).getWebServer().getPort();
        managementPort = Integer.parseInt(salesContext.getEnvironment().getProperty("local.management.port"));
        sales = salesContext.getBean(JdbcTemplate.class);
        redis = salesContext.getBean(StringRedisTemplate.class);
        meters = salesContext.getBean(MeterRegistry.class);
        orders = salesContext.getBean(OrderService.class);
        salesContext.getBean(MemberService.class).register(EMAIL, "password", "soldoutgate");
        memberId = sales.queryForObject("SELECT id FROM members WHERE email = ?", Long.class, EMAIL);
        product = salesContext.getBean(ProductCommand.class).register("gate product", "", 1000L).productId();
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

    private static final class Client {
        private final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        private String token;

        HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
            return http.send(builder.timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
        }

        private String csrf() throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/csrf")).GET();
            return JSON.readTree(send(request).body()).path("data").path("token").asString();
        }

        static Client login() throws Exception {
            Client client = new Client();
            client.token = client.csrf();
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/sessions"))
                .header("X-CSRF-TOKEN", client.token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + EMAIL + "\",\"password\":\"password\"}"));
            assertThat(client.send(request).statusCode()).isEqualTo(200);
            client.token = client.csrf();
            return client;
        }

        HttpResponse<String> order(long saleId, UUID key, int quantity) throws Exception {
            return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/orders"))
                .header("X-CSRF-TOKEN", token).header("Content-Type", "application/json")
                .header("Idempotency-Key", key.toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"saleId\":" + saleId + ",\"quantity\":" + quantity
                    + "}")));
        }

        HttpResponse<String> order(long saleId) throws Exception {
            return order(saleId, UUID.randomUUID(), 1);
        }

        String createOrder(long saleId) throws Exception {
            var response = order(saleId);
            assertCode(response, OrderResponseCode.ORDER_CREATED);
            return orderId(response);
        }

        HttpResponse<String> pay(String orderId) throws Exception {
            return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/orders/" + orderId
                + "/payment")).header("X-CSRF-TOKEN", token).POST(HttpRequest.BodyPublishers.noBody()));
        }
    }

    private static String orderId(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).path("data").path("orderId").asString();
    }

    private static long openSale(int totalQuantity, int perMemberLimit) {
        sales.update("INSERT INTO sales(product_id,name,description,product_name_snapshot,unit_price,starts_at,ends_at,"
            + "total_quantity,per_member_limit,created_at,updated_at) VALUES (?, 'sale', '', 'product', 1000, "
            + "UTC_TIMESTAMP(6)-INTERVAL 1 HOUR,UTC_TIMESTAMP(6)+INTERVAL 1 HOUR,?,?,UTC_TIMESTAMP(6),"
            + "UTC_TIMESTAMP(6))", product, totalQuantity, perMemberLimit);
        return sales.queryForObject("SELECT MAX(id) FROM sales", Long.class);
    }

    /** A sale whose last unit is already reserved, written directly so no marker exists yet. */
    private static long soldOutSale() {
        long sale = openSale(1, 1);
        sales.update("UPDATE sales SET reserved_quantity = 1 WHERE id = ?", sale);
        return sale;
    }

    private static long version(long saleId) {
        return sales.queryForObject("SELECT version FROM sales WHERE id = ?", Long.class, saleId);
    }

    private static String key(long saleId) {
        return "limited-sale:sale:sold-out:v1:" + saleId;
    }

    private static boolean marked(long saleId) {
        return Boolean.TRUE.equals(redis.hasKey(key(saleId)));
    }

    private static void mark(long saleId) {
        redis.opsForValue().set(key(saleId), "1", Duration.ofSeconds(30));
    }

    private static double requests(String result) {
        return meters.get("sale.sold.out.gate.requests").tag("result", result).counter().count();
    }

    private static double updates(String operation, String result) {
        return meters.get("sale.sold.out.gate.updates").tag("operation", operation).tag("result", result)
            .counter().count();
    }

    private static Map<String, Object> saleRow(long saleId) {
        return sales.queryForMap("SELECT version, reserved_quantity, sold_quantity, total_quantity, updated_at "
            + "FROM sales WHERE id = ?", saleId);
    }

    private static int count(String table, long saleId) {
        return sales.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE sale_id = ?", Integer.class, saleId);
    }

    private static void simulate(String orderId, String scenario) throws Exception {
        String hex = sales.queryForObject("SELECT HEX(p.pg_key) FROM payments p JOIN purchase_orders o "
            + "ON o.id=p.order_id WHERE o.public_id=UNHEX(REPLACE(?, '-', ''))", String.class, orderId);
        String pgKey = hex.replaceFirst("(........)(....)(....)(....)(............)", "$1-$2-$3-$4-$5").toLowerCase();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + pgPort + "/api/v1/simulations/" + pgKey))
            .header("X-Mock-PG-Key", "test-secret").header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("{\"scenario\":\"" + scenario + "\"}")).build();
        assertThat(ANONYMOUS.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }

    private static void whilePaused(GenericContainer<?> container, Runnable action) {
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(container.getContainerId()).exec();
        try {
            action.run();
        } finally {
            docker.unpauseContainerCmd(container.getContainerId()).exec();
        }
    }

    private static <T> T inTransaction(boolean commit, java.util.function.Supplier<T> work) {
        var template = new TransactionTemplate(salesContext.getBean(PlatformTransactionManager.class));
        if (commit) {
            return template.execute(status -> work.get());
        }
        var rollback = new IllegalStateException("rollback on purpose");
        assertThatThrownBy(() -> template.execute(status -> {
            work.get();
            throw rollback;
        })).isSameAs(rollback);
        return null;
    }

    @Test
    void theLastUnitMarksAfterCommitWithTheTtlAndEarlierUnitsDoNot() throws Exception {
        Client client = Client.login();
        long sale = openSale(2, 2);
        double marks = updates("mark", "success");

        client.createOrder(sale);
        assertThat(marked(sale)).as("one unit is still available").isFalse();
        client.createOrder(sale);

        assertThat(marked(sale)).isTrue();
        assertThat(redis.getExpire(key(sale), TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(30_000);
        assertThat(updates("mark", "success")).isEqualTo(marks + 1);
    }

    @Test
    void aSoldOutDecisionByMySqlMarksAfterCommit() throws Exception {
        Client client = Client.login();
        // The stock is gone but no marker exists, as after an expired or lost marker.
        long sale = soldOutSale();
        double misses = requests("miss");
        double marks = updates("mark", "success");

        assertCode(client.order(sale), OrderResponseCode.SALE_SOLD_OUT);

        assertThat(requests("miss")).isEqualTo(misses + 1);
        assertThat(marked(sale)).as("MySQL decided SALE_SOLD_OUT").isTrue();
        assertThat(updates("mark", "success")).isEqualTo(marks + 1);
    }

    @Test
    void aRequestForMoreThanWhatRemainsIsRejectedWithoutMarkingTheSale() throws Exception {
        Client client = Client.login();
        long sale = openSale(2, 2);
        sales.update("UPDATE sales SET reserved_quantity = 1 WHERE id = ?", sale);
        double marks = updates("mark", "success");

        assertCode(client.order(sale, UUID.randomUUID(), 2), OrderResponseCode.SALE_SOLD_OUT);

        assertThat(marked(sale)).as("one unit is still available").isFalse();
        assertThat(updates("mark", "success")).isEqualTo(marks);
        assertThat(client.order(sale, UUID.randomUUID(), 1).statusCode()).as("the last unit still sells")
            .isEqualTo(201);
        assertThat(marked(sale)).isTrue();
    }

    @Test
    void anExistingKeyReplaysBeforeTheMarkerCanRejectIt() throws Exception {
        Client client = Client.login();
        long sale = openSale(1, 1);
        UUID key = UUID.randomUUID();
        var created = client.order(sale, key, 1);
        assertCode(created, OrderResponseCode.ORDER_CREATED);
        assertThat(marked(sale)).isTrue();
        double hits = requests("hit");

        var replay = client.order(sale, key, 1);

        assertCode(replay, OrderResponseCode.ORDER_FOUND);
        assertThat(orderId(replay)).isEqualTo(orderId(created));
        assertThat(requests("hit")).as("the gate was read, and the key still won").isEqualTo(hits + 1);
        assertCode(client.order(sale, key, 2), OrderResponseCode.IDEMPOTENCY_CONFLICT);
        assertThat(count("purchase_orders", sale)).isEqualTo(1);
        assertThat(count("order_request_results", sale)).isEqualTo(1);
    }

    @Test
    void aNewKeyOnAMarkedSaleIsRejectedWithoutTheSaleRowLockOrAnUpdateAndTheRejectionIsStored() throws Exception {
        Client client = Client.login();
        long sale = openSale(1, 1);
        client.createOrder(sale);
        assertThat(marked(sale)).isTrue();
        Map<String, Object> before = saleRow(sale);
        UUID key = UUID.randomUUID();
        DataSource dataSource = salesContext.getBean(DataSource.class);

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var statement = holder.createStatement()) {
                statement.executeQuery("SELECT id FROM sales WHERE id = " + sale + " FOR UPDATE").close();
                // Anything that waited for this lock would run into the HTTP timeout instead of answering.
                var first = client.order(sale, key, 1);
                assertCode(first, OrderResponseCode.SALE_SOLD_OUT);
                assertCode(client.order(sale, key, 1), OrderResponseCode.SALE_SOLD_OUT);
            } finally {
                holder.rollback();
            }
        }

        assertThat(saleRow(sale)).isEqualTo(before);
        assertThat(count("purchase_orders", sale)).isEqualTo(1);
        assertThat(sales.queryForList("SELECT outcome, rejection_reason FROM order_request_results WHERE sale_id = ? "
            + "AND outcome = 'REJECTED'", sale)).singleElement().satisfies(row ->
                assertThat(row).containsEntry("rejection_reason", "SALE_SOLD_OUT"));
        // The stored answer belongs to the key: it stays even after the marker is gone and stock exists again.
        redis.delete(key(sale));
        sales.update("UPDATE sales SET total_quantity = 5, per_member_limit = 5 WHERE id = ?", sale);
        assertCode(client.order(sale, key, 1), OrderResponseCode.SALE_SOLD_OUT);
        assertThat(client.order(sale).statusCode()).isEqualTo(201);
    }

    @Test
    void aFalseMarkerDisappearsAfterTheTtlAndOrdersSellAgain() throws Exception {
        long sale = openSale(1, 1);
        try (var shortTtl = application("--limited-sale.sale.sold-out-gate.ttl=PT2S")) {
            OrderService service = shortTtl.getBean(OrderService.class);
            assertThat(service.create(memberId, UUID.randomUUID(), sale, 1).kind())
                .isEqualTo(OrderResult.Kind.CREATED);
            assertThat(marked(sale)).isTrue();
            assertThat(redis.getExpire(key(sale), TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(2_000);
            // Stock appears behind the application's back, so the marker is false and nothing clears it.
            sales.update("UPDATE sales SET total_quantity = 3, per_member_limit = 3 WHERE id = ?", sale);
            UUID rejected = UUID.randomUUID();
            assertThat(service.create(memberId, rejected, sale, 1).kind()).isEqualTo(OrderResult.Kind.SALE_SOLD_OUT);

            await().atMost(Duration.ofSeconds(6)).until(() -> !marked(sale));

            assertThat(service.create(memberId, UUID.randomUUID(), sale, 1).kind())
                .isEqualTo(OrderResult.Kind.CREATED);
            assertThat(service.create(memberId, rejected, sale, 1).kind())
                .as("a rejection stays with its key").isEqualTo(OrderResult.Kind.SALE_SOLD_OUT);
            assertThat(saleRow(sale)).containsEntry("reserved_quantity", 2);
        }
    }

    @Test
    void whileRedisIsStoppedMySqlDecidesAndStockSellsExactly() throws Exception {
        // Three units are left; the member's limit never gets in the way of the sold-out decision.
        long sale = openSale(10, 10);
        sales.update("UPDATE sales SET reserved_quantity = 7 WHERE id = ?", sale);
        double lookupErrors = requests("error");
        double markErrors = updates("mark", "error");
        List<OrderResult.Kind> kinds = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            whilePaused(REDIS, () -> {
                List<Future<OrderResult>> futures = new ArrayList<>();
                for (int i = 0; i < 10; i++) {
                    Callable<OrderResult> call = () -> orders.create(memberId, UUID.randomUUID(), sale, 1);
                    futures.add(pool.submit(call));
                }
                for (var future : futures) {
                    try {
                        kinds.add(future.get(60, TimeUnit.SECONDS).kind());
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }
            });
        } finally {
            pool.shutdownNow();
        }

        assertThat(kinds.stream().filter(kind -> kind == OrderResult.Kind.CREATED)).hasSize(3);
        assertThat(kinds.stream().filter(kind -> kind == OrderResult.Kind.SALE_SOLD_OUT)).hasSize(7);
        assertThat(saleRow(sale)).containsEntry("reserved_quantity", 10).containsEntry("sold_quantity", 0);
        assertThat(count("purchase_orders", sale)).isEqualTo(3);
        assertThat(requests("error")).isEqualTo(lookupErrors + 10);
        assertThat(updates("mark", "error")).isGreaterThan(markErrors);

        // Back online the gate answers again, and a repeat of the key still finds MySQL's stored answer.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            double hits = requests("hit");
            double misses = requests("miss");
            orders.create(memberId, UUID.randomUUID(), sale, 1);
            assertThat(requests("hit") + requests("miss")).isEqualTo(hits + misses + 1);
        });
        assertThat(count("purchase_orders", sale)).isEqualTo(3);
    }

    @Test
    void aRolledBackTransactionLeavesTheMarkerAsItWas() throws Exception {
        Client client = Client.login();
        SaleReservation reservation = salesContext.getBean(SaleReservation.class);
        double marks = updates("mark", "success");
        double clears = updates("clear", "success");

        // The last unit is taken inside a transaction that does not commit: no marker.
        long open = openSale(1, 1);
        inTransaction(false, () -> reservation.allocate(open, memberId, 1));
        assertThat(marked(open)).isFalse();
        assertThat(saleRow(open)).containsEntry("reserved_quantity", 0);

        // A sold-out decision inside a transaction that does not commit: no marker.
        long full = soldOutSale();
        inTransaction(false, () -> reservation.allocate(full, memberId, 1));
        assertThat(marked(full)).isFalse();

        // A release inside a transaction that does not commit: the marker stays.
        long held = openSale(1, 1);
        String order = client.createOrder(held);
        assertThat(marked(held)).isTrue();
        long orderId = sales.queryForObject("SELECT id FROM purchase_orders WHERE public_id = UNHEX(REPLACE(?, '-', ''))",
            Long.class, order);
        inTransaction(false, () -> {
            reservation.finish(orderId, held, memberId, 1, false, Instant.now());
            return null;
        });
        assertThat(marked(held)).isTrue();
        assertThat(saleRow(held)).containsEntry("reserved_quantity", 1);

        // Only the order that really committed marked anything.
        assertThat(updates("mark", "success")).isEqualTo(marks + 1);
        assertThat(updates("clear", "success")).isEqualTo(clears);
        // The same calls that commit do change the marker.
        inTransaction(true, () -> {
            reservation.finish(orderId, held, memberId, 1, false, Instant.now());
            return null;
        });
        assertThat(marked(held)).isFalse();
        assertThat(inTransaction(true, () -> reservation.allocate(open, memberId, 1)).reason())
            .isEqualTo(SaleAllocation.Reason.ALLOCATED);
        assertThat(marked(open)).isTrue();
    }

    @Test
    void aReleaseAfterAFailedPaymentClearsTheMarkerAndAConfirmationDoesNot() throws Exception {
        Client client = Client.login();
        long declined = openSale(1, 1);
        String order = client.createOrder(declined);
        assertThat(marked(declined)).isTrue();
        double clears = updates("clear", "success");
        simulate(order, "DECLINE");

        assertThat(client.pay(order).statusCode()).isEqualTo(409);

        assertThat(marked(declined)).isFalse();
        assertThat(updates("clear", "success")).isEqualTo(clears + 1);
        assertThat(saleRow(declined)).containsEntry("reserved_quantity", 0);
        assertThat(client.order(declined).statusCode()).as("the released unit sells again").isEqualTo(201);

        long confirmed = openSale(1, 1);
        String paid = client.createOrder(confirmed);
        assertThat(marked(confirmed)).isTrue();
        assertThat(client.pay(paid).statusCode()).isEqualTo(200);
        assertThat(marked(confirmed)).as("a confirmation changes nothing about being sold out").isTrue();
        assertCode(client.order(confirmed), OrderResponseCode.SALE_SOLD_OUT);
    }

    @Test
    void anAdminQuantityChangeAndADeleteClearTheMarkerButOtherEditsDoNot() {
        SaleCommand commands = salesContext.getBean(SaleCommand.class);
        Instant start = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS);
        SaleDetails sale = commands.create(new SaleCreation(product, "sale", "description", null, start,
            start.plusSeconds(3600), 5, null));
        mark(sale.saleId());

        commands.patch(sale.saleId(),
            new SalePatch(sale.version(), null, "renamed", null, null, null, null, null, null));
        assertThat(marked(sale.saleId())).as("a name edit does not touch stock").isTrue();
        commands.stop(sale.saleId());
        commands.resume(sale.saleId());
        assertThat(marked(sale.saleId())).isTrue();

        double clears = updates("clear", "success");
        commands.patch(sale.saleId(), new SalePatch(version(sale.saleId()), null, null, null, null, null, null, 8, null));
        assertThat(marked(sale.saleId())).isFalse();
        assertThat(updates("clear", "success")).isEqualTo(clears + 1);

        mark(sale.saleId());
        commands.delete(sale.saleId());
        assertThat(marked(sale.saleId())).isFalse();
        assertThat(updates("clear", "success")).isEqualTo(clears + 2);
    }

    @Test
    void aFailedAdminEditLeavesTheMarkerAlone() {
        SaleCommand commands = salesContext.getBean(SaleCommand.class);
        Instant start = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS);
        SaleDetails sale = commands.create(new SaleCreation(product, "sale", "description", null, start,
            start.plusSeconds(3600), 5, null));
        mark(sale.saleId());

        assertThatThrownBy(() -> commands.patch(sale.saleId(),
            new SalePatch(sale.version() + 7, null, null, null, null, null, null, 9, null)))
            .isInstanceOf(RuntimeException.class);

        assertThat(marked(sale.saleId())).isTrue();
    }

    @Test
    void metricsCarryOnlyOutcomeLabelsNeverAnIdentifier() throws Exception {
        Client client = Client.login();
        long sale = openSale(1, 1);
        client.createOrder(sale);
        client.order(sale);

        var response = ANONYMOUS.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort
            + "/actuator/prometheus")).GET().build(), HttpResponse.BodyHandlers.ofString());
        List<String> lines = response.body().lines().filter(line -> line.startsWith("sale_sold_out_gate_")).toList();

        assertThat(lines).anyMatch(line -> line.startsWith("sale_sold_out_gate_requests_total{result=\"hit\"}"));
        assertThat(lines).anyMatch(line -> line.startsWith("sale_sold_out_gate_updates_total{operation=\"mark\""));
        Pattern label = Pattern.compile("(\\w+)=\"");
        Set<String> names = new TreeSet<>();
        for (String line : lines) {
            Matcher matcher = label.matcher(line.substring(0, line.indexOf('}')));
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
            assertThat(line).doesNotContain(Long.toString(sale), "sold-out-gate@example.com");
        }
        assertThat(names).containsExactlyInAnyOrder("operation", "result");
    }
}
