package com.example.shop.catalog.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.shop.catalog.app.ProductService;
import com.example.shop.common.catalog.ProductDto;

@RestController
@RequestMapping("/products")
public class ProductController {

    private final ProductService products;

    public ProductController(ProductService products) {
        this.products = products;
    }

    @GetMapping
    public List<ProductDto> list(@RequestParam(name = "category", required = false) String category) {
        return products.list(category);
    }

    @GetMapping("/{id}")
    public ProductDto get(@PathVariable("id") long id) {
        return products.get(id);
    }

    @PostMapping("/{id}/reservations")
    public void reserve(@PathVariable("id") long id, @RequestParam("quantity") int quantity) {
        products.reserve(id, quantity);
    }
}
