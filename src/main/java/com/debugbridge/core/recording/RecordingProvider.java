package com.debugbridge.core.recording;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Kernel-side orchestrator for {@code record_video} requests. One instance per
 * mod; created by {@code AbstractDebugBridgeMod} with a version-specific
 * {@link FrameCapturer} injected.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>The websocket handler thread calls {@link #record(RecordingRequest)},
 *       which installs a {@link RecordingSession} under {@link #active} and
 *       blocks on its future.
 *   <li>The version's render-tick mixin pumps {@link #onRenderFrame()} every
 *       frame. Each call delegates to the active session (if any).
 *   <li>When the session completes (success or failure), it clears
 *       {@link #active}, completes the future, and the handler thread
 *       continues.
 * </ol>
 *
 * <p>Only one recording at a time. A second {@link #record} call while one is
 * active throws {@link RecordingException.Busy}.
 */
public final class RecordingProvider {
    private static final Logger LOG = Logger.getLogger("DebugBridge");

    private final FrameCapturer capturer;
    private final Path recordingsBaseDir;
    private final AtomicReference<RecordingSession> active = new AtomicReference<>();

    /**
     * @param capturer          version-specific frame capture primitive
     * @param recordingsBaseDir directory under which {@code <reqId>/} subdirs
     *                          are created (typically {@code
     *                          <gameDir>/debugbridge-recordings/})
     */
    public RecordingProvider(FrameCapturer capturer, Path recordingsBaseDir) {
        this.capturer = capturer;
        this.recordingsBaseDir = recordingsBaseDir;
    }

    /**
     * Drive the active session forward by one frame. Called from the render
     * thread once per render tick (via the per-version mixin).
     *
     * <p>No-op when no recording is in progress — cheap fast path that all
     * three Fabric modules trigger every frame.
     */
    public void onRenderFrame() {
        RecordingSession s = active.get();
        if (s != null) {
            s.onFrame();
        }
    }

    /**
     * Whether any recording is currently active. Used by other handlers (e.g.
     * single-shot {@code screenshot}) to short-circuit with {@code BUSY}
     * while the render thread is being driven by a recording.
     */
    public boolean isActive() {
        return active.get() != null;
    }

    /**
     * Run one recording end-to-end. Blocks the caller until all output files
     * are flushed to disk. Throws on validation / IO / framebuffer failures.
     */
    public RecordingResult record(RecordingRequest req) throws RecordingException, InterruptedException {
        RecordingSession session = new RecordingSession(req, recordingsBaseDir, capturer);
        if (!active.compareAndSet(null, session)) {
            throw new RecordingException.Busy();
        }
        try {
            session.start();
            return session.awaitResult();
        } finally {
            active.set(null);
            session.shutdown();
            LOG.info("[DebugBridge] Recording " + req.requestId + " finished");
        }
    }
}
