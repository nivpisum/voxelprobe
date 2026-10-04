package com.debugbridge.forge1201;

import com.debugbridge.core.BridgeConfig;
import java.util.function.IntSupplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;

/** Local client commands: permission changes must be entered by the player. */
public final class VoxelProbeClientCommands {
    private VoxelProbeClientCommands() {}

    public static void register(RegisterClientCommandsEvent event, BridgeConfig config,
            IntSupplier port, Runnable permissionsChanged) {
        var root = Commands.literal("voxelprobe")
                .executes(ctx -> status(ctx.getSource(), config, port))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource(), config, port)))
                .then(Commands.literal("pair").executes(ctx -> message(ctx.getSource(), "voxelprobe.command.pair")))
                .then(Commands.literal("help").executes(ctx -> message(ctx.getSource(), "voxelprobe.command.help")));
        var permissions = Commands.literal("permissions");
        for (String mode : new String[] {"read", "edit", "script"}) {
            permissions.then(Commands.literal(mode).executes(ctx -> {
                if (!config.developerModeAccepted) {
                    ctx.getSource().sendFailure(Component.translatable("voxelprobe.command.disabled"));
                    return 0;
                }
                config.worldWriteEnabled = !mode.equals("read");
                config.runCommandEnabled = !mode.equals("read");
                config.scriptEnabled = mode.equals("script");
                if (mode.equals("read")) config.sessionControlEnabled = false;
                config.save();
                permissionsChanged.run();
                status(ctx.getSource(), config, port);
                return mode.equals("script") ? message(ctx.getSource(), "voxelprobe.command.script_trust") : 1;
            }));
        }
        event.getDispatcher().register(root.then(permissions));
    }

    private static int status(CommandSourceStack source, BridgeConfig config, IntSupplier port) {
        int actualPort = port.getAsInt();
        Component state = Component.translatable(config.developerModeAccepted && actualPort > 0
                ? "voxelprobe.state.enabled" : "voxelprobe.state.disabled");
        Component mode = Component.translatable("voxelprobe.mode."
                + (config.scriptEnabled ? "script" : config.worldWriteEnabled ? "edit" : "read"));
        Component address = actualPort > 0 ? Component.literal("127.0.0.1:" + actualPort)
                : Component.translatable("voxelprobe.state.not_listening");
        source.sendSuccess(() -> Component.translatable("voxelprobe.command.status", state, mode, address), false);
        return 1;
    }

    private static int message(CommandSourceStack source, String key) {
        source.sendSuccess(() -> Component.translatable(key), false);
        return 1;
    }
}
