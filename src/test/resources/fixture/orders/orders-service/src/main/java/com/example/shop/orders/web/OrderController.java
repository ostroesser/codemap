package com.example.shop.orders.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.shop.orders.app.OrderService;
import com.example.shop.orders.domain.Order;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Long place(@RequestBody PlaceOrderRequest request) {
        return orderService.place(request.productId(), request.quantity());
    }

    @GetMapping("/{id}")
    public Order get(@PathVariable("id") Long id) {
        return orderService.find(id);
    }
}
