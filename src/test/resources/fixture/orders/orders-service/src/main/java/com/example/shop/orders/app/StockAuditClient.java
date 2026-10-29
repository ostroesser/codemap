package com.example.shop.orders.app;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Imperative HTTP client building URLs by concatenation. */
@Component
public class StockAuditClient {

    private final RestClient rest = RestClient.create();
    private String baseUrl = "http://catalog:8080";

    public void audit(long productId, int quantity) {
        rest.post()
                .uri(baseUrl + "/products/" + productId + "/reservations?quantity=" + quantity)
                .retrieve()
                .toBodilessEntity();
    }

    public void listAll() {
        rest.get().uri(baseUrl + "/products").retrieve().toBodilessEntity();
    }
}
