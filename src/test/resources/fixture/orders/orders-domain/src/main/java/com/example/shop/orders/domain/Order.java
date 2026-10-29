package com.example.shop.orders.domain;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

@Entity
@Table(name = "purchase_orders")
public class Order {

    @Id
    @GeneratedValue
    private Long id;

    private String status = "NEW";

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    private List<OrderLine> lines = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    public void markPlaced() {
        this.status = "PLACED";
    }

    public List<OrderLine> getLines() {
        return lines;
    }

    public void addLine(long productId, int quantity, long priceCents) {
        lines.add(new OrderLine(this, productId, quantity, priceCents));
    }
}
