package com.wooyongjeong.limitedsale.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
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
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ObservabilityIntegrationTest {
    private static final MySQLContainer SALES = new MySQLContainer("mysql:8.4.7")
            .withCommand("--log-bin-trust-function-creators=1");
    private static final MySQLContainer PG = new MySQLContainer("mysql:8.4.7")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static ConfigurableApplicationContext pgContext;
    private static ConfigurableApplicationContext salesContext;
    private static JdbcTemplate sales;
    private static int port;
    private static int managementPort;
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
                "--spring.autoconfigure.exclude=org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration,"
                        + "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration,"
                        + "org.springframework.boot.session.autoconfigure.SessionAutoConfiguration,"
                        + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration,"
                        + "org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration,"
                        + "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration,"
                        + "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration");
        pgPort = ((ServletWebServerApplicationContext) pgContext).getWebServer().getPort();
        // mock-pg's application.yml shadows the sales one on this classpath, so load the real one explicitly.
        salesContext = new SpringApplicationBuilder(LimitedSaleApplication.class).run("--server.port=0",
                "--spring.config.additional-location=file:src/main/resources/application.yml",
                "--spring.profiles.active=test", "--limited-sale.recovery.enabled=false",
                "--limited-sale.observability.backlog-interval=PT0.2S", "--spring.flyway.enabled=true",
                "--spring.jpa.hibernate.ddl-auto=validate", "--MOCK_PG_API_KEY=test-secret",
                "--spring.flyway.locations=filesystem:src/main/resources/db/migration,classpath:db/nickmigration",
                "--MOCK_PG_BASE_URL=http://localhost:" + pgPort, "--spring.datasource.url=" + SALES.getJdbcUrl(),
                "--spring.datasource.username=" + SALES.getUsername(), "--spring.datasource.password=" + SALES.getPassword(),
                "--spring.data.redis.host=" + REDIS.getHost(), "--spring.data.redis.port=" + REDIS.getMappedPort(6379));
        port = ((ServletWebServerApplicationContext) salesContext).getWebServer().getPort();
        managementPort = Integer.parseInt(salesContext.getEnvironment().getProperty("local.management.port"));
        sales = salesContext.getBean(JdbcTemplate.class);
        var members = salesContext.getBean(MemberService.class);
        members.register("user-obs@example.com", "password", "userobs");
        members.register("other-obs@example.com", "password", "otherobs");
        salesContext.getBean(ProductCommand.class).register("observability product", "", 1000L);
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

    private record Fixture(Client client, String order, long sale) {
    }

    private long sale(int totalQuantity) {
        long product = sales.queryForObject("SELECT MIN(id) FROM products", Long.class);
        sales.update("INSERT INTO sales(product_id,name,description,product_name_snapshot,unit_price,starts_at,ends_at,"
                + "total_quantity,per_member_limit,created_at,updated_at) VALUES (?, 'sale', '', 'product', 1000, "
                + "UTC_TIMESTAMP(6)-INTERVAL 1 HOUR,UTC_TIMESTAMP(6)+INTERVAL 1 HOUR,?,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                product, totalQuantity);
        return sales.queryForObject("SELECT MAX(id) FROM sales", Long.class);
    }

    private Fixture orderFixture() throws Exception {
        Client client = new Client();
        client.login("user-obs@example.com");
        long sale = sale(10);
        var created = client.order(sale);
        assertThat(created.statusCode()).isEqualTo(201);
        return new Fixture(client, JSON.readTree(created.body()).path("data").path("orderId").asString(), sale);
    }

    private void declineNextPayment(Fixture fixture) throws Exception {
        String hex = sales.queryForObject("SELECT HEX(p.pg_key) FROM payments p JOIN purchase_orders o ON o.id=p.order_id "
                + "WHERE o.public_id=UNHEX(REPLACE(?, '-', ''))", String.class, fixture.order());
        String key = hex.replaceFirst("(........)(....)(....)(....)(............)", "$1-$2-$3-$4-$5").toLowerCase();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + pgPort + "/api/v1/simulations/" + key))
                .header("X-Mock-PG-Key", "test-secret").header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"scenario\":\"DECLINE\"}")).build();
        assertThat(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(200);
    }

    private static HttpResponse<String> get(int onPort, String path, String requestId) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + onPort + path));
        if (requestId != null) {
            builder.header("X-Request-Id", requestId);
        }
        return HttpClient.newHttpClient().send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String scrape() throws Exception {
        var response = get(managementPort, "/actuator/prometheus", null);
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    /** Value of one exposed sample such as {@code order_creation_results_total{result="created"}}. */
    private static double sample(String body, String series) {
        for (String line : body.split("\n")) {
            if (line.startsWith(series + " ")) {
                return Double.parseDouble(line.substring(series.length() + 1).trim());
            }
        }
        throw new AssertionError("Series not exposed: " + series);
    }

    private static double sample(String series) throws Exception {
        return sample(scrape(), series);
    }

    @Test
    void managementPortServesOnlyHealthAndPrometheus() throws Exception {
        assertThat(get(managementPort, "/actuator/health", null).statusCode()).isEqualTo(200);
        var prometheus = get(managementPort, "/actuator/prometheus", null);
        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.headers().firstValue("Content-Type").orElse("")).startsWith("text/plain");
        for (String other : new String[] {"/actuator/env", "/actuator/metrics", "/actuator/info", "/actuator/beans"}) {
            assertThat(get(managementPort, other, null).statusCode()).as(other).isIn(401, 404);
        }
    }

    @Test
    void publicPortKeepsHealthButNeverServesMetrics() throws Exception {
        var health = get(port, "/actuator/health", null);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(health.body())).isEqualTo(JSON.readTree("{\"status\":\"UP\"}"));
        var anonymous = get(port, "/actuator/prometheus", null);
        assertThat(anonymous.statusCode()).isNotEqualTo(200);
        assertThat(anonymous.body()).doesNotContain("jvm_memory");
        Client user = new Client();
        user.login("user-obs@example.com");
        var authenticated = user.request("GET", "/actuator/prometheus", null, null);
        assertThat(authenticated.statusCode()).isEqualTo(404);
        assertThat(authenticated.body()).doesNotContain("jvm_memory").doesNotContain("order_creation_results");
    }

    @Test
    void scrapeContainsHttpJvmHikariPaymentClientAndBusinessMetrics() throws Exception {
        Fixture f = orderFixture();
        assertThat(f.client().request("POST", "/api/v1/orders/" + f.order() + "/payment", null, f.client().token)
                .statusCode()).isEqualTo(200);
        String body = scrape();
        assertThat(body).contains("http_server_requests_seconds_count{", "http_server_requests_seconds_bucket{",
                "jvm_memory_used_bytes{", "jvm_gc_pause_seconds_count", "hikaricp_connections_active{",
                "hikaricp_connections_pending{", "hikaricp_connections_acquire_seconds_count{",
                "order_creation_results_total{result=\"created\"}", "order_payment_results_total{result=\"payment_succeeded\"}",
                "order_unfinished", "order_requires_review",
                "http_client_requests_seconds_count{client_name=\"localhost\",exception=\"none\",method=\"POST\","
                        + "outcome=\"SUCCESS\",status=\"200\",uri=\"/api/v1/payments\"}");
    }

    @Test
    void businessCountersRiseByResult() throws Exception {
        String created = "order_creation_results_total{result=\"created\"}";
        String soldOut = "order_creation_results_total{result=\"sale_sold_out\"}";
        String overLimit = "order_creation_results_total{result=\"purchase_limit_exceeded\"}";
        String succeeded = "order_payment_results_total{result=\"payment_succeeded\"}";
        String declined = "order_payment_results_total{result=\"payment_declined\"}";
        String before = scrape();

        Client client = new Client();
        client.login("user-obs@example.com");
        long single = sale(1);
        assertThat(client.order(single).statusCode()).isEqualTo(201);
        assertThat(client.order(single).statusCode()).isEqualTo(409);
        Client other = new Client();
        other.login("other-obs@example.com");
        assertThat(other.order(single).statusCode()).isEqualTo(409);
        Fixture paid = orderFixture();
        assertThat(paid.client().request("POST", "/api/v1/orders/" + paid.order() + "/payment", null,
                paid.client().token).statusCode()).isEqualTo(200);
        Fixture refused = orderFixture();
        declineNextPayment(refused);
        assertThat(refused.client().request("POST", "/api/v1/orders/" + refused.order() + "/payment", null,
                refused.client().token).statusCode()).isEqualTo(409);

        String after = scrape();
        assertThat(sample(after, created) - sample(before, created)).isEqualTo(3);
        assertThat(sample(after, soldOut) - sample(before, soldOut)).isEqualTo(1);
        assertThat(sample(after, overLimit) - sample(before, overLimit)).isEqualTo(1);
        assertThat(sample(after, succeeded) - sample(before, succeeded)).isEqualTo(1);
        assertThat(sample(after, declined) - sample(before, declined)).isEqualTo(1);
    }

    @Test
    void gaugesFollowTheDatabase() throws Exception {
        Fixture f = orderFixture();
        sales.update("UPDATE purchase_orders SET requires_review=true WHERE public_id=UNHEX(REPLACE(?, '-', ''))",
                f.order());
        long unfinished = sales.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE status IN "
                + "('AWAITING_PAYMENT','PAYMENT_PENDING')", Long.class);
        long review = sales.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE requires_review=true "
                + "AND status IN ('AWAITING_PAYMENT','PAYMENT_PENDING')", Long.class);
        assertThat(review).isGreaterThanOrEqualTo(1);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            String body = scrape();
            assertThat(sample(body, "order_unfinished")).isEqualTo(unfinished);
            assertThat(sample(body, "order_requires_review")).isEqualTo(review);
        });
        sales.update("UPDATE purchase_orders SET requires_review=false WHERE public_id=UNHEX(REPLACE(?, '-', ''))",
                f.order());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(sample("order_requires_review")).isEqualTo(review - 1));
    }

    @Test
    void noMetricCarriesAMemberOrderOrRequestIdentifier() throws Exception {
        Fixture f = orderFixture();
        String requestId = "trace-" + UUID.randomUUID();
        assertThat(get(port, "/api/v1/products", requestId).statusCode()).isEqualTo(200);
        var pay = f.client().request("POST", "/api/v1/orders/" + f.order() + "/payment", null, f.client().token);
        assertThat(pay.statusCode()).isEqualTo(200);
        long memberId = sales.queryForObject("SELECT id FROM members WHERE email='user-obs@example.com'", Long.class);
        String pgKey = sales.queryForObject("SELECT LOWER(INSERT(INSERT(INSERT(INSERT(HEX(p.pg_key),9,0,'-'),14,0,'-'),"
                + "19,0,'-'),24,0,'-')) FROM payments p JOIN purchase_orders o ON o.id=p.order_id "
                + "WHERE o.public_id=UNHEX(REPLACE(?, '-', ''))", String.class, f.order());
        String body = scrape();

        assertThat(body).doesNotContain(f.order()).doesNotContain(requestId).doesNotContain(pgKey)
                .doesNotContain("user-obs@example.com");
        Set<String> labelNames = new HashSet<>();
        var label = Pattern.compile("[{,]([A-Za-z_][A-Za-z0-9_]*)=\"");
        for (String line : body.split("\n")) {
            if (line.startsWith("order_") || line.startsWith("http_server_requests") || line.startsWith("http_client_requests")) {
                var matcher = label.matcher(line.substring(0, line.lastIndexOf('}') + 1));
                while (matcher.find()) {
                    labelNames.add(matcher.group(1));
                }
                assertThat(UUID_PATTERN.matcher(line).find()).as(line).isFalse();
                assertThat(line).doesNotContain("memberId=\"" + memberId + "\"");
            }
        }
        assertThat(labelNames).isSubsetOf("result", "client_name", "exception", "method", "outcome", "status", "uri",
                "error", "le");
    }

    @Test
    void aSafeRequestIdIsEchoedAndAnUnsafeOrMissingOneIsReplaced() throws Exception {
        assertThat(get(port, "/api/v1/products", "client-id_42").headers().firstValue("X-Request-Id"))
                .contains("client-id_42");
        String tooLong = "a".repeat(65);
        for (String bad : new String[] {"has space", tooLong, "semi;colon", "%ED%95%9C"}) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/products"))
                    .header("X-Request-Id", bad).GET().build();
            var echoed = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
                    .headers().firstValue("X-Request-Id").orElseThrow();
            assertThat(echoed).as(bad).matches(UUID_PATTERN);
        }
        String first = get(port, "/api/v1/products", null).headers().firstValue("X-Request-Id").orElseThrow();
        String second = get(port, "/api/v1/products", null).headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(first).matches(UUID_PATTERN);
        assertThat(second).matches(UUID_PATTERN).isNotEqualTo(first);
    }

    @Test
    void securityRejectionsStillCarryTheRequestId() throws Exception {
        var unauthenticated = get(port, "/api/v1/orders", "reject-401");
        assertThat(unauthenticated.statusCode()).isEqualTo(401);
        assertThat(unauthenticated.headers().firstValue("X-Request-Id")).contains("reject-401");
        var generated = get(port, "/api/v1/orders", null);
        assertThat(generated.statusCode()).isEqualTo(401);
        assertThat(generated.headers().firstValue("X-Request-Id").orElseThrow()).matches(UUID_PATTERN);
        var noCsrf = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/sessions")).header("X-Request-Id", "reject-403")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(noCsrf.statusCode()).isEqualTo(403);
        assertThat(noCsrf.headers().firstValue("X-Request-Id")).contains("reject-403");
    }

    @Test
    void theRequestIdIsInTheLogContextOfTheRequest() throws Exception {
        var dispatcher = (Logger) LoggerFactory.getLogger("org.springframework.web.servlet.DispatcherServlet");
        Level previous = dispatcher.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        dispatcher.setLevel(Level.DEBUG);
        dispatcher.addAppender(appender);
        try {
            get(port, "/api/v1/products", "log-context-1");
            get(port, "/api/v1/products", "log-context-2");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                var ids = appender.list.stream().map(event -> event.getMDCPropertyMap().get("requestId")).toList();
                assertThat(ids).contains("log-context-1", "log-context-2");
            });
            assertThat(appender.list).allSatisfy(event ->
                    assertThat(event.getMDCPropertyMap().get("requestId")).isIn("log-context-1", "log-context-2"));
        } finally {
            dispatcher.detachAppender(appender);
            dispatcher.setLevel(previous);
        }
    }

    private static final class Client {
        private final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        private String token;

        HttpResponse<String> request(String method, String path, String body, String csrf) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (csrf != null) {
                builder.header("X-CSRF-TOKEN", csrf);
            }
            if (body != null) {
                builder.header("Content-Type", "application/json");
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
            token = JSON.readTree(request("GET", "/api/v1/csrf", null, null).body()).path("data").path("token").asString();
            assertThat(request("POST", "/api/v1/sessions", "{\"email\":\"" + email
                    + "\",\"password\":\"password\"}", token).statusCode()).isEqualTo(200);
            token = JSON.readTree(request("GET", "/api/v1/csrf", null, null).body()).path("data").path("token").asString();
        }
    }
}
