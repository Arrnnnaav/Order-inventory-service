package com.arnav.orders.service;

import com.arnav.orders.domain.OrderStatus;

/** Domain errors, mapped to HTTP statuses in web.ApiExceptionHandler. */
public final class Errors {
    private Errors() {}

    public static class NotFound extends RuntimeException {
        public NotFound(String what) {
            super(what + " not found");
        }
    }

    public static class InsufficientStock extends RuntimeException {
        public InsufficientStock(Long productId, int requested) {
            super("Not enough stock for product " + productId + " (requested " + requested + ")");
        }
    }

    public static class InvalidTransition extends RuntimeException {
        public InvalidTransition(Long orderId, OrderStatus from, OrderStatus to) {
            super("Order " + orderId + " cannot go from " + from + " to " + to);
        }
    }

    /** Same Idempotency-Key reused for a different request body. */
    public static class IdempotencyConflict extends RuntimeException {
        public IdempotencyConflict(String key) {
            super("Idempotency-Key " + key + " was already used with a different request");
        }
    }
}
