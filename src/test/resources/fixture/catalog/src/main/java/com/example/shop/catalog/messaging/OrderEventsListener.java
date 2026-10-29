package com.example.shop.catalog.messaging;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.example.shop.catalog.app.ProductService;
import com.example.shop.common.events.OrderPlacedEvent;

@Component
public class OrderEventsListener {

    private final ProductService products;

    public OrderEventsListener(ProductService products) {
        this.products = products;
    }

    @KafkaListener(topics = "${shop.topics.orders}", groupId = "catalog")
    public void onOrderPlaced(OrderPlacedEvent event) {
        products.reserve(event.productId(), event.quantity());
    }
}
