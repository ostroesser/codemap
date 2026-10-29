package com.example.shop.orders.app;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.example.shop.common.catalog.CatalogClient;
import com.example.shop.common.catalog.ProductDto;
import com.example.shop.common.events.OrderPlacedEvent;
import com.example.shop.common.events.Topics;
import com.example.shop.orders.domain.Order;
import com.example.shop.orders.domain.OrderRepository;

@Service
public class DefaultOrderService implements OrderService {

    private final OrderRepository orders;
    private final CatalogClient catalog;
    private final KafkaTemplate<String, Object> kafka;
    private final String ordersTopic;

    public DefaultOrderService(OrderRepository orders, CatalogClient catalog,
                               KafkaTemplate<String, Object> kafka,
                               @Value("${shop.topics.orders}") String ordersTopic) {
        this.orders = orders;
        this.catalog = catalog;
        this.kafka = kafka;
        this.ordersTopic = ordersTopic;
    }

    @Override
    public Long place(long productId, int quantity) {
        ProductDto product = catalog.get(productId);
        catalog.reserve(productId, quantity);
        Order order = new Order();
        order.addLine(productId, quantity, product.priceCents());
        order.markPlaced();
        Order saved = orders.save(order);
        kafka.send(ordersTopic, new OrderPlacedEvent(saved.getId(), productId, quantity));
        kafka.send(Topics.ORDERS_AUDIT, "placed " + saved.getId());
        return saved.getId();
    }

    @Override
    public Order find(Long id) {
        return orders.findById(id).orElseThrow();
    }
}
