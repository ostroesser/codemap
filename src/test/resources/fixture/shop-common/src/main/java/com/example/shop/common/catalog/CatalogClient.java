package com.example.shop.common.catalog;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/** Declarative HTTP client for the catalog service. */
@HttpExchange("/products")
public interface CatalogClient {

    @GetExchange("/{id}")
    ProductDto get(@PathVariable("id") long id);

    @PostExchange("/{id}/reservations")
    void reserve(@PathVariable("id") long id, @RequestParam("quantity") int quantity);
}
