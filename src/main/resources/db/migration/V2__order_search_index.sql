-- Serves "recent orders in a status": equality on status, then the newest
-- first by created_at. Measured in BenchmarkIT (see README).
CREATE INDEX idx_orders_status_created ON orders (status, created_at DESC);
