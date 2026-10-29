package com.example.shop.orders.app;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.shop.orders.domain.OrderRepository;

@Component
public class StaleOrderJob {

    private final OrderRepository orders;

    public StaleOrderJob(OrderRepository orders) {
        this.orders = orders;
    }

    @Scheduled(cron = "${shop.jobs.stale-cron:0 0 * * * *}")
    public void purgeStaleOrders() {
        orders.findByStatus("NEW").forEach(order -> orders.delete(order));
    }
}
