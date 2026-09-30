package com.arnav.orders.web;

import com.arnav.orders.domain.OrderStatus;
import com.arnav.orders.service.OrderRequest;
import com.arnav.orders.service.OrderService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.stream.Collectors;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    /** 201 for a new order, 200 when an Idempotency-Key retry returns the existing one. */
    @PostMapping
    public ResponseEntity<Dtos.OrderResponse> place(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody Dtos.CreateOrderRequest body) {
        var request = new OrderRequest(body.customerId(),
                body.lines().stream().map(l -> new OrderRequest.Line(l.productId(), l.quantity())).toList());
        var result = service.place(idempotencyKey, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(Dtos.OrderResponse.of(result.order()));
    }

    @GetMapping("/{id}")
    public Dtos.OrderResponse get(@PathVariable Long id) {
        return Dtos.OrderResponse.of(service.get(id));
    }

    @PostMapping("/{id}/pay")
    public Dtos.OrderResponse pay(@PathVariable Long id) {
        return Dtos.OrderResponse.of(service.pay(id));
    }

    @PostMapping("/{id}/cancel")
    public Dtos.OrderResponse cancel(@PathVariable Long id) {
        return Dtos.OrderResponse.of(service.cancel(id));
    }

    @GetMapping
    public Dtos.OrderPage search(
            @RequestParam OrderStatus status,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var slice = service.search(status, from, to, page, size);
        return new Dtos.OrderPage(
                slice.getContent().stream().map(Dtos.OrderSummary::of).collect(Collectors.toList()),
                slice.hasNext());
    }
}
