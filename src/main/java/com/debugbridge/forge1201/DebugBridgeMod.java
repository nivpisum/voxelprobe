package com.debugbridge.forge1201;

import com.debugbridge.core.block.NearbyBlocksProvider;
import com.debugbridge.core.chat.ChatHistoryProvider;
import com.debugbridge.core.entity.LookedAtEntityProvider;
import com.debugbridge.core.entity.NearbyEntitiesProvider;
import com.debugbridge.core.lifecycle.AbstractDebugBridgeMod;
import com.debugbridge.core.mapping.FabricNamespaceLookup;
import com.debugbridge.core.mapping.MappingCache;
import com.debugbridge.core.mapping.MappingDownloader;
import com.debugbridge.core.mapping.MappingResolver;
import com.debugbridge.core.mapping.ParsedMappings;
import com.debugbridge.core.mapping.ProGuardParser;
import com.debugbridge.core.protocol.dto.SnapshotDto;
import com.debugbridge.core.protocol.dto.SnapshotPlayerDto;
import com.debugbridge.core.protocol.dto.SnapshotTargetDto;
import com.debugbridge.core.protocol.dto.SnapshotVehicleDto;
import com.debugbridge.core.protocol.dto.SnapshotWorldDto;
import com.debugbridge.core.protocol.dto.Vec3Dto;
import com.debugbridge.core.recording.FrameCapturer;
import com.debugbridge.core.screen.ScreenInspectProvider;
import com.debugbridge.core.screenshot.ScreenshotProvider;
import com.debugbridge.core.session.SessionControlProvider;
import com.debugbridge.core.snapshot.GameStateProvider;
import com.debugbridge.core.text.TextLinks;
import com.debugbridge.core.texture.ItemTextureProvider;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;

@Mod("debugbridge")
public class DebugBridgeMod extends AbstractDebugBridgeMod {
    private static final String MC_VERSION = "1.20.1";
    private static DebugBridgeMod INSTANCE;
    private volatile ForgeWorldAccess worldAccess;

    public DebugBridgeMod() {
        INSTANCE = this;
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onClientSetup);
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        MinecraftForge.EVENT_BUS.addListener(this::onClientTickEvent);
        MinecraftForge.EVENT_BUS.addListener(this::onServerTickEvent);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStoppedEvent);
        MinecraftForge.EVENT_BUS.addListener(this::onRenderTickEvent);
        initialize();
        if (config != null) MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.client.event.RegisterClientCommandsEvent commands) ->
                VoxelProbeClientCommands.register(commands, config, () -> server == null ? -1 : server.getPort(), this::permissionsChanged));
    }

    private void onServerTickEvent(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && worldAccess != null) worldAccess.onServerTick();
    }

    private void onServerStoppedEvent(net.minecraftforge.event.server.ServerStoppedEvent event) {
        // Release the old world's observations, script bindings and object references.
        if (server != null) worldAccess = new ForgeWorldAccess(server.getResolver(), config);
    }

    private void onRenderTickEvent(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.END) handleRenderFrame();
    }

    private void onClientTickEvent(final TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            handleTick();
        }
    }

    public static void onClientTick(Minecraft mc) {
        if (INSTANCE != null) INSTANCE.handleTick();
    }

    public static void onRenderFrame(Minecraft mc) {
        if (INSTANCE != null) INSTANCE.handleRenderFrame();
    }

    public static void onClientClose(Minecraft mc) {
        if (INSTANCE != null) INSTANCE.handleClose();
    }

    @Override
    protected String mcVersion() {
        return MC_VERSION;
    }

    @Override
    protected Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    protected Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    protected FabricNamespaceLookup createNamespaceLookup() {
        return null;
    }

    @Override
    protected MappingResolver buildResolver() {
        // The transformed 1.20.1 member map is bundled: no private drive or online setup.
        return new ForgeSearchResolver(MC_VERSION, new ParsedMappings(
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of()));
    }

    @Override
    protected void submitToGameThread(Runnable task) {
        Minecraft.getInstance().execute(task);
    }

    @Override
    protected GameStateProvider createStateProvider() {
        return new Minecraft1201StateProvider();
    }

    @Override
    protected ScreenshotProvider createScreenshotProvider() {
        return new Minecraft1201ScreenshotProvider();
    }

    @Override
    protected void onServerStarted(int actualPort) {
        worldAccess = new ForgeWorldAccess(server.getResolver(), config);
        server.setWorldHandler(request -> worldAccess.apply(request));
        server.setWorldIdentity(() -> Minecraft.getInstance().getSingleplayerServer() == null ? null : worldAccess.getWorldId());
        config.writeConnection(gameDir(), actualPort, server.getInstanceId());
        permissionsChanged();
    }

    private void permissionsChanged() {
        if (server == null || config == null) return;
        server.setRunCommandEnabled(config.runCommandEnabled && config.worldWriteEnabled);
        server.setSessionControlEnabled(config.sessionControlEnabled);
    }

    @Override
    protected FrameCapturer createFrameCapturer() {
        return new Minecraft1201FrameCapturer();
    }

    @Override
    protected ItemTextureProvider createTextureProvider() {
        return new Minecraft1201ItemTextureProvider();
    }

    @Override
    protected NearbyEntitiesProvider createEntitiesProvider() {
        return new Minecraft1201NearbyEntitiesProvider();
    }

    @Override
    protected NearbyBlocksProvider createBlocksProvider() {
        return new Minecraft1201NearbyBlocksProvider();
    }

    @Override
    protected LookedAtEntityProvider createLookedAtEntityProvider() {
        return new Minecraft1201LookedAtEntityProvider();
    }

    @Override
    protected ChatHistoryProvider createChatHistoryProvider() {
        return new Minecraft1201ChatHistoryProvider();
    }

    @Override
    protected ScreenInspectProvider createScreenInspectProvider() {
        return new Minecraft1201ScreenInspectProvider();
    }

    @Override
    protected SessionControlProvider createSessionControlProvider() {
        return new SessionControlProvider() {
            public void quit() { Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop()); }
            public void disconnect() {
                Minecraft.getInstance().execute(() -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.level != null) mc.level.disconnect();
                    mc.clearLevel();
                    mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
                });
            }
            public void joinServer(String address, boolean acceptResourcePacks) {
                throw new UnsupportedOperationException("Join via the Minecraft client; this build targets local integrated worlds");
            }
        };
    }

    @Override
    protected boolean displayPlayerError(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        mc.player.displayClientMessage(playerMessage(message, 0xFF5555), false);
        return true;
    }

    @Override
    protected boolean displayPlayerInfo(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        mc.player.displayClientMessage(playerMessage(message, 0x55FF55), false);
        return true;
    }

    /**
     * "[DebugBridge] " + message in {@code color}, with any http(s) URLs
     * (notably the startup "Web UI: http://localhost:NNNN") rendered as
     * clickable, underlined links.
     */
    private static Component playerMessage(String message, int color) {
        MutableComponent root = Component.literal("[VoxelProbe] ");
        for (TextLinks.Segment seg : TextLinks.split(message)) {
            if (seg.isLink()) {
                // 1.19: legacy action+value ClickEvent (records arrived in 1.21.5).
                ClickEvent open = new ClickEvent(ClickEvent.Action.OPEN_URL, seg.text());
                root.append(Component.literal(seg.text())
                        .withStyle(
                                s -> s.withColor(0x55FFFF).withUnderlined(true).withClickEvent(open)));
            } else {
                root.append(Component.literal(seg.text()));
            }
        }
        return root.withStyle(s -> s.withColor(color));
    }

    @Override
    protected boolean canShowWarningScreen() {
        Minecraft mc = Minecraft.getInstance();
        return mc.screen == null && mc.getOverlay() == null;
    }

    @Override
    protected void showWarningScreen(Consumer<Boolean> onResult) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new DeveloperWarningScreen(config, accepted -> {
            mc.setScreen(null);
            onResult.accept(accepted);
        }));
    }

    private static class Minecraft1201StateProvider implements GameStateProvider {
        @Override
        public SnapshotDto captureSnapshot() {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            SnapshotDto snap = new SnapshotDto();
            if (player != null) {
                SnapshotPlayerDto p = new SnapshotPlayerDto();
                p.name = player.getName().getString();
                p.x = player.getX();
                p.y = player.getY();
                p.z = player.getZ();
                p.yaw = player.getYRot();
                p.pitch = player.getXRot();
                p.hotbarSlot = player.getInventory().selected;
                p.health = player.getHealth();
                p.maxHealth = player.getMaxHealth();
                p.food = player.getFoodData().getFoodLevel();
                p.saturation = player.getFoodData().getSaturationLevel();
                p.dimension = player.level().dimension().location().toString();
                p.biome = "";
                Vec3 vel = player.getDeltaMovement();
                p.velocity = new Vec3Dto(vel.x, vel.y, vel.z);
                Vec3 look = player.getLookAngle();
                p.look = new Vec3Dto(look.x, look.y, look.z);
                Entity vehicle = player.getVehicle();
                if (vehicle != null) {
                    SnapshotVehicleDto v = new SnapshotVehicleDto();
                    v.entityId = vehicle.getId();
                    v.type = vehicle.getClass().getName();
                    p.vehicle = v;
                }
                snap.player = p;
            }
            HitResult hit = mc.hitResult;
            if (hit != null && hit.getType() != HitResult.Type.MISS) {
                SnapshotTargetDto t = new SnapshotTargetDto();
                t.type = hit.getType().name().toLowerCase();
                if (hit instanceof BlockHitResult bhr) {
                    BlockPos pos = bhr.getBlockPos();
                    t.x = pos.getX();
                    t.y = pos.getY();
                    t.z = pos.getZ();
                    t.face = bhr.getDirection().name().toLowerCase();
                } else if (hit instanceof EntityHitResult ehr) {
                    t.entityId = ehr.getEntity().getId();
                    t.entityType = ehr.getEntity().getClass().getName();
                }
                snap.target = t;
            }
            if (mc.level != null) {
                SnapshotWorldDto w = new SnapshotWorldDto();
                w.dayTime = mc.level.getDayTime();
                w.isRaining = mc.level.isRaining();
                w.isThundering = mc.level.isThundering();
                snap.world = w;
            }
            snap.fps = mc.getFps();
            snap.version = MC_VERSION;
            return snap;
        }
    }
}
