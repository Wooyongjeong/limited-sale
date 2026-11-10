CREATE TABLE members
(
    id            BIGINT                                  NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email         VARCHAR(254) COLLATE utf8mb4_0900_as_ci NOT NULL,
    password_hash VARCHAR(255)                            NOT NULL,
    role          VARCHAR(16) COLLATE utf8mb4_bin         NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,
    CONSTRAINT uk_members_email UNIQUE (email),
    CONSTRAINT ck_members_role CHECK (role IN ('USER', 'ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE products
(
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL,
    description VARCHAR(2000) NOT NULL,
    base_price  BIGINT        NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    CONSTRAINT ck_products_name CHECK (CHAR_LENGTH(name) BETWEEN 1 AND 100),
    CONSTRAINT ck_products_price CHECK (base_price BETWEEN 1 AND 1000000000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sales
(
    id                    BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id            BIGINT        NOT NULL,
    name                  VARCHAR(100)  NOT NULL,
    description           VARCHAR(2000) NOT NULL,
    product_name_snapshot VARCHAR(100)  NOT NULL,
    unit_price            BIGINT        NOT NULL,
    starts_at             DATETIME(6) NOT NULL,
    ends_at               DATETIME(6) NOT NULL,
    total_quantity        INT           NOT NULL,
    reserved_quantity     INT           NOT NULL DEFAULT 0,
    sold_quantity         INT           NOT NULL DEFAULT 0,
    per_member_limit      INT           NOT NULL DEFAULT 1,
    is_stopped            BOOLEAN       NOT NULL DEFAULT FALSE,
    version               BIGINT        NOT NULL DEFAULT 0,
    created_at            DATETIME(6) NOT NULL,
    updated_at            DATETIME(6) NOT NULL,
    INDEX                 ix_sales_product (product_id),
    CONSTRAINT fk_sales_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_sales_price CHECK (unit_price BETWEEN 1 AND 1000000000),
    CONSTRAINT ck_sales_period CHECK (starts_at < ends_at),
    CONSTRAINT ck_sales_total CHECK (total_quantity BETWEEN 1 AND 1000000),
    CONSTRAINT ck_sales_limit CHECK (per_member_limit BETWEEN 1 AND total_quantity),
    CONSTRAINT ck_sales_quantities CHECK (reserved_quantity >= 0 AND sold_quantity >= 0 AND
                                          reserved_quantity + sold_quantity <= total_quantity),
    CONSTRAINT ck_sales_stopped CHECK (is_stopped IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sale_buyer_quantities
(
    id                 BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    sale_id            BIGINT NOT NULL,
    member_id          BIGINT NOT NULL,
    reserved_quantity  INT    NOT NULL DEFAULT 0,
    purchased_quantity INT    NOT NULL DEFAULT 0,
    version            BIGINT NOT NULL DEFAULT 0,
    created_at         DATETIME(6) NOT NULL,
    updated_at         DATETIME(6) NOT NULL,
    CONSTRAINT uk_buyer_sale_member UNIQUE (sale_id, member_id),
    INDEX              ix_buyer_member (member_id),
    CONSTRAINT fk_buyer_sale FOREIGN KEY (sale_id) REFERENCES sales (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_buyer_member FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_buyer_quantities CHECK (reserved_quantity >= 0 AND purchased_quantity >= 0 AND
                                          reserved_quantity + purchased_quantity <= 1000000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE purchase_orders
(
    id                    BIGINT                          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    public_id             BINARY(16) NOT NULL,
    member_id             BIGINT                          NOT NULL,
    request_key           BINARY(16) NOT NULL,
    sale_id               BIGINT                          NOT NULL,
    product_id            BIGINT                          NOT NULL,
    product_name_snapshot VARCHAR(100)                    NOT NULL,
    unit_price            BIGINT                          NOT NULL,
    total_amount          BIGINT                          NOT NULL,
    quantity              INT                             NOT NULL,
    status                VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    expires_at            DATETIME(6) NOT NULL,
    next_recovery_at      DATETIME(6),
    lease_token           BINARY(16),
    lease_until           DATETIME(6),
    requires_review       BOOLEAN                         NOT NULL DEFAULT FALSE,
    version               BIGINT                          NOT NULL DEFAULT 0,
    created_at            DATETIME(6) NOT NULL,
    updated_at            DATETIME(6) NOT NULL,
    CONSTRAINT uk_orders_public UNIQUE (public_id),
    CONSTRAINT uk_orders_request UNIQUE (member_id, request_key),
    INDEX                 ix_orders_member_id (member_id,id),
    INDEX                 ix_orders_sale (sale_id),
    INDEX                 ix_orders_product (product_id),
    INDEX                 ix_orders_recovery (status,next_recovery_at,id),
    CONSTRAINT fk_orders_member FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_orders_sale FOREIGN KEY (sale_id) REFERENCES sales (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_orders_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_orders_price CHECK (unit_price BETWEEN 1 AND 1000000000),
    CONSTRAINT ck_orders_quantity CHECK (quantity BETWEEN 1 AND 1000000),
    CONSTRAINT ck_orders_amount CHECK (total_amount = unit_price * quantity),
    CONSTRAINT ck_orders_status CHECK (status IN ('AWAITING_PAYMENT', 'PAYMENT_PENDING', 'CONFIRMED', 'PAYMENT_FAILED',
                                                  'EXPIRED')),
    CONSTRAINT ck_orders_lease CHECK ((lease_token IS NULL AND lease_until IS NULL) OR
                                      (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CONSTRAINT ck_orders_review CHECK (requires_review IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sale_reservations
(
    id         BIGINT                          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id   BIGINT                          NOT NULL,
    sale_id    BIGINT                          NOT NULL,
    member_id  BIGINT                          NOT NULL,
    quantity   INT                             NOT NULL,
    status     VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    version    BIGINT                          NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_reservations_order UNIQUE (order_id),
    INDEX      ix_reservations_buyer (sale_id,member_id),
    CONSTRAINT fk_reservations_order FOREIGN KEY (order_id) REFERENCES purchase_orders (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_reservations_buyer FOREIGN KEY (sale_id, member_id) REFERENCES sale_buyer_quantities (sale_id, member_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_reservations_quantity CHECK (quantity BETWEEN 1 AND 1000000),
    CONSTRAINT ck_reservations_status CHECK (status IN ('RESERVED', 'CONFIRMED', 'RELEASED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE payments
(
    id              BIGINT                          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id        BIGINT                          NOT NULL,
    pg_key          BINARY(16) NOT NULL,
    amount          BIGINT                          NOT NULL,
    status          VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    requested_at    DATETIME(6),
    last_checked_at DATETIME(6),
    finalized_at    DATETIME(6),
    version         BIGINT                          NOT NULL DEFAULT 0,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    CONSTRAINT uk_payments_order UNIQUE (order_id),
    CONSTRAINT uk_payments_pg_key UNIQUE (pg_key),
    CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES purchase_orders (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_payments_amount CHECK (amount BETWEEN 1 AND 1000000000000000),
    CONSTRAINT ck_payments_status CHECK (status IN ('READY', 'PROCESSING', 'UNKNOWN', 'SUCCEEDED', 'FAILED', 'CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
