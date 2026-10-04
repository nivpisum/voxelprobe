package com.debugbridge.core;

import com.debugbridge.core.block.NearbyBlocksProvider;
import com.debugbridge.core.chat.ChatHistoryProvider;
import com.debugbridge.core.entity.LookedAtEntityProvider;
import com.debugbridge.core.entity.NearbyEntitiesProvider;
import com.debugbridge.core.lifecycle.AbstractDebugBridgeMod;
import com.debugbridge.core.mapping.FabricNamespaceLookup;
import com.debugbridge.core.mapping.PassthroughResolver;
import com.debugbridge.core.protocol.BridgeRequest;
import com.debugbridge.core.protocol.BridgeResponse;
import com.debugbridge.core.refs.ObjectRefStore;
import com.debugbridge.core.screen.ScreenInspectProvider;
import com.debugbridge.core.screenshot.ScreenshotProvider;
import com.debugbridge.core.script.DirectDispatcher;
import com.debugbridge.core.script.GroovyBridge;
import com.debugbridge.core.script.ScriptRuntime;
import com.debugbridge.core.script.ThreadDispatcher;
import com.debugbridge.core.server.ResultSerializer;
import com.debugbridge.core.server.BridgeServer;
import com.google.gson.JsonObject;
import com.debugbridge.core.session.SessionControlProvider;
import com.debugbridge.core.snapshot.GameStateProvider;
import com.debugbridge.core.texture.ItemTextureProvider;
import com.google.gson.JsonElement;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Standalone pure-JVM checks. No Minecraft classes, sockets or retained world are used. */
public final class CoreBoundaryTest {
    public static void main(String[] args) throws Exception {
        scriptsCarryTheirDeadlineToTheGameThread();
        scriptOutputIsBoundedAtWriteTime();
        serializerBoundsContainersBeforeExpandingThem();
        clientRequestsCancelBeforeStartingAndReportUnknownAfterStarting();
        clientChangesRequireAnActiveWorldTargetAfterTheMenu();
        System.out.println("CoreBoundaryTest: 5 groups passed");
    }

    private static void clientChangesRequireAnActiveWorldTargetAfterTheMenu() throws Exception {
        BridgeServer bridge = new BridgeServer(0, new PassthroughResolver("test"), new DirectDispatcher());
        BridgeConfig policy = new BridgeConfig();
        policy.worldWriteEnabled = policy.scriptEnabled = policy.runCommandEnabled = true;
        bridge.setAccessPolicy(policy);
        bridge.setRunCommandEnabled(true);
        java.util.concurrent.atomic.AtomicReference<String> world = new java.util.concurrent.atomic.AtomicReference<>();
        bridge.setWorldIdentity(world::get);
        var handle = BridgeServer.class.getDeclaredMethod("handleRequest", BridgeRequest.class);
        handle.setAccessible(true);
        JsonObject payload = new JsonObject();
        payload.addProperty("expected_instance_id", bridge.getInstanceId());
        payload.addProperty("code", "counter = 11; return counter");
        BridgeResponse menu = (BridgeResponse) handle.invoke(bridge, new BridgeRequest("menu", "execute", payload));
        check(menu.success, "A trusted client script should still work on the menu without a world");
        world.set("entered-world");
        payload.addProperty("code", "counter = 999; return counter");
        BridgeResponse stale = (BridgeResponse) handle.invoke(bridge, new BridgeRequest("stale", "execute", payload));
        check(!stale.success && stale.error.contains("TARGET_CHANGED"), "A menu binding must not authorize an active-world client script");
        JsonObject command = payload.deepCopy();
        command.addProperty("command", "time set night");
        BridgeResponse staleCommand = (BridgeResponse) handle.invoke(bridge, new BridgeRequest("command", "runCommand", command));
        check(!staleCommand.success && staleCommand.error.contains("TARGET_CHANGED"), "Client commands must require the current world target too");
        payload.addProperty("expected_world_id", "other-world");
        BridgeResponse wrong = (BridgeResponse) handle.invoke(bridge, new BridgeRequest("wrong", "execute", payload));
        check(!wrong.success && wrong.error.contains("TARGET_CHANGED"), "A wrong client world target was accepted");
        payload.addProperty("expected_world_id", "entered-world");
        payload.addProperty("code", "return counter");
        BridgeResponse refreshed = (BridgeResponse) handle.invoke(bridge, new BridgeRequest("fresh", "execute", payload));
        check(refreshed.success && refreshed.result.getAsJsonObject().get("value").getAsInt() == 11,
                "Refreshed world target should work; refused calls must not have executed");
    }

    private static ExecutorService gameExecutor() {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "fake-game");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static ScriptRuntime runtime(ThreadDispatcher dispatcher) {
        return new ScriptRuntime(new PassthroughResolver("test"), dispatcher, new ObjectRefStore());
    }

    private static ScriptRuntime.ExecutionResult readyExecution(ScriptRuntime runtime, String code) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            ScriptRuntime.ExecutionResult result = runtime.execute(code, 5000);
            if (result.error == null || !result.error.startsWith("Previous script")) return result;
            Thread.sleep(10);
        }
        throw new AssertionError("Cancelled script worker did not leave the runtime");
    }

    private static void scriptsCarryTheirDeadlineToTheGameThread() throws Exception {
        ExecutorService game = gameExecutor();
        try {
            ScriptRuntime runtime = runtime(new FakeClient(game::execute).dispatcher());
            check(runtime.execute("counter = 4; saved = { counter += 1 }; return saved()", 5000).isSuccess(),
                    "Initial script, captured variables and closure compilation failed");
            ScriptRuntime.ExecutionResult next = runtime.execute("return java.sync { saved() }", 5000);
            check(next.isSuccess() && ((Number) next.returnValue).intValue() == 6,
                    "Persistent closures must use the current call's budget on a different thread");

            CountDownLatch entered = new CountDownLatch(1);
            AtomicBoolean gameInterrupted = new AtomicBoolean();
            runtime.bindVariable("entered", entered);
            runtime.bindVariable("gameInterrupted", gameInterrupted);
            ScriptRuntime.ExecutionResult loop = runtime.execute(
                    "sync { entered.countDown(); try { while (true) {} } finally { gameInterrupted.set(Thread.currentThread().isInterrupted()) } }",
                    750);
            check(entered.getCount() == 0, "Deadline test never reached the fake game thread");
            check(!loop.isSuccess() && loop.error.contains("timed out"), "Runaway sync loop did not report timeout");
            game.submit(() -> null).get(2, TimeUnit.SECONDS);
            check(!gameInterrupted.get(), "The fake game thread must stop via its deadline, without interruption");
            check(readyExecution(runtime, "return 7").isSuccess(), "Runtime did not recover after loop cancellation");

            // Closure entry checks also bound iterations performed by native collection methods.
            runtime.bindVariable("entered", new CountDownLatch(1));
            ScriptRuntime.ExecutionResult iterations = runtime.execute(
                    "sync { entered.countDown(); (0..<100_000_000).each { value -> value + 1 } }", 750);
            check(!iterations.isSuccess() && iterations.error.contains("timed out"),
                    "Closure callbacks did not share the sync call's deadline: " + iterations.error);
            game.submit(() -> null).get(2, TimeUnit.SECONDS);
            check(readyExecution(runtime, "return 8").isSuccess(), "Closure cancellation left a worker running");

            ThreadDispatcher actual = new FakeClient(game::execute).dispatcher();
            ScriptRuntime shorterDispatch = runtime(new ThreadDispatcher() {
                @Override public <T> T executeOnGameThread(Callable<T> task, long timeout) throws Exception {
                    return actual.executeOnGameThread(task, Math.min(timeout, 100));
                }
            });
            check(shorterDispatch.execute("return 1", 5000).isSuccess(), "Short-dispatch runtime did not warm up");
            ScriptRuntime.ExecutionResult earlyTimeout = shorterDispatch.execute("sync { while (true) {} }", 5000);
            check(!earlyTimeout.isSuccess() && earlyTimeout.error.contains("timed out"), "Dispatch timeout was not reported");
            game.submit(() -> null).get(1, TimeUnit.SECONDS);
        } finally {
            game.shutdownNow();
        }
    }

    private static void scriptOutputIsBoundedAtWriteTime() {
        ScriptRuntime runtime = runtime(new DirectDispatcher());
        ScriptRuntime.ExecutionResult output = runtime.execute(
                "10000.times { println('abcdefghijklmnop') }; return 42", 5000);
        check(output.isSuccess() && ((Number) output.returnValue).intValue() == 42,
                "Truncation must preserve ordinary execution and return values");
        check(output.output.length() <= 65_536 && output.output.contains("[output truncated"),
                "Captured output exceeded its limit or did not explain truncation");
        ScriptRuntime.ExecutionResult following = runtime.execute("println('next'); return 1", 5000);
        check(following.isSuccess() && following.output.equals("next" + System.lineSeparator()),
                "Output and truncation state leaked into the following request");
        check(!runtime.execute(" ".repeat(65_537), 5000).isSuccess(), "Oversize script source was accepted");
    }

    private static void serializerBoundsContainersBeforeExpandingThem() {
        PassthroughResolver resolver = new PassthroughResolver("test");
        ObjectRefStore refs = new ObjectRefStore();
        ResultSerializer serializer = new ResultSerializer(resolver, refs,
                new GroovyBridge(resolver, new DirectDispatcher(), refs));
        List<Object> shared = List.of("value", 3, true);
        JsonElement normal = serializer.serialize(Map.of("a", shared, "b", shared));
        check(normal.getAsJsonObject().get("type").getAsString().equals("table"),
                "Ordinary maps and repeated shared values changed their wire envelope");

        List<Object> cyclicList = new ArrayList<>();
        cyclicList.add(cyclicList);
        expectLimit(() -> serializer.serialize(cyclicList), "cyclic");
        Map<String, Object> cyclicMap = new LinkedHashMap<>();
        cyclicMap.put("self", cyclicMap);
        expectLimit(() -> serializer.serialize(cyclicMap), "cyclic");
        Object[] cyclicArray = new Object[1];
        cyclicArray[0] = cyclicArray;
        expectLimit(() -> serializer.serialize(cyclicArray), "cyclic");
        Object deep = 1;
        for (int index = 0; index < 40; index++) deep = List.of(deep);
        Object tooDeep = deep;
        expectLimit(() -> serializer.serialize(tooDeep), "depth");
        expectLimit(() -> serializer.serialize(Collections.nCopies(20_000, 1)), "node");

        CharSequence oversize = new CharSequence() {
            @Override public int length() { return 1_000_000; }
            @Override public char charAt(int index) { return 'x'; }
            @Override public CharSequence subSequence(int start, int end) { throw new AssertionError("Unexpected copy"); }
            @Override public String toString() { throw new AssertionError("Oversize text must be rejected before copying"); }
        };
        expectLimit(() -> serializer.serialize(oversize), "character");
        expectLimit(() -> serializer.serialize(Map.of(oversize, 1)), "character");
        expectLimit(() -> serializer.serialize(Collections.nCopies(20, "x".repeat(100_000))), "character");
        expectLimit(() -> serializer.serialize(new LargeField()), "character");

        JsonElement escaped = serializer.serialize(Collections.nCopies(100, "\u0000<>\"&\\".repeat(500)));
        check(escaped.toString().length() < 4 * 1024 * 1024, "JSON escaping exceeded the conservative character budget");
        check(serializer.serialize(9).getAsJsonObject().get("value").getAsInt() == 9,
                "Failed serialization leaked a traversal budget into the next call");
    }

    public static final class LargeField { public String text = "x".repeat(1_000_000); }

    private static void expectLimit(Runnable action, String expected) {
        try {
            action.run();
            throw new AssertionError("Expected serialization limit: " + expected);
        } catch (IllegalArgumentException error) {
            check(error.getMessage().contains(expected), "Unexpected serialization error: " + error);
        }
    }

    private static void clientRequestsCancelBeforeStartingAndReportUnknownAfterStarting() throws Exception {
        Queue<Runnable> queued = new ArrayDeque<>();
        ThreadDispatcher paused = new FakeClient(queued::add).dispatcher();
        AtomicInteger writes = new AtomicInteger();
        try {
            paused.executeOnGameThread(writes::incrementAndGet, 50);
            throw new AssertionError("Queued client call did not time out");
        } catch (TimeoutException error) {
            check(error.getMessage().contains("before starting"), "Queued timeout did not establish cancellation");
        }
        queued.remove().run();
        check(writes.get() == 0, "A timed-out queued client operation later changed state");

        ExecutorService game = gameExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        try {
            ThreadDispatcher running = new FakeClient(game::execute).dispatcher();
            try {
                running.executeOnGameThread(() -> {
                    entered.countDown();
                    try {
                        release.await(); // Simulates an already-started blocking native operation.
                        return writes.incrementAndGet();
                    } catch (InterruptedException error) {
                        interrupted.set(true);
                        throw error;
                    } finally {
                        finished.countDown();
                    }
                }, 150);
                throw new AssertionError("Started client call did not time out");
            } catch (TimeoutException error) {
                check(entered.getCount() == 0 && error.getMessage().contains("outcome is unknown"),
                        "Started timeout did not disclose the unknown outcome");
            }
            release.countDown();
            check(finished.await(2, TimeUnit.SECONDS), "The fake native operation did not finish");
            check(writes.get() == 1 && !interrupted.get(), "Started cancellation interrupted the game thread or claimed rollback");
            check(running.executeOnGameThread(() -> 5, 1000) == 5, "Client dispatcher did not recover after timeout");
        } finally {
            release.countDown();
            game.shutdownNow();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeClient extends AbstractDebugBridgeMod {
        final Consumer<Runnable> submit;
        FakeClient(Consumer<Runnable> submit) { this.submit = submit; }
        ThreadDispatcher dispatcher() { return createDispatcher(); }
        @Override protected void submitToGameThread(Runnable task) { submit.accept(task); }
        @Override protected String mcVersion() { return "test"; }
        @Override protected Path configDir() { return null; }
        @Override protected Path gameDir() { return null; }
        @Override protected FabricNamespaceLookup createNamespaceLookup() { return null; }
        @Override protected GameStateProvider createStateProvider() { return null; }
        @Override protected ScreenshotProvider createScreenshotProvider() { return null; }
        @Override protected ItemTextureProvider createTextureProvider() { return null; }
        @Override protected NearbyEntitiesProvider createEntitiesProvider() { return null; }
        @Override protected NearbyBlocksProvider createBlocksProvider() { return null; }
        @Override protected LookedAtEntityProvider createLookedAtEntityProvider() { return null; }
        @Override protected ChatHistoryProvider createChatHistoryProvider() { return null; }
        @Override protected ScreenInspectProvider createScreenInspectProvider() { return null; }
        @Override protected SessionControlProvider createSessionControlProvider() { return null; }
        @Override protected boolean displayPlayerError(String message) { return false; }
        @Override protected boolean displayPlayerInfo(String message) { return false; }
        @Override protected boolean canShowWarningScreen() { return false; }
        @Override protected void showWarningScreen(Consumer<Boolean> onResult) { throw new AssertionError("No UI in JVM tests"); }
    }
}
