package com.debugbridge.core.script;

import com.debugbridge.core.mapping.MappingResolver;
import com.debugbridge.core.refs.ObjectRefStore;
import groovy.lang.GroovyShell;
import groovy.lang.MissingMethodException;
import groovy.lang.MissingPropertyException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.VariableScope;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.DoWhileStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.ast.stmt.WhileStatement;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;
import org.codehaus.groovy.runtime.MethodClosure;

/**
 * A persistent Groovy execution environment with a Java/Minecraft bridge.
 * State persists across calls — undeclared assignments ({@code x = ...}) land in
 * the shared binding and survive to the next call. Loops, methods and closures
 * check one per-call deadline, including when dispatched to the game thread.
 * This is cooperative cancellation of trusted code, not isolation or a way to
 * forcibly stop a blocking native/Java method.
 *
 * <p>This is the Groovy successor to the old LuaRuntime; the wire contract
 * (code in, {@code returnValue}/{@code output}/{@code error} out) is unchanged.
 */
public class ScriptRuntime {
    private static final int MAX_CODE_CHARS = 65_536;
    private static final int MAX_OUTPUT_CHARS = 65_536;
    private static final String OUTPUT_TRUNCATED = "\n[output truncated at 65536 characters]\n";
    private static final ThreadLocal<ExecutionBudget> EXECUTION = new ThreadLocal<>();
    private final GroovyShell shell;
    private final GroovyBridge bridge;
    private long maxExecutionTimeMs = 10_000;

    private volatile Thread scriptThread;

    public ScriptRuntime(MappingResolver resolver, ThreadDispatcher dispatcher, ObjectRefStore refs) {
        this.bridge = new GroovyBridge(resolver, new ThreadDispatcher() {
            @Override
            public <T> T executeOnGameThread(Callable<T> task, long timeoutMs) throws Exception {
                ExecutionBudget budget = EXECUTION.get();
                if (budget == null) return dispatcher.executeOnGameThread(task, timeoutMs);
                checkExecutionDeadline();
                long remainingMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(budget.remainingNanos()));
                return dispatcher.executeOnGameThread(() -> {
                    ExecutionBudget previous = EXECUTION.get();
                    EXECUTION.set(budget);
                    try {
                        checkExecutionDeadline();
                        return task.call();
                    } finally {
                        if (previous == null) EXECUTION.remove();
                        else EXECUTION.set(previous);
                    }
                }, Math.min(timeoutMs, remainingMs));
            }
        }, refs);

        ScriptBinding binding = new ScriptBinding(bridge);
        JavaHelpers helpers = new JavaHelpers(bridge);
        binding.setVariable("java", helpers);
        // Top-level `sync { ... }` sugar for the game-thread batching helper.
        binding.setVariable("sync", new MethodClosure(helpers, "sync"));
        // Capture println/print output instead of writing to stdout.
        binding.setVariable("out", new PrintWriter(captureWriter(), true));

        this.shell = new GroovyShell(getClass().getClassLoader(), binding, compilerConfig());
    }

    private Writer captureWriter() {
        return new Writer() {
            @Override
            public void write(char[] cbuf, int off, int len) {
                ExecutionBudget budget = EXECUTION.get();
                if (budget != null) budget.append(cbuf, off, len);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
    }

    private CompilerConfiguration compilerConfig() {
        CompilerConfiguration cfg = new CompilerConfiguration();
        cfg.addCompilationCustomizers(new CompilationCustomizer(CompilePhase.CANONICALIZATION) {
            @Override
            public void call(SourceUnit source, GeneratorContext context, ClassNode node) {
                new ClassCodeVisitorSupport() {
                    @Override protected SourceUnit getSourceUnit() { return source; }

                    private Statement checked(Statement body) {
                        BlockStatement block = new BlockStatement();
                        block.setVariableScope(new VariableScope());
                        block.addStatement(new ExpressionStatement(new StaticMethodCallExpression(
                                ClassHelper.make(ScriptRuntime.class), "checkExecutionDeadline",
                                ArgumentListExpression.EMPTY_ARGUMENTS)));
                        block.addStatement(body);
                        return block;
                    }

                    @Override public void visitMethod(MethodNode method) {
                        if (method.getCode() != null && !method.isSynthetic()) method.setCode(checked(method.getCode()));
                        super.visitMethod(method);
                    }
                    @Override public void visitClosureExpression(ClosureExpression closure) {
                        closure.setCode(checked(closure.getCode()));
                        super.visitClosureExpression(closure);
                    }
                    @Override public void visitForLoop(ForStatement loop) {
                        loop.setLoopBlock(checked(loop.getLoopBlock()));
                        super.visitForLoop(loop);
                    }
                    @Override public void visitWhileLoop(WhileStatement loop) {
                        loop.setLoopBlock(checked(loop.getLoopBlock()));
                        super.visitWhileLoop(loop);
                    }
                    @Override public void visitDoWhileLoop(DoWhileStatement loop) {
                        loop.setLoopBlock(checked(loop.getLoopBlock()));
                        super.visitDoWhileLoop(loop);
                    }
                }.visitClass(node);
            }
        });

        // Best-effort sandbox mirroring SecurityPolicy: block dangerous imports,
        // including inline fully-qualified references (indirect import check).
        SecureASTCustomizer sec = new SecureASTCustomizer();
        sec.setIndirectImportCheckEnabled(true);
        sec.setDisallowedImports(SecurityPolicy.BLOCKED_IMPORTS);
        sec.setDisallowedStarImports(SecurityPolicy.BLOCKED_STAR_IMPORTS);
        cfg.addCompilationCustomizers(sec);
        return cfg;
    }

    public GroovyBridge getBridge() {
        return bridge;
    }

    public void bindVariable(String name, Object value) {
        shell.getContext().setVariable(name, value);
    }

    public void setMaxExecutionTimeMs(long ms) {
        this.maxExecutionTimeMs = ms;
    }

    /** Called by compiled Groovy code on both worker and dispatched game threads. */
    public static void checkExecutionDeadline() {
        ExecutionBudget budget = EXECUTION.get();
        if (budget == null || budget.cancelled || budget.remainingNanos() <= 0 || Thread.currentThread().isInterrupted()) {
            throw new ScriptDeadlineException();
        }
    }

    /** Execute Groovy code with the runtime's default timeout. */
    public ExecutionResult execute(String code) {
        return execute(code, maxExecutionTimeMs);
    }

    /** Execute Groovy code with an explicit per-call timeout (snapshotted per call). */
    public synchronized ExecutionResult execute(String code, long timeoutMs) {
        final long effectiveTimeoutMs = Math.max(1, timeoutMs > 0 ? timeoutMs : maxExecutionTimeMs);
        if (code == null || code.length() > MAX_CODE_CHARS) {
            return new ExecutionResult(null, "", "Script code is limited to 65536 characters");
        }
        if (scriptThread != null) {
            return new ExecutionResult(null, "", "Previous script is still running after cancellation; wait for it to stop before another request");
        }
        ExecutionBudget budget = new ExecutionBudget(effectiveTimeoutMs);

        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "groovy-exec");
            t.setDaemon(true);
            return t;
        });

        Future<ExecutionResult> future = executor.submit(() -> {
            scriptThread = Thread.currentThread();
            EXECUTION.set(budget);
            try {
                checkExecutionDeadline();
                Object result = shell.evaluate(code);
                checkExecutionDeadline();
                return new ExecutionResult(result, budget.output(), null);
            } catch (MultipleCompilationErrorsException e) {
                return new ExecutionResult(null, budget.output(), "Compilation error: " + e.getMessage());
            } catch (MissingPropertyException | MissingMethodException e) {
                return new ExecutionResult(null, budget.output(), e.getMessage());
            } catch (StackOverflowError e) {
                return new ExecutionResult(
                        null,
                        budget.output(),
                        "Stack overflow — script has infinite recursion or is too deeply nested");
            } catch (OutOfMemoryError e) {
                return new ExecutionResult(
                        null, budget.output(), "Out of memory — script allocated too much data");
            } catch (Throwable e) {
                if (isInterrupt(e)) {
                    return new ExecutionResult(null, budget.output(), timeoutMessage(effectiveTimeoutMs));
                }
                return new ExecutionResult(null, budget.output(), describe(e));
            } finally {
                EXECUTION.remove();
                scriptThread = null;
            }
        });

        try {
            return future.get(Math.max(1, budget.remainingNanos()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            budget.cancelled = true;
            future.cancel(true);
            Thread t = scriptThread;
            if (t != null) t.interrupt();
            return new ExecutionResult(null, budget.output(), timeoutMessage(effectiveTimeoutMs));
        } catch (Exception e) {
            budget.cancelled = true;
            future.cancel(true);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new ExecutionResult(null, budget.output(), describe(e));
        } finally {
            budget.cancelled = true; // A shorter dispatch timeout must also stop its still-running Groovy closure.
            executor.shutdownNow();
        }
    }

    private static boolean isInterrupt(Throwable e) {
        for (Throwable c = e; c != null && c.getCause() != c; c = c.getCause()) {
            if (c instanceof InterruptedException || c instanceof ScriptDeadlineException) return true;
            String msg = c.getMessage();
            if (msg != null && msg.toLowerCase().contains("interrupt")) return true;
            if (c.getCause() == null) break;
        }
        return false;
    }

    private static String timeoutMessage(long ms) {
        return "Execution timed out after " + ms + "ms; cooperative script checks cancel loops, but an already-started native/Java call may still finish. Inspect state before retrying edits.";
    }

    private static final class ScriptDeadlineException extends RuntimeException {
        ScriptDeadlineException() { super("Script execution deadline reached"); }
    }

    private static final class ExecutionBudget {
        final long deadline;
        final StringBuilder output = new StringBuilder();
        volatile boolean cancelled;
        boolean truncated;

        ExecutionBudget(long timeoutMs) { deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs); }
        long remainingNanos() { return deadline - System.nanoTime(); }

        synchronized void append(char[] chars, int offset, int length) {
            int accepted = Math.min(length, MAX_OUTPUT_CHARS - output.length());
            if (accepted > 0) output.append(chars, offset, accepted);
            if (accepted < length) truncated = true;
        }

        synchronized String output() {
            if (!truncated) return output.toString();
            return output.substring(0, MAX_OUTPUT_CHARS - OUTPUT_TRUNCATED.length()) + OUTPUT_TRUNCATED;
        }
    }

    private static String describe(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String msg = c.getMessage();
        return c.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }

    /** Result of executing a script. {@code returnValue} is a plain Java/Groovy object (or wrapper). */
    public static class ExecutionResult {
        public final Object returnValue;
        public final String output;
        public final String error;

        public ExecutionResult(Object returnValue, String output, String error) {
            this.returnValue = returnValue;
            this.output = output;
            this.error = error;
        }

        public boolean isSuccess() {
            return error == null;
        }
    }
}
