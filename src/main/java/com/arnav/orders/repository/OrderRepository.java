package com.arnav.orders.repository;

import com.arnav.orders.domain.Order;
import com.arnav.orders.domain.OrderStatus;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * Fetches the items too: the replay path returns this order straight to
     * the caller after the transaction ended (open-in-view is off), so a lazy
     * collection would blow up while the response is being built.
     */
    @Query("select o from Order o left join fetch o.items where o.idempotencyKey = :key")
    Optional<Order> findByIdempotencyKey(@Param("key") String idempotencyKey);

    @Query("select o from Order o left join fetch o.items where o.id = :id")
    Optional<Order> findWithItems(@Param("id") Long id);

    /**
     * Compare-and-set on status: the row only changes if it is still in the
     * state the caller saw. A concurrent pay/cancel loses cleanly (returns 0)
     * instead of overwriting the other's result.
     */
    @Modifying(clearAutomatically = true)
    @Query("update Order o set o.status = :to where o.id = :id and o.status = :from")
    int transition(@Param("id") Long id, @Param("from") OrderStatus from, @Param("to") OrderStatus to);

    /** Served by idx_orders_status_created. Slice avoids a second COUNT query. */
    Slice<Order> findByStatusAndCreatedAtBetweenOrderByCreatedAtDesc(
            OrderStatus status, Instant from, Instant to, Pageable pageable);
}
