package com.arnav.orders.repository;

import com.arnav.orders.domain.Product;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findAllByIdIn(List<Long> ids);

    /**
     * Atomic reservation: the stock check and the decrement are one SQL
     * statement, so two concurrent buyers can never both take the last unit
     * (a read-then-write in Java would). Returns 0 when stock is short.
     */
    @Modifying
    @Query("update Product p set p.stock = p.stock - :qty where p.id = :id and p.stock >= :qty")
    int reserve(@Param("id") Long id, @Param("qty") int qty);

    @Modifying
    @Query("update Product p set p.stock = p.stock + :qty where p.id = :id")
    int release(@Param("id") Long id, @Param("qty") int qty);
}
