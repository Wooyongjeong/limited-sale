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

class PaymentSimulationIntegrationTest {
    private static final MySQLContainer<?> SALES = new MySQLContainer<>("mysql:8.4.7");
    private static final MySQLContainer<?> PG = new MySQLContainer<>("mysql:8.4.7");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static ConfigurableApplicationContext pgContext;
    private static ConfigurableApplicationContext salesContext;
    private static JdbcTemplate sales;
    private static JdbcTemplate pg;
    private static int port;
    private static int pgPort;
    private static UUID orderId;
    private static UUID paymentKey;

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
        long memberId = sales.queryForObject("SELECT id FROM members WHERE email='user-sim@example.com'", Long.class);
        long product = salesContext.getBean(ProductCommand.class).register("simulation product", "", 1000L).productId();
        sales.update("INSERT INTO sales(product_id,name,description,product_name_snapshot,unit_price,starts_at,ends_at,"
                + "total_quantity,per_member_limit,created_at,updated_at) VALUES (?, 'sale', '', 'product', 1000, "
                + "UTC_TIMESTAMP(6)-INTERVAL 1 HOUR,UTC_TIMESTAMP(6)+INTERVAL 1 HOUR,10,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", product);
        long sale = sales.queryForObject("SELECT MAX(id) FROM sales", Long.class);
        orderId = UUID.randomUUID();
        paymentKey = UUID.randomUUID();
        sales.update("INSERT INTO purchase_orders(public_id,member_id,request_key,sale_id,product_id,product_name_snapshot,"
                + "unit_price,total_amount,quantity,status,expires_at,created_at,updated_at) VALUES "
                + "(UNHEX(REPLACE(?, '-', '')),?,UNHEX(REPLACE(?, '-', '')),?,?, 'product',1000,1000,1,"
                + "'AWAITING_PAYMENT',UTC_TIMESTAMP(6)+INTERVAL 5 MINUTE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                orderId.toString(), memberId, UUID.randomUUID().toString(), sale, product);
        long order = sales.queryForObject("SELECT MAX(id) FROM purchase_orders", Long.class);
        sales.update("INSERT INTO payments(order_id,pg_key,amount,status,created_at,updated_at) VALUES "
                + "(?,UNHEX(REPLACE(?, '-', '')),1000,'READY',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                order, paymentKey.toString());
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

    @Test
    void adminRequiresSessionAndCsrfAndNeverStartsPayment() throws Exception {
        Client anonymous = new Client();
        String path = "/api/v1/admin/orders/" + orderId + "/payment-simulation";
        assertThat(anonymous.request("PUT", path, "{\"scenario\":\"DELAY\"}", null).statusCode()).isIn(401, 403);
        Client user = new Client();
        user.login("user-sim@example.com");
        assertThat(user.request("PUT", path, "{\"scenario\":\"DELAY\"}", user.token).statusCode()).isEqualTo(403);
        Client admin = new Client();
        admin.login("admin-sim@example.com");
        assertThat(admin.request("PUT", path, "{\"scenario\":\"DELAY\"}", null).statusCode()).isEqualTo(403);
        var result = admin.request("PUT", path, "{\"scenario\":\"DELAY\"}", admin.token);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body()).contains(orderId.toString(), "DELAY").doesNotContain(paymentKey.toString(), "pgKey");
        assertThat(pg.queryForObject("SELECT COUNT(*) FROM mock_payments", Integer.class)).isZero();
        assertThat(sales.queryForObject("SELECT status FROM payments WHERE pg_key=UNHEX(REPLACE(?, '-', ''))",
                String.class, paymentKey.toString())).isEqualTo("READY");
        assertThat(pg.queryForObject("SELECT COUNT(*) FROM mock_scenario_settings WHERE scenario='DELAY'",
                Integer.class)).isEqualTo(1);
        assertThat(admin.request("PUT", path, "{\"scenario\":\"SUCCESS\",\"pgKey\":\"x\"}",
                admin.token).statusCode()).isEqualTo(400);
        assertThat(admin.request("PUT", path, "{\"scenario\":\"SUCCESS\",\"scenario\":\"DECLINE\"}",
                admin.token).statusCode()).isEqualTo(400);
        for (String invalid : java.util.List.of("null", "{}", "{\"scenario\":null}",
                "{\"scenario\":\"DELAY\",\"delaySeconds\":null}",
                "{\"scenario\":\"DELAY\",\"delaySeconds\":601}",
                "{\"scenario\":\"DELAY\",\"delaySeconds\":1.5}",
                "{\"scenario\":\"SUCCESS\",\"amount\":1000}",
                "{\"scenario\":\"SUCCESS\"} {}")) {
            assertThat(admin.request("PUT", path, invalid, admin.token).statusCode()).isEqualTo(400);
        }
        assertThat(admin.request("PUT", "/api/v1/admin/orders/" + UUID.randomUUID() + "/payment-simulation",
                "{\"scenario\":\"SUCCESS\"}", admin.token).statusCode()).isEqualTo(404);
        var charge = HttpRequest.newBuilder(URI.create("http://localhost:" + pgPort + "/api/v1/payments"))
                .header("X-Mock-PG-Key", "test-secret").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"pgKey\":\"" + paymentKey + "\",\"amount\":1000}"))
                .build();
        assertThat(HttpClient.newHttpClient().send(charge, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(202);
        assertThat(admin.request("PUT", path, "{\"scenario\":\"SUCCESS\"}", admin.token).statusCode()).isEqualTo(409);
        pgContext.close();
        assertThat(admin.request("PUT", path, "{\"scenario\":\"SUCCESS\"}", admin.token).statusCode()).isEqualTo(503);
    }

    @Test
    void unsupportedMediaTypeUsesCommonResponse() throws Exception {
        Client admin = new Client();
        admin.login("admin-sim@example.com");
        String path = "/api/v1/admin/orders/" + orderId + "/payment-simulation";
        var response = admin.request("PUT", path, "plain text", admin.token, "text/plain");
        assertCommonError(response, 415, "UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 요청 본문 형식입니다");
    }

    @Test
    void databaseFailureUsesCommonResponseWithoutExceptionDetails() throws Exception {
        Client admin = new Client();
        admin.login("admin-sim@example.com");
        String path = "/api/v1/admin/orders/" + orderId + "/payment-simulation";
        sales.execute("RENAME TABLE payments TO payments_failure_test");
        try {
            var response = admin.request("PUT", path, "{\"scenario\":\"SUCCESS\"}", admin.token);
            assertCommonError(response, 500, "INTERNAL_ERROR", "서버 오류가 발생했습니다");
            assertThat(response.body()).doesNotContain("SQLException", "payments_failure_test", "test-secret");
        } finally {
            sales.execute("RENAME TABLE payments_failure_test TO payments");
        }
    }

    private static void assertCommonError(HttpResponse<String> response, int status, String code, String message)
            throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        var body = JSON.readTree(response.body());
        assertThat(body.size()).isEqualTo(4);
        assertThat(body.path("httpStatus").intValue()).isEqualTo(status);
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.has("data")).isTrue();
        assertThat(body.path("data").isNull()).isTrue();
        assertThat(body.path("message").asText()).isEqualTo(message);
    }

    @Test
    void forwardingRejectsAnEnclosingDatabaseTransaction() {
        var payment = salesContext.getBean(com.wooyongjeong.limitedsale.payment.api.PaymentSimulation.class);
        var transactions = salesContext.getBean(org.springframework.transaction.support.TransactionTemplate.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transactions.execute(status -> {
            payment.configure(1L, new com.wooyongjeong.limitedsale.payment.api.SimulationConfiguration("SUCCESS", null));
            return null;
        })).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void nonLocalProfileDoesNotRegisterSimulationEndpoint() {
        try (var prod = new SpringApplicationBuilder(LimitedSaleApplication.class).run("--server.port=0",
                "--spring.profiles.active=production", "--spring.datasource.url=" + SALES.getJdbcUrl(),
                "--spring.datasource.username=" + SALES.getUsername(), "--spring.datasource.password=" + SALES.getPassword(),
                "--spring.flyway.locations=filesystem:src/main/resources/db/migration,classpath:db/nickmigration",
                "--spring.data.redis.host=" + REDIS.getHost(), "--spring.data.redis.port=" + REDIS.getMappedPort(6379))) {
            var mapping = prod.getBean("requestMappingHandlerMapping",
                    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
            assertThat(mapping.getHandlerMethods().keySet().stream().map(Object::toString))
                    .noneMatch(path -> path.contains("payment-simulation"));
        }
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

        void login(String email) throws Exception {
            token = JSON.readTree(request("GET", "/api/v1/csrf", null, null).body()).path("data").path("token").asText();
            assertThat(request("POST", "/api/v1/sessions", "{\"email\":\"" + email
                    + "\",\"password\":\"password\"}", token).statusCode()).isEqualTo(200);
            token = JSON.readTree(request("GET", "/api/v1/csrf", null, null).body()).path("data").path("token").asText();
        }
    }
}
