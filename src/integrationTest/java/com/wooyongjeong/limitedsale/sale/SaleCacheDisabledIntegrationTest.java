package com.wooyongjeong.limitedsale.sale;

import com.wooyongjeong.limitedsale.LimitedSaleApplication;
import com.wooyongjeong.limitedsale.product.api.ProductCommand;
import com.wooyongjeong.limitedsale.sale.api.SaleCommand;
import com.wooyongjeong.limitedsale.sale.api.SaleCreation;
import com.wooyongjeong.limitedsale.sale.api.SaleDetails;
import com.wooyongjeong.limitedsale.sale.api.SalePatch;
import com.wooyongjeong.limitedsale.sale.domain.SaleCache;
import com.wooyongjeong.limitedsale.sale.infrastructure.cache.DisabledSaleCache;
import com.wooyongjeong.limitedsale.sale.infrastructure.cache.RedisSaleCache;
import com.wooyongjeong.limitedsale.sale.presentation.code.SaleResponseCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static com.wooyongjeong.limitedsale.support.ResponseEnvelope.assertCode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * With `limited-sale.sale.cache.enabled=false` the detail query reads MySQL only. The proof is Redis's own command
 * counters: no command at all is executed by reads or by the commit-time invalidation of edits, and no cache key
 * or metric appears. A control application with the default setting shows that the same counters do see the cache.
 */
class SaleCacheDisabledIntegrationTest {
    private static final MySQLContainer DB = new MySQLContainer("mysql:8.4.7")
        .withDatabaseName("sales").withUsername("sales_user").withPassword("test_password");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final HttpClient ANONYMOUS = HttpClient.newHttpClient();
    private static ConfigurableApplicationContext context;
    private static StringRedisTemplate redis;
    private static JdbcTemplate jdbc;
    private static SaleCommand commands;
    private static MeterRegistry meters;
    private static int port;
    private static long product;

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ConfigurableApplicationContext application(String... extra) {
        // mock-pg's application.yml shadows the sales one on this classpath, so load the shipped settings explicitly.
        List<String> arguments = new ArrayList<>(List.of("--server.port=0", "--management.server.port=" + freePort(),
            "--spring.config.additional-location=file:src/main/resources/application.yml",
            "--limited-sale.recovery.enabled=false", "--spring.datasource.url=" + DB.getJdbcUrl(),
            "--spring.datasource.username=" + DB.getUsername(), "--spring.datasource.password=" + DB.getPassword(),
            "--spring.data.redis.host=" + REDIS.getHost(), "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
            "--spring.flyway.locations=filesystem:src/main/resources/db/migration,classpath:db/nickmigration"));
        arguments.addAll(List.of(extra));
        return new SpringApplicationBuilder(LimitedSaleApplication.class).run(arguments.toArray(String[]::new));
    }

    @BeforeAll
    static void start() {
        DB.start();
        REDIS.start();
        context = application("--limited-sale.sale.cache.enabled=false");
        port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        jdbc = context.getBean(JdbcTemplate.class);
        commands = context.getBean(SaleCommand.class);
        redis = context.getBean(StringRedisTemplate.class);
        meters = context.getBean(MeterRegistry.class);
        product = context.getBean(ProductCommand.class).register("cache product", "", 1000L).productId();
    }

    @BeforeEach
    void reset() {
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

    private static SaleDetails create() {
        Instant start = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS);
        return commands.create(new SaleCreation(product, "sale", "description", null, start,
            start.plusSeconds(3600), 10, null));
    }

    private static HttpResponse<String> get(int applicationPort, long id) throws Exception {
        return ANONYMOUS.send(HttpRequest.newBuilder(URI.create("http://localhost:" + applicationPort
            + "/api/v1/sales/" + id)).timeout(Duration.ofSeconds(30)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode detail(long id) throws Exception {
        var response = get(port, id);
        assertCode(response, SaleResponseCode.SALE_FOUND);
        return JSON.readTree(response.body()).path("data");
    }

    /** Calls per Redis command since the server started, without INFO itself, which this check issues. */
    private static Map<String, Long> redisCommands() {
        Properties stats = redis.execute((RedisCallback<Properties>) connection ->
            connection.serverCommands().info("commandstats"));
        Map<String, Long> calls = new TreeMap<>();
        for (String name : stats.stringPropertyNames()) {
            if (name.startsWith("cmdstat_") && !name.equals("cmdstat_info")) {
                String value = stats.getProperty(name);
                calls.put(name, Long.parseLong(value.substring(value.indexOf('=') + 1, value.indexOf(','))));
            }
        }
        return calls;
    }

    @Test
    void theDisabledSwitchReplacesTheRedisCacheWithTheNoOpOne() {
        assertThat(context.getBeansOfType(SaleCache.class).values()).singleElement()
            .isInstanceOf(DisabledSaleCache.class);
        assertThat(context.getBeansOfType(RedisSaleCache.class)).isEmpty();
    }

    @Test
    void detailReadsGoToMySqlWithoutAnyCacheKeyMetricOrRedisCommand() throws Exception {
        SaleDetails sale = create();
        detail(sale.saleId());
        Map<String, Long> before = redisCommands();

        for (int i = 0; i < 5; i++) {
            assertThat(detail(sale.saleId()).path("name").asString()).isEqualTo("sale");
        }
        // A write that bypasses the application would stay invisible behind a cache; here it shows at once.
        jdbc.update("UPDATE sales SET name = 'changed in database' WHERE id = ?", sale.saleId());
        assertThat(detail(sale.saleId()).path("name").asString()).isEqualTo("changed in database");

        // Read before the KEYS call below, which is itself a Redis command.
        assertThat(redisCommands()).isEqualTo(before);
        assertThat(redis.keys("limited-sale:cache:*")).isEmpty();
        assertThat(meters.find("sale.cache.requests").counters()).isEmpty();
        assertThat(meters.find("sale.cache.evictions").counters()).isEmpty();
    }

    @Test
    void editsStopsResumesAndDeletesDoNotCallRedisAndStayVisible() throws Exception {
        SaleDetails sale = create();
        SaleDetails deleted = create();
        detail(sale.saleId());
        Map<String, Long> before = redisCommands();

        commands.patch(sale.saleId(), new SalePatch(0, null, "renamed", null, null, null, null, null, null));
        assertThat(detail(sale.saleId()).path("name").asString()).isEqualTo("renamed");
        commands.stop(sale.saleId());
        assertThat(detail(sale.saleId()).path("status").asString()).isEqualTo("STOPPED");
        commands.resume(sale.saleId());
        assertThat(detail(sale.saleId()).path("status").asString()).isEqualTo("SCHEDULED");
        commands.delete(deleted.saleId());
        assertThat(get(port, deleted.saleId()).statusCode()).isEqualTo(404);

        assertThat(redisCommands()).isEqualTo(before);
        assertThat(redis.keys("limited-sale:cache:*")).isEmpty();
        assertThat(meters.find("sale.cache.requests").counters()).isEmpty();
        assertThat(meters.find("sale.cache.evictions").counters()).isEmpty();
    }

    @Test
    void theSameCountersSeeTheCacheWhenItIsEnabled() throws Exception {
        SaleDetails sale = create();
        try (var enabled = application()) {
            int enabledPort = ((ServletWebServerApplicationContext) enabled).getWebServer().getPort();
            Map<String, Long> before = redisCommands();

            assertThat(get(enabledPort, sale.saleId()).statusCode()).isEqualTo(200);
            assertThat(get(enabledPort, sale.saleId()).statusCode()).isEqualTo(200);

            assertThat(redisCommands()).isNotEqualTo(before);
            assertThat(redis.keys("limited-sale:cache:*")).hasSize(1);
            var registry = enabled.getBean(MeterRegistry.class);
            assertThat(registry.get("sale.cache.requests").tag("result", "hit").counter().count()).isEqualTo(1);
            assertThat(registry.get("sale.cache.requests").tag("result", "miss").counter().count()).isEqualTo(1);
        }
    }
}
