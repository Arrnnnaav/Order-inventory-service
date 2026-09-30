package com.arnav.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OrderStatusTest {

    @ParameterizedTest
    @CsvSource({
        "PLACED, PAID, true",
        "PLACED, CANCELLED, true",
        "PAID, CANCELLED, true",
        "PAID, PLACED, false",
        "PAID, PAID, false",
        "PLACED, PLACED, false",
        "CANCELLED, PLACED, false",
        "CANCELLED, PAID, false",
        "CANCELLED, CANCELLED, false"
    })
    void transitionTable(OrderStatus from, OrderStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @Test
    void cancelledIsTerminal() {
        for (OrderStatus next : OrderStatus.values()) {
            assertThat(OrderStatus.CANCELLED.canTransitionTo(next)).isFalse();
        }
    }
}
