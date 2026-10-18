package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Render-thread ownership boundary for resources associated with one immutable shaderpack
 * generation. Preparation never mutates this object; publication and retirement are atomic.
 *
 * <p>The lifecycle deliberately stores no Minecraft or backend type. A backend adapter supplies a
 * resource bundle and retires it only after all frame leases have been closed. This makes resize,
 * device reset, reload failure, and close behavior testable without creating another graphics
 * device.</p>
 */
public final class ShaderpackGenerationLifecycle implements AutoCloseable {
    private final AtomicReference<Published> current = new AtomicReference<>();
    private final AtomicLong serial = new AtomicLong();
    private final Consumer<Throwable> failureSink;
    private volatile boolean closed;

    public ShaderpackGenerationLifecycle() {
        this(ignored -> { });
    }

    public ShaderpackGenerationLifecycle(Consumer<Throwable> failureSink) {
        this.failureSink = Objects.requireNonNull(failureSink, "failureSink");
    }

    public boolean isClosed() {
        return closed;
    }

    public synchronized Published current() {
        return current.get();
    }

    /** Publishes a complete generation and retires the previous one after its leases drain. */
    public synchronized Published publish(ShaderpackGeneration generation, Resources resources) {
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(resources, "resources");
        if (closed) {
            closeQuietly(resources);
            throw new IllegalStateException("shaderpack generation lifecycle is closed");
        }
        if (!generation.executable()) {
            closeQuietly(resources);
            throw new IllegalArgumentException("cannot publish non-executable generation: " + generation.outcome());
        }
        Published replacement = new Published(serial.incrementAndGet(), generation, resources, failureSink);
        Published old = current.getAndSet(replacement);
        if (old != null) old.retire();
        return replacement;
    }

    /** Acquires a lease for the current generation, or returns an empty lease when vanilla is active. */
    public synchronized Lease acquire() {
        Published value = current.get();
        if (value == null || value.retired()) return Lease.empty();
        return value.acquire();
    }

    /** Retires the current generation without affecting the previous-generation diagnostics. */
    public synchronized void disable() {
        Published old = current.getAndSet(null);
        if (old != null) old.retire();
    }

    /** Rebuilds resources after a resize/device reset without exposing a partial bundle. */
    public synchronized Published rebuild(Resources replacement) {
        Objects.requireNonNull(replacement, "replacement");
        if (closed) {
            closeQuietly(replacement);
            throw new IllegalStateException("shaderpack generation lifecycle is closed");
        }
        Published old = current.get();
        if (old == null) {
            closeQuietly(replacement);
            return null;
        }
        Published next;
        try {
            next = new Published(serial.incrementAndGet(), old.generation(), replacement, failureSink);
        } catch (RuntimeException failure) {
            closeQuietly(replacement);
            reportFailure(failure);
            throw failure;
        }
        current.set(next);
        old.retire();
        return next;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        Published old = current.getAndSet(null);
        if (old != null) old.retire();
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("shaderpack generation lifecycle is closed");
    }

    private void closeQuietly(Resources resources) {
        try {
            resources.close();
        } catch (Throwable failure) {
            reportFailure(failure);
        }
    }

    private void reportFailure(Throwable failure) {
        try {
            failureSink.accept(failure);
        } catch (Throwable ignored) {
            // Diagnostic callbacks must not compromise resource retirement or state transitions.
        }
    }

    public interface Resources extends AutoCloseable {
        @Override
        void close();
    }

    public static final class Published {
        private final long serial;
        private final ShaderpackGeneration generation;
        private final Resources resources;
        private final Consumer<Throwable> failureSink;
        private int leases;
        private boolean retired;
        private boolean closed;

        private Published(long serial, ShaderpackGeneration generation, Resources resources,
                          Consumer<Throwable> failureSink) {
            this.serial = serial;
            this.generation = generation;
            this.resources = resources;
            this.failureSink = failureSink;
        }

        public long serial() {
            return serial;
        }

        public ShaderpackGeneration generation() {
            return generation;
        }

        public synchronized boolean retired() {
            return retired;
        }

        public synchronized int activeLeases() {
            return leases;
        }

        private synchronized Lease acquire() {
            if (retired || closed) return Lease.empty();
            leases++;
            return new Lease(this);
        }

        private synchronized void retire() {
            retired = true;
            closeWhenDrained();
        }

        private synchronized void release() {
            if (leases > 0) leases--;
            closeWhenDrained();
        }

        private void closeWhenDrained() {
            if (retired && leases == 0 && !closed) {
                closed = true;
                try {
                    resources.close();
                } catch (Throwable failure) {
                    try {
                        failureSink.accept(failure);
                    } catch (Throwable ignored) {
                        // Cleanup remains deterministic even if diagnostics fail.
                    }
                }
            }
        }
    }

    public static final class Lease implements AutoCloseable {
        private static final Lease EMPTY = new Lease();
        private Published owner;
        private boolean closed;

        private Lease() {
            closed = true;
        }

        private Lease(Published owner) {
            this.owner = owner;
        }

        private static Lease empty() {
            return EMPTY;
        }

        public synchronized boolean present() {
            return owner != null && !closed;
        }

        public synchronized Published published() {
            if (!present()) throw new IllegalStateException("empty or closed shaderpack lease");
            return owner;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            Published value = owner;
            owner = null;
            value.release();
        }
    }
}
