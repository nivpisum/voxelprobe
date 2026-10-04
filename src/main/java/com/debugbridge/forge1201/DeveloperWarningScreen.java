package com.debugbridge.forge1201;

import com.debugbridge.core.BridgeConfig;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class DeveloperWarningScreen extends Screen {

    private final BridgeConfig config;
    private final Consumer<Boolean> onComplete;
    private MultiLineLabel description = MultiLineLabel.EMPTY;
    private MultiLineLabel permissions = MultiLineLabel.EMPTY;
    private int contentY;

    public DeveloperWarningScreen(BridgeConfig config, Consumer<Boolean> onComplete) {
        super(Component.translatable("voxelprobe.welcome.title"));
        this.config = config;
        this.onComplete = onComplete;
    }

    @Override
    protected void init() {
        int textWidth = Math.min(420, this.width - 40);
        description = MultiLineLabel.create(this.font, Component.translatable("voxelprobe.welcome.description"), textWidth);
        permissions = MultiLineLabel.create(this.font, Component.translatable("voxelprobe.welcome.permissions"), textWidth);
        int textHeight = (description.getLineCount() + permissions.getLineCount()) * 12;
        contentY = Math.max(16, (this.height - textHeight - 90) / 2);
        int buttonY = contentY + textHeight + 58;
        int buttonWidth = Math.min(150, (this.width - 50) / 2);
        int left = (this.width - buttonWidth * 2 - 10) / 2;
        this.addRenderableWidget(Button.builder(Component.translatable("voxelprobe.welcome.start"), button -> {
            config.worldWriteEnabled = false;
            config.scriptEnabled = false;
            config.runCommandEnabled = false;
            config.sessionControlEnabled = false;
            config.developerModeAccepted = true;
            config.save();
            onComplete.accept(true);
        }).bounds(left, buttonY, buttonWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("voxelprobe.welcome.disabled"),
                button -> onComplete.accept(false)).bounds(left + buttonWidth + 10, buttonY, buttonWidth, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xE0182028);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, contentY, 0xFF88DDCC);
        int y = description.renderCentered(graphics, this.width / 2, contentY + 26, 12, 0xFFFFFFFF);
        permissions.renderCentered(graphics, this.width / 2, y + 10, 12, 0xFFCCCCCC);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
