package com.arnav.orders.bench;

import com.arnav.orders.domain.OrderStatus;
import com.arnav.orders.service.OrderService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Measures the order-search query with and without idx_orders_status_created
 * on 500,000 seeded orders. Not part of `mvn test` / `mvn verify` (the name
 * matches neither pattern); run it explicitly:
 *
 * <pre>./mvnw test -Dtest=SearchIndexBenchmarkBench</pre>
 *
 * Results are written to docs/benchmark.json.
 */
@SpringBootTest
class SearchIndexBenchmarkBench {

    static final int ORDERS = 500_000;
    static final int RUNS = 40;

    @Autowired OrderService service;
    @Autowired JdbcTemplate jdbc;

    @Test
    void measure() throws Exception {
        jdbc.execute("TRUNCATE order_items, orders, products RESTART IDENTITY CASCADE");
        // Skewed like real data: most orders are PAID, few are still PLACED.
        jdbc.update("""
                INSERT INTO orders (idempotency_key, request_hash, customer_id, status, total_cents, created_at)
                SELECT 'seed-' || g, 'h', 'cust-' || (g % 5000),
                       CASE WHEN r < 0.08 THEN 'PLACED' WHEN r < 0.75 THEN 'PAID' ELSE 'CANCELLED' END,
                       (random() * 50000)::bigint,
                       now() - (random() * interval '365 days')
                FROM (SELECT g, random() AS r FROM generate_series(1, ?) g) s
                """, ORDERS);

        Instant to = Instant.now();
        Instant from = to.minus(7, ChronoUnit.DAYS);

        jdbc.execute("DROP INDEX IF EXISTS idx_orders_status_created");
        jdbc.execute("ANALYZE orders");
        Result without = run("no index (status, created_at)", from, to);

        jdbc.execute("CREATE INDEX idx_orders_status_created ON orders (status, created_at DESC)");
        jdbc.execute("ANALYZE orders");
        Result with = run("with idx_orders_status_created", from, to);

        String json = """
                {
                  "orders": %d,
                  "query": "status=PLACED, last 7 days, newest first, page size 20",
                  "runs": %d,
                  "matching_rows": %d,
                  "without_index": {"p50_ms": %.2f, "p95_ms": %.2f, "plan": "%s"},
                  "with_index": {"p50_ms": %.2f, "p95_ms": %.2f, "plan": "%s"},
                  "speedup_p50": %.1f
                }
                """.formatted(ORDERS, RUNS, matching(from, to),
                without.p50, without.p95, without.plan, with.p50, with.p95, with.plan, without.p50 / with.p50);
        Files.createDirectories(Path.of("docs"));
        Files.writeString(Path.of("docs/benchmark.json"), json);
        System.out.println(json);
    }

    private int matching(Instant from, Instant to) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE status = 'PLACED' AND created_at BETWEEN ? AND ?",
                Integer.class, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
    }

    private Result run(String label, Instant from, Instant to) {
        for (int i = 0; i < 5; i++) service.search(OrderStatus.PLACED, from, to, 0, 20);  // warm up
        List<Double> ms = new ArrayList<>();
        for (int i = 0; i < RUNS; i++) {
            long t0 = System.nanoTime();
            service.search(OrderStatus.PLACED, from, to, 0, 20);
            ms.add((System.nanoTime() - t0) / 1e6);
        }
        Collections.sort(ms);
        String plan = String.join(" | ", jdbc.queryForList("""
                EXPLAIN SELECT * FROM orders WHERE status = 'PLACED'
                AND created_at BETWEEN now() - interval '7 days' AND now()
                ORDER BY created_at DESC LIMIT 21""", String.class)).replace("\"", "'");
        Result r = new Result(ms.get(ms.size() / 2), ms.get((int) (ms.size() * 0.95) - 1),
                plan.length() > 300 ? plan.substring(0, 300) : plan);
        System.out.printf("%s: p50 %.2f ms, p95 %.2f ms%n", label, r.p50, r.p95);
        return r;
    }

    record Result(double p50, double p95, String plan) {}
}
