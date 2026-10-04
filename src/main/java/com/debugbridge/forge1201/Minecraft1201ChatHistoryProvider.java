package com.debugbridge.forge1201;

import com.debugbridge.core.chat.ChatHistoryProvider;
import com.debugbridge.core.mapping.MappingResolver;
import com.debugbridge.core.protocol.dto.ChatMessageDto;
import com.google.gson.JsonElement;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;

public class Minecraft1201ChatHistoryProvider implements ChatHistoryProvider {

    private static volatile Field allMessagesField;

    private static Field allMessagesField(MappingResolver resolver) throws NoSuchFieldException {
        Field f = allMessagesField;
        if (f != null) return f;
        synchronized (Minecraft1201ChatHistoryProvider.class) {
            f = allMessagesField;
            if (f != null) return f;
            String runtime = resolver.resolveField("net.minecraft.client.gui.components.ChatComponent", "allMessages");
            f = ChatComponent.class.getDeclaredField(runtime);
            f.setAccessible(true);
            allMessagesField = f;
            return f;
        }
    }

    @Override
    public List<ChatMessageDto> getRecentMessages(int limit, MappingResolver resolver, boolean includeJson)
            throws Exception {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<List<ChatMessageDto>> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                if (mc.gui == null) {
                    future.complete(Collections.emptyList());
                    return;
                }
                ChatComponent chat = mc.gui.getChat();
                if (chat == null) {
                    future.complete(Collections.emptyList());
                    return;
                }

                @SuppressWarnings("unchecked")
                List<GuiMessage> messages =
                        (List<GuiMessage>) allMessagesField(resolver).get(chat);
                if (messages == null) {
                    future.complete(Collections.emptyList());
                    return;
                }

                int n = Math.min(limit, messages.size());
                List<ChatMessageDto> out = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    GuiMessage msg = messages.get(i);
                    ChatMessageDto dto = new ChatMessageDto();
                    dto.plain = msg.content().getString();
                    dto.addedTime = msg.addedTime();
                    if (includeJson) {
                        try {
                            Class<?> ser = Class.forName("net.minecraft.network.chat.Component$Serializer");
                            dto.json = (JsonElement)
                                    ser.getMethod("toJsonTree", net.minecraft.network.chat.Component.class)
                                            .invoke(null, msg.content());
                        } catch (Exception ignore) {
                        }
                    }
                    out.add(dto);
                }
                future.complete(out);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future.get(5, TimeUnit.SECONDS);
    }
}
