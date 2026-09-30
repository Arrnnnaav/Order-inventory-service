package com.arnav.orders.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Order lifecycle. The allowed transitions live here, in one place, so the
 * service cannot skip a state by accident:
 *
 * <pre>
 *   PLACED --> PAID --> CANCELLED
 *      \-----------------^
 * </pre>
 *
 * CANCELLED is terminal. PAID may still be cancelled (stock is returned;
 * refunds are out of scope).
 */
public enum OrderStatus {
    PLACED, PAID, CANCELLED;

    private Set<OrderStatus> allowedNext() {
        return switch (this) {
            case PLACED -> EnumSet.of(PAID, CANCELLED);
            case PAID -> EnumSet.of(CANCELLED);
            case CANCELLED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canTransitionTo(OrderStatus next) {
        return allowedNext().contains(next);
    }
}
