package com.arnav.orders.service;

import com.arnav.orders.domain.Order;
import com.arnav.orders.domain.OrderItem;
import com.arnav.orders.domain.OrderStatus;
import com.arnav.orders.domain.Product;
import com.arnav.orders.repository.OrderRepository;
import com.arnav.orders.repository.ProductRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderService {

    private final OrderRepository orders;
    private final ProductRepository products;
    private final TransactionTemplate tx;

    public OrderService(OrderRepository orders, ProductRepository products, PlatformTransactionManager txManager) {
        this.orders = orders;
        this.products = products;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Places an order exactly once per Idempotency-Key.
     *
     * <p>A retry after a timeout returns the original order instead of taking
     * stock twice. Two simultaneous first attempts race on the UNIQUE
     * constraint; the loser's transaction rolls back (including its stock
     * reservation) and it returns the winner's order.
     */
    public PlacedOrder place(String idempotencyKey, OrderRequest request) {
        String hash = hash(request);
        var existing = orders.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), hash);
        }
        try {
            Order created = tx.execute(status -> createOrder(idempotencyKey, hash, request));
            return new PlacedOrder(created, true);
        } catch (DataIntegrityViolationException raced) {
            Order winner = orders.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> raced);
            return replay(winner, hash);
        }
    }

    private PlacedOrder replay(Order existing, String hash) {
        if (!existing.getRequestHash().equals(hash)) {
            throw new Errors.IdempotencyConflict(existing.getIdempotencyKey());
        }
        return new PlacedOrder(existing, false);
    }

    private Order createOrder(String key, String hash, OrderRequest request) {
        Map<Long, Product> catalog = products.findAllByIdIn(
                        request.lines().stream().map(OrderRequest.Line::productId).toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));

        Order order = new Order(key, hash, request.customerId());
        // Fixed lock order (by product id) so two orders touching the same
        // products in opposite order cannot deadlock each other.
        List<OrderRequest.Line> sorted = request.lines().stream()
                .sorted(Comparator.comparing(OrderRequest.Line::productId)).toList();
        for (OrderRequest.Line line : sorted) {
            Product product = catalog.get(line.productId());
            if (product == null) {
                throw new Errors.NotFound("Product " + line.productId());
            }
            if (products.reserve(line.productId(), line.quantity()) == 0) {
                throw new Errors.InsufficientStock(line.productId(), line.quantity());
            }
            order.addItem(product.getId(), line.quantity(), product.getPriceCents());
        }
        return orders.saveAndFlush(order);
    }

    public Order pay(Long orderId) {
        return transition(orderId, OrderStatus.PAID);
    }

    /** Cancelling returns the reserved stock in the same transaction. */
    public Order cancel(Long orderId) {
        return transition(orderId, OrderStatus.CANCELLED);
    }

    private Order transition(Long orderId, OrderStatus to) {
        return tx.execute(status -> {
            Order order = orders.findWithItems(orderId)
                    .orElseThrow(() -> new Errors.NotFound("Order " + orderId));
            OrderStatus from = order.getStatus();
            if (!from.canTransitionTo(to)) {
                throw new Errors.InvalidTransition(orderId, from, to);
            }
            // Snapshot the items before the bulk update clears the persistence context.
            List<OrderItem> items = List.copyOf(order.getItems());
            if (orders.transition(orderId, from, to) == 0) {
                // Someone else moved it between our read and this write.
                throw new Errors.InvalidTransition(orderId, orders.findById(orderId).orElseThrow().getStatus(), to);
            }
            if (to == OrderStatus.CANCELLED) {
                items.stream()
                        .sorted(Comparator.comparing(OrderItem::getProductId))
                        .forEach(i -> products.release(i.getProductId(), i.getQuantity()));
            }
            return orders.findWithItems(orderId).orElseThrow();
        });
    }

    public Order get(Long orderId) {
        return orders.findWithItems(orderId).orElseThrow(() -> new Errors.NotFound("Order " + orderId));
    }

    public Slice<Order> search(OrderStatus status, Instant from, Instant to, int page, int size) {
        return orders.findByStatusAndCreatedAtBetweenOrderByCreatedAtDesc(
                status, from, to, PageRequest.of(page, Math.min(size, 100)));
    }

    static String hash(OrderRequest request) {
        String canonical = request.customerId() + "|" + request.lines().stream()
                .sorted(Comparator.comparing(OrderRequest.Line::productId))
                .map(l -> l.productId() + "x" + l.quantity())
                .collect(Collectors.joining(","));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code created} is false when an idempotent retry returned an existing order. */
    public record PlacedOrder(Order order, boolean created) {}
}
