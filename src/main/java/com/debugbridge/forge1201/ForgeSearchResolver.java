package com.debugbridge.forge1201;

import com.debugbridge.core.mapping.MappingResolver;
import com.debugbridge.core.mapping.ParsedMappings;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Forge keeps Mojang class names, but production 1.20.1 uses SRG member names.
 * The bundled, version-specific member map joins Mojang and MCPConfig mappings;
 * ParsedMappings remains the source of search results and complete signatures.
 */
public class ForgeSearchResolver implements MappingResolver {
    private static final String MINECRAFT = "net.minecraft.client.Minecraft";
    private static final String RESOURCE = "/voxel_probe/mapping/forge_1_20_1.tsv.gz";
    private final String version;
    private final ParsedMappings mappings;
    private final boolean srgRuntime;

    public ForgeSearchResolver(String version, ParsedMappings mappings) {
        if (!"1.20.1".equals(version)) {
            throw new IllegalArgumentException("Forge member mappings only cover Minecraft 1.20.1, not " + version);
        }
        this.version = version;
        this.mappings = mappings;
        this.srgRuntime = detectSrgRuntime();
    }

    /** Probe names without initializing Minecraft or invoking any game method. */
    private static boolean detectSrgRuntime() {
        String srgName = MemberMaps.METHODS.getOrDefault(MINECRAFT, Map.of()).get("getInstance()");
        if (srgName == null) {
            throw new IllegalStateException("Forge member map lacks Minecraft.getInstance()");
        }
        try {
            Class<?> minecraft = Class.forName(MINECRAFT, false, ForgeSearchResolver.class.getClassLoader());
            for (var method : minecraft.getDeclaredMethods()) {
                if (method.getName().equals("getInstance") && method.getParameterCount() == 0) return false;
            }
            for (var method : minecraft.getDeclaredMethods()) {
                if (method.getName().equals(srgName) && method.getParameterCount() == 0) return true;
            }
            throw new IllegalStateException("Minecraft has neither the Mojang nor SRG getInstance method");
        } catch (ClassNotFoundException | LinkageError e) {
            throw new IllegalStateException("Cannot identify the Forge runtime member namespace", e);
        }
    }

    @Override
    public String resolveClass(String mojangClassName) {
        return mojangClassName;
    }

    @Override
    public String resolveField(String mojangClassName, String mojangFieldName) {
        if (!srgRuntime) return mojangFieldName;
        return MemberMaps.FIELDS.getOrDefault(mojangClassName, Map.of()).getOrDefault(mojangFieldName, mojangFieldName);
    }

    @Override
    public String resolveMethod(String mojangClassName, String mojangMethodName, String[] mojangParamTypes) {
        if (!srgRuntime) return mojangMethodName;
        Map<String, String> methods = MemberMaps.METHODS.getOrDefault(mojangClassName, Map.of());
        if (mojangParamTypes != null) {
            String signature = mojangMethodName + "(" + String.join(",", mojangParamTypes) + ")";
            return methods.getOrDefault(signature, mojangMethodName);
        }
        // Never silently select one of several differently named overloads.
        String resolved = null;
        String prefix = mojangMethodName + "(";
        for (var entry : methods.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            if (resolved != null && !resolved.equals(entry.getValue())) return mojangMethodName;
            resolved = entry.getValue();
        }
        return resolved != null ? resolved : mojangMethodName;
    }

    @Override
    public String unresolveClass(String runtimeClassName) {
        return runtimeClassName;
    }

    @Override
    public Collection<String> getAllClassNames() {
        var classes = new java.util.TreeSet<>(mappings.classes.keySet());
        classes.addAll(MemberMaps.FIELDS.keySet());
        classes.addAll(MemberMaps.METHODS.keySet());
        return Collections.unmodifiableSet(classes);
    }

    @Override
    public Collection<String> getFieldNames(String mojangClassName) {
        var fields = mappings.fields.get(mojangClassName);
        if (fields == null) return MemberMaps.FIELDS.getOrDefault(mojangClassName, Map.of()).keySet();
        return fields.keySet();
    }

    @Override
    public Collection<String> getMethodSignatures(String mojangClassName) {
        var methods = mappings.methods.get(mojangClassName);
        if (methods == null) return MemberMaps.METHODS.getOrDefault(mojangClassName, Map.of()).keySet();
        return methods.keySet();
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public boolean isObfuscated() {
        return srgRuntime;
    }

    private static final class MemberMaps {
        static final Map<String, Map<String, String>> FIELDS = new HashMap<>();
        static final Map<String, Map<String, String>> METHODS = new HashMap<>();

        static {
            try (InputStream resource = ForgeSearchResolver.class.getResourceAsStream(RESOURCE)) {
                if (resource == null) throw new IOException("Missing resource " + RESOURCE);
                try (var reader = new BufferedReader(new InputStreamReader(
                        new GZIPInputStream(resource), StandardCharsets.UTF_8))) {
                    if (!"# debugbridge-forge-member-map-v1\t1.20.1".equals(reader.readLine())) {
                        throw new IOException("Unsupported Forge member map header");
                    }
                    String owner = null;
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isBlank() || line.startsWith("#")) continue;
                        String[] parts = line.split("\t", -1);
                        if (parts.length == 2 && parts[0].equals("C")) {
                            owner = parts[1];
                        } else if (owner != null && parts.length == 3
                                && (parts[0].equals("F") || parts[0].equals("M"))) {
                            var target = parts[0].equals("F") ? FIELDS : METHODS;
                            String previous = target.computeIfAbsent(owner, key -> new HashMap<>())
                                    .putIfAbsent(parts[1], parts[2]);
                            if (previous != null) throw new IOException("Duplicate mapping in " + owner + ": " + parts[1]);
                        } else {
                            throw new IOException("Malformed Forge member mapping: " + line);
                        }
                    }
                    if (FIELDS.isEmpty() || METHODS.isEmpty()) throw new IOException("Empty Forge member mapping");
                }
            } catch (IOException e) {
                throw new IllegalStateException("Cannot load Forge 1.20.1 member mappings", e);
            }
        }
    }
}
