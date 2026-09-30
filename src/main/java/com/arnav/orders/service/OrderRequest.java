package com.arnav.orders.service;

import java.util.List;

public record OrderRequest(String customerId, List<Line> lines) {
    public record Line(Long productId, int quantity) {}
}
