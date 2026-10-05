package com.debugbridge.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/** Per-instance settings. Inspection is the default; editing and JVM scripts are opt-in. */
public class BridgeConfig {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public int port = 9876;
    public long timeoutMs = 5000;
    public int maxResults = 100;
    public long scriptMaxExecutionTimeMs = 5000;
    public volatile boolean developerModeAccepted = false;
    public volatile boolean worldWriteEnabled = false;
    public volatile boolean scriptEnabled = false;
    public volatile boolean runCommandEnabled = false;
    public volatile boolean sessionControlEnabled = false;
    public boolean webUiEnabled = false;
    public String token = newToken();
    private transient Path configFile;
    private transient JsonObject original = new JsonObject();

    private static String newToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static BridgeConfig load(Path configDir) {
        BridgeConfig c = new BridgeConfig();
        c.configFile = configDir.resolve("voxel_probe.json");
        try {
            // Read the legacy file only when the canonical file is absent. Keep
            // the old file intact so existing settings and tokens remain recoverable.
            Path source = Files.exists(c.configFile) ? c.configFile : configDir.resolve("debugbridge.json");
            if (Files.exists(source)) {
                JsonObject j = JSON.fromJson(Files.readString(source), JsonObject.class);
                if (j == null) throw new IllegalArgumentException();
                c.original = j.deepCopy();
                if (j.has("port")) c.port = j.get("port").getAsInt();
                if (j.has("timeout_ms")) c.timeoutMs = j.get("timeout_ms").getAsLong();
                if (j.has("max_results")) c.maxResults = j.get("max_results").getAsInt();
                if (j.has("developer_mode_accepted")) c.developerModeAccepted = j.get("developer_mode_accepted").getAsBoolean();
                if (j.has("world_write_enabled")) c.worldWriteEnabled = j.get("world_write_enabled").getAsBoolean();
                if (j.has("script_enabled")) c.scriptEnabled = j.get("script_enabled").getAsBoolean();
                if (j.has("run_command_enabled")) c.runCommandEnabled = j.get("run_command_enabled").getAsBoolean();
                if (j.has("session_control_enabled")) c.sessionControlEnabled = j.get("session_control_enabled").getAsBoolean();
                if (j.has("token")) c.token = j.get("token").getAsString();
                JsonObject scripts = j.has("script") ? j.getAsJsonObject("script") : j.getAsJsonObject("lua");
                if (scripts != null && scripts.has("max_execution_time_ms")) c.scriptMaxExecutionTimeMs = scripts.get("max_execution_time_ms").getAsLong();
            }
            if (c.port < 1 || c.port > 65535 || c.timeoutMs < 1 || c.timeoutMs > 30000 ||
                    c.maxResults < 1 || c.maxResults > 10000 || c.scriptMaxExecutionTimeMs < 1 || c.scriptMaxExecutionTimeMs > 30000 ||
                    !c.token.matches("[A-Za-z0-9_-]{32,256}")) throw new IllegalArgumentException();
            c.save();
            return c;
        } catch (IOException | RuntimeException e) {
            // Do not echo malformed JSON: it may contain the private token.
            throw new IllegalStateException("Invalid or unreadable VoxelProbe configuration; the bridge remains disabled.");
        }
    }

    public synchronized void save() {
        if (configFile == null) return;
        JsonObject j = original.deepCopy();
        j.addProperty("port", port);
        j.addProperty("timeout_ms", timeoutMs);
        j.addProperty("max_results", maxResults);
        j.addProperty("developer_mode_accepted", developerModeAccepted);
        j.addProperty("world_write_enabled", worldWriteEnabled);
        j.addProperty("script_enabled", scriptEnabled);
        j.addProperty("run_command_enabled", runCommandEnabled);
        j.addProperty("session_control_enabled", sessionControlEnabled);
        j.addProperty("web_ui_enabled", false);
        j.addProperty("token", token);
        JsonObject scripts = new JsonObject();
        scripts.addProperty("max_execution_time_ms", scriptMaxExecutionTimeMs);
        j.add("script", scripts);
        try {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, JSON.toJson(j));
            ownerOnly(configFile);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save VoxelProbe configuration.");
        }
    }

    public void writeConnection(Path gameDir, int actualPort, String instanceId) {
        JsonObject j = new JsonObject();
        j.addProperty("protocol_version", 2);
        j.addProperty("port", actualPort);
        j.addProperty("token", token);
        j.addProperty("instance_id", instanceId);
        j.addProperty("game_dir", gameDir.toAbsolutePath().normalize().toString());
        Path file = gameDir.resolve("config/voxel_probe_connection.json");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.toJson(j));
            ownerOnly(file);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the private VoxelProbe connection descriptor.");
        }
    }

    public boolean authorizes(String header) {
        if (header == null || !header.startsWith("Bearer ")) return false;
        return MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                header.substring(7).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void ownerOnly(Path file) throws IOException {
        try { Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
        catch (UnsupportedOperationException ignored) { /* Windows inherits the instance directory's ACL. */ }
    }
}
