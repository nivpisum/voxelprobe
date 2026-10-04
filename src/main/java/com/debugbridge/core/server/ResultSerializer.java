package com.debugbridge.core.server;

import com.debugbridge.core.mapping.MappingResolver;
import com.debugbridge.core.refs.ObjectRefStore;
import com.debugbridge.core.script.GroovyBridge;
import com.debugbridge.core.script.GroovyJavaClass;
import com.debugbridge.core.script.GroovyJavaObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Serializes script return values to JSON for transmission over WebSocket.
 * <p>
 * The wire format (a {@code {type, value, ...}} envelope) is unchanged from the
 * Lua era so the web UI and MCP clients keep working; the difference is that
 * Groovy hands us plain Java/Groovy objects (numbers, strings, {@code List},
 * {@code Map}, wrappers, or raw Minecraft objects) instead of {@code LuaValue}s.
 */
public class ResultSerializer {
    private static final int MAX_DEPTH = 32;
    private static final int MAX_NODES = 16_384;
    private static final int MAX_JSON_CHARS = 4 * 1024 * 1024;
    private final MappingResolver resolver;
    private final ObjectRefStore refs;
    private final GroovyBridge bridge;

    public ResultSerializer(MappingResolver resolver, ObjectRefStore refs, GroovyBridge bridge) {
        this.resolver = resolver;
        this.refs = refs;
        this.bridge = bridge;
    }

    /** Serialize a script return value to a JSON element. */
    public JsonElement serialize(Object value) {
        return serialize(value, new Budget(), 0);
    }

    private JsonElement serialize(Object value, Budget budget, int depth) {
        budget.node(depth);
        if (value == null) {
            return typed("nil");
        }

        if (value instanceof Boolean b) {
            JsonObject obj = typed("boolean");
            obj.addProperty("value", b);
            return obj;
        }

        if (value instanceof Number n) {
            budget.text(n.toString());
            JsonObject obj = typed("number");
            obj.addProperty("value", n);
            return obj;
        }

        if (value instanceof Character || value instanceof CharSequence) {
            JsonObject obj = typed("string");
            obj.addProperty("value", budget.text(value instanceof CharSequence chars ? chars : value.toString()));
            return obj;
        }

        if (value instanceof GroovyJavaObject wrapper) {
            return serializeJavaObject(wrapper.getTarget(), budget, depth);
        }

        if (value instanceof GroovyJavaClass wrapper) {
            JsonObject obj = typed("class");
            obj.addProperty("className", budget.text(wrapper.getMojangName()));
            return obj;
        }

        if (value instanceof Map<?, ?> map) {
            budget.enter(value);
            try {
                JsonObject obj = typed("table");
                JsonObject inner = new JsonObject();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    Object key = e.getKey();
                    String name = budget.text(key instanceof CharSequence chars ? chars : String.valueOf(key));
                    inner.add(name, serialize(e.getValue(), budget, depth + 1));
                }
                obj.add("value", inner);
                return obj;
            } finally {
                budget.leave(value);
            }
        }

        if (value instanceof Collection<?> coll) {
            budget.enter(value);
            try {
                JsonObject obj = typed("table");
                JsonArray arr = new JsonArray();
                for (Object item : coll) arr.add(serialize(item, budget, depth + 1));
                obj.add("value", arr);
                return obj;
            } finally {
                budget.leave(value);
            }
        }

        if (value.getClass().isArray()) {
            budget.enter(value);
            try {
                JsonObject obj = typed("table");
                JsonArray arr = new JsonArray();
                int len = Array.getLength(value);
                for (int i = 0; i < len; i++) arr.add(serialize(Array.get(value, i), budget, depth + 1));
                obj.add("value", arr);
                return obj;
            } finally {
                budget.leave(value);
            }
        }

        // Any other raw Java object (e.g. a Minecraft object returned unwrapped).
        return serializeJavaObject(value, budget, depth);
    }

    private JsonObject typed(String type) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", type);
        return obj;
    }

    private JsonElement serializeJavaObject(Object javaObj, Budget budget, int depth) {
        if (javaObj == null) {
            return typed("null");
        }

        String mojangType = resolver.unresolveClass(javaObj.getClass().getName());
        String ref = refs.store(javaObj);

        JsonObject obj = typed("object");
        obj.addProperty("className", budget.text(mojangType));
        obj.addProperty("ref", budget.text(ref));

        try {
            // Wrapped containers stay object references; do not expand their contents via toString.
            String summary = javaObj instanceof Map || javaObj instanceof Collection || javaObj.getClass().isArray()
                    || javaObj instanceof CharSequence
                    ? mojangType + "@" + Integer.toHexString(System.identityHashCode(javaObj)) : javaObj.toString();
            obj.addProperty("toString", budget.text(summary));
        } catch (SerializationLimitException e) {
            throw e;
        } catch (Exception | StackOverflowError e) {
            obj.addProperty("toString", budget.text(mojangType + "@" + Integer.toHexString(System.identityHashCode(javaObj))));
        }

        try {
            JsonObject fields = summarizeFields(javaObj, budget, depth);
            if (fields.size() > 0) {
                obj.add("fields", fields);
            }
        } catch (SerializationLimitException e) {
            throw e;
        } catch (Exception e) {
            // Skip field summary on error
        }
        return obj;
    }

    private JsonObject summarizeFields(Object obj, Budget budget, int depth) {
        JsonObject fields = new JsonObject();
        int count = 0;
        for (Field f : obj.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (count >= 15) break;
            try {
                f.setAccessible(true);
                Object val = f.get(obj);
                String name = budget.text(bridge.getFieldMojangName(f.getDeclaringClass(), f));
                budget.node(depth + 1);
                if (val == null) {
                    fields.add(name, JsonNull.INSTANCE);
                } else if (val instanceof Boolean b) {
                    fields.addProperty(name, b);
                } else if (val instanceof Number n) {
                    budget.text(n.toString());
                    fields.addProperty(name, n);
                } else if (val instanceof String s) {
                    fields.addProperty(name, budget.text(s));
                } else {
                    fields.addProperty(
                            name,
                            budget.text(val.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(val))));
                }
                count++;
            } catch (SerializationLimitException e) {
                throw e;
            } catch (Exception e) {
                // Skip inaccessible fields
            }
        }
        return fields;
    }

    private static final class SerializationLimitException extends IllegalArgumentException {
        SerializationLimitException(String message) { super("Script result " + message); }
    }

    /** Conservative JSON budget: envelopes, field summaries and worst-case string escaping. */
    private static final class Budget {
        final IdentityHashMap<Object, Boolean> path = new IdentityHashMap<>();
        int nodes;
        int remainingChars = MAX_JSON_CHARS;

        void node(int depth) {
            if (depth > MAX_DEPTH) throw new SerializationLimitException("exceeds the depth limit of " + MAX_DEPTH);
            if (++nodes > MAX_NODES) throw new SerializationLimitException("exceeds the node limit of " + MAX_NODES);
            take(128); // More than the fixed envelope/field punctuation emitted per visited node.
        }

        String text(CharSequence value) {
            if (value == null) return null;
            long worstCaseChars = (long) value.length() * 6 + 2;
            if (worstCaseChars > remainingChars) throw new SerializationLimitException("exceeds the 4 MiB JSON character budget");
            take((int) worstCaseChars);
            return value.toString();
        }

        void take(int chars) {
            if (chars > remainingChars) throw new SerializationLimitException("exceeds the 4 MiB JSON character budget");
            remainingChars -= chars;
        }

        void enter(Object container) {
            if (path.put(container, Boolean.TRUE) != null) throw new SerializationLimitException("contains a cyclic container reference");
        }

        void leave(Object container) { path.remove(container); }
    }
}
