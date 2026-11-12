package com.wooyongjeong.limitedsale;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import static org.assertj.core.api.Assertions.*;

class MySqlSchemaIntegrationTest {
    static MySQLContainer<?> db = new MySQLContainer<>("mysql:8.4.7")
        .withDatabaseName("sales").withUsername("sales_user").withPassword("test_password");
    static Connection root;
    static Flyway sales;
    static Flyway pg;

    static void sql(String statement) throws SQLException {
        try (Statement s = root.createStatement()) {
            s.execute(statement);
        }
    }

    static long count(String query) throws SQLException {
        try (Statement s = root.createStatement(); ResultSet result = s.executeQuery(query)) {
            result.next();
            return result.getLong(1);
        }
    }

    @BeforeAll
    static void start() throws Exception {
        db.start();
        root = DriverManager.getConnection(db.getJdbcUrl(), "root", db.getPassword());
        sql("CREATE DATABASE pg CHARACTER SET utf8mb4");
        sql("CREATE USER 'pg_user'@'%' IDENTIFIED BY 'test_password_pg'");
        sql("GRANT ALL ON pg.* TO 'pg_user'@'%'");
        sales = Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
            .locations("filesystem:src/main/resources/db/migration", "classpath:db/nickmigration").load();
        pg = Flyway.configure()
            .dataSource(db.getJdbcUrl().replace("/sales", "/pg"), "pg_user", "test_password_pg")
            .locations("filesystem:mock-pg/src/main/resources/db/migration").load();
        Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
            .locations("filesystem:src/main/resources/db/migration", "classpath:db/nickmigration").target("1").load().migrate();
        Flyway.configure().dataSource(db.getJdbcUrl().replace("/sales", "/pg"), "pg_user", "test_password_pg")
            .locations("filesystem:mock-pg/src/main/resources/db/migration").target("1").load().migrate();
        sql("""
            INSERT INTO sales.members(email, password_hash, role, created_at, updated_at) VALUES
            ('test@example.com', 'hash', 'USER', '2020-01-02 03:04:05.123456', '2021-02-03 04:05:06.234567')
            """);
        sql("INSERT INTO sales.members(id, email, password_hash, role, created_at, updated_at) "
            + "VALUES (-9223372036854775808, 'legacy@example.com', 'legacyhash', 'ADMIN', NOW(6), NOW(6))");
        sql("""
            INSERT INTO sales.products(name, description, base_price, created_at, updated_at) VALUES
            ('product', '', 1000000000, NOW(6), NOW(6))
            """);
        sql("""
            INSERT INTO sales.sales(product_id, name, description, product_name_snapshot, unit_price,
            starts_at, ends_at, total_quantity, per_member_limit, created_at, updated_at) VALUES (1,
            'sale', '', 'product', 1000000000, NOW(6), NOW(6)+INTERVAL 1 DAY, 1000000, 1, NOW(6),
            NOW(6))
            """);
        sql("INSERT INTO sales.sale_buyer_quantities(sale_id,member_id,created_at,updated_at) VALUES (1,1,NOW(6),NOW(6))");
        sql("""
            INSERT INTO sales.purchase_orders(public_id, member_id, request_key, sale_id, product_id,
            product_name_snapshot, unit_price, total_amount, quantity, status, expires_at, created_at,
            updated_at) VALUES (UNHEX('00112233445566778899AABBCCDDEEFF'), 1,
            UNHEX('11223344556677889900AABBCCDDEEFF'), 1, 1, 'product', 1000000000, 1000000000000000,
            1000000, 'AWAITING_PAYMENT', NOW(6), NOW(6), NOW(6))
            """);
        sql("""
            INSERT INTO sales.sale_reservations(order_id, sale_id, member_id, quantity, status,
            created_at, updated_at) VALUES (1, 1, 1, 1, 'RESERVED', NOW(6), NOW(6))
            """);
        sql("""
            INSERT INTO sales.payments(order_id, pg_key, amount, status, created_at, updated_at) VALUES
            (1, UNHEX('21223344556677889900AABBCCDDEEFF'), 1000000000000000, 'READY', NOW(6), NOW(6))
            """);
        sql("""
            INSERT INTO pg.mock_payments(pg_key, amount, status, scenario, created_at, updated_at)
            VALUES (UNHEX('21223344556677889900AABBCCDDEEFF'), 1000000000000000, 'PROCESSING',
            'SUCCESS', NOW(6), NOW(6))
            """);
        assertThat(sales.migrate().migrationsExecuted).isEqualTo(3);
        assertThat(pg.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM sales.purchase_orders")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM sales.purchase_orders o JOIN sales.members m ON o.member_id=m.id "
            + "WHERE m.id=1 AND m.email='test@example.com' AND m.password_hash='hash' AND m.role='USER' "
            + "AND m.created_at='2020-01-02 03:04:05.123456' "
            + "AND m.updated_at='2021-02-03 04:05:06.234567'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM pg.mock_payments")).isEqualTo(1);
        assertThat(count("SELECT COUNT(DISTINCT nickname_key) FROM sales.members WHERE nickname IS NOT NULL "
            + "AND CHAR_LENGTH(nickname) BETWEEN 1 AND 12")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM sales.members WHERE email='legacy@example.com' "
            + "AND password_hash='legacyhash' AND role='ADMIN'")).isEqualTo(1);
        sql("DELETE FROM sales.members WHERE email='legacy@example.com'");
    }

    @Test
    void orderRequestResultsEnforceOutcomeAndPreserveMissingSaleId() throws Exception {
        sql("INSERT INTO sales.order_request_results(member_id, request_key, sale_id, quantity, outcome, "
            + "rejection_reason, created_at) VALUES (1, UNHEX('01010101010101010101010101010101'), "
            + "999999, 1, 'REJECTED', 'SALE_NOT_FOUND', NOW(6))");
        assertThat(count("SELECT COUNT(*) FROM sales.order_request_results WHERE sale_id=999999")).isEqualTo(1);
        assertSqlErrors(1062, List.of("INSERT INTO sales.order_request_results(member_id, request_key, sale_id, "
            + "quantity, outcome, rejection_reason, created_at) VALUES (1, "
            + "UNHEX('01010101010101010101010101010101'), 999999, 1, 'REJECTED', 'SALE_NOT_FOUND', NOW(6))"));
        assertSqlErrors(3819, List.of("INSERT INTO sales.order_request_results(member_id, request_key, sale_id, "
            + "quantity, outcome, order_id, rejection_reason, created_at) VALUES (1, "
            + "UNHEX('02020202020202020202020202020202'), 1, 1, 'CREATED', 1, 'SALE_NOT_FOUND', NOW(6))"));
        assertSqlErrors(1452, List.of("INSERT INTO sales.order_request_results(member_id, request_key, sale_id, "
            + "quantity, outcome, order_id, created_at) VALUES (1, "
            + "UNHEX('03030303030303030303030303030303'), 1, 1, 'CREATED', 999999, NOW(6))",
            "INSERT INTO sales.order_request_results(member_id, request_key, sale_id, quantity, outcome, "
            + "rejection_reason, created_at) VALUES (999999, UNHEX('04040404040404040404040404040404'), "
            + "1, 1, 'REJECTED', 'SALE_NOT_FOUND', NOW(6))"));
        assertSqlErrors(3819, List.of("INSERT INTO sales.order_request_results(member_id, request_key, sale_id, "
            + "quantity, outcome, rejection_reason, created_at) VALUES (1, "
            + "UNHEX('05050505050505050505050505050505'), 1, 1, 'REJECTED', 'unknown', NOW(6))"));
        assertThat(count("SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE "
            + "TABLE_SCHEMA='sales' AND TABLE_NAME='order_request_results' AND COLUMN_NAME='request_key' "
            + "AND DATA_TYPE='binary'")).isEqualTo(16);
    }

    @Test
    void nicknameMigrationHandlesBothSignedLongExtremes() throws Exception {
        sql("CREATE DATABASE nickname_boundaries CHARACTER SET utf8mb4");
        try {
            String url = db.getJdbcUrl().replace("/sales", "/nickname_boundaries");
            Flyway.configure().dataSource(url, "root", db.getPassword())
                .locations("filesystem:src/main/resources/db/migration", "classpath:db/nickmigration")
                .target("1").load().migrate();
            sql("INSERT INTO nickname_boundaries.members(id, email, password_hash, role, created_at, updated_at) "
                + "VALUES (-9223372036854775808, 'min@example.com', 'minhash', 'USER', NOW(6), NOW(6)), "
                + "(9223372036854775807, 'max@example.com', 'maxhash', 'ADMIN', NOW(6), NOW(6))");
            Flyway.configure().dataSource(url, "root", db.getPassword())
                .locations("filesystem:src/main/resources/db/migration", "classpath:db/nickmigration")
                .load().migrate();
            assertThat(count("SELECT COUNT(DISTINCT nickname_key) FROM nickname_boundaries.members "
                + "WHERE CHAR_LENGTH(nickname) BETWEEN 1 AND 12 "
                + "AND nickname = nickname_key AND nickname REGEXP '^[a-z0-9가나다라마]+$'")).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM nickname_boundaries.members WHERE "
                + "(id = -9223372036854775808 AND email = 'min@example.com' "
                + "AND password_hash = 'minhash' AND role = 'USER') OR "
                + "(id = 9223372036854775807 AND email = 'max@example.com' "
                + "AND password_hash = 'maxhash' AND role = 'ADMIN')")).isEqualTo(2);
        } finally {
            sql("DROP DATABASE nickname_boundaries");
        }
    }
    @AfterAll
    static void stop() throws Exception {
        if (root != null) root.close();
        db.stop();
    }

    @Test
    void migrationsAreIndependentAndRepeatWithoutDataLoss() throws Exception {
        assertThat(sales.migrate().migrationsExecuted).isZero();
        assertThat(pg.migrate().migrationsExecuted).isZero();
        assertThat(count("SELECT COUNT(*) FROM sales.purchase_orders")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM pg.mock_payments")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM sales.flyway_schema_history WHERE success=1")).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM pg.flyway_schema_history WHERE success=1")).isEqualTo(2);
    }
    @Test
    void rejectsUniqueForeignKeyAndCheckViolations() {
        assertSqlErrors(1062, List.of(
            """
                INSERT INTO sales.members(email, password_hash, nickname, nickname_key, role, created_at, updated_at) VALUES
                ('TEST@example.com', 'h', 'Second', 'second', 'USER', NOW(6), NOW(6))
                """,
            """
                INSERT INTO sales.sale_buyer_quantities(sale_id, member_id, created_at, updated_at) VALUES (1,
                1, NOW(6), NOW(6))
                """,
            """
                INSERT INTO sales.purchase_orders(public_id, member_id, request_key, sale_id, product_id,
                product_name_snapshot, unit_price, total_amount, quantity, status, expires_at, created_at,
                updated_at) SELECT public_id, member_id, UNHEX('AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'), sale_id,
                product_id, product_name_snapshot, unit_price, total_amount, quantity, status, expires_at,
                created_at, updated_at FROM sales.purchase_orders WHERE id=1
                """,
            """
                INSERT INTO sales.purchase_orders(public_id, member_id, request_key, sale_id, product_id,
                product_name_snapshot, unit_price, total_amount, quantity, status, expires_at, created_at,
                updated_at) SELECT UNHEX('BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB'), member_id, request_key, sale_id,
                product_id, product_name_snapshot, unit_price, total_amount, quantity, status, expires_at,
                created_at, updated_at FROM sales.purchase_orders WHERE id=1
                """,
            """
                INSERT INTO sales.sale_reservations(order_id, sale_id, member_id, quantity, status, created_at,
                updated_at) SELECT order_id, sale_id, member_id, quantity, status, created_at, updated_at FROM
                sales.sale_reservations WHERE id=1
                """,
            """
                INSERT INTO sales.payments(order_id, pg_key, amount, status, created_at, updated_at) SELECT
                order_id, UNHEX('BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB'), amount, status, created_at, updated_at FROM
                sales.payments WHERE id=1
                """,
            """
                INSERT INTO sales.payments(order_id, pg_key, amount, status, created_at, updated_at) SELECT
                order_id, pg_key, amount, status, created_at, updated_at FROM sales.payments WHERE id=1
                """,
            """
                INSERT INTO pg.mock_payments(pg_key, amount, status, scenario, created_at, updated_at) SELECT
                pg_key, amount, status, scenario, created_at, updated_at FROM pg.mock_payments WHERE id=1
                """
        ));
        assertSqlErrors(1451, List.of(
            """
                UPDATE sales.sale_buyer_quantities SET member_id=999 WHERE id=1
                """,
            """
                UPDATE sales.sale_buyer_quantities SET sale_id=999 WHERE id=1
                """,
            """
                DELETE FROM sales.members WHERE id=1
                """,
            """
                DELETE FROM sales.products WHERE id=1
                """,
            """
                DELETE FROM sales.sales WHERE id=1
                """,
            """
                DELETE FROM sales.sale_buyer_quantities WHERE id=1
                """,
            """
                DELETE FROM sales.purchase_orders WHERE id=1
                """,
            """
                UPDATE sales.products SET id=2 WHERE id=1
                """,
            """
                UPDATE sales.members SET id=2 WHERE id=1
                """,
            """
                UPDATE sales.sales SET id=2 WHERE id=1
                """,
            """
                UPDATE sales.purchase_orders SET id=2 WHERE id=1
                """
        ));
        assertSqlErrors(1452, List.of(
            """
                INSERT INTO sales.sale_buyer_quantities(sale_id, member_id, created_at, updated_at)
                VALUES (999, 1, NOW(6), NOW(6))
                """,
            """
                INSERT INTO sales.sale_buyer_quantities(sale_id, member_id, created_at, updated_at)
                VALUES (1, 999, NOW(6), NOW(6))
                """,
            """
                UPDATE sales.sales SET product_id=999 WHERE id=1
                """,
            """
                UPDATE sales.purchase_orders SET member_id=999 WHERE id=1
                """,
            """
                UPDATE sales.purchase_orders SET sale_id=999 WHERE id=1
                """,
            """
                UPDATE sales.purchase_orders SET product_id=999 WHERE id=1
                """,
            """
                UPDATE sales.sale_reservations SET order_id=999 WHERE id=1
                """,
            """
                UPDATE sales.sale_reservations SET member_id=999 WHERE id=1
                """,
            """
                UPDATE sales.payments SET order_id=999 WHERE id=1
                """
        ));
        assertSqlErrors(3819, List.of(
            """
                UPDATE sales.members SET role='user' WHERE id=1
                """,
            """
                UPDATE sales.products SET base_price=0 WHERE id=1
                """,
            """
                UPDATE sales.products SET name='' WHERE id=1
                """,
            """
                UPDATE sales.sales SET total_quantity=0 WHERE id=1
                """,
            """
                UPDATE sales.sales SET starts_at=ends_at WHERE id=1
                """,
            """
                UPDATE sales.sales SET per_member_limit=0 WHERE id=1
                """,
            """
                UPDATE sales.sales SET reserved_quantity=-1 WHERE id=1
                """,
            """
                UPDATE sales.sales SET reserved_quantity=1000001 WHERE id=1
                """,
            """
                UPDATE sales.sales SET is_stopped=2 WHERE id=1
                """,
            """
                UPDATE sales.sales SET unit_price=1000000001 WHERE id=1
                """,
            """
                UPDATE sales.sale_buyer_quantities SET purchased_quantity=-1 WHERE id=1
                """,
            """
                UPDATE sales.sale_buyer_quantities SET purchased_quantity=1000001 WHERE id=1
                """,
            """
                UPDATE sales.purchase_orders SET quantity=0 WHERE id=1
                """,
            """
                UPDATE sales.purchase_orders SET total_amount=1 WHERE id=1
                """,
            """
                UPDATE sales.purchase_orders SET status='invalid' WHERE id=1
                """,
            """
                UPDATE sales.purchase_orders SET lease_token=UNHEX('00112233445566778899AABBCCDDEEFF') WHERE
                id=1
                """,
            """
                UPDATE sales.purchase_orders SET requires_review=2 WHERE id=1
                """,
            """
                UPDATE sales.sale_reservations SET status='invalid' WHERE id=1
                """,
            """
                UPDATE sales.sale_reservations SET quantity=0 WHERE id=1
                """,
            """
                UPDATE sales.payments SET amount=0 WHERE id=1
                """,
            """
                UPDATE sales.payments SET status='invalid' WHERE id=1
                """,
            """
                UPDATE pg.mock_payments SET status='invalid' WHERE id=1
                """,
            """
                UPDATE pg.mock_payments SET scenario='invalid' WHERE id=1
                """,
            """
                UPDATE pg.mock_payments SET response_loss_consumed=2 WHERE id=1
                """,
            """
                UPDATE pg.mock_payments SET amount=1000000000000001 WHERE id=1
                """,
            """
                UPDATE sales.purchase_orders SET unit_price=0 WHERE id=1
                """,
            """
                UPDATE sales.payments SET amount=1000000000000001 WHERE id=1
                """
        ));
    }

    private static void assertSqlErrors(int expectedCode, List<String> statements) {
        for (String statement : statements) {
            assertThatThrownBy(() -> sql(statement)).as(statement)
                .isInstanceOfSatisfying(SQLException.class,
                    exception -> assertThat(exception.getErrorCode()).as(statement).isEqualTo(expectedCode));
        }
    }

    @Test
    void differentMembersMayUseTheSameRequestKey() throws SQLException {
        sql("""
            INSERT INTO sales.members(email, password_hash, nickname, nickname_key, role, created_at, updated_at)
            VALUES ('second@example.com', 'hash', 'Second', 'second', 'USER', NOW(6), NOW(6))
            """);
        try {
            sql("""
                INSERT INTO sales.purchase_orders(public_id, member_id, request_key, sale_id, product_id,
                    product_name_snapshot, unit_price, total_amount, quantity, status, expires_at, created_at, updated_at)
                SELECT UNHEX('DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD'), m.id, o.request_key, o.sale_id, o.product_id,
                    o.product_name_snapshot, o.unit_price, o.total_amount, o.quantity, o.status, o.expires_at, NOW(6), NOW(6)
                FROM sales.purchase_orders o JOIN sales.members m ON m.email='second@example.com' WHERE o.id=1
                """);
            assertThat(count("SELECT COUNT(*) FROM sales.purchase_orders WHERE request_key = "
                + "UNHEX('11223344556677889900AABBCCDDEEFF')")).isEqualTo(2);
        } finally {
            sql("DELETE FROM sales.purchase_orders WHERE public_id=UNHEX('DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD')");
            sql("DELETE FROM sales.members WHERE email='second@example.com'");
        }
    }

    @Test
    void paymentPgKeyIsUniqueAcrossDifferentOrders() throws Exception {
        sql("""
            INSERT INTO sales.purchase_orders(public_id, member_id, request_key, sale_id, product_id,
            product_name_snapshot, unit_price, total_amount, quantity, status, expires_at, created_at,
            updated_at) VALUES (UNHEX('BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB'), 1,
            UNHEX('CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC'), 1, 1, 'product', 1, 1, 1, 'AWAITING_PAYMENT',
            NOW(6), NOW(6), NOW(6))
            """);
        try {
            assertThatThrownBy(() -> sql("""
            INSERT INTO sales.payments(order_id, pg_key, amount, status, created_at, updated_at) SELECT
            o.id, p.pg_key, 1, 'READY', NOW(6), NOW(6) FROM sales.purchase_orders o JOIN sales.payments
            p ON p.id=1 WHERE HEX(o.public_id)='BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB'
            """))
                .isInstanceOfSatisfying(SQLException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(1062));
        } finally {
            sql("DELETE FROM sales.purchase_orders WHERE HEX(public_id)='BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB'");
        }
    }
    @Test
    void jpaRoundTripsUuidEnumUtcAndMaximumAmount() throws Exception {
        sql("UPDATE sales.purchase_orders SET created_at='2024-01-02 03:04:05.123456' WHERE id=1");
        String url = db.getJdbcUrl() + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(LimitedSaleApplication.class)
                .web(org.springframework.boot.WebApplicationType.SERVLET)
                .run("--server.port=0", "--spring.datasource.url=" + url,
                     "--spring.datasource.username=" + db.getUsername(),
                     "--spring.datasource.password=" + db.getPassword(), "--spring.flyway.enabled=false",
                     "--management.health.redis.enabled=false")) {
            assertHealth(context);
            assertConnectionSettings(context);
            var emf = context.getBean(jakarta.persistence.EntityManagerFactory.class);
            var em = emf.createEntityManager();
            var type = com.wooyongjeong.limitedsale.order.infrastructure.persistence.PurchaseOrderEntity.class;
            Object order = em.find(type, 1L);
            assertThat(field(order, "publicId")).isEqualTo(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"));
            assertThat(field(order, "status").toString()).isEqualTo("AWAITING_PAYMENT");
            assertThat(field(order, "createdAt")).isEqualTo(java.time.Instant.parse("2024-01-02T03:04:05.123456Z"));
            assertThat(field(order, "totalAmount")).isEqualTo(1000000000000000L);
            em.getTransaction().begin();
            setField(order, "publicId", UUID.fromString("fedcba98-7654-3210-fedc-ba9876543210"));
            setField(order, "status", com.wooyongjeong.limitedsale.order.infrastructure.persistence.OrderStatus.PAYMENT_PENDING);
            setField(order, "createdAt", java.time.Instant.parse("2025-02-03T04:05:06.654321Z"));
            em.getTransaction().commit();
            em.clear();
            order = em.find(type, 1L);
            assertThat(field(order, "publicId")).isEqualTo(UUID.fromString("fedcba98-7654-3210-fedc-ba9876543210"));
            assertThat(field(order, "status").toString()).isEqualTo("PAYMENT_PENDING");
            assertThat(field(order, "createdAt")).isEqualTo(java.time.Instant.parse("2025-02-03T04:05:06.654321Z"));
            assertThat(count("""
            SELECT COUNT(*) FROM sales.purchase_orders WHERE
            HEX(public_id)='FEDCBA9876543210FEDCBA9876543210' AND status='PAYMENT_PENDING' AND
            created_at='2025-02-03 04:05:06.654321' AND total_amount=1000000000000000
            """)).isEqualTo(1);
            em.close();
        }
    }
    @Test
    void pgJpaRoundTrips() throws Exception {
        sql("UPDATE pg.mock_payments SET created_at='2024-01-02 03:04:05.123456' WHERE id=1");
        String url = db.getJdbcUrl().replace("/sales", "/pg") + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(com.wooyongjeong.mockpg.MockPgApplication.class)
                .web(org.springframework.boot.WebApplicationType.SERVLET)
                .run("--server.port=0", "--spring.config.location=file:mock-pg/src/main/resources/application.yml",
                     "--spring.datasource.url=" + url, "--spring.datasource.username=pg_user",
                     "--spring.datasource.password=test_password_pg", "--spring.flyway.enabled=false",
                     "--management.health.redis.enabled=false",
                     "--spring.autoconfigure.exclude="
                         + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
                         + "org.springframework.boot.actuate.autoconfigure.security.servlet."
                         + "ManagementWebSecurityAutoConfiguration")) {
            assertHealth(context);
            assertConnectionSettings(context);
            var em = context.getBean(jakarta.persistence.EntityManagerFactory.class).createEntityManager();
            var type = com.wooyongjeong.mockpg.infrastructure.persistence.MockPaymentEntity.class;
            Object payment = em.find(type, 1L);
            assertThat(field(payment, "pgKey")).isEqualTo(UUID.fromString("21223344-5566-7788-9900-aabbccddeeff"));
            assertThat(field(payment, "amount")).isEqualTo(1000000000000000L);
            assertThat(field(payment, "createdAt")).isEqualTo(java.time.Instant.parse("2024-01-02T03:04:05.123456Z"));
            em.getTransaction().begin();
            setField(payment, "status", com.wooyongjeong.mockpg.infrastructure.persistence.MockPaymentStatus.SUCCEEDED);
            setField(payment, "scenario", com.wooyongjeong.mockpg.infrastructure.persistence.MockScenario.RESPONSE_LOSS);
            em.getTransaction().commit();
            em.clear();
            payment = em.find(type, 1L);
            assertThat(field(payment, "status").toString()).isEqualTo("SUCCEEDED");
            assertThat(field(payment, "scenario").toString()).isEqualTo("RESPONSE_LOSS");
            assertThat(count("SELECT COUNT(*) FROM pg.mock_payments WHERE status='SUCCEEDED' AND scenario='RESPONSE_LOSS'")).isEqualTo(1);
            em.close();
        }
    }
    static void assertConnectionSettings(org.springframework.context.ConfigurableApplicationContext context) throws SQLException {
        try (Connection connection = context.getBean(javax.sql.DataSource.class).getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT @@session.time_zone, @@session.transaction_isolation")) {
            assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isIn("+00:00", "UTC");
            assertThat(result.getString(2)).isEqualTo("READ-COMMITTED");
        }
    }
    static void assertHealth(org.springframework.context.ConfigurableApplicationContext context) throws Exception {
        int port = ((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext) context).getWebServer().getPort();
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/actuator/health")).GET().build();
        var response = java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }
    static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    static void setField(Object object, String name, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    @Test
    void enumValuesRequireExactCaseAndNoTrailingSpace() {
        String[][] columns = {
            {"sales.members", "role", "USER"},
            {"sales.purchase_orders", "status", "AWAITING_PAYMENT"},
            {"sales.sale_reservations", "status", "RESERVED"},
            {"sales.payments", "status", "READY"},
            {"pg.mock_payments", "status", "PROCESSING"},
            {"pg.mock_payments", "scenario", "SUCCESS"}
        };
        for (String[] column : columns) {
            for (String value : List.of(column[2].toLowerCase(Locale.ROOT), column[2] + " ")) {
                String statement = "UPDATE " + column[0] + " SET " + column[1] + "='" + value + "' WHERE id=1";
                assertThatThrownBy(() -> sql(statement)).as(statement)
                    .isInstanceOfSatisfying(SQLException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(3819));
            }
        }
    }
    @Test
    void accountsCannotReadTheOtherSchema() throws Exception {
        try (Connection c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword()); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeQuery("SELECT * FROM pg.mock_payments")).isInstanceOf(SQLException.class);
        }
        try (Connection c = DriverManager.getConnection(db.getJdbcUrl().replace("/sales", "/pg"),
                "pg_user", "test_password_pg"); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeQuery("SELECT * FROM sales.members")).isInstanceOf(SQLException.class);
        }
    }
}
