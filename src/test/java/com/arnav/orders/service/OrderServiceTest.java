package com.arnav.orders.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.arnav.orders.domain.Order;
import com.arnav.orders.domain.OrderStatus;
import com.arnav.orders.domain.Product;
import com.arnav.orders.repository.OrderRepository;
import com.arnav.orders.repository.ProductRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Business rules only: repositories are mocked, no database. */
class OrderServiceTest {

    private OrderRepository orders;
    private ProductRepository products;
    private OrderService service;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        products = mock(ProductRepository.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new OrderService(orders, products, tm);
        when(orders.saveAndFlush(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Product product(long id, long price) {
        Product p = mock(Product.class);
        when(p.getId()).thenReturn(id);
        when(p.getPriceCents()).thenReturn(price);
        return p;
    }

    private static OrderRequest request(Object... productQty) {
        var lines = new java.util.ArrayList<OrderRequest.Line>();
        for (int i = 0; i < productQty.length; i += 2) {
            lines.add(new OrderRequest.Line(((Number) productQty[i]).longValue(), (Integer) productQty[i + 1]));
        }
        return new OrderRequest("cust-1", lines);
    }

    @Test
    void reservesStockInProductIdOrderAndTotalsThePrice() {
        var p2 = product(2, 500);
        var p1 = product(1, 250);
        when(products.findAllByIdIn(any())).thenReturn(List.of(p2, p1));
        when(products.reserve(anyLong(), anyInt())).thenReturn(1);

        var placed = service.place("k1", request(2L, 2, 1L, 3));

        assertThat(placed.created()).isTrue();
        assertThat(placed.order().getTotalCents()).isEqualTo(2 * 500 + 3 * 250);
        var inOrder = org.mockito.Mockito.inOrder(products);
        inOrder.verify(products).reserve(1L, 3);  // lower id first, whatever the request order was
        inOrder.verify(products).reserve(2L, 2);
    }

    @Test
    void outOfStockFailsTheWholeOrder() {
        var p1 = product(1, 100);
        var p2 = product(2, 100);
        when(products.findAllByIdIn(any())).thenReturn(List.of(p1, p2));
        when(products.reserve(1L, 1)).thenReturn(1);
        when(products.reserve(2L, 5)).thenReturn(0);

        assertThatThrownBy(() -> service.place("k2", request(1L, 1, 2L, 5)))
                .isInstanceOf(Errors.InsufficientStock.class);
        verify(orders, never()).saveAndFlush(any());
    }

    @Test
    void unknownProductIsNotFound() {
        when(products.findAllByIdIn(any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.place("k3", request(99L, 1))).isInstanceOf(Errors.NotFound.class);
        verify(products, never()).reserve(anyLong(), anyInt());
    }

    @Test
    void retryWithSameKeyAndBodyReturnsTheExistingOrderWithoutTouchingStock() {
        var req = request(1L, 1);
        var existing = new Order("k4", OrderService.hash(req), "cust-1");
        when(orders.findByIdempotencyKey("k4")).thenReturn(Optional.of(existing));

        var placed = service.place("k4", req);

        assertThat(placed.created()).isFalse();
        assertThat(placed.order()).isSameAs(existing);
        verify(products, never()).reserve(anyLong(), anyInt());
    }

    @Test
    void reusingAKeyForADifferentBodyIsRejected() {
        var existing = new Order("k5", OrderService.hash(request(1L, 1)), "cust-1");
        when(orders.findByIdempotencyKey("k5")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.place("k5", request(1L, 2)))
                .isInstanceOf(Errors.IdempotencyConflict.class);
    }

    @Test
    void lineOrderDoesNotChangeTheRequestHash() {
        assertThat(OrderService.hash(request(1L, 1, 2L, 2))).isEqualTo(OrderService.hash(request(2L, 2, 1L, 1)));
    }

    @Test
    void losingTheRaceOnTheUniqueKeyReturnsTheWinnersOrder() {
        var req = request(1L, 1);
        var winner = new Order("k6", OrderService.hash(req), "cust-1");
        when(orders.findByIdempotencyKey("k6")).thenReturn(Optional.empty(), Optional.of(winner));
        var p1 = product(1, 100);
        when(products.findAllByIdIn(any())).thenReturn(List.of(p1));
        when(products.reserve(anyLong(), anyInt())).thenReturn(1);
        when(orders.saveAndFlush(any(Order.class))).thenThrow(new DataIntegrityViolationException("duplicate key"));

        var placed = service.place("k6", req);

        assertThat(placed.created()).isFalse();
        assertThat(placed.order()).isSameAs(winner);
    }

    @Test
    void cancelReturnsReservedStockAndPayDoesNot() {
        var order = new Order("k7", "h", "cust-1");
        order.addItem(1L, 2, 100);
        order.addItem(2L, 1, 100);
        when(orders.findWithItems(7L)).thenReturn(Optional.of(order));
        when(orders.transition(7L, OrderStatus.PLACED, OrderStatus.CANCELLED)).thenReturn(1);
        when(orders.transition(7L, OrderStatus.PLACED, OrderStatus.PAID)).thenReturn(1);

        service.cancel(7L);
        verify(products).release(1L, 2);
        verify(products).release(2L, 1);

        org.mockito.Mockito.clearInvocations(products);
        service.pay(7L);
        verify(products, never()).release(anyLong(), anyInt());
    }

    @Test
    void cancelledOrdersCannotBePaidAndPaidOrdersCannotBePaidAgain() {
        for (OrderStatus terminalish : List.of(OrderStatus.CANCELLED, OrderStatus.PAID)) {
            var order = mock(Order.class);
            when(order.getStatus()).thenReturn(terminalish);
            when(orders.findWithItems(8L)).thenReturn(Optional.of(order));
            assertThatThrownBy(() -> service.pay(8L)).isInstanceOf(Errors.InvalidTransition.class);
        }
        verify(orders, never()).transition(anyLong(), any(), any());
    }

    @Test
    void losingAPayVsCancelRaceIsReportedNotSilentlyOverwritten() {
        var order = new Order("k9", "h", "cust-1");
        order.addItem(1L, 1, 100);
        when(orders.findWithItems(9L)).thenReturn(Optional.of(order));
        when(orders.transition(9L, OrderStatus.PLACED, OrderStatus.PAID)).thenReturn(0);
        var moved = mock(Order.class);
        when(moved.getStatus()).thenReturn(OrderStatus.CANCELLED);
        when(orders.findById(9L)).thenReturn(Optional.of(moved));

        assertThatThrownBy(() -> service.pay(9L)).isInstanceOf(Errors.InvalidTransition.class)
                .hasMessageContaining("CANCELLED");
        verify(products, times(0)).release(anyLong(), anyInt());
    }
}
