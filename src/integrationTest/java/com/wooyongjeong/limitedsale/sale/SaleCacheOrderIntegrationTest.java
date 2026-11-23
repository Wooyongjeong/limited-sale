package com.wooyongjeong.limitedsale.sale;

import com.wooyongjeong.limitedsale.LimitedSaleApplication;
import com.wooyongjeong.limitedsale.member.application.MemberService;
import com.wooyongjeong.limitedsale.order.presentation.code.OrderResponseCode;
import com.wooyongjeong.limitedsale.product.api.ProductCommand;
import com.wooyongjeong.mockpg.MockPgApplication;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Consumer;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static com.wooyongjeong.limitedsale.support.ResponseEnvelope.assertCode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives real orders and payments through the sale cache: the confirmation that sells out a sale removes the entry,
 * other outcomes leave it, and whatever the entry claims never decides an order or a payment.
 */
class SaleCacheOrderIntegrationTest {
    private static final MySQLContainer SALES = new MySQLContainer("mysql:8.4.7");
    private static final MySQLContainer PG = new MySQLContainer("mysql:8.4.7")
        .withUrlParam("connectionTimeZone", "UTC").withUrlParam("forceConnectionTimeZoneToSession", "true");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final HttpClient ANONYMOUS = HttpClient.newHttpClient();
    private static ConfigurableApplicationContext pgContext;
    private static ConfigurableApplicationContext salesContext;
    private static JdbcTemplate sales;
    private static JdbcTemplate pg;
    private static StringRedisTemplate redis;
    private static MeterRegistry meters;
    private static int port;
    private static int pgPort;
    private static long product;

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
            "--spring.autoconfigure.exclude=org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration,"
                + "org.springframework.boot.session.autoconfigure.SessionAutoConfiguration,"
                + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration");
        pgPort = ((ServletWebServerApplicationContext) pgContext).getWebServer().getPort();
        pg = pgContext.getBean(JdbcTemplate.class);
        salesContext = new SpringApplicationBuilder(LimitedSaleApplication.class).run("--server.port=0",
            "--limited-sale.order.rate-limit.enabled=false",
            "--spring.profiles.active=test", "--limited-sale.recovery.enabled=false", "--spring.flyway.enabled=true",
            "--spring.jpa.hibernate.ddl-auto=validate", "--MOCK_PG_API_KEY=test-secret",
            "--spring.flyway.locations=filesystem:src/main/resources/db/migration,classpath:db/nickmigration",
            "--MOCK_PG_BASE_URL=http://localhost:" + pgPort, "--spring.datasource.url=" + SALES.getJdbcUrl(),
            "--spring.datasource.username=" + SALES.getUsername(),
            "--spring.datasource.password=" + SALES.getPassword(), "--spring.data.redis.host=" + REDIS.getHost(),
            "--spring.data.redis.port=" + REDIS.getMappedPort(6379));
        port = ((ServletWebServerApplicationContext) salesContext).getWebServer().getPort();
        sales = salesContext.getBean(JdbcTemplate.class);
        redis = salesContext.getBean(StringRedisTemplate.class);
        meters = salesContext.getBean(MeterRegistry.class);
        salesContext.getBean(MemberService.class).register("cache-order@example.com", "password", "cacheorder");
        product = salesContext.getBean(ProductCommand.class).register("cache order product", "", 1000L).productId();
    }

    @BeforeEach
    void clearCache() {
        redis.delete(redis.keys("limited-sale:cache:*"));
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
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"email\":\"cache-order@example.com\",\"password\":\"password\"}"));
            assertThat(client.send(request).statusCode()).isEqualTo(200);
            client.token = client.csrf();
            return client;
        }

        HttpResponse<String> order(long saleId) throws Exception {
            return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/orders"))
                .header("X-CSRF-TOKEN", token).header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"saleId\":" + saleId + ",\"quantity\":1}")));
        }

        String createOrder(long saleId) throws Exception {
            var response = order(saleId);
            assertCode(response, OrderResponseCode.ORDER_CREATED);
            return JSON.readTree(response.body()).path("data").path("orderId").asString();
        }

        HttpResponse<String> pay(String orderId) throws Exception {
            return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/orders/" + orderId
                + "/payment")).header("X-CSRF-TOKEN", token).POST(HttpRequest.BodyPublishers.noBody()));
        }
    }

    private static long openSale(int totalQuantity, int perMemberLimit) {
        sales.update("INSERT INTO sales(product_id,name,description,product_name_snapshot,unit_price,starts_at,ends_at,"
            + "total_quantity,per_member_limit,created_at,updated_at) VALUES (?, 'sale', '', 'product', 1000, "
            + "UTC_TIMESTAMP(6)-INTERVAL 1 HOUR,UTC_TIMESTAMP(6)+INTERVAL 1 HOUR,?,?,UTC_TIMESTAMP(6),"
            + "UTC_TIMESTAMP(6))", product, totalQuantity, perMemberLimit);
        return sales.queryForObject("SELECT MAX(id) FROM sales", Long.class);
    }

    private static String status(long saleId) throws Exception {
        var response = ANONYMOUS.send(HttpRequest.newBuilder(
            URI.create("http://localhost:" + port + "/api/v1/sales/" + saleId)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).path("data").path("status").asString();
    }

    private static String key(long saleId) {
        return "limited-sale:cache:sale:v1:" + saleId;
    }

    private static boolean cached(long saleId) {
        return Boolean.TRUE.equals(redis.hasKey(key(saleId)));
    }

    private static double evictions(String result) {
        return meters.get("sale.cache.evictions").tag("result", result).counter().count();
    }

    private static void rewrite(long saleId, Consumer<ObjectNode> change) throws Exception {
        ObjectNode node = (ObjectNode) JSON.readTree(redis.opsForValue().get(key(saleId)));
        change.accept(node);
        redis.opsForValue().set(key(saleId), node.toString(), Duration.ofSeconds(60));
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

    @Test
    void soldOutShowsOnlyAfterTheLastConfirmationAndEarlierStepsKeepTheEntry() throws Exception {
        Client client = Client.login();
        long sale = openSale(2, 2);
        assertThat(status(sale)).isEqualTo("OPEN");
        String first = client.createOrder(sale);
        String second = client.createOrder(sale);
        assertThat(cached(sale)).as("allocating stock leaves the entry").isTrue();
        double removals = evictions("success");

        assertThat(client.pay(first).statusCode()).isEqualTo(200);

        assertThat(sales.queryForObject("SELECT sold_quantity FROM sales WHERE id = ?", Integer.class, sale))
            .isEqualTo(1);
        assertThat(cached(sale)).as("a confirmation that is not the last one leaves the entry").isTrue();
        assertThat(evictions("success")).isEqualTo(removals);
        assertThat(status(sale)).isEqualTo("OPEN");

        assertThat(client.pay(second).statusCode()).isEqualTo(200);

        assertThat(sales.queryForObject("SELECT sold_quantity FROM sales WHERE id = ?", Integer.class, sale))
            .isEqualTo(2);
        assertThat(cached(sale)).as("removed once the sale-out committed").isFalse();
        assertThat(evictions("success")).isEqualTo(removals + 1);
        assertThat(status(sale)).isEqualTo("SOLD_OUT");

        assertThat(client.pay(second).statusCode()).as("replay finalizes nothing").isEqualTo(200);
        assertThat(cached(sale)).isTrue();
        assertThat(evictions("success")).isEqualTo(removals + 1);
    }

    @Test
    void declinedPaymentAndReleasedStockLeaveTheEntry() throws Exception {
        Client client = Client.login();
        long sale = openSale(2, 2);
        String order = client.createOrder(sale);
        assertThat(status(sale)).isEqualTo("OPEN");
        double removals = evictions("success");
        simulate(order, "DECLINE");

        assertThat(client.pay(order).statusCode()).isEqualTo(409);

        assertThat(sales.queryForObject("SELECT reserved_quantity FROM sales WHERE id = ?", Integer.class, sale))
            .isZero();
        assertThat(cached(sale)).isTrue();
        assertThat(evictions("success")).isEqualTo(removals);
    }

    @Test
    void anEntryClaimingEndedAndSoldOutDoesNotStopOrdersOrPayments() throws Exception {
        Client client = Client.login();
        long sale = openSale(10, 5);
        assertThat(status(sale)).isEqualTo("OPEN");
        Instant past = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        rewrite(sale, node -> node.put("stopped", true).put("soldQuantity", 10).put("startsAt", past.toString())
            .put("endsAt", past.plusSeconds(3600).toString()));
        assertThat(status(sale)).as("the entry is what the detail query serves").isEqualTo("ENDED");

        String order = client.createOrder(sale);
        assertThat(client.pay(order).statusCode()).isEqualTo(200);

        assertThat(sales.queryForObject("SELECT sold_quantity FROM sales WHERE id = ?", Integer.class, sale))
            .isEqualTo(1);
        redis.opsForValue().set(key(sale), "{broken", Duration.ofSeconds(60));
        assertThat(client.order(sale).statusCode()).as("an unreadable entry does not matter either").isEqualTo(201);
    }

    @Test
    void anEntryClaimingOpenDoesNotLetAStoppedSaleTakeNewOrders() throws Exception {
        Client client = Client.login();
        long sale = openSale(10, 5);
        String existing = client.createOrder(sale);
        sales.update("UPDATE sales SET is_stopped = true WHERE id = ?", sale);
        assertThat(status(sale)).isEqualTo("STOPPED");
        rewrite(sale, node -> node.put("stopped", false));
        assertThat(status(sale)).as("the stale entry still shows an open sale").isEqualTo("OPEN");

        assertCode(client.order(sale), OrderResponseCode.SALE_STOPPED);

        assertThat(client.pay(existing).statusCode()).as("an order taken before the stop still pays").isEqualTo(200);
        assertThat(sales.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE sale_id = ?", Integer.class, sale))
            .isEqualTo(1);
    }
}
