package com.arnav.orders.web;

import com.arnav.orders.domain.Order;
import com.arnav.orders.domain.OrderStatus;
import com.arnav.orders.domain.Product;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class Dtos {
    private Dtos() {}

    public record CreateProductRequest(
            @NotBlank @Size(max = 64) String sku,
            @NotBlank @Size(max = 200) String name,
            @Min(0) long priceCents,
            @Min(0) int stock) {}

    public record ProductResponse(Long id, String sku, String name, long priceCents, int stock) {
        public static ProductResponse of(Product p) {
            return new ProductResponse(p.getId(), p.getSku(), p.getName(), p.getPriceCents(), p.getStock());
        }
    }

    public record LineRequest(@NotNull Long productId, @Min(1) int quantity) {}

    public record CreateOrderRequest(
            @NotBlank @Size(max = 100) String customerId,
            @NotEmpty @Valid List<LineRequest> lines) {}

    public record ItemResponse(Long productId, int quantity, long unitPriceCents) {}

    public record OrderResponse(
            Long id, String customerId, OrderStatus status, long totalCents, Instant createdAt, List<ItemResponse> items) {
        public static OrderResponse of(Order o) {
            return new OrderResponse(o.getId(), o.getCustomerId(), o.getStatus(), o.getTotalCents(), o.getCreatedAt(),
                    o.getItems().stream()
                            .map(i -> new ItemResponse(i.getProductId(), i.getQuantity(), i.getUnitPriceCents()))
                            .toList());
        }
    }

    /** List view: no items, so paging never triggers a query per order. */
    public record OrderSummary(Long id, String customerId, OrderStatus status, long totalCents, Instant createdAt) {
        public static OrderSummary of(Order o) {
            return new OrderSummary(o.getId(), o.getCustomerId(), o.getStatus(), o.getTotalCents(), o.getCreatedAt());
        }
    }

    public record OrderPage(List<OrderSummary> orders, boolean hasNext) {}

    public record ErrorResponse(String error, String message) {}
}
