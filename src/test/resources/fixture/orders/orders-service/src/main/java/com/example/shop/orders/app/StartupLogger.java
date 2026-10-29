package com.example.shop.orders.app;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class StartupLogger {

    @EventListener
    public void onReady(ApplicationReadyEvent event) {
        System.out.println("orders ready");
    }
}
