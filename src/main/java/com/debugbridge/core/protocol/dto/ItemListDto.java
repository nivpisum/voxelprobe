package com.debugbridge.core.protocol.dto;

import java.util.List;

public final class ItemListDto {
    public List<String> items;
    public int count;
    public int total;

    public ItemListDto(List<String> items, int total) {
        this.items = items;
        this.count = items.size();
        this.total = total;
    }
}
