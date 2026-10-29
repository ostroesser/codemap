package com.example.shop.orders;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.example.shop.orders", "com.example.shop.common"})
@EnableScheduling
public class OrdersApplication {

    public static void main(String[] args) {
        // SpringApplication.run(OrdersApplication.class, args) in a real app
    }
}
