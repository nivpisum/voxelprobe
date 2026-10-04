package com.debugbridge.forge1201;

import com.debugbridge.core.protocol.dto.ItemStackDto;
import com.debugbridge.core.protocol.dto.ScreenInspectDto;
import com.debugbridge.core.protocol.dto.SlotDto;
import com.debugbridge.core.screen.ScreenInspectProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class Minecraft1201ScreenInspectProvider implements ScreenInspectProvider {

    @Override
    public ScreenInspectDto inspectCurrentScreen() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<ScreenInspectDto> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                ScreenInspectDto dto = new ScreenInspectDto();
                Screen screen = mc.screen;
                if (screen == null) {
                    dto.open = false;
                    future.complete(dto);
                    return;
                }
                dto.open = true;
                dto.type = screen.getClass().getName();
                dto.title = screen.getTitle().getString();

                if (screen instanceof AbstractContainerScreen<?> cs) {
                    AbstractContainerMenu menu = cs.getMenu();
                    dto.menuClass = menu.getClass().getName();
                    List<SlotDto> slots = new ArrayList<>(menu.slots.size());
                    for (int i = 0; i < menu.slots.size(); i++) {
                        Slot slot = menu.slots.get(i);
                        SlotDto s = new SlotDto();
                        s.idx = i;
                        s.container = slot.container.getClass().getName();
                        ItemStack stack = slot.getItem();
                        if (!stack.isEmpty()) {
                            ItemStackDto item = new ItemStackDto();
                            item.itemId = BuiltInRegistries.ITEM
                                    .getKey(stack.getItem())
                                    .toString();
                            item.count = stack.getCount();
                            if (stack.isDamageableItem()) {
                                item.damage = stack.getDamageValue();
                                item.maxDamage = stack.getMaxDamage();
                            }
                            if (stack.hasCustomHoverName()) {
                                item.name = stack.getHoverName().getString();
                            }
                            s.item = item;
                        }
                        slots.add(s);
                    }
                    dto.slots = slots;
                }
                future.complete(dto);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future.get(5, TimeUnit.SECONDS);
    }
}
