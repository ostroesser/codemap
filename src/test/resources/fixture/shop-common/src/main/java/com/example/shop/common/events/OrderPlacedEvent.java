package com.example.shop.common.events;

public record OrderPlacedEvent(long orderId, long productId, int quantity) {
}
