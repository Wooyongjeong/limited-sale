package com.wooyongjeong.limitedsale.order;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;

import static com.wooyongjeong.limitedsale.support.ResponseEnvelope.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Two sales applications share one MySQL and one Redis, as two instances of a deployment would. The window is long
 * enough that a test can send its whole burst inside it, and each test uses its own member so the counters of one
 * test never leak into another.
 */
@ExtendWith(OutputCaptureExtension.class)
class OrderRateLimitIntegrationTest {
    private static final Duration WINDOW = Duration.ofSeconds(4);
    private static final MySQLContainer SALES = new MySQLContainer("mysql:8.4.7")
        .withCommand("--log-bin-trust-function-creators=1");
    private static final MySQLContainer PG = new MySQLContainer("mysql:8.4.7")
        .withUrlParam("connectionTimeZone", "UTC").withUrlParam("forceConnectionTimeZoneToSession", "true");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger MEMBERS = new AtomicInteger();
    private static ConfigurableApplicationContext pgContext;
    private static ConfigurableApplicationContext instanceA;
    private static ConfigurableApplicationContext instanceB;
    private static JdbcTemplate sales;
    private static StringRedisTemplate redis;
    private static int pgPort;
    private static int portA;
    private static int portB;
    private static long product;

    @BeforeAll
    static void start() {
        SALES.start();
        PG.start();
        REDIS.start();
        pgContext = new SpringApplicationBuilder(MockPgApplication.class).run("--server.port=0",
            "--mock-pg.api-key=test-secret", "--spring.config.location=file:mock-pg/src/main/resources/application.yml",
            "--spring.flyway.locations=filesystem:mock-pg/src/main/resources/db/migration",
            "--spring.datasource.url=" + PG.getJdbcUrl(), "--spring.datasource.username=" + PG.getUsername(),
            "--spring.datasource.password=" + PG.getPassword(),
            "--spring.autoconfigure.exclude=org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration,"
                + "org.springframework.boot.session.autoconfigure.SessionAutoConfiguration,"
                + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration,"
                + "org.springframework.boot.security.autoconfigure.actuate.web.servlet"
                + ".ManagementWebSecurityAutoConfiguration");
        pgPort = ((ServletWebServerApplicationContext) pgContext).getWebServer().getPort();
        instanceA = salesApplication(true);
        instanceB = salesApplication(true);
        portA = port(instanceA);
        portB = port(instanceB);
        sales = instanceA.getBean(JdbcTemplate.class);
        redis = instanceA.getBean(StringRedisTemplate.class);
        product = instanceA.getBean(ProductCommand.class).register("rate limit product", "", 1000L).productId();
    }

    @AfterAll
    static void stop() {
        for (var context : new ConfigurableApplicationContext[] {instanceB, instanceA, pgContext}) {
            if (context != null) {
                context.close();
            }
        }
        REDIS.stop();
        PG.stop();
        SALES.stop();
    }

    /**
     * Uses the shipped application.yml; the limit window is shortened to what a test can wait for. The tests below
     * count to five and six requests, so they pin max-requests to 5 instead of depending on the application default,
     * which is one; the default has its own test.
     */
    private static ConfigurableApplicationContext salesApplication(boolean limitEnabled) {
        return salesApplication(limitEnabled, "5");
    }

    /** A null maxRequests leaves the application default in place. */
    private static ConfigurableApplicationContext salesApplication(boolean limitEnabled, String maxRequests) {
        List<String> arguments = new ArrayList<>(List.of("--server.port=0",
            "--management.server.port=0", "--limited-sale.order.rate-limit.enabled=" + limitEnabled,
            "--limited-sale.order.rate-limit.window=" + WINDOW,
            "--spring.config.additional-location=file:src/main/resources/application.yml",
            "--spring.profiles.active=test", "--limited-sale.recovery.enabled=false", "--spring.flyway.enabled=true",
            "--spring.jpa.hibernate.ddl-auto=validate", "--MOCK_PG_API_KEY=test-secret",
            "--spring.flyway.locations=filesystem:src/main/resources/db/migration,classpath:db/nickmigration",
            "--MOCK_PG_BASE_URL=http://localhost:" + pgPort, "--spring.datasource.url=" + SALES.getJdbcUrl(),
            "--spring.datasource.username=" + SALES.getUsername(),
            "--spring.datasource.password=" + SALES.getPassword(),
            "--spring.data.redis.host=" + REDIS.getHost(), "--spring.data.redis.port=" + REDIS.getMappedPort(6379)));
        if (maxRequests != null) {
            arguments.add("--limited-sale.order.rate-limit.max-requests=" + maxRequests);
        }
        return new SpringApplicationBuilder(LimitedSaleApplication.class).run(arguments.toArray(String[]::new));
    }

    private static int port(ConfigurableApplicationContext context) {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    private static final class Member {
        final long id;
        final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String token;

        Member(int loginPort) throws Exception {
            int number = MEMBERS.incrementAndGet();
            String email = "limit" + number + "@example.com";
            instanceA.getBean(MemberService.class).register(email, "password", "limit" + number);
            id = sales.queryForObject("SELECT id FROM members WHERE email=?", Long.class, email);
            token = csrf(loginPort);
            assertThat(send(loginPort, "POST", "/api/v1/sessions",
                "{\"email\":\"" + email + "\",\"password\":\"password\"}", null).statusCode()).isEqualTo(200);
            token = csrf(loginPort);
        }

        private String csrf(int port) throws Exception {
            return JSON.readTree(send(port, "GET", "/api/v1/csrf", null, null).body()).path("data").path("token")
                .asString();
        }

        HttpResponse<String> send(int port, String method, String path, String body, String key) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30));
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

        HttpResponse<String> order(int port, String key, long saleId, int quantity) throws Exception {
            return send(port, "POST", "/api/v1/orders", "{\"saleId\":" + saleId + ",\"quantity\":" + quantity + "}",
                key);
        }

        HttpResponse<String> pay(int port, String orderId) throws Exception {
            return send(port, "POST", "/api/v1/orders/" + orderId + "/payment", null, null);
        }

        String createKey() {
            return "limited-sale:rate-limit:order:create:" + id;
        }

        String paymentKey() {
            return "limited-sale:rate-limit:order:payment:" + id;
        }
    }

    private static long sale(int total, int limit) {
        sales.update("INSERT INTO sales(product_id,name,description,product_name_snapshot,unit_price,starts_at,ends_at,"
            + "total_quantity,per_member_limit,created_at,updated_at) VALUES (?, 'sale', '', 'product', 1000, "
            + "UTC_TIMESTAMP(6)-INTERVAL 1 HOUR, UTC_TIMESTAMP(6)+INTERVAL 1 HOUR, ?, ?, "
            + "UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", product, total, limit);
        return sales.queryForObject("SELECT MAX(id) FROM sales", Long.class);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static void awaitWindowEnd(String key) {
        await().atMost(WINDOW.plusSeconds(6)).until(() -> !redis.hasKey(key));
    }

    private static void assertRateLimited(HttpResponse<String> response) throws Exception {
        assertCode(response, OrderResponseCode.ORDER_RATE_LIMITED);
        assertThat(JSON.readTree(response.body()).path("data").isNull()).isTrue();
        long retryAfter = Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow());
        assertThat(retryAfter).isBetween(1L, WINDOW.toSeconds());
    }

    private static int requestRows(long memberId) {
        return sales.queryForObject("SELECT COUNT(*) FROM order_request_results WHERE member_id=?", Integer.class,
            memberId);
    }

    private static int orderRows(long memberId) {
        return sales.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE member_id=?", Integer.class,
            memberId);
    }

    private static int reserved(long saleId) {
        return sales.queryForObject("SELECT reserved_quantity FROM sales WHERE id=?", Integer.class, saleId);
    }

    private static int sold(long saleId) {
        return sales.queryForObject("SELECT sold_quantity FROM sales WHERE id=?", Integer.class, saleId);
    }

    private static double count(ConfigurableApplicationContext context, String operation, String result) {
        return context.getBean(MeterRegistry.class).get("order.rate.limit").tag("operation", operation)
            .tag("result", result).counter().count();
    }

    @Test
    void theSixthCreateInsideTheWindowIsRejectedWithoutTouchingStockAndAllowedAgainAfterTheWindow()
            throws Exception {
        var member = new Member(portA);
        long saleId = sale(100, 100);
        for (int i = 0; i < 5; i++) {
            assertCode(member.order(portA, newKey(), saleId, 1), OrderResponseCode.ORDER_CREATED);
        }
        assertThat(redis.getExpire(member.createKey(), java.util.concurrent.TimeUnit.MILLISECONDS))
            .isBetween(1L, WINDOW.toMillis());
        assertThat(redis.opsForValue().get(member.createKey())).isEqualTo("5");
        int requests = requestRows(member.id);
        int orders = orderRows(member.id);
        assertThat(requests).isEqualTo(5);
        assertThat(reserved(saleId)).isEqualTo(5);

        String rejectedKey = newKey();
        var rejected = member.order(portA, rejectedKey, saleId, 1);
        assertRateLimited(rejected);
        // A retry with the same key still counts, so the limit cannot be bypassed by repeating one request.
        assertRateLimited(member.order(portB, rejectedKey, saleId, 1));
        assertThat(redis.opsForValue().get(member.createKey())).isEqualTo("7");
        assertThat(requestRows(member.id)).isEqualTo(requests);
        assertThat(orderRows(member.id)).isEqualTo(orders);
        assertThat(sales.queryForObject("SELECT COUNT(*) FROM order_request_results WHERE member_id=? AND "
            + "request_key=UNHEX(REPLACE(?, '-', ''))", Integer.class, member.id, rejectedKey)).isZero();
        assertThat(reserved(saleId)).isEqualTo(5);
        assertThat(sales.queryForObject("SELECT purchased_quantity + reserved_quantity FROM sale_buyer_quantities "
            + "WHERE sale_id=? AND member_id=?", Integer.class, saleId, member.id)).isEqualTo(5);

        awaitWindowEnd(member.createKey());
        var accepted = member.order(portA, rejectedKey, saleId, 1);
        assertCode(accepted, OrderResponseCode.ORDER_CREATED);
        assertThat(requestRows(member.id)).isEqualTo(requests + 1);
        assertThat(reserved(saleId)).isEqualTo(6);
        assertCode(member.order(portA, newKey(), saleId, 1), OrderResponseCode.ORDER_CREATED);
    }

    @Test
    void theApplicationDefaultAllowsOneRequestPerWindowAndRejectsTheSecond() throws Exception {
        try (var defaults = salesApplication(true, null)) {
            int defaultPort = port(defaults);
            var member = new Member(defaultPort);
            long saleId = sale(100, 100);

            assertCode(member.order(defaultPort, newKey(), saleId, 1), OrderResponseCode.ORDER_CREATED);
            assertRateLimited(member.order(defaultPort, newKey(), saleId, 1));

            assertThat(redis.opsForValue().get(member.createKey())).isEqualTo("2");
            assertThat(orderRows(member.id)).isEqualTo(1);
            assertThat(reserved(saleId)).isEqualTo(1);
        }
    }

    @Test
    void paymentHasItsOwnCounterAndStaysAllowedWhileCreationIsBlocked() throws Exception {
        var member = new Member(portA);
        long saleId = sale(100, 100);
        String first = JSON.readTree(member.order(portA, newKey(), saleId, 1).body()).path("data").path("orderId")
            .asString();
        for (int i = 0; i < 4; i++) {
            assertCode(member.order(portA, newKey(), saleId, 1), OrderResponseCode.ORDER_CREATED);
        }
        assertRateLimited(member.order(portA, newKey(), saleId, 1));

        assertCode(member.pay(portA, first), OrderResponseCode.PAYMENT_SUCCEEDED);
        assertThat(sold(saleId)).isEqualTo(1);
        assertThat(reserved(saleId)).isEqualTo(4);
        for (int i = 0; i < 4; i++) {
            assertCode(member.pay(portB, first), OrderResponseCode.PAYMENT_SUCCEEDED);
        }
        var rejected = member.pay(portA, first);
        assertRateLimited(rejected);
        assertThat(redis.opsForValue().get(member.createKey())).isEqualTo("6");
        assertThat(redis.opsForValue().get(member.paymentKey())).isEqualTo("6");
        assertThat(sold(saleId)).isEqualTo(1);
        assertThat(sales.queryForObject("SELECT COUNT(*) FROM payments p JOIN purchase_orders o ON o.id=p.order_id "
            + "WHERE o.member_id=?", Integer.class, member.id)).isEqualTo(5);
    }

    @Test
    void anotherMemberIsNotAffectedByAnExhaustedWindow() throws Exception {
        var heavy = new Member(portA);
        var other = new Member(portB);
        long saleId = sale(100, 100);
        for (int i = 0; i < 5; i++) {
            assertCode(heavy.order(portA, newKey(), saleId, 1), OrderResponseCode.ORDER_CREATED);
        }
        assertRateLimited(heavy.order(portA, newKey(), saleId, 1));
        for (int i = 0; i < 5; i++) {
            assertCode(other.order(portB, newKey(), saleId, 1), OrderResponseCode.ORDER_CREATED);
        }
        assertThat(redis.hasKey(other.paymentKey())).isFalse();
        assertThat(orderRows(other.id)).isEqualTo(5);
    }

    @Test
    void requestsAlternatingBetweenTwoInstancesShareOneCounter() throws Exception {
        var member = new Member(portA);
        long saleId = sale(100, 100);
        double allowedA = count(instanceA, "create", "allowed");
        double allowedB = count(instanceB, "create", "allowed");
        double rejectedA = count(instanceA, "create", "rejected");
        double rejectedB = count(instanceB, "create", "rejected");
        for (int i = 0; i < 6; i++) {
            int port = i % 2 == 0 ? portA : portB;
            var response = member.order(port, newKey(), saleId, 1);
            if (i < 5) {
                assertCode(response, OrderResponseCode.ORDER_CREATED);
            } else {
                assertRateLimited(response);
            }
        }
        // Three requests reached A and three reached B; neither instance alone saw more than the limit.
        assertThat(count(instanceA, "create", "allowed") - allowedA).isEqualTo(3);
        assertThat(count(instanceB, "create", "allowed") - allowedB).isEqualTo(2);
        assertThat(count(instanceB, "create", "rejected") - rejectedB).isEqualTo(1);
        assertThat(count(instanceA, "create", "rejected") - rejectedA).isZero();
        assertThat(orderRows(member.id)).isEqualTo(5);
    }

    @Test
    void concurrentRequestsFromBothInstancesAdmitExactlyTheLimit() throws Exception {
        var member = new Member(portA);
        long saleId = sale(100, 100);
        int requests = 20;
        ExecutorService workers = Executors.newFixedThreadPool(requests);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                int port = i % 2 == 0 ? portA : portB;
                results.add(workers.submit(() -> member.order(port, newKey(), saleId, 1).statusCode()));
            }
            int created = 0;
            int limited = 0;
            for (Future<Integer> result : results) {
                int status = result.get();
                if (status == 201) {
                    created++;
                } else if (status == 429) {
                    limited++;
                }
            }
            assertThat(created).isEqualTo(5);
            assertThat(limited).isEqualTo(requests - 5);
        } finally {
            workers.shutdownNow();
        }
        assertThat(orderRows(member.id)).isEqualTo(5);
        assertThat(reserved(saleId)).isEqualTo(5);
    }

    @Test
    void readsAndInvalidInputAreNeverCounted() throws Exception {
        var member = new Member(portA);
        long saleId = sale(100, 100);
        String orderId = JSON.readTree(member.order(portA, newKey(), saleId, 1).body()).path("data").path("orderId")
            .asString();
        assertThat(redis.opsForValue().get(member.createKey())).isEqualTo("1");

        for (int i = 0; i < 12; i++) {
            assertCode(member.send(i % 2 == 0 ? portA : portB, "GET", "/api/v1/orders/" + orderId, null, null),
                OrderResponseCode.ORDER_FOUND);
            assertCode(member.send(portA, "GET", "/api/v1/orders", null, null), OrderResponseCode.ORDERS_FOUND);
        }
        assertThat(redis.opsForValue().get(member.createKey())).isEqualTo("1");
        assertThat(redis.hasKey(member.paymentKey())).isFalse();

        // Each of these fails input validation before the use case starts, so none of them uses up the budget.
        for (int i = 0; i < 10; i++) {
            assertThat(member.send(portA, "POST", "/api/v1/orders", "{\"saleId\":" + saleId + ",\"quantity\":1}",
                null).statusCode()).as("missing Idempotency-Key").isEqualTo(400);
            assertThat(member.order(portA, newKey(), saleId, 0).statusCode()).as("zero quantity").isEqualTo(400);
            assertThat(member.send(portB, "POST", "/api/v1/orders", "{\"saleId\":", newKey()).statusCode())
                .as("malformed JSON").isEqualTo(400);
            assertThat(member.send(portB, "POST", "/api/v1/orders/not-a-uuid/payment", null, null).statusCode())
                .as("malformed order id").isEqualTo(400);
        }
        assertThat(redis.opsForValue().get(member.createKey())).isEqualTo("1");
        assertThat(redis.hasKey(member.paymentKey())).isFalse();
        for (int i = 0; i < 4; i++) {
            assertCode(member.order(portA, newKey(), saleId, 1), OrderResponseCode.ORDER_CREATED);
        }
        assertRateLimited(member.order(portA, newKey(), saleId, 1));
    }

    @Test
    void aRedisCommandFailureLetsTheRequestThroughAndIsRecordedAsBypassedWithoutAStackTrace(CapturedOutput output)
            throws Exception {
        var member = new Member(portA);
        long saleId = sale(100, 100);
        // A key of the wrong type makes only the limit script fail; the session and its login keep working.
        redis.opsForHash().put(member.createKey(), "field", "value");
        double bypassed = count(instanceA, "create", "bypassed");
        double allowed = count(instanceA, "create", "allowed");
        double rejected = count(instanceA, "create", "rejected");
        try {
            for (int i = 0; i < 7; i++) {
                assertCode(member.order(portA, newKey(), saleId, 1), OrderResponseCode.ORDER_CREATED);
            }
        } finally {
            redis.delete(member.createKey());
        }
        assertThat(count(instanceA, "create", "bypassed") - bypassed).isEqualTo(7);
        assertThat(count(instanceA, "create", "allowed") - allowed).isZero();
        assertThat(count(instanceA, "create", "rejected") - rejected).isZero();
        assertThat(orderRows(member.id)).isEqualTo(7);
        assertThat(output.getAll()).contains("주문 요청 제한 확인 실패").contains("WRONGTYPE")
            .doesNotContain("\tat org.springframework.data.redis");
        // The failure ended with the bad key: the limit applies again to the same member.
        for (int i = 0; i < 5; i++) {
            assertCode(member.order(portA, newKey(), saleId, 1), OrderResponseCode.ORDER_CREATED);
        }
        assertRateLimited(member.order(portA, newKey(), saleId, 1));
    }

    @Test
    void aDisabledLimitDoesNotLimitOrderOrCreateRedisKeys() throws Exception {
        try (var unlimited = salesApplication(false)) {
            int port = port(unlimited);
            var member = new Member(port);
            long saleId = sale(100, 100);
            for (int i = 0; i < 8; i++) {
                assertCode(member.order(port, newKey(), saleId, 1), OrderResponseCode.ORDER_CREATED);
            }
            assertCode(member.pay(port, sales.queryForObject("SELECT LOWER(CONCAT_WS('-', SUBSTR(HEX(public_id),1,8), "
                + "SUBSTR(HEX(public_id),9,4), SUBSTR(HEX(public_id),13,4), SUBSTR(HEX(public_id),17,4), "
                + "SUBSTR(HEX(public_id),21,12))) FROM purchase_orders WHERE member_id=? LIMIT 1", String.class,
                member.id)), OrderResponseCode.PAYMENT_SUCCEEDED);
            assertThat(redis.keys("limited-sale:rate-limit:*:" + member.id)).isEmpty();
            for (String operation : new String[] {"create", "payment"}) {
                for (String result : new String[] {"allowed", "rejected", "bypassed"}) {
                    assertThat(count(unlimited, operation, result)).as(operation + "/" + result).isZero();
                }
            }
        }
    }

    @Test
    void aKeyWithoutExpiryGetsAnExpiryInsteadOfBlockingTheMemberForever() throws Exception {
        var member = new Member(portA);
        long saleId = sale(100, 100);
        redis.opsForValue().set(member.createKey(), "100");
        assertThat(redis.getExpire(member.createKey())).isEqualTo(-1L);

        assertRateLimited(member.order(portA, newKey(), saleId, 1));
        assertThat(redis.getExpire(member.createKey(), java.util.concurrent.TimeUnit.MILLISECONDS))
            .isBetween(1L, WINDOW.toMillis());
        assertThat(orderRows(member.id)).isZero();

        awaitWindowEnd(member.createKey());
        assertCode(member.order(portA, newKey(), saleId, 1), OrderResponseCode.ORDER_CREATED);
    }

    @Test
    void theMetricIsExposedWithOnlyTheOperationAndResultLabels() throws Exception {
        var member = new Member(portA);
        long saleId = sale(100, 100);
        for (int i = 0; i < 6; i++) {
            member.order(portA, newKey(), saleId, 1);
        }
        int managementPort = Integer.parseInt(instanceA.getEnvironment().getProperty("local.management.port"));
        String body = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
            URI.create("http://localhost:" + managementPort + "/actuator/prometheus")).build(),
            HttpResponse.BodyHandlers.ofString()).body();
        Pattern label = Pattern.compile("(\\w+)=\"([^\"]*)\"");
        Set<String> combinations = new HashSet<>();
        for (String line : body.split("\\R")) {
            if (!line.startsWith("order_rate_limit_total{")) {
                continue;
            }
            Matcher matcher = label.matcher(line.substring(line.indexOf('{'), line.indexOf('}')));
            Set<String> names = new HashSet<>();
            String operation = null;
            String result = null;
            while (matcher.find()) {
                names.add(matcher.group(1));
                operation = matcher.group(1).equals("operation") ? matcher.group(2) : operation;
                result = matcher.group(1).equals("result") ? matcher.group(2) : result;
            }
            assertThat(names).as(line).containsExactlyInAnyOrder("operation", "result");
            combinations.add(operation + "/" + result);
        }
        assertThat(combinations).containsExactlyInAnyOrder("create/allowed", "create/rejected", "create/bypassed",
            "payment/allowed", "payment/rejected", "payment/bypassed");
        assertThat(body).doesNotContain("member=").doesNotContain("memberId");
    }
}
