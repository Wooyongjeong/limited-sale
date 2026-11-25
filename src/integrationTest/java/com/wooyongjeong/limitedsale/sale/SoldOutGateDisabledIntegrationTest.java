package com.wooyongjeong.limitedsale.sale;

import com.wooyongjeong.limitedsale.LimitedSaleApplication;
import com.wooyongjeong.limitedsale.member.application.MemberService;
import com.wooyongjeong.limitedsale.order.application.OrderResult;
import com.wooyongjeong.limitedsale.order.application.OrderService;
import com.wooyongjeong.limitedsale.product.api.ProductCommand;
import com.wooyongjeong.limitedsale.sale.api.SaleCommand;
import com.wooyongjeong.limitedsale.sale.api.SaleCreation;
import com.wooyongjeong.limitedsale.sale.api.SaleDetails;
import com.wooyongjeong.limitedsale.sale.api.SalePatch;
import com.wooyongjeong.limitedsale.sale.api.SaleReservation;
import com.wooyongjeong.limitedsale.sale.api.SoldOutGate;
import com.wooyongjeong.limitedsale.sale.domain.SoldOutMarker;
import com.wooyongjeong.limitedsale.sale.infrastructure.gate.DisabledSoldOutGate;
import com.wooyongjeong.limitedsale.sale.infrastructure.gate.RedisSoldOutGate;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With `limited-sale.sale.sold-out-gate.enabled=false` order creation, releases and admin edits never call Redis.
 * The proof is Redis's own command counters, as for the sale cache switch: no command at all is executed, and no
 * marker key or gate metric appears. A control application with the default setting shows that the same counters do
 * see the gate. The request limit and the sale cache are switched off in both so only the gate can touch Redis.
 */
class SoldOutGateDisabledIntegrationTest {
    private static final MySQLContainer DB = new MySQLContainer("mysql:8.4.7")
        .withDatabaseName("sales").withUsername("sales_user").withPassword("test_password");
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static ConfigurableApplicationContext context;
    private static StringRedisTemplate redis;
    private static JdbcTemplate jdbc;
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
        // Loads the shipped settings explicitly (until 4-2 the Mock PG's application.yml shadowed them on this classpath).
        List<String> arguments = new ArrayList<>(List.of("--server.port=0", "--management.server.port=" + freePort(),
            "--spring.config.additional-location=file:src/main/resources/application.yml",
            "--limited-sale.recovery.enabled=false", "--limited-sale.order.rate-limit.enabled=false",
            "--limited-sale.sale.cache.enabled=false", "--spring.datasource.url=" + DB.getJdbcUrl(),
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
        context = application("--limited-sale.sale.sold-out-gate.enabled=false");
        jdbc = context.getBean(JdbcTemplate.class);
        redis = context.getBean(StringRedisTemplate.class);
        context.getBean(MemberService.class).register("gate-off@example.com", "password", "gateoff");
        memberId = jdbc.queryForObject("SELECT id FROM members WHERE email = 'gate-off@example.com'", Long.class);
        product = context.getBean(ProductCommand.class).register("gate product", "", 1000L).productId();
    }

    @BeforeEach
    void reset() {
        redis.delete(redis.keys("limited-sale:sale:sold-out:*"));
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
        REDIS.stop();
        DB.stop();
    }

    private static long openSale(int totalQuantity, int perMemberLimit) {
        jdbc.update("INSERT INTO sales(product_id,name,description,product_name_snapshot,unit_price,starts_at,ends_at,"
            + "total_quantity,per_member_limit,created_at,updated_at) VALUES (?, 'sale', '', 'product', 1000, "
            + "UTC_TIMESTAMP(6)-INTERVAL 1 HOUR,UTC_TIMESTAMP(6)+INTERVAL 1 HOUR,?,?,UTC_TIMESTAMP(6),"
            + "UTC_TIMESTAMP(6))", product, totalQuantity, perMemberLimit);
        return jdbc.queryForObject("SELECT MAX(id) FROM sales", Long.class);
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

    /** Sells out a one-unit sale, orders again, releases the unit and edits and deletes a future sale. */
    private static void exercise(ConfigurableApplicationContext app, OrderResult.Kind second) {
        OrderService orders = app.getBean(OrderService.class);
        SaleReservation reservation = app.getBean(SaleReservation.class);
        long sale = openSale(1, 1);
        assertThat(orders.create(memberId, UUID.randomUUID(), sale, 1).kind()).isEqualTo(OrderResult.Kind.CREATED);
        assertThat(orders.create(memberId, UUID.randomUUID(), sale, 1).kind())
            .as("MySQL names the member limit; a marker would name the sold-out sale").isEqualTo(second);
        long orderId = jdbc.queryForObject("SELECT id FROM purchase_orders WHERE sale_id = ?", Long.class, sale);
        new TransactionTemplate(app.getBean(PlatformTransactionManager.class)).executeWithoutResult(status ->
            reservation.finish(orderId, sale, memberId, 1, false, Instant.now()));
        assertThat(orders.create(memberId, UUID.randomUUID(), sale, 1).kind())
            .as("the released unit sells again").isEqualTo(OrderResult.Kind.CREATED);

        SaleCommand commands = app.getBean(SaleCommand.class);
        Instant start = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS);
        SaleDetails future = commands.create(new SaleCreation(product, "future", "description", null, start,
            start.plusSeconds(3600), 5, null));
        commands.patch(future.saleId(), new SalePatch(0, null, null, null, null, null, null, 8, null));
        commands.delete(future.saleId());
    }

    @Test
    void theDisabledSwitchReplacesTheRedisGateWithTheNoOpOne() {
        assertThat(context.getBeansOfType(SoldOutGate.class).values()).singleElement()
            .isInstanceOf(DisabledSoldOutGate.class);
        assertThat(context.getBeansOfType(SoldOutMarker.class).values()).singleElement()
            .isInstanceOf(DisabledSoldOutGate.class);
        assertThat(context.getBeansOfType(RedisSoldOutGate.class)).isEmpty();
    }

    @Test
    void ordersReleasesAndAdminEditsCallNoRedisCommandAndLeaveNoKeyOrMetric() {
        Map<String, Long> before = redisCommands();

        exercise(context, OrderResult.Kind.PURCHASE_LIMIT_EXCEEDED);

        // Read before the KEYS call below, which is itself a Redis command.
        assertThat(redisCommands()).isEqualTo(before);
        assertThat(redis.keys("limited-sale:sale:sold-out:*")).isEmpty();
        var meters = context.getBean(MeterRegistry.class);
        assertThat(meters.find("sale.sold.out.gate.requests").counters()).isEmpty();
        assertThat(meters.find("sale.sold.out.gate.updates").counters()).isEmpty();
    }

    @Test
    void theSameCountersSeeTheGateWhenItIsEnabled() {
        try (var enabled = application()) {
            Map<String, Long> before = redisCommands();

            exercise(enabled, OrderResult.Kind.SALE_SOLD_OUT);

            assertThat(redisCommands()).isNotEqualTo(before);
            var registry = enabled.getBean(MeterRegistry.class);
            assertThat(registry.get("sale.sold.out.gate.requests").tag("result", "miss").counter().count())
                .isPositive();
            assertThat(registry.get("sale.sold.out.gate.updates").tag("operation", "mark").tag("result", "success")
                .counter().count()).isPositive();
            assertThat(registry.get("sale.sold.out.gate.updates").tag("operation", "clear").tag("result", "success")
                .counter().count()).isPositive();
        }
    }
}
