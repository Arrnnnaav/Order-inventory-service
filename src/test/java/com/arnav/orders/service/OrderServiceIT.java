package com.arnav.orders.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.arnav.orders.domain.OrderStatus;
import com.arnav.orders.domain.Product;
import com.arnav.orders.repository.ProductRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs against the real PostgreSQL from docker-compose (port 5434): the
 * guarantees under test (atomic stock, unique idempotency keys, rollback)
 * are the database's, so an in-memory stand-in would prove nothing.
 */
@SpringBootTest
class OrderServiceIT {

    @Autowired OrderService service;
    @Autowired ProductRepository products;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE order_items, orders, products RESTART IDENTITY CASCADE");
    }

    private Product newProduct(int stock) {
        return products.save(new Product("SKU-" + UUID.randomUUID(), "Widget", 1_000, stock));
    }

    private int stockOf(Long productId) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId);
    }

    private static OrderRequest buy(Long productId, int qty) {
        return new OrderRequest("cust", List.of(new OrderRequest.Line(productId, qty)));
    }

    @Test
    void concurrentBuyersNeverOversell() throws Exception {
        Product p = newProduct(25);
        int buyers = 80;
        ExecutorService pool = Executors.newFixedThreadPool(buyers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            String key = "buyer-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    service.place(key, buy(p.getId(), 1));
                    ok.incrementAndGet();
                } catch (Errors.InsufficientStock e) {
                    soldOut.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        assertThat(ok.get()).isEqualTo(25);
        assertThat(soldOut.get()).isEqualTo(55);
        assertThat(stockOf(p.getId())).isZero();
        Integer sold = jdbc.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM order_items", Integer.class);
        assertThat(sold).isEqualTo(25);
    }

    @Test
    void sameKeyFromManyThreadsCreatesExactlyOneOrder() throws Exception {
        Product p = newProduct(100);
        int attempts = 30;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OrderService.PlacedOrder>> futures = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return service.place("one-key", buy(p.getId(), 2));
            }));
        }
        start.countDown();
        List<Long> ids = new ArrayList<>();
        int created = 0;
        for (Future<OrderService.PlacedOrder> f : futures) {
            var placed = f.get();
            ids.add(placed.order().getId());
            if (placed.created()) created++;
        }
        pool.shutdown();

        assertThat(created).isEqualTo(1);
        assertThat(ids.stream().distinct()).hasSize(1);
        assertThat(stockOf(p.getId())).isEqualTo(98);  // reserved once, not 30 times
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);
    }

    @Test
    void aReplayedOrderStillHasItsItemsForTheResponse() {
        // Regression: found by a curl smoke test. The replay path returned the
        // order with a lazy items collection, and building the HTTP response
        // threw LazyInitializationException (a 500 instead of a 200).
        Product p = newProduct(10);
        var first = service.place("replay", buy(p.getId(), 3));
        var retry = service.place("replay", buy(p.getId(), 3));

        assertThat(retry.created()).isFalse();
        assertThat(retry.order().getItems()).hasSize(1);
        assertThat(retry.order().getItems().get(0).getQuantity()).isEqualTo(3);
        assertThat(retry.order().getId()).isEqualTo(first.order().getId());
    }

    @Test
    void aFailedLineRollsBackTheLinesBeforeIt() {
        Product plenty = newProduct(10);
        Product scarce = newProduct(1);
        var request = new OrderRequest("cust", List.of(
                new OrderRequest.Line(plenty.getId(), 4),
                new OrderRequest.Line(scarce.getId(), 5)));

        assertThatThrownBy(() -> service.place("partial", request)).isInstanceOf(Errors.InsufficientStock.class);

        assertThat(stockOf(plenty.getId())).isEqualTo(10);
        assertThat(stockOf(scarce.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isZero();
    }

    @Test
    void oppositeLineOrdersAtTheSameTimeDoNotDeadlock() throws Exception {
        Product a = newProduct(500);
        Product b = newProduct(500);
        int threads = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            boolean flip = i % 2 == 0;
            String key = "dl-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                var lines = flip
                        ? List.of(new OrderRequest.Line(a.getId(), 1), new OrderRequest.Line(b.getId(), 1))
                        : List.of(new OrderRequest.Line(b.getId(), 1), new OrderRequest.Line(a.getId(), 1));
                service.place(key, new OrderRequest("cust", lines));
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get();  // a deadlock would surface as an ExecutionException
        pool.shutdown();

        assertThat(stockOf(a.getId())).isEqualTo(500 - threads);
        assertThat(stockOf(b.getId())).isEqualTo(500 - threads);
    }

    @Test
    void cancelRestoresStockAndAPaidOrderCanStillBeCancelled() {
        Product p = newProduct(10);
        var order = service.place("c1", buy(p.getId(), 4)).order();
        assertThat(stockOf(p.getId())).isEqualTo(6);

        service.pay(order.getId());
        var cancelled = service.cancel(order.getId());

        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stockOf(p.getId())).isEqualTo(10);
        assertThatThrownBy(() -> service.cancel(order.getId())).isInstanceOf(Errors.InvalidTransition.class);
        assertThat(stockOf(p.getId())).isEqualTo(10);  // a second cancel must not restock again
    }

    @Test
    void payAndCancelRacingLeaveExactlyOneWinnerAndConsistentStock() throws Exception {
        for (int round = 0; round < 10; round++) {
            Product p = newProduct(5);
            var order = service.place("race-" + round, buy(p.getId(), 2)).order();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> pay = pool.submit(() -> attempt(start, () -> service.pay(order.getId())));
            Future<Boolean> cancel = pool.submit(() -> attempt(start, () -> service.cancel(order.getId())));
            start.countDown();
            boolean paid = pay.get();
            boolean cancelled = cancel.get();
            pool.shutdown();

            String finalStatus = jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order.getId());
            if (cancelled) {
                assertThat(finalStatus).isEqualTo("CANCELLED");
                assertThat(stockOf(p.getId())).isEqualTo(5);
            } else {
                assertThat(paid).isTrue();
                assertThat(finalStatus).isEqualTo("PAID");
                assertThat(stockOf(p.getId())).isEqualTo(3);
            }
        }
    }

    private static boolean attempt(CountDownLatch start, Runnable action) throws InterruptedException {
        start.await();
        try {
            action.run();
            return true;
        } catch (Errors.InvalidTransition e) {
            return false;
        }
    }
}
