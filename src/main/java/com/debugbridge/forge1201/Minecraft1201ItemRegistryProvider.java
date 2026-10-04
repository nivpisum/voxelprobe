package com.debugbridge.forge1201;

import com.debugbridge.core.registry.ItemRegistryProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

public class Minecraft1201ItemRegistryProvider implements ItemRegistryProvider {

    @Override
    public ListResult listItems(String filter, int limit) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<ListResult> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                List<String> items = new ArrayList<>();
                int total = 0;
                for (ResourceLocation key : BuiltInRegistries.ITEM.keySet()) {
                    String id = key.toString();
                    if (filter == null || id.contains(filter)) {
                        total++;
                        if (items.size() < limit) {
                            items.add(id);
                        }
                    }
                }
                future.complete(new ListResult(items, total));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future.get(10, TimeUnit.SECONDS);
    }
}
