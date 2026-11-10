ALTER TABLE members
    DROP CHECK ck_members_role,
    MODIFY role VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL,
    ADD CONSTRAINT ck_members_role CHECK (role IN ('USER', 'ADMIN'));

ALTER TABLE purchase_orders
    DROP CHECK ck_orders_status,
    MODIFY status VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
    ADD CONSTRAINT ck_orders_status CHECK (status IN
        ('AWAITING_PAYMENT', 'PAYMENT_PENDING', 'CONFIRMED', 'PAYMENT_FAILED', 'EXPIRED'));

ALTER TABLE sale_reservations
    DROP CHECK ck_reservations_status,
    MODIFY status VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
    ADD CONSTRAINT ck_reservations_status CHECK (status IN ('RESERVED', 'CONFIRMED', 'RELEASED'));

ALTER TABLE payments
    DROP CHECK ck_payments_status,
    MODIFY status VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
    ADD CONSTRAINT ck_payments_status CHECK (status IN
        ('READY', 'PROCESSING', 'UNKNOWN', 'SUCCEEDED', 'FAILED', 'CLOSED'));
