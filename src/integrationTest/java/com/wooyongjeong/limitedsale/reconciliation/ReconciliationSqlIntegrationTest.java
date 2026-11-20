package com.wooyongjeong.limitedsale.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.mysql.MySQLContainer;

/** Runs tools/reconciliation/reconcile.sql on a real MySQL schema, clean and deliberately corrupted. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReconciliationSqlIntegrationTest {
    static final MySQLContainer DB = new MySQLContainer("mysql:8.4.7")
        .withDatabaseName("sales").withUsername("sales_user").withPassword("test_password");
    static Connection root;
    static String reconcile;
    static boolean guardsDropped;

    @BeforeAll
    static void start() throws Exception {
        DB.start();
        Flyway.configure().dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
            .locations("filesystem:src/main/resources/db/migration", "classpath:db/nickmigration").load().migrate();
        root = DriverManager.getConnection(DB.getJdbcUrl(), "root", DB.getPassword());
        reconcile = Files.readString(Path.of("tools/reconciliation/reconcile.sql"));
    }

    @AfterAll
    static void stop() throws SQLException {
        root.close();
        DB.stop();
    }

    @BeforeEach
    void consistentData() throws SQLException {
        sql("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : List.of("payments", "order_request_results", "sale_reservations", "purchase_orders",
                "sale_buyer_quantities", "sales", "products", "members")) {
            sql("TRUNCATE TABLE " + table);
        }
        sql("SET FOREIGN_KEY_CHECKS = 1");
        for (int member = 1; member <= 4; member++) {
            sql("INSERT INTO members(id, email, nickname, nickname_key, password_hash, role, created_at, updated_at) "
                + "VALUES (" + member + ", 'm" + member + "@example.test', 'member" + member + "', 'member"
                + member + "', 'hash', 'USER', NOW(6), NOW(6))");
        }
        sql("INSERT INTO products(id, name, description, base_price, created_at, updated_at) "
            + "VALUES (1, 'product', '', 1000, NOW(6), NOW(6))");
        // Stock 10, limit 2: order 1 and 2 sold 3 units, order 3 holds 1 unit, orders 4 and 5 released theirs.
        sql("INSERT INTO sales(id, product_id, name, description, product_name_snapshot, unit_price, starts_at, "
            + "ends_at, total_quantity, reserved_quantity, sold_quantity, per_member_limit, created_at, updated_at) "
            + "VALUES (1, 1, 'sale', '', 'product', 1000, NOW(6), NOW(6) + INTERVAL 1 DAY, 10, 1, 3, 2, "
            + "NOW(6), NOW(6))");
        buyer(1, 0, 1);
        buyer(2, 0, 2);
        buyer(3, 1, 0);
        order(1, 1, 1, "CONFIRMED", "CONFIRMED", "SUCCEEDED");
        order(2, 2, 2, "CONFIRMED", "CONFIRMED", "SUCCEEDED");
        order(3, 3, 1, "AWAITING_PAYMENT", "RESERVED", "READY");
        order(4, 3, 1, "PAYMENT_FAILED", "RELEASED", "FAILED");
        order(5, 1, 1, "EXPIRED", "RELEASED", "CLOSED");
        sql("INSERT INTO order_request_results(member_id, request_key, sale_id, quantity, outcome, "
            + "rejection_reason, created_at) VALUES (4, " + uuid(40) + ", 1, 1, 'REJECTED', 'SALE_SOLD_OUT', NOW(6))");
    }

    @Test
    @Order(1)
    void consistentDatabaseReturnsNoRows() throws SQLException {
        assertThat(violations()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corruptions")
    void corruptedDatabaseReturnsTheViolatedCheck(String check, List<String> statements) throws SQLException {
        defeatGuards();
        for (String statement : statements) {
            sql(statement);
        }
        assertThat(violations()).contains(check);
    }

    static Stream<Arguments> corruptions() {
        return Stream.of(
            corruption("SALE_QUANTITY_BOUNDS", "UPDATE sales SET reserved_quantity = 20"),
            corruption("SALE_RESERVED_VS_RESERVATIONS", "UPDATE sales SET reserved_quantity = 2"),
            corruption("SALE_SOLD_VS_RESERVATIONS", "UPDATE sales SET sold_quantity = 2"),
            corruption("SALE_OVERSOLD", "UPDATE sales SET total_quantity = 2"),
            corruption("BUYER_QUANTITY_BOUNDS",
                "UPDATE sale_buyer_quantities SET purchased_quantity = 3 WHERE member_id = 2"),
            corruption("BUYER_VS_RESERVATIONS",
                "UPDATE sale_buyer_quantities SET purchased_quantity = 0 WHERE member_id = 1"),
            corruption("BUYER_LIMIT_EXCEEDED", "UPDATE sales SET per_member_limit = 1"),
            corruption("ORDER_MISSING_RESERVATION", "DELETE FROM sale_reservations WHERE order_id = 4"),
            corruption("ORDER_MISSING_PAYMENT", "DELETE FROM payments WHERE order_id = 4"),
            corruption("ORDER_MISSING_REQUEST_RESULT", "DELETE FROM order_request_results WHERE order_id = 4"),
            corruption("ORDER_AMOUNT", "UPDATE purchase_orders SET total_amount = 1 WHERE id = 1"),
            corruption("ORDER_UNIT_PRICE_VS_SALE",
                "UPDATE purchase_orders SET unit_price = 999, total_amount = 999 WHERE id = 1"),
            corruption("PAYMENT_AMOUNT_VS_ORDER", "UPDATE payments SET amount = 1 WHERE order_id = 1"),
            corruption("CONFIRMED_ORDER_WITHOUT_SUCCEEDED_PAYMENT",
                "UPDATE payments SET status = 'PROCESSING' WHERE order_id = 1"),
            corruption("TERMINAL_ORDER_PAYMENT_STATUS", "UPDATE payments SET status = 'SUCCEEDED' WHERE order_id = 4"),
            corruption("RESERVATION_VS_ORDER", "UPDATE sale_reservations SET quantity = 2 WHERE order_id = 3"),
            corruption("RESERVATION_STATUS_VS_ORDER",
                "UPDATE sale_reservations SET status = 'RESERVED' WHERE order_id = 4"),
            corruption("DUPLICATE_ORDER_REQUEST_KEY",
                "INSERT INTO purchase_orders(id, public_id, member_id, request_key, sale_id, product_id, "
                    + "product_name_snapshot, unit_price, total_amount, quantity, status, expires_at, created_at, "
                    + "updated_at) VALUES (6, " + uuid(106) + ", 1, " + uuid(201) + ", 1, 1, 'product', 1000, "
                    + "1000, 1, 'CONFIRMED', NOW(6), NOW(6), NOW(6))"),
            corruption("DUPLICATE_REQUEST_RESULT_KEY",
                "INSERT INTO order_request_results(member_id, request_key, sale_id, quantity, outcome, "
                    + "rejection_reason, created_at) VALUES (4, " + uuid(40) + ", 1, 1, 'REJECTED', "
                    + "'SALE_SOLD_OUT', NOW(6))"),
            corruption("REQUEST_RESULT_VS_ORDER", "UPDATE order_request_results SET quantity = 2 WHERE order_id = 1"),
            corruption("REJECTED_REQUEST_HAS_ORDER",
                "UPDATE order_request_results SET outcome = 'REJECTED', order_id = NULL, "
                    + "rejection_reason = 'SALE_SOLD_OUT' WHERE order_id = 4"));
    }

    static Arguments corruption(String check, String... statements) {
        return Arguments.of(check, List.of(statements));
    }

    /** The schema rejects several of these corruptions, so the guards are removed before seeding them. */
    static void defeatGuards() throws SQLException {
        if (guardsDropped) {
            return;
        }
        sql("ALTER TABLE sales DROP CHECK ck_sales_quantities");
        sql("ALTER TABLE purchase_orders DROP CHECK ck_orders_amount");
        sql("ALTER TABLE purchase_orders DROP INDEX uk_orders_request");
        sql("CREATE INDEX ix_results_member ON order_request_results (member_id)");
        sql("ALTER TABLE order_request_results DROP INDEX uk_order_request_results_member_key");
        guardsDropped = true;
    }

    static void buyer(int member, int reserved, int purchased) throws SQLException {
        sql("INSERT INTO sale_buyer_quantities(sale_id, member_id, reserved_quantity, purchased_quantity, "
            + "created_at, updated_at) VALUES (1, " + member + ", " + reserved + ", " + purchased
            + ", NOW(6), NOW(6))");
    }

    static void order(int id, int member, int quantity, String status, String reservation, String payment)
            throws SQLException {
        long amount = 1000L * quantity;
        sql("INSERT INTO purchase_orders(id, public_id, member_id, request_key, sale_id, product_id, "
            + "product_name_snapshot, unit_price, total_amount, quantity, status, expires_at, created_at, "
            + "updated_at) VALUES (" + id + ", " + uuid(100 + id) + ", " + member + ", " + uuid(200 + id)
            + ", 1, 1, 'product', 1000, " + amount + ", " + quantity + ", '" + status
            + "', NOW(6), NOW(6), NOW(6))");
        sql("INSERT INTO sale_reservations(order_id, sale_id, member_id, quantity, status, created_at, updated_at) "
            + "VALUES (" + id + ", 1, " + member + ", " + quantity + ", '" + reservation + "', NOW(6), NOW(6))");
        sql("INSERT INTO payments(order_id, pg_key, amount, status, created_at, updated_at) VALUES (" + id + ", "
            + uuid(300 + id) + ", " + amount + ", '" + payment + "', NOW(6), NOW(6))");
        sql("INSERT INTO order_request_results(member_id, request_key, sale_id, quantity, outcome, order_id, "
            + "created_at) VALUES (" + member + ", " + uuid(200 + id) + ", 1, " + quantity + ", 'CREATED', " + id
            + ", NOW(6))");
    }

    static String uuid(int value) {
        return "UNHEX(LPAD('" + Integer.toHexString(value) + "', 32, '0'))";
    }

    static void sql(String statement) throws SQLException {
        try (Statement s = root.createStatement()) {
            s.execute(statement);
        }
    }

    static List<String> violations() throws SQLException {
        List<String> checks = new ArrayList<>();
        try (Statement s = root.createStatement(); ResultSet result = s.executeQuery(reconcile)) {
            while (result.next()) {
                checks.add(result.getString("check_name"));
            }
        }
        return checks;
    }
}
