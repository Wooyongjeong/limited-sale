package com.wooyongjeong.limitedsale.sale;

import com.wooyongjeong.limitedsale.LimitedSaleApplication;
import com.wooyongjeong.limitedsale.member.application.MemberService;
import com.wooyongjeong.limitedsale.product.api.ProductCommand;
import com.wooyongjeong.limitedsale.sale.api.SaleCommand;
import com.wooyongjeong.limitedsale.sale.api.SaleCreation;
import com.wooyongjeong.limitedsale.sale.api.SaleDetails;
import com.wooyongjeong.limitedsale.sale.api.SalePatch;
import com.wooyongjeong.limitedsale.sale.api.SaleQuery;
import com.wooyongjeong.limitedsale.sale.presentation.code.SaleResponseCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static com.wooyongjeong.limitedsale.support.ResponseEnvelope.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Runs the sale detail cache against real MySQL and Redis; the clock is controlled, the databases are not mocked. */
class SaleCacheIntegrationTest {
    private static final MySQLContainer DB = new MySQLContainer("mysql:8.4.7")
        .withDatabaseName("sales").withUsername("sales_user").withPassword("test_password");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final HttpClient ANONYMOUS = HttpClient.newHttpClient();
    private static final ControlledClock CLOCK = new ControlledClock();
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static SaleCommand commands;
    private static StringRedisTemplate redis;
    private static MeterRegistry meters;
    private static int port;
    private static int managementPort;
    private static long product;

    /** Follows the real time until a test pins it. */
    private static final class ControlledClock extends Clock {
        private volatile Instant pinned;

        void pin(Instant instant) {
            pinned = instant;
        }

        void release() {
            pinned = null;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Instant value = pinned;
            return value == null ? Instant.now() : value;
        }
    }

    // The management endpoints are only reachable on a port of their own; port 0 would leave them behind the login.
    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ConfigurableApplicationContext application(String... extra) {
        ApplicationContextInitializer<ConfigurableApplicationContext> clock = candidate ->
            ((GenericApplicationContext) candidate).registerBean("controlledClock", Clock.class, () -> CLOCK,
                bean -> bean.setPrimary(true));
        // Loads the shipped settings explicitly (until 4-2 the Mock PG's application.yml shadowed them on this classpath).
        List<String> arguments = new ArrayList<>(List.of("--server.port=0", "--management.server.port=" + freePort(),
            "--spring.config.additional-location=file:src/main/resources/application.yml",
            "--limited-sale.recovery.enabled=false", "--spring.datasource.url=" + DB.getJdbcUrl(),
            "--spring.datasource.username=" + DB.getUsername(), "--spring.datasource.password=" + DB.getPassword(),
            "--spring.data.redis.host=" + REDIS.getHost(), "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
            "--spring.data.redis.timeout=1s", "--spring.data.redis.connect-timeout=1s",
            "--spring.flyway.locations=filesystem:src/main/resources/db/migration,classpath:db/nickmigration"));
        arguments.addAll(List.of(extra));
        return new SpringApplicationBuilder(LimitedSaleApplication.class).initializers(clock)
            .run(arguments.toArray(String[]::new));
    }

    @BeforeAll
    static void start() {
        DB.start();
        REDIS.start();
        context = application();
        port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        managementPort = Integer.parseInt(context.getEnvironment().getProperty("local.management.port"));
        jdbc = context.getBean(JdbcTemplate.class);
        commands = context.getBean(SaleCommand.class);
        redis = context.getBean(StringRedisTemplate.class);
        meters = context.getBean(MeterRegistry.class);
        product = context.getBean(ProductCommand.class).register("cache product", "", 1000L).productId();
        context.getBean(MemberService.class).initializeAdmin("cache-admin@example.com", "password", "cacheadmin");
    }

    @BeforeEach
    void reset() {
        CLOCK.release();
        jdbc.update("DELETE FROM sales");
        redis.delete(redis.keys("limited-sale:cache:*"));
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
        REDIS.stop();
        DB.stop();
    }

    private static final class Admin {
        private final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        private String token;

        HttpResponse<String> send(String method, String path, String body) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (token != null) {
                builder.header("X-CSRF-TOKEN", token);
            }
            if (body != null) {
                builder.header("Content-Type", "application/json");
            }
            return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }

        private void refreshToken() throws Exception {
            token = null;
            token = JSON.readTree(send("GET", "/api/v1/csrf", null).body()).path("data").path("token").asString();
        }

        static Admin login() throws Exception {
            Admin admin = new Admin();
            admin.refreshToken();
            assertThat(admin.send("POST", "/api/v1/sessions",
                "{\"email\":\"cache-admin@example.com\",\"password\":\"password\"}").statusCode()).isEqualTo(200);
            admin.refreshToken();
            return admin;
        }
    }

    private static HttpResponse<String> get(long id) throws Exception {
        return ANONYMOUS.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/sales/" + id))
            .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode detail(long id) throws Exception {
        var response = get(id);
        assertCode(response, SaleResponseCode.SALE_FOUND);
        return JSON.readTree(response.body()).path("data");
    }

    private static String key(long id) {
        return "limited-sale:cache:sale:v1:" + id;
    }

    private static boolean cached(long id) {
        return Boolean.TRUE.equals(redis.hasKey(key(id)));
    }

    private static double count(MeterRegistry registry, String name, String result) {
        return registry.get(name).tag("result", result).counter().count();
    }

    private static double requests(String result) {
        return count(meters, "sale.cache.requests", result);
    }

    private static double evictions(String result) {
        return count(meters, "sale.cache.evictions", result);
    }

    private static Instant future() {
        return Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS);
    }

    private static SaleDetails create() {
        Instant start = future();
        return commands.create(new SaleCreation(product, "sale", "description", null, start,
            start.plusSeconds(3600), 10, null));
    }

    private static SaleDetails createStarted() {
        SaleDetails sale = create();
        jdbc.update("UPDATE sales SET starts_at = UTC_TIMESTAMP(6) - INTERVAL 1 HOUR WHERE id = ?", sale.saleId());
        return sale;
    }

    private static void rewrite(long id, java.util.function.Consumer<ObjectNode> change) throws Exception {
        ObjectNode node = (ObjectNode) JSON.readTree(redis.opsForValue().get(key(id)));
        change.accept(node);
        redis.opsForValue().set(key(id), node.toString(), Duration.ofSeconds(60));
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

    @Test
    void firstReadFillsTheKeyWithinTheTtlAndTheSecondReadHits() throws Exception {
        SaleDetails sale = create();
        assertThat(cached(sale.saleId())).as("creating a sale does not fill the cache").isFalse();
        double misses = requests("miss");
        double hits = requests("hit");

        JsonNode first = detail(sale.saleId());

        assertThat(requests("miss")).isEqualTo(misses + 1);
        assertThat(cached(sale.saleId())).isTrue();
        long ttl = redis.getExpire(key(sale.saleId()), TimeUnit.MILLISECONDS);
        assertThat(ttl).isPositive().isLessThanOrEqualTo(60_000);
        // The calculated status is not stored: the boundaries must move without waiting for the entry to expire.
        Set<String> stored = Set.copyOf(JSON.readTree(redis.opsForValue().get(key(sale.saleId()))).propertyNames());
        assertThat(stored).doesNotContain("status", "reservedQuantity")
            .contains("soldQuantity", "stopped", "version", "productNameSnapshot", "createdAt", "updatedAt");

        JsonNode second = detail(sale.saleId());

        assertThat(second).isEqualTo(first);
        assertThat(requests("hit")).isEqualTo(hits + 1);
        assertThat(requests("miss")).isEqualTo(misses + 1);
    }

    @Test
    void cacheHitIsServedWhileMySqlIsPaused() throws Exception {
        SaleDetails cachedSale = create();
        SaleDetails uncachedSale = create();
        JsonNode expected = detail(cachedSale.saleId());
        double hits = requests("hit");
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(DB.getContainerId()).exec();
        try {
            long begin = System.nanoTime();
            var response = get(cachedSale.saleId());
            Duration elapsed = Duration.ofNanos(System.nanoTime() - begin);

            assertCode(response, SaleResponseCode.SALE_FOUND);
            assertThat(JSON.readTree(response.body()).path("data")).isEqualTo(expected);
            assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
        } finally {
            docker.unpauseContainerCmd(DB.getContainerId()).exec();
        }

        assertThat(requests("hit")).isEqualTo(hits + 1);
        // The database answers again, and an id that was never cached is read from it.
        assertThat(detail(uncachedSale.saleId()).path("saleId").asLong()).isEqualTo(uncachedSale.saleId());
    }

    @Test
    void entryExpiresAfterTheConfiguredTtlAndTheDatabaseIsReadAgain() throws Exception {
        SaleDetails sale = create();
        try (var shortLived = application("--limited-sale.sale.cache.ttl=PT2S")) {
            var query = shortLived.getBean(SaleQuery.class);
            var registry = shortLived.getBean(MeterRegistry.class);
            assertThat(query.get(sale.saleId()).name()).isEqualTo("sale");
            assertThat(redis.getExpire(key(sale.saleId()), TimeUnit.MILLISECONDS)).isPositive()
                .isLessThanOrEqualTo(2_000);
            // A write that bypasses the application cannot invalidate anything, so only expiry can show it.
            jdbc.update("UPDATE sales SET name = 'changed in database' WHERE id = ?", sale.saleId());
            assertThat(query.get(sale.saleId()).name()).isEqualTo("sale");
            double misses = count(registry, "sale.cache.requests", "miss");

            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .until(() -> !cached(sale.saleId()));

            assertThat(query.get(sale.saleId()).name()).isEqualTo("changed in database");
            assertThat(count(registry, "sale.cache.requests", "miss")).isEqualTo(misses + 1);
            assertThat(cached(sale.saleId())).isTrue();
        }
    }

    @Test
    void nameAndDescriptionEditsShowOnTheNextRead() throws Exception {
        Admin admin = Admin.login();
        SaleDetails sale = createStarted();
        assertThat(detail(sale.saleId()).path("name").asString()).isEqualTo("sale");
        double removals = evictions("success");

        assertCode(admin.send("PATCH", "/api/v1/admin/sales/" + sale.saleId(),
            "{\"version\":0,\"name\":\"renamed\",\"description\":\"new description\"}"),
            SaleResponseCode.SALE_UPDATED);

        assertThat(cached(sale.saleId())).as("removed by the time the response returns").isFalse();
        assertThat(evictions("success")).isEqualTo(removals + 1);
        JsonNode next = detail(sale.saleId());
        assertThat(next.path("name").asString()).isEqualTo("renamed");
        assertThat(next.path("description").asString()).isEqualTo("new description");
        assertThat(next.path("version").asLong()).isEqualTo(1);
        assertThat(next.path("status").asString()).isEqualTo("OPEN");
    }

    @Test
    void conditionEditBeforeStartShowsOnTheNextRead() throws Exception {
        Admin admin = Admin.login();
        SaleDetails sale = create();
        assertThat(detail(sale.saleId()).path("totalQuantity").asInt()).isEqualTo(10);

        assertCode(admin.send("PATCH", "/api/v1/admin/sales/" + sale.saleId(),
            "{\"version\":0,\"unitPrice\":2500,\"totalQuantity\":4,\"perMemberLimit\":2}"),
            SaleResponseCode.SALE_UPDATED);

        JsonNode next = detail(sale.saleId());
        assertThat(next.path("unitPrice").asLong()).isEqualTo(2500);
        assertThat(next.path("totalQuantity").asInt()).isEqualTo(4);
        assertThat(next.path("perMemberLimit").asInt()).isEqualTo(2);
    }

    @Test
    void stopAndResumeShowOnTheNextRead() throws Exception {
        Admin admin = Admin.login();
        SaleDetails sale = createStarted();
        assertThat(detail(sale.saleId()).path("status").asString()).isEqualTo("OPEN");
        double removals = evictions("success");

        assertCode(admin.send("POST", "/api/v1/admin/sales/" + sale.saleId() + "/stop", null),
            SaleResponseCode.SALE_STOP_COMPLETED);
        assertThat(detail(sale.saleId()).path("status").asString()).isEqualTo("STOPPED");
        assertCode(admin.send("POST", "/api/v1/admin/sales/" + sale.saleId() + "/resume", null),
            SaleResponseCode.SALE_RESUMED);
        assertThat(detail(sale.saleId()).path("status").asString()).isEqualTo("OPEN");

        assertThat(evictions("success")).isEqualTo(removals + 2);
    }

    @Test
    void deletionShowsNotFoundAndNotFoundIsNotCached() throws Exception {
        Admin admin = Admin.login();
        SaleDetails sale = create();
        detail(sale.saleId());
        assertThat(cached(sale.saleId())).isTrue();

        assertCode(admin.send("DELETE", "/api/v1/admin/sales/" + sale.saleId(), null),
            SaleResponseCode.SALE_DELETED);

        assertCode(get(sale.saleId()), SaleResponseCode.SALE_NOT_FOUND);
        assertThat(cached(sale.saleId())).isFalse();
        assertCode(get(sale.saleId()), SaleResponseCode.SALE_NOT_FOUND);
        assertThat(redis.keys("limited-sale:cache:*")).isEmpty();
    }

    @Test
    void failedEditLeavesTheEntryInPlaceAndTheResponseCorrect() throws Exception {
        Admin admin = Admin.login();
        SaleDetails sale = create();
        JsonNode before = detail(sale.saleId());
        double removals = evictions("success");
        double failures = evictions("error");

        assertCode(admin.send("PATCH", "/api/v1/admin/sales/" + sale.saleId(),
            "{\"version\":7,\"name\":\"stale editor\"}"), SaleResponseCode.SALE_VERSION_CONFLICT);

        assertThat(cached(sale.saleId())).as("a rolled back change removes nothing").isTrue();
        assertThat(evictions("success")).isEqualTo(removals);
        assertThat(evictions("error")).isEqualTo(failures);
        assertThat(detail(sale.saleId())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT name FROM sales WHERE id = ?", String.class, sale.saleId()))
            .isEqualTo("sale");
    }

    @Test
    void removalHappensAtCommitAndNeverForARolledBackTransaction() {
        SaleDetails sale = create();
        var transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        context.getBean(SaleQuery.class).get(sale.saleId());
        double removals = evictions("success");

        transactions.executeWithoutResult(status -> {
            commands.stop(sale.saleId());
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT is_stopped FROM sales WHERE id = ?", Boolean.class, sale.saleId()))
            .isFalse();
        assertThat(cached(sale.saleId())).isTrue();
        assertThat(evictions("success")).isEqualTo(removals);

        transactions.executeWithoutResult(status -> {
            commands.stop(sale.saleId());
            assertThat(cached(sale.saleId())).as("still cached before the commit").isTrue();
        });

        assertThat(cached(sale.saleId())).isFalse();
        assertThat(evictions("success")).isEqualTo(removals + 1);
    }

    @Test
    void cachedEntryChangesStatusAtTheStartAndEndBoundariesWithoutExpiring() throws Exception {
        SaleDetails sale = create();
        Instant start = sale.startsAt();
        Instant end = sale.endsAt();
        CLOCK.pin(start.minus(10, ChronoUnit.MINUTES));
        assertThat(detail(sale.saleId()).path("status").asString()).isEqualTo("SCHEDULED");
        double misses = requests("miss");
        double hits = requests("hit");

        List<Instant> times = List.of(start.minus(1, ChronoUnit.MICROS), start, start.plus(1, ChronoUnit.MICROS),
            end.minus(1, ChronoUnit.MICROS), end, end.plus(1, ChronoUnit.HOURS));
        List<String> expected = List.of("SCHEDULED", "OPEN", "OPEN", "OPEN", "ENDED", "ENDED");
        for (int index = 0; index < times.size(); index++) {
            CLOCK.pin(times.get(index));
            assertThat(detail(sale.saleId()).path("status").asString()).as("at " + times.get(index))
                .isEqualTo(expected.get(index));
        }

        // Every read above was served from the one entry written while the sale was still scheduled.
        assertThat(requests("hit")).isEqualTo(hits + times.size());
        assertThat(requests("miss")).isEqualTo(misses);
    }

    @Test
    void redisOutageServesFromMySqlCountsErrorsAndRecovers() throws Exception {
        SaleDetails sale = create();
        SaleDetails later = create();
        detail(sale.saleId());
        jdbc.update("UPDATE sales SET name = 'from database' WHERE id = ?", sale.saleId());
        double failures = requests("error");

        whilePaused(REDIS, () -> {
            try {
                long begin = System.nanoTime();
                var response = get(sale.saleId());
                Duration elapsed = Duration.ofNanos(System.nanoTime() - begin);

                assertCode(response, SaleResponseCode.SALE_FOUND);
                assertThat(JSON.readTree(response.body()).path("data").path("name").asString())
                    .as("the stale cached name is unreachable, so the answer is the database's")
                    .isEqualTo("from database");
                // One command timeout, not a second one for storing the result after the read already failed.
                assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });

        assertThat(requests("error")).isEqualTo(failures + 1);
        // Back online the cache fills and answers again without restarting the application.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            detail(later.saleId());
            assertThat(cached(later.saleId())).isTrue();
        });
        double hits = requests("hit");
        detail(later.saleId());
        assertThat(requests("hit")).isEqualTo(hits + 1);
    }

    @Test
    void editCommittedWhileRedisIsPausedSucceedsAndCountsTheFailedRemoval() throws Exception {
        SaleDetails sale = createStarted();
        detail(sale.saleId());
        double removalFailures = evictions("error");
        double removals = evictions("success");

        whilePaused(REDIS, () -> commands.patch(sale.saleId(),
            new SalePatch(0, null, "renamed", null, null, null, null, null, null)));

        assertThat(jdbc.queryForObject("SELECT name FROM sales WHERE id = ?", String.class, sale.saleId()))
            .isEqualTo("renamed");
        assertThat(evictions("error")).isEqualTo(removalFailures + 1);
        assertThat(evictions("success")).isEqualTo(removals);
    }

    @Test
    void staleEntryAfterAFailedRemovalIsBoundedByTtlAndRepairedByARepeatedRequest() throws Exception {
        SaleDetails sale = createStarted();
        detail(sale.saleId());
        double removalFailures = evictions("error");
        // A paused Redis still executes the queued DEL once it resumes, so refuse the command to keep the entry.
        REDIS.execInContainer("redis-cli", "ACL", "SETUSER", "default", "-del");
        try {
            commands.patch(sale.saleId(), new SalePatch(0, null, "renamed", null, null, null, null, null, null));
        } finally {
            REDIS.execInContainer("redis-cli", "ACL", "SETUSER", "default", "+del");
        }

        assertThat(jdbc.queryForObject("SELECT name FROM sales WHERE id = ?", String.class, sale.saleId()))
            .isEqualTo("renamed");
        assertThat(evictions("error")).isEqualTo(removalFailures + 1);
        // The committed edit is not visible yet: the old entry lives until its TTL, which is the accepted bound.
        assertThat(detail(sale.saleId()).path("name").asString()).isEqualTo("sale");
        assertThat(redis.getExpire(key(sale.saleId()), TimeUnit.MILLISECONDS)).isPositive()
            .isLessThanOrEqualTo(60_000);

        // Repeating a state change that changes nothing still removes the entry, which repairs the gap.
        commands.resume(sale.saleId());
        assertThat(detail(sale.saleId()).path("name").asString()).isEqualTo("renamed");
    }

    @Test
    void unreadableOrImpossibleValuesAreMissesAndTheKeyIsReplaced() throws Exception {
        SaleDetails sale = create();
        detail(sale.saleId());
        List<java.util.function.Consumer<ObjectNode>> corruptions = List.of(
            node -> node.put("endsAt", node.path("startsAt").asString()),
            node -> node.put("totalQuantity", 0),
            node -> node.remove("version"));
        List<String> raw = List.of("{not json", "[]", "");
        double misses = requests("miss");
        double failures = requests("error");
        int attempts = 0;

        for (String value : raw) {
            redis.opsForValue().set(key(sale.saleId()), value, Duration.ofSeconds(60));
            assertThat(detail(sale.saleId()).path("name").asString()).as(value).isEqualTo("sale");
            assertThat(JSON.readTree(redis.opsForValue().get(key(sale.saleId()))).path("name").asString())
                .as("replaced after " + value).isEqualTo("sale");
            attempts++;
        }
        for (var corruption : corruptions) {
            rewrite(sale.saleId(), corruption);
            assertThat(detail(sale.saleId()).path("name").asString()).isEqualTo("sale");
            assertThat(JSON.readTree(redis.opsForValue().get(key(sale.saleId()))).path("totalQuantity").asInt())
                .isEqualTo(10);
            attempts++;
        }

        assertThat(requests("miss")).isEqualTo(misses + attempts);
        assertThat(requests("error")).isEqualTo(failures);
    }

    @Test
    void metricsAreExposedWithOutcomeLabelsOnly() throws Exception {
        SaleDetails sale = create();
        detail(sale.saleId());
        detail(sale.saleId());
        var scrape = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus")).build(),
            HttpResponse.BodyHandlers.ofString()).body();

        List<String> samples = scrape.lines().filter(line -> line.startsWith("sale_cache_")).toList();

        assertThat(samples).anyMatch(line -> line.startsWith("sale_cache_requests_total{result=\"hit\"}"))
            .anyMatch(line -> line.startsWith("sale_cache_requests_total{result=\"miss\"}"))
            .anyMatch(line -> line.startsWith("sale_cache_requests_total{result=\"error\"}"))
            .anyMatch(line -> line.startsWith("sale_cache_evictions_total{result=\"success\"}"))
            .anyMatch(line -> line.startsWith("sale_cache_evictions_total{result=\"error\"}"));
        assertThat(samples)
            .allMatch(line -> line.matches("sale_cache_(requests|evictions)_total\\{result=\"[a-z]+\"} .*"));
    }
}
