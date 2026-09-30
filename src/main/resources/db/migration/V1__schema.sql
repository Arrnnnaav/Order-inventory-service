CREATE TABLE products (
    id           BIGSERIAL PRIMARY KEY,
    sku          VARCHAR(64)  NOT NULL UNIQUE,
    name         VARCHAR(200) NOT NULL,
    price_cents  BIGINT       NOT NULL CHECK (price_cents >= 0),
    -- The database, not just application code, refuses to oversell.
    stock        INTEGER      NOT NULL CHECK (stock >= 0)
);

CREATE TABLE orders (
    id               BIGSERIAL PRIMARY KEY,
    idempotency_key  VARCHAR(100) NOT NULL UNIQUE,
    request_hash     VARCHAR(64)  NOT NULL,
    customer_id      VARCHAR(100) NOT NULL,
    status           VARCHAR(16)  NOT NULL CHECK (status IN ('PLACED', 'PAID', 'CANCELLED')),
    total_cents      BIGINT       NOT NULL CHECK (total_cents >= 0),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE order_items (
    id                BIGSERIAL PRIMARY KEY,
    order_id          BIGINT  NOT NULL REFERENCES orders (id),
    product_id        BIGINT  NOT NULL REFERENCES products (id),
    quantity          INTEGER NOT NULL CHECK (quantity > 0),
    unit_price_cents  BIGINT  NOT NULL CHECK (unit_price_cents >= 0)
);

CREATE INDEX idx_order_items_order ON order_items (order_id);
