package com.debugbridge.core.recording;

/**
 * Base for typed failures raised by the recording pipeline. The first token
 * of {@link #getMessage()} is the protocol error code (see
 * {@code RECORD_VIDEO_PROTOCOL.md} §4); the rest is human-readable detail.
 * {@code BridgeServer.handleRecordVideo} surfaces the whole string as the
 * {@code error} field on the wire.
 */
public class RecordingException extends Exception {
    public RecordingException(String message) {
        super(message);
    }

    public RecordingException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Another recording is already in progress on this client. */
    public static final class Busy extends RecordingException {
        public Busy() {
            super("BUSY: another record_video request is already in progress");
        }
    }

    /** Render target was missing or unreadable when capture started. */
    public static final class FramebufferUnavailable extends RecordingException {
        public FramebufferUnavailable(String detail) {
            super("FRAMEBUFFER_UNAVAILABLE: " + detail);
        }
    }

    /** Frame dimensions changed mid-recording (window resized). */
    public static final class FramebufferResized extends RecordingException {
        public FramebufferResized(int expectedW, int expectedH, int gotW, int gotH) {
            super("FRAMEBUFFER_RESIZED: frame dimensions changed from " + expectedW + "x" + expectedH + " to " + gotW
                    + "x" + gotH + " mid-recording");
        }
    }

    /** Disk write failed while encoding or composing the recording. */
    public static final class IoError extends RecordingException {
        public IoError(String detail, Throwable cause) {
            super("IO_ERROR: " + detail, cause);
        }
    }
}
