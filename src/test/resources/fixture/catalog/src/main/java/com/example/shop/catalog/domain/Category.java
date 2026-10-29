package com.example.shop.catalog.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "categories")
public class Category {

    @Id
    private Long id;

    private String name;

    public String getName() {
        return name;
    }
}
