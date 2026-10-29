package com.example.shop.orders.app;

import com.example.shop.orders.domain.Order;

public interface OrderService {

    Long place(long productId, int quantity);

    Order find(Long id);
}
