package com.example.shop.catalog.app;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
public class LoggingStockReservations implements StockReservations {

    @Override
    public void reserve(long productId, int quantity) {
        System.out.println("reserve " + productId + " x" + quantity);
    }
}
