package com.example.shop.orders.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

@Entity
public class OrderLine {

    @Id
    @GeneratedValue
    private Long id;

    @ManyToOne
    private Order order;

    private long productId;
    private int quantity;
    private long priceCents;

    protected OrderLine() {
    }

    OrderLine(Order order, long productId, int quantity, long priceCents) {
        this.order = order;
        this.productId = productId;
        this.quantity = quantity;
        this.priceCents = priceCents;
    }

    public long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }
}
