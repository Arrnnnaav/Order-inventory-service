package com.arnav.orders.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.arnav.orders.domain.Order;
import com.arnav.orders.service.Errors;
import com.arnav.orders.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** HTTP contract only: status codes, headers, validation. The service is mocked. */
class OrderControllerTest {

    private OrderService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(OrderService.class);
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new OrderController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(validator)
                .build();
    }

    private static final String BODY = "{\"customerId\":\"c1\",\"lines\":[{\"productId\":1,\"quantity\":2}]}";

    private static Order order() {
        var o = new Order("k", "h", "c1");
        o.addItem(1L, 2, 500);
        return o;
    }

    @Test
    void newOrderIs201AndAnIdempotentRetryIs200() throws Exception {
        when(service.place(eq("k"), any())).thenReturn(new OrderService.PlacedOrder(order(), true));
        mvc.perform(post("/orders").header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.totalCents").value(1000));

        when(service.place(eq("k"), any())).thenReturn(new OrderService.PlacedOrder(order(), false));
        mvc.perform(post("/orders").header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
    }

    @Test
    void missingIdempotencyKeyIs400() throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing_header"));
        verifyNoInteractions(service);
    }

    @Test
    void invalidBodiesAre400WithFieldNames() throws Exception {
        String bad = "{\"customerId\":\"\",\"lines\":[{\"productId\":1,\"quantity\":0}]}";
        mvc.perform(post("/orders").header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"));
        verifyNoInteractions(service);
    }

    @Test
    void domainErrorsMapToTheRightStatuses() throws Exception {
        when(service.place(eq("stock"), any())).thenThrow(new Errors.InsufficientStock(1L, 2));
        when(service.place(eq("reuse"), any())).thenThrow(new Errors.IdempotencyConflict("reuse"));
        when(service.get(99L)).thenThrow(new Errors.NotFound("Order 99"));

        mvc.perform(post("/orders").header("Idempotency-Key", "stock").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("insufficient_stock"));
        mvc.perform(post("/orders").header("Idempotency-Key", "reuse").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/orders/99")).andExpect(status().isNotFound());
    }
}
