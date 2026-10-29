package com.example.shop.catalog.app;

import java.util.List;

import com.example.shop.catalog.domain.Product;
import com.example.shop.catalog.domain.ProductRepository;
import com.example.shop.common.catalog.ProductDto;
import com.example.shop.common.stereotype.UseCase;

@UseCase
public class ProductService {

    private final ProductRepository repository;
    private final StockReservations reservations;

    public ProductService(ProductRepository repository, StockReservations reservations) {
        this.repository = repository;
        this.reservations = reservations;
    }

    public List<ProductDto> list(String category) {
        List<Product> found = category == null ? repository.findAll() : repository.findByCategoryName(category);
        return found.stream().map(ProductService::toDto).toList();
    }

    public ProductDto get(long id) {
        return toDto(repository.findById(id).orElseThrow());
    }

    public void reserve(long id, int quantity) {
        reservations.reserve(id, quantity);
    }

    private static ProductDto toDto(Product p) {
        return new ProductDto(p.getId(), p.getName(), p.getPriceCents(), p.getStock());
    }
}
