package com.debugbridge.forge1201;

import com.debugbridge.core.BridgeConfig;
import com.debugbridge.core.mapping.MappingResolver;
import com.debugbridge.core.protocol.BridgeRequest;
import com.debugbridge.core.protocol.BridgeResponse;
import com.debugbridge.core.refs.ObjectRefStore;
import com.debugbridge.core.script.ScriptRuntime;
import com.debugbridge.core.script.ThreadDispatcher;
import com.debugbridge.core.server.ResultSerializer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.phys.AABB;

/**
 * Bounded access to the authoritative integrated server. Call apply from a bridge
 * worker, and onServerTick exactly once at the END of each Forge server tick.
 * Reads and edits run on the server thread; a remote client's cached level is
 * never substituted for a server. Batch validation is not a rollback transaction.
 */
public final class ForgeWorldAccess {
    private static final int MAX_BLOCKS = 65_536;
    private static final int MAX_OUTPUT_CHARS = 4 * 1024 * 1024;
    private static final int MAX_NBT_CHARS = 65_536;
    private static final int NBT_PREVIEW_CHARS = 32_768;
    private static final int MAX_OBSERVATIONS = 8;
    private static final int MAX_PREVIEW_SAMPLES = 64;
    private static final long SERVER_TIMEOUT_MS = 30_000;
    private final MappingResolver resolver;
    private final BridgeConfig config;
    private final String worldId = UUID.randomUUID().toString();
    private volatile String worldName;
    // Observations are touched only on the owning server thread.
    private MinecraftServer observationServer;
    private final Map<String, Observation> observations = new LinkedHashMap<>();
    private final Object scriptLock = new Object();
    private MinecraftServer scriptServer;
    private ScriptRuntime scriptRuntime;
    private ResultSerializer scriptSerializer;

    public ForgeWorldAccess(MappingResolver resolver) {
        this(resolver, new BridgeConfig());
    }

    public ForgeWorldAccess(MappingResolver resolver, BridgeConfig config) {
        this.resolver = resolver;
        this.config = java.util.Objects.requireNonNull(config, "config");
    }

    /** Identity of this world-access session; the mod replaces it when the server stops. */
    public String getWorldId() { return worldId; }

    public BridgeResponse apply(BridgeRequest req) {
        String id = req == null ? null : req.id;
        try {
            if (req == null || req.payload == null) throw invalid("A world payload is required");
            MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
            if (server == null) {
                throw invalid("No integrated server is available. Open a local world; remote client state is not authoritative server access.");
            }
            JsonObject payload = req.payload;
            String op = string(payload, "op");
            if (op.equals("script")) return script(req, server);
            return onServer(server, () -> dispatch(req, server, op, payload), SERVER_TIMEOUT_MS);
        } catch (Exception e) {
            return BridgeResponse.error(id, describe(e));
        }
    }

    private BridgeResponse dispatch(BridgeRequest req, MinecraftServer server, String op, JsonObject p)
            throws Exception {
        requireCurrentServer(server);
        worldName = server.getWorldData().getLevelName();
        if (op.equals("batch") || op.equals("command") || op.equals("save") || op.startsWith("structure_")) {
            requireWorldWrite(p);
        }
        resetObservations(server);
        JsonObject result;
        switch (op) {
            case "status" -> result = status(server);
            case "block" -> result = block(level(server, p), position(p));
            case "region" -> result = region(level(server, p), p);
            case "entities" -> result = entities(level(server, p), p);
            case "batch" -> { return batch(req.id, server, level(server, p), p, false); }
            case "preview" -> { return batch(req.id, server, level(server, p), p, true); }
            case "command" -> result = command(server, p);
            case "save" -> {
                result = authority();
                result.addProperty("saved", server.saveEverything(false, true, false));
                result.addProperty("flush", true);
            }
            case "structure_save" -> result = structureSave(server, level(server, p), p);
            case "structure_load" -> result = structureLoad(server, level(server, p), p);
            case "observe_start" -> result = observeStart(server, level(server, p), p);
            case "observe_result" -> result = observeResult(p);
            case "observe_stop" -> result = observeStop(p);
            default -> throw invalid("Unknown world op: " + op);
        }
        return BridgeResponse.success(req.id, result, null);
    }

    private JsonObject status(MinecraftServer server) {
        JsonObject out = authority();
        out.addProperty("server_tick", server.getTickCount());
        // This is Minecraft's own measured rolling average, not an inferred TPS.
        out.addProperty("average_tick_ms", server.getAverageTickTime());
        out.addProperty("tick_metric_source", "MinecraftServer.getAverageTickTime");
        out.addProperty("server_version", server.getServerVersion());
        out.addProperty("world_write_enabled", config.worldWriteEnabled);
        out.addProperty("script_enabled", config.scriptEnabled);
        JsonArray dimensions = new JsonArray();
        for (ServerLevel level : server.getAllLevels()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", level.dimension().location().toString());
            row.addProperty("game_time", level.getGameTime());
            row.addProperty("day_time", level.getDayTime());
            row.addProperty("min_y", level.getMinBuildHeight());
            row.addProperty("max_y_exclusive", level.getMaxBuildHeight());
            dimensions.add(row);
        }
        out.add("dimensions", dimensions);
        JsonArray players = new JsonArray();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            JsonObject row = new JsonObject();
            row.addProperty("uuid", player.getUUID().toString());
            row.addProperty("name", player.getGameProfile().getName());
            row.addProperty("dimension", player.serverLevel().dimension().location().toString());
            row.add("position", vector(player.getX(), player.getY(), player.getZ()));
            row.addProperty("game_mode", player.gameMode.getGameModeForPlayer().getName());
            players.add(row);
        }
        out.add("players", players);
        return out;
    }

    private JsonObject block(ServerLevel level, BlockPos pos) {
        validatePosition(level, pos);
        JsonObject out = authority();
        out.addProperty("dimension", level.dimension().location().toString());
        out.add("block", blockInfo(level, pos));
        return out;
    }

    private JsonObject blockInfo(ServerLevel level, BlockPos pos) {
        JsonObject out = new JsonObject();
        out.add("pos", positionJson(pos));
        boolean loaded = level.hasChunkAt(pos);
        out.addProperty("loaded", loaded);
        if (!loaded) return out;
        out.addProperty("state", stateString(level.getBlockState(pos)));
        out.addProperty("block_light", level.getBrightness(LightLayer.BLOCK, pos));
        out.addProperty("sky_light", level.getBrightness(LightLayer.SKY, pos));
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) out.add("block_entity_snbt", JsonNull.INSTANCE);
        else addNbt(out, "block_entity_snbt", be.saveWithFullMetadata());
        return out;
    }

    private JsonObject region(ServerLevel level, JsonObject p) {
        Bounds bounds = bounds(level, p, MAX_BLOCKS);
        int offset = integer(p, "offset", 0, 0, (int) bounds.volume());
        int limit = integer(p, "limit", 4096, 1, MAX_BLOCKS);
        boolean includeAir = bool(p, "include_air", true);
        JsonArray rows = new JsonArray();
        int chars = 0;
        int next = offset;
        while (next < bounds.volume() && rows.size() < limit) {
            BlockPos pos = bounds.at(next);
            if (!includeAir && level.hasChunkAt(pos) && level.getBlockState(pos).isAir()) {
                next++;
                continue;
            }
            JsonObject row = blockInfo(level, pos);
            int size = row.toString().length() + 1;
            if (chars + size > MAX_OUTPUT_CHARS - 4096) break;
            rows.add(row);
            chars += size;
            next++;
        }
        JsonObject out = authority();
        out.addProperty("dimension", level.dimension().location().toString());
        out.add("from", positionJson(bounds.min));
        out.add("to", positionJson(bounds.max));
        out.addProperty("volume", bounds.volume());
        out.addProperty("offset", offset);
        out.addProperty("scanned", next - offset);
        out.addProperty("returned", rows.size());
        out.addProperty("complete", next == bounds.volume());
        if (next < bounds.volume()) out.addProperty("next_offset", next);
        out.addProperty("order", "x_then_y_then_z");
        out.add("blocks", rows);
        return out;
    }

    private JsonObject entities(ServerLevel level, JsonObject p) {
        int limit = integer(p, "limit", 100, 1, 1024);
        AABB box;
        if (p.has("center")) {
            JsonArray center = array(p, "center");
            if (center.size() != 3) throw invalid("center must be [x,y,z]");
            double x = finite(center.get(0)), y = finite(center.get(1)), z = finite(center.get(2));
            double radius = p.has("radius") ? finite(p.get("radius")) : 32;
            if (radius <= 0 || radius > 128) throw invalid("radius must be > 0 and <= 128");
            if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) throw invalid("center is outside world coordinates");
            box = new AABB(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
        } else {
            Bounds b = bounds(level, p, 256L * 256 * 256);
            if (b.dx() > 256 || b.dy() > 256 || b.dz() > 256) throw invalid("Entity bounds may span at most 256 blocks per axis");
            box = new AABB(b.min.getX(), b.min.getY(), b.min.getZ(),
                    b.max.getX() + 1.0, b.max.getY() + 1.0, b.max.getZ() + 1.0);
        }
        JsonArray rows = new JsonArray();
        int chars = 0;
        List<Entity> found = level.getEntities((Entity) null, box, entity -> true);
        for (Entity entity : found) {
            if (rows.size() >= limit) break;
            JsonObject row = new JsonObject();
            row.addProperty("uuid", entity.getUUID().toString());
            row.addProperty("id", entity.getId());
            row.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            row.add("position", vector(entity.getX(), entity.getY(), entity.getZ()));
            addNbt(row, "snbt", entity.saveWithoutId(new CompoundTag()));
            int size = row.toString().length() + 1;
            if (chars + size > MAX_OUTPUT_CHARS - 4096) break;
            rows.add(row);
            chars += size;
        }
        JsonObject out = authority();
        out.addProperty("dimension", level.dimension().location().toString());
        out.addProperty("matching_loaded_entities", found.size());
        out.addProperty("returned", rows.size());
        out.addProperty("truncated", rows.size() < found.size());
        out.addProperty("scope", "loaded_entities_intersecting_box");
        out.add("entities", rows);
        return out;
    }

    private BridgeResponse batch(String requestId, MinecraftServer server, ServerLevel level, JsonObject p,
            boolean preview) throws Exception {
        JsonArray blocks = array(p, "blocks");
        if (blocks.isEmpty() || blocks.size() > MAX_BLOCKS) throw invalid("blocks must contain 1..65536 entries");
        List<Edit> edits = new ArrayList<>(blocks.size());
        Set<BlockPos> unique = new HashSet<>();
        int nbtChars = 0;
        // No world writes before this entire pass has succeeded.
        for (int index = 0; index < blocks.size(); index++) {
            try {
                if (!blocks.get(index).isJsonArray()) throw invalid("entry must be [x,y,z,block,optional_snbt]");
                JsonArray row = blocks.get(index).getAsJsonArray();
                if (row.size() < 4 || row.size() > 5) throw invalid("entry must be [x,y,z,block,optional_snbt]");
                BlockPos pos = new BlockPos(exactInteger(row.get(0)), exactInteger(row.get(1)), exactInteger(row.get(2)));
                validatePosition(level, pos);
                requireLoaded(level, pos);
                if (!unique.add(pos)) throw invalid("Duplicate position: " + pos.toShortString());
                BlockState state = parseState(text(row.get(3), "block"));
                CompoundTag nbt = null;
                if (row.size() == 5 && !row.get(4).isJsonNull()) {
                    String snbt = text(row.get(4), "nbt");
                    nbtChars += snbt.length();
                    if (snbt.length() > MAX_NBT_CHARS || nbtChars > MAX_OUTPUT_CHARS) throw invalid("NBT input limit exceeded (64 KiB each / 4 MiB total characters)");
                    nbt = TagParser.parseTag(snbt);
                    if (!(state.getBlock() instanceof EntityBlock factory)) throw invalid("NBT requires a block entity");
                    BlockEntity probe = factory.newBlockEntity(pos, state);
                    if (probe == null) throw invalid("The selected state does not create a block entity");
                    String expectedId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(probe.getType()).toString();
                    if (nbt.contains("id") && (!nbt.contains("id", Tag.TAG_STRING) || !nbt.getString("id").equals(expectedId))) {
                        throw invalid("NBT id must match " + expectedId);
                    }
                    nbt.putString("id", expectedId);
                    nbt.putInt("x", pos.getX());
                    nbt.putInt("y", pos.getY());
                    nbt.putInt("z", pos.getZ());
                }
                edits.add(new Edit(pos, state, nbt));
            } catch (Exception e) {
                BridgeResponse response = BridgeResponse.error(requestId, "Batch validation failed at index " + index + ": " + describe(e));
                JsonObject result = batchResult(blocks.size(), 0, 0, false);
                result.addProperty("validation_failed_index", index);
                result.addProperty("writes_started", false);
                response.result = result;
                return response;
            }
        }
        Bounds editBounds = editBounds(edits);
        JsonObject backup = null;
        boolean backupEnabled = bool(p, "backup", true);
        try {
            if (backupEnabled) {
                if (editBounds.volume() > MAX_BLOCKS) throw invalid("Batch bounding box exceeds 65536 positions");
                requireLoaded(level, editBounds);
            }
            if (preview) {
                JsonObject result = preview(level, edits, editBounds);
                result.addProperty("backup_enabled", backupEnabled);
                result.addProperty("backup_saved", false);
                return BridgeResponse.success(requestId, result, null);
            }
            if (backupEnabled) {
                ResourceLocation name = resource("voxelprobe:recovery_" + UUID.randomUUID().toString().replace("-", ""));
                backup = saveStructure(server, level, name, editBounds, false, false);
                backup.add("from", positionJson(editBounds.min));
            }
        } catch (Exception e) {
            BridgeResponse response = BridgeResponse.error(requestId,
                    "Recovery preparation failed; no edits were applied. Use explicit backup:false only to proceed without recovery: " + describe(e));
            JsonObject result = batchResult(edits.size(), 0, 0, false);
            result.addProperty("writes_started", false);
            result.addProperty("backup_enabled", backupEnabled);
            result.addProperty("backup_saved", false);
            addBounds(result, editBounds);
            response.result = result;
            return response;
        }
        int changed = 0;
        for (int index = 0; index < edits.size(); index++) {
            Edit edit = edits.get(index);
            BlockState oldState = null;
            CompoundTag oldNbt = null;
            boolean writesAtPositionStarted = false;
            try {
                oldState = level.getBlockState(edit.pos);
                oldNbt = blockNbt(level, edit.pos);
                writesAtPositionStarted = true;
                if (!oldState.equals(edit.state) && !level.setBlock(edit.pos, edit.state, Block.UPDATE_ALL)) {
                    throw invalid("setBlock rejected the change");
                }
                if (edit.nbt != null) {
                    BlockEntity be = level.getBlockEntity(edit.pos);
                    if (be == null) throw invalid("No block entity exists after placement");
                    be.load(edit.nbt.copy());
                    be.setChanged();
                    BlockState actual = level.getBlockState(edit.pos);
                    level.sendBlockUpdated(edit.pos, actual, actual, Block.UPDATE_ALL);
                }
                if (hasChanged(level, edit.pos, oldState, oldNbt)) changed++;
            } catch (Exception e) {
                Boolean failedPositionChanged = false;
                if (writesAtPositionStarted) {
                    try { failedPositionChanged = hasChanged(level, edit.pos, oldState, oldNbt); }
                    catch (Exception snapshotFailure) { failedPositionChanged = null; }
                }
                if (Boolean.TRUE.equals(failedPositionChanged)) changed++;
                JsonObject result = batchResult(edits.size(), index + 1, changed, false);
                result.addProperty("failed_index", index);
                result.add("failed_position", positionJson(edit.pos));
                result.addProperty("failed_position_changed", failedPositionChanged);
                result.addProperty("changed_is_lower_bound", failedPositionChanged == null);
                result.addProperty("writes_started", true);
                addBackup(result, backupEnabled, backup);
                BridgeResponse response = BridgeResponse.error(requestId, "Batch stopped after partial application: " + describe(e));
                response.result = result;
                return response;
            }
        }
        JsonObject result = batchResult(edits.size(), edits.size(), changed, true);
        result.addProperty("writes_started", true);
        addBackup(result, backupEnabled, backup);
        return BridgeResponse.success(requestId, result, null);
    }

    private JsonObject preview(ServerLevel level, List<Edit> edits, Bounds bounds) {
        JsonObject out = authority();
        JsonArray samples = new JsonArray();
        int wouldChange = 0;
        for (Edit edit : edits) {
            BlockState before = level.getBlockState(edit.pos);
            boolean nbtChanged = edit.nbt != null && !edit.nbt.equals(blockNbt(level, edit.pos));
            if (before.equals(edit.state) && !nbtChanged) continue;
            wouldChange++;
            if (samples.size() < MAX_PREVIEW_SAMPLES) {
                JsonObject sample = new JsonObject();
                sample.add("pos", positionJson(edit.pos));
                sample.addProperty("before", stateString(before));
                sample.addProperty("after", stateString(edit.state));
                sample.addProperty("nbt_would_change", nbtChanged);
                samples.add(sample);
            }
        }
        out.addProperty("preview", true);
        out.addProperty("requested", edits.size());
        out.addProperty("would_change", wouldChange);
        out.add("changed", samples);
        out.addProperty("changed_samples_truncated", wouldChange > samples.size());
        out.addProperty("writes_started", false);
        out.addProperty("prediction_scope", "requested block-state and supplied NBT snapshot comparison; no neighbor updates, block-entity load or tick simulation");
        addBounds(out, bounds);
        return out;
    }

    private static Bounds editBounds(List<Edit> edits) {
        BlockPos min = edits.get(0).pos, max = min;
        for (Edit edit : edits) {
            min = new BlockPos(Math.min(min.getX(), edit.pos.getX()), Math.min(min.getY(), edit.pos.getY()), Math.min(min.getZ(), edit.pos.getZ()));
            max = new BlockPos(Math.max(max.getX(), edit.pos.getX()), Math.max(max.getY(), edit.pos.getY()), Math.max(max.getZ(), edit.pos.getZ()));
        }
        return new Bounds(min, max);
    }

    private static void addBounds(JsonObject out, Bounds bounds) {
        JsonObject value = new JsonObject();
        value.add("from", positionJson(bounds.min));
        value.add("to", positionJson(bounds.max));
        value.addProperty("volume", bounds.volume());
        out.add("bounds", value);
    }

    private static void addBackup(JsonObject out, boolean enabled, JsonObject backup) {
        out.addProperty("backup_enabled", enabled);
        out.addProperty("backup_saved", backup != null);
        if (backup != null) {
            out.add("backup_name", backup.get("name"));
            out.add("backup_from", backup.get("from"));
            out.add("backup_size", backup.get("size"));
            out.addProperty("backup_scope", "bounding-box blocks and block-entity NBT; entities and running ticks excluded; restore with structure_load");
        }
    }

    private JsonObject batchResult(int requested, int attempted, int changed, boolean complete) {
        JsonObject out = authority();
        out.addProperty("requested", requested);
        out.addProperty("attempted", attempted);
        out.addProperty("changed", changed);
        out.addProperty("complete", complete);
        out.addProperty("atomic", false);
        out.addProperty("changed_is_lower_bound", false);
        out.addProperty("changed_scope", "requested_positions_changed_immediately_after_each_edit; neighbor and later tick effects excluded");
        return out;
    }

    private JsonObject command(MinecraftServer server, JsonObject p) {
        String command = string(p, "command");
        if (command.isBlank() || command.length() > 32_768 || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            throw invalid("command must be one nonempty line, at most 32768 characters");
        }
        ServerPlayer player = selectedPlayer(server, p, false);
        CommandCapture capture = new CommandCapture();
        CommandSourceStack context = player == null ? server.createCommandSourceStack() : player.createCommandSourceStack();
        if (p.has("dimension")) context = context.withLevel(level(server, p));
        // An explicitly authorized local tool has console-level permission, including when
        // a real player is the actor required by WorldEdit. The actor is never fabricated.
        context = context.withSource(capture).withPermission(4).withCallback((ctx, success, value) -> {
            capture.callbacks++;
            if (success) capture.successes++; else capture.failures++;
            capture.callbackResult += value;
        });
        int result = server.getCommands().performPrefixedCommand(context, command);
        JsonObject out = authority();
        out.addProperty("command_result", result);
        out.addProperty("callback_count", capture.callbacks);
        out.addProperty("success_count", capture.successes);
        out.addProperty("failure_count", capture.failures);
        out.addProperty("failure_message_count", capture.failureMessages);
        if (capture.callbacks > 0 || capture.failureMessages > 0) {
            out.addProperty("command_success", capture.successes > 0 && capture.failures == 0 && capture.failureMessages == 0);
        } else out.add("command_success", JsonNull.INSTANCE);
        out.addProperty("callback_result_sum", capture.callbackResult);
        out.addProperty("permission_level", 4);
        if (player != null) out.addProperty("player", player.getUUID().toString());
        else out.addProperty("actor", "server_console");
        out.add("messages", capture.messages);
        out.addProperty("messages_truncated", capture.truncated);
        out.addProperty("message_classification", "failure uses vanilla red failure formatting; callback counts report execution success");
        return out;
    }

    private JsonObject structureSave(MinecraftServer server, ServerLevel level, JsonObject p) {
        Bounds b = bounds(level, p, MAX_BLOCKS);
        ResourceLocation name = structureName(p);
        if (name.getNamespace().equals("voxelprobe") && name.getPath().startsWith("recovery_")) {
            throw invalid("voxelprobe:recovery_* names are reserved for automatic recovery backups");
        }
        return saveStructure(server, level, name, b, bool(p, "include_entities", false), bool(p, "overwrite", false));
    }

    private JsonObject saveStructure(MinecraftServer server, ServerLevel level, ResourceLocation name,
            Bounds b, boolean includeEntities, boolean overwrite) {
        requireLoaded(level, b);
        StructureTemplateManager manager = server.getStructureManager();
        if (!overwrite && manager.get(name).isPresent()) throw invalid("Structure already exists; set overwrite:true to replace it");
        StructureTemplate template = manager.getOrCreate(name);
        template.fillFromWorld(level, b.min, new BlockPos(b.dx(), b.dy(), b.dz()), includeEntities, null);
        template.setAuthor("VoxelProbe");
        boolean saved = manager.save(name);
        if (!saved) throw invalid("Structure template could not be written: " + name);
        JsonObject out = authority();
        out.addProperty("name", name.toString());
        out.addProperty("saved", true);
        out.add("size", vector(b.dx(), b.dy(), b.dz()));
        return out;
    }

    private JsonObject structureLoad(MinecraftServer server, ServerLevel level, JsonObject p) {
        ResourceLocation name = structureName(p);
        StructureTemplate template = server.getStructureManager().get(name).orElseThrow(() -> invalid("Unknown structure: " + name));
        BlockPos pos = position(p);
        validatePosition(level, pos);
        long volume = (long) template.getSize().getX() * template.getSize().getY() * template.getSize().getZ();
        if (volume < 1 || volume > MAX_BLOCKS) throw invalid("Structure must contain 1..65536 bounding-box positions");
        Rotation rotation;
        Mirror mirror;
        try {
            rotation = Rotation.valueOf(optionalString(p, "rotation", "NONE"));
            mirror = Mirror.valueOf(optionalString(p, "mirror", "NONE"));
        } catch (IllegalArgumentException e) {
            throw invalid("Use vanilla Rotation and Mirror enum names");
        }
        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation).setMirror(mirror)
                .setIgnoreEntities(!bool(p, "include_entities", false));
        // Validate the complete transformed bounding box before vanilla places anything.
        var box = template.getBoundingBox(settings, pos);
        Bounds bounds = new Bounds(new BlockPos(box.minX(), box.minY(), box.minZ()),
                new BlockPos(box.maxX(), box.maxY(), box.maxZ()));
        validatePosition(level, bounds.min);
        validatePosition(level, bounds.max);
        requireLoaded(level, bounds);
        boolean placed = template.placeInWorld(level, pos, pos, settings, level.getRandom(), Block.UPDATE_ALL);
        JsonObject out = authority();
        out.addProperty("name", name.toString());
        out.addProperty("placed", placed);
        out.addProperty("atomic", false);
        out.addProperty("placement_result_source", "vanilla_structure_template; not an exact changed-block count");
        out.add("from", positionJson(bounds.min));
        out.add("to", positionJson(bounds.max));
        return out;
    }

    private JsonObject observeStart(MinecraftServer server, ServerLevel level, JsonObject p) {
        JsonArray input = array(p, "blocks");
        if (input.isEmpty() || input.size() > 256) throw invalid("Observation requires 1..256 block positions");
        List<BlockPos> positions = new ArrayList<>();
        for (JsonElement row : input) {
            BlockPos pos = position(row);
            validatePosition(level, pos);
            positions.add(pos);
        }
        int ticks = integer(p, "ticks", 100, 1, 1200);
        int interval = integer(p, "interval", 1, 1, ticks);
        long active = observations.values().stream().filter(o -> !o.done).count();
        if (active >= 4) throw invalid("At most four observations may run concurrently");
        while (observations.size() >= MAX_OBSERVATIONS) {
            Iterator<Observation> it = observations.values().iterator();
            boolean removed = false;
            while (it.hasNext()) if (it.next().done) { it.remove(); removed = true; break; }
            if (!removed) throw invalid("Observation storage is full");
        }
        Observation observation = new Observation(UUID.randomUUID().toString(), level, positions, ticks, interval, server.getTickCount());
        observations.put(observation.id, observation);
        return observationInfo(observation);
    }

    /** Must be invoked at Forge ServerTickEvent.Phase.END, not on the client tick. */
    public void onServerTick() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || !server.isSameThread()) return;
        resetObservations(server);
        for (Observation o : observations.values()) {
            if (o.done) continue;
            // Server tick count, rather than callback invocation count, prevents duplicate samples.
            int elapsed = server.getTickCount() - o.startedTick;
            if (elapsed <= o.elapsed) continue;
            o.elapsed = elapsed;
            if (elapsed <= o.ticks && (elapsed % o.interval == 0 || elapsed == o.ticks)) {
                try {
                    JsonObject sample = new JsonObject();
                    sample.addProperty("elapsed_ticks", elapsed);
                    sample.addProperty("server_tick", server.getTickCount());
                    sample.addProperty("game_time", o.level.getGameTime());
                    JsonArray blocks = new JsonArray();
                    int sampleChars = 0;
                    for (BlockPos pos : o.positions) {
                        JsonObject row = blockInfo(o.level, pos);
                        sampleChars += row.toString().length();
                        if (o.chars + sampleChars + 8192 > MAX_OUTPUT_CHARS) {
                            o.done = true;
                            o.reason = "output_limit";
                            break;
                        }
                        blocks.add(row);
                    }
                    if (!o.done) {
                        sample.add("blocks", blocks);
                        o.chars += sample.toString().length();
                        o.samples.add(sample);
                    }
                } catch (Exception e) {
                    o.done = true;
                    o.reason = "sample_error: " + describe(e);
                }
            }
            if (!o.done && elapsed >= o.ticks) {
                o.done = true;
                o.reason = "completed";
            }
        }
    }

    private JsonObject observeResult(JsonObject p) {
        String id = string(p, "observation_id");
        Observation o = observations.get(id);
        if (o == null) throw invalid("Unknown or expired observation_id: " + id);
        int offset = integer(p, "offset", 0, 0, o.samples.size());
        int limit = integer(p, "limit", 100, 1, 1200);
        JsonObject out = observationInfo(o);
        JsonArray rows = new JsonArray();
        int chars = 0;
        int next = offset;
        while (next < o.samples.size() && rows.size() < limit) {
            JsonObject sample = o.samples.get(next);
            int size = sample.toString().length();
            if (chars + size > MAX_OUTPUT_CHARS - 4096) break;
            rows.add(sample.deepCopy());
            chars += size;
            next++;
        }
        out.addProperty("offset", offset);
        out.addProperty("next_offset", next);
        out.addProperty("returned", rows.size());
        out.add("samples", rows);
        return out;
    }

    private JsonObject observeStop(JsonObject p) {
        String id = string(p, "observation_id");
        Observation o = observations.get(id);
        if (o == null) throw invalid("Unknown or expired observation_id: " + id);
        if (!o.done) {
            o.done = true;
            o.reason = "cancelled";
        }
        return observationInfo(o);
    }

    private JsonObject observationInfo(Observation o) {
        JsonObject out = authority();
        out.addProperty("observation_id", o.id);
        out.addProperty("dimension", o.level.dimension().location().toString());
        out.addProperty("block_count", o.positions.size());
        out.addProperty("ticks", o.ticks);
        out.addProperty("interval", o.interval);
        out.addProperty("elapsed_ticks", o.elapsed);
        out.addProperty("sample_count", o.samples.size());
        out.addProperty("done", o.done);
        if (o.reason != null) out.addProperty("reason", o.reason);
        out.addProperty("sampling", "server_tick_end; state and full block-entity NBT include stored inventory data; unloaded chunks are explicit");
        return out;
    }

    private void resetObservations(MinecraftServer server) {
        if (observationServer != server) {
            observations.clear();
            observationServer = server;
        }
    }

    private BridgeResponse script(BridgeRequest req, MinecraftServer server) throws Exception {
        requireScriptEnabled();
        if (server.isSameThread()) throw invalid("world script must be submitted from a bridge worker, not the server tick thread");
        String code = string(req.payload, "code");
        if (code.length() > 65_536) throw invalid("Script code is limited to 65536 characters");
        int timeout = integer(req.payload, req.payload.has("timeoutMs") ? "timeoutMs" : "timeout_ms", 10_000, 1, 30_000);
        synchronized (scriptLock) {
            if (scriptServer != server) {
                ObjectRefStore refs = new ObjectRefStore();
                scriptRuntime = new ScriptRuntime(resolver, new ThreadDispatcher() {
                    @Override
                    public <T> T executeOnGameThread(Callable<T> task, long timeoutMs) throws Exception {
                        return onServer(server, () -> {
                            requireScriptEnabled();
                            return task.call();
                        }, Math.min(SERVER_TIMEOUT_MS, Math.max(1, timeoutMs)));
                    }
                }, refs);
                scriptSerializer = new ResultSerializer(resolver, refs, scriptRuntime.getBridge());
                scriptServer = server;
            }
            onServer(server, () -> {
                requireScriptEnabled();
                requireWorldTarget(req.payload);
                worldName = server.getWorldData().getLevelName();
                ServerLevel level = level(server, req.payload);
                ServerPlayer player = selectedPlayer(server, req.payload, true);
                scriptRuntime.bindVariable("server", scriptRuntime.getBridge().wrap(server));
                scriptRuntime.bindVariable("world", scriptRuntime.getBridge().wrap(level));
                scriptRuntime.bindVariable("level", scriptRuntime.getBridge().wrap(level));
                scriptRuntime.bindVariable("player", scriptRuntime.getBridge().wrap(player));
                scriptRuntime.bindVariable("serverPlayer", scriptRuntime.getBridge().wrap(player));
                scriptRuntime.bindVariable("mc", null);
                return null;
            }, SERVER_TIMEOUT_MS);
            ScriptRuntime.ExecutionResult execution = scriptRuntime.execute(code, timeout);
            String output = bounded(execution.output, NBT_PREVIEW_CHARS);
            if (!execution.isSuccess()) {
                BridgeResponse response = BridgeResponse.error(req.id, execution.error);
                response.output = output;
                return response;
            }
            JsonElement value = onServer(server, () -> {
                requireScriptEnabled();
                requireWorldTarget(req.payload);
                return scriptSerializer.serialize(execution.returnValue);
            }, SERVER_TIMEOUT_MS);
            if (value.toString().length() > MAX_OUTPUT_CHARS) throw invalid("Script return value exceeds the 4 MiB character output limit; return a smaller summary");
            JsonObject result = authority();
            result.add("value", value);
            result.addProperty("runtime", "persistent_per_integrated_server; reflection dispatched to server thread");
            return BridgeResponse.success(req.id, result, output);
        }
    }

    private static <T> T onServer(MinecraftServer server, Callable<T> task, long timeout) throws Exception {
        if (server.isSameThread()) {
            requireCurrentServer(server);
            return task.call();
        }
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean cancelled = new AtomicBoolean();
        Object startGate = new Object();
        FutureTask<T> future = new FutureTask<>(() -> {
            synchronized (startGate) {
                if (cancelled.get()) throw invalid("Request was cancelled before execution");
                requireCurrentServer(server);
                started.set(true);
            }
            return task.call();
        });
        server.execute(future);
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Never interrupt Minecraft's server thread. Cancellation prevents a queued task
            // from starting, but cannot roll back or stop a task which already began.
            boolean didStart;
            synchronized (startGate) {
                cancelled.set(true);
                didStart = started.get();
                future.cancel(false);
            }
            throw new TimeoutException(didStart
                    ? "Server operation timed out after starting; it may still finish and edits are not rolled back. Inspect the world before retrying."
                    : "Server operation timed out before starting; the queued request was cancelled.");
        } catch (InterruptedException e) {
            synchronized (startGate) {
                cancelled.set(true);
                future.cancel(false);
            }
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    private static void requireCurrentServer(MinecraftServer server) {
        if (Minecraft.getInstance().getSingleplayerServer() != server || server.isStopped()) {
            throw invalid("TARGET_CHANGED: The integrated server changed or stopped; this request was not applied to another world");
        }
    }

    private void requireWorldTarget(JsonObject p) {
        JsonElement expected = p.get("expected_world_id");
        if (expected == null || !expected.isJsonPrimitive() || !expected.getAsJsonPrimitive().isString()
                || !worldId.equals(expected.getAsString())) {
            throw invalid("TARGET_CHANGED: expected_world_id must match the current world_id; refresh world status before retrying");
        }
    }

    private void requireWorldWrite(JsonObject p) {
        if (!config.worldWriteEnabled) throw invalid("WORLD_WRITE_DISABLED: Enable world_write_enabled to authorize world mutations");
        requireWorldTarget(p);
    }

    private void requireScriptEnabled() {
        if (!config.scriptEnabled) throw invalid("SCRIPT_DISABLED: Enable script_enabled to authorize unsandboxed scripts");
    }

    private static ServerLevel level(MinecraftServer server, JsonObject p) {
        if (!p.has("dimension")) {
            ServerPlayer player = selectedPlayer(server, p, true);
            return player != null ? player.serverLevel() : server.overworld();
        }
        ResourceLocation name = resource(string(p, "dimension"));
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, name));
        if (level == null) throw invalid("Dimension is not loaded: " + name);
        return level;
    }

    private static ServerPlayer selectedPlayer(MinecraftServer server, JsonObject p, boolean defaultSinglePlayer) {
        if (p.has("player")) {
            UUID uuid;
            try { uuid = UUID.fromString(string(p, "player")); }
            catch (IllegalArgumentException e) { throw invalid("player must be a server player UUID"); }
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) throw invalid("No connected server player has UUID " + uuid);
            return player;
        }
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        return defaultSinglePlayer && players.size() == 1 ? players.get(0) : null;
    }

    private static Bounds bounds(ServerLevel level, JsonObject p, long maximum) {
        BlockPos min = position(p.get(p.has("from") ? "from" : "min"));
        BlockPos max = position(p.get(p.has("to") ? "to" : "max"));
        validatePosition(level, min);
        validatePosition(level, max);
        if (min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ()) {
            throw invalid("from/min must be <= to/max on every axis; bounds are inclusive");
        }
        Bounds bounds = new Bounds(min, max);
        if (bounds.volume() > maximum) throw invalid("Bounds exceed the maximum volume of " + maximum);
        return bounds;
    }

    private static void validatePosition(ServerLevel level, BlockPos pos) {
        if (pos.getX() <= -30_000_000 || pos.getX() >= 30_000_000 || pos.getZ() <= -30_000_000 || pos.getZ() >= 30_000_000
                || level.isOutsideBuildHeight(pos)) throw invalid("Position is outside dimension build bounds: " + pos.toShortString());
        if (!level.getWorldBorder().isWithinBounds(pos)) throw invalid("Position is outside the world border: " + pos.toShortString());
    }

    private static void requireLoaded(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) throw invalid("Chunk is not loaded at " + pos.toShortString() + "; this operation does not generate or force-load chunks");
    }

    private static void requireLoaded(ServerLevel level, Bounds bounds) {
        for (int x = bounds.min.getX() >> 4; x <= bounds.max.getX() >> 4; x++) {
            for (int z = bounds.min.getZ() >> 4; z <= bounds.max.getZ() >> 4; z++) {
                requireLoaded(level, new BlockPos(x << 4, bounds.min.getY(), z << 4));
            }
        }
    }

    private static BlockState parseState(String input) {
        if (input.length() > 2048) throw invalid("Block state string is too long");
        int bracket = input.indexOf('[');
        String name = bracket < 0 ? input : input.substring(0, bracket);
        ResourceLocation id = resource(name);
        if (!BuiltInRegistries.BLOCK.containsKey(id)) throw invalid("Unknown block: " + id);
        Block block = BuiltInRegistries.BLOCK.get(id);
        BlockState state = block.defaultBlockState();
        if (bracket < 0) return state;
        if (!input.endsWith("]") || input.indexOf('[', bracket + 1) >= 0) throw invalid("Invalid block state: " + input);
        String body = input.substring(bracket + 1, input.length() - 1);
        if (body.isEmpty()) return state;
        Set<String> seen = new HashSet<>();
        for (String entry : body.split(",", -1)) {
            String[] pair = entry.split("=", -1);
            if (pair.length != 2 || !seen.add(pair[0])) throw invalid("Invalid or duplicate block property: " + entry);
            Property<?> property = block.getStateDefinition().getProperty(pair[0]);
            if (property == null) throw invalid("Unknown block property: " + pair[0]);
            state = propertyValue(state, property, pair[1]);
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState propertyValue(BlockState state, Property<T> property, String text) {
        return state.setValue(property, property.getValue(text).orElseThrow(() -> invalid("Invalid value for " + property.getName() + ": " + text)));
    }

    private static String stateString(BlockState state) {
        StringBuilder out = new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        if (!state.getValues().isEmpty()) {
            out.append('[');
            boolean first = true;
            for (Map.Entry<Property<?>, Comparable<?>> entry : state.getValues().entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(entry.getKey().getName()).append('=').append(propertyName(entry.getKey(), entry.getValue()));
            }
            out.append(']');
        }
        return out.toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String propertyName(Property property, Comparable value) { return property.getName(value); }

    private static CompoundTag blockNbt(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return be == null ? null : be.saveWithFullMetadata();
    }

    private static boolean hasChanged(ServerLevel level, BlockPos pos, BlockState before, CompoundTag beforeNbt) {
        return !before.equals(level.getBlockState(pos)) || !java.util.Objects.equals(beforeNbt, blockNbt(level, pos));
    }

    private static void addNbt(JsonObject out, String key, CompoundTag nbt) {
        String snbt = nbt.toString();
        if (snbt.length() <= NBT_PREVIEW_CHARS) out.addProperty(key, snbt);
        else {
            out.add(key, JsonNull.INSTANCE);
            out.addProperty(key + "_preview", snbt.substring(0, NBT_PREVIEW_CHARS));
            out.addProperty(key + "_truncated", true);
            out.addProperty(key + "_characters", snbt.length());
        }
    }

    private static ResourceLocation structureName(JsonObject p) {
        String value = string(p, "name");
        ResourceLocation name = resource(value.contains(":") ? value : "debugbridge:" + value);
        for (String segment : name.getPath().split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) throw invalid("Structure name must not contain empty or traversal path segments");
        }
        return name;
    }

    private static ResourceLocation resource(String value) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) throw invalid("Invalid resource location: " + value);
        return id;
    }

    private static BlockPos position(JsonObject p) {
        if (p.has("pos")) return position(p.get("pos"));
        return new BlockPos(exactInteger(p.get("x")), exactInteger(p.get("y")), exactInteger(p.get("z")));
    }

    private static BlockPos position(JsonElement value) {
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() != 3) throw invalid("Position must be [x,y,z]");
        JsonArray row = value.getAsJsonArray();
        return new BlockPos(exactInteger(row.get(0)), exactInteger(row.get(1)), exactInteger(row.get(2)));
    }

    private static int exactInteger(JsonElement value) {
        try {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid("Expected an integer number");
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException e) { throw invalid("Expected an exact 32-bit integer"); }
    }

    private static double finite(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid("Expected a finite number");
        double result = value.getAsDouble();
        if (!Double.isFinite(result)) throw invalid("Expected a finite number");
        return result;
    }

    private static int integer(JsonObject p, String key, int fallback, int min, int max) {
        int value = p.has(key) ? exactInteger(p.get(key)) : fallback;
        if (value < min || value > max) throw invalid(key + " must be between " + min + " and " + max);
        return value;
    }

    private static boolean bool(JsonObject p, String key, boolean fallback) {
        if (!p.has(key)) return fallback;
        JsonElement value = p.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw invalid(key + " must be boolean");
        return value.getAsBoolean();
    }

    private static JsonArray array(JsonObject p, String key) {
        if (!p.has(key) || !p.get(key).isJsonArray()) throw invalid(key + " must be an array");
        return p.getAsJsonArray(key);
    }

    private static String text(JsonElement value, String key) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid(key + " must be a string");
        return value.getAsString();
    }

    private static String string(JsonObject p, String key) { return text(p.get(key), key); }
    private static String optionalString(JsonObject p, String key, String fallback) { return p.has(key) ? string(p, key) : fallback; }
    private static String bounded(String text, int length) { return text == null || text.length() <= length ? text : text.substring(0, length) + "\n[truncated]"; }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }

    private static String describe(Throwable error) {
        while (error.getCause() != null && error.getCause() != error) error = error.getCause();
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }

    private static JsonArray positionJson(BlockPos pos) { return vector(pos.getX(), pos.getY(), pos.getZ()); }
    private static JsonArray vector(Number x, Number y, Number z) {
        JsonArray out = new JsonArray(); out.add(x); out.add(y); out.add(z); return out;
    }

    private JsonObject authority() {
        JsonObject out = new JsonObject();
        out.addProperty("authority", "integrated_server");
        out.addProperty("thread", "server");
        out.addProperty("world_id", worldId);
        out.addProperty("world_name", worldName);
        return out;
    }

    private record Edit(BlockPos pos, BlockState state, CompoundTag nbt) {}

    private record Bounds(BlockPos min, BlockPos max) {
        int dx() { return max.getX() - min.getX() + 1; }
        int dy() { return max.getY() - min.getY() + 1; }
        int dz() { return max.getZ() - min.getZ() + 1; }
        long volume() { return (long) dx() * dy() * dz(); }
        BlockPos at(int index) { return min.offset(index % dx(), index / dx() % dy(), index / (dx() * dy())); }
    }

    private static final class Observation {
        final String id;
        final ServerLevel level;
        final List<BlockPos> positions;
        final int ticks, interval, startedTick;
        final List<JsonObject> samples = new ArrayList<>();
        int elapsed, chars;
        boolean done;
        String reason;
        Observation(String id, ServerLevel level, List<BlockPos> positions, int ticks, int interval, int startedTick) {
            this.id = id; this.level = level; this.positions = positions;
            this.ticks = ticks; this.interval = interval; this.startedTick = startedTick;
        }
    }

    private static final class CommandCapture implements CommandSource {
        final JsonArray messages = new JsonArray();
        int callbacks, successes, failures, failureMessages, callbackResult, chars;
        boolean truncated;
        @Override public void sendSystemMessage(Component component) {
            String text = component.getString();
            if (messages.size() >= 256 || chars + text.length() > 65_536) { truncated = true; return; }
            chars += text.length();
            JsonObject row = new JsonObject();
            row.addProperty("text", text);
            boolean red = component.getStyle().getColor() != null && component.getStyle().getColor().getValue() == 0xFF5555;
            if (red) failureMessages++;
            row.addProperty("kind", red ? "failure" : "message");
            messages.add(row);
        }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
    }
}
