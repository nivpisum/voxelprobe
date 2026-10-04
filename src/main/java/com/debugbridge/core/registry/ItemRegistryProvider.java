package com.debugbridge.core.registry;

import java.util.List;

public interface ItemRegistryProvider {

    ListResult listItems(String filter, int limit) throws Exception;

    record ListResult(List<String> items, int total) {}
}
