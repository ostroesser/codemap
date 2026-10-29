package com.example.shop.catalog.app;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.example.shop.catalog.domain.Product;
import com.example.shop.catalog.domain.ProductRepository;

@Component
@Primary
public class JpaStockReservations implements StockReservations {

    private final ProductRepository repository;

    public JpaStockReservations(ProductRepository repository) {
        this.repository = repository;
    }

    @Override
    public void reserve(long productId, int quantity) {
        Product product = repository.findById(productId).orElseThrow();
        product.decreaseStock(quantity);
        repository.save(product);
    }
}
