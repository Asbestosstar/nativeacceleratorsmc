package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Pure precondition tests for {@link NativeVulkanCommandEncoder}.
 *
 * <p>These tests do not require a live Vulkan device. They exercise:</p>
 * <ul>
 *   <li>foreign-resource rejection (before any Vulkan call is made);</li>
 *   <li>fail-closed stubs for operations requiring infrastructure not yet present;</li>
 *   <li>{@link NativeVulkanDeviceState} lifecycle guards (which requireOpen() delegates to);</li>
 *   <li>{@link NativeVulkanFormat} coverage (every GpuFormat must map to a nonzero VkFormat).</li>
 * </ul>
 */
public final class NativeVulkanCommandEncoderTest {

    public static void main(String[] args) {
        constructorRejectsNullState();
        constructorRejectsNullFrame();
        fourArgConstructorAcceptsNullStagingDependencies();
        writeToBufferRejectsForeignBuffer();
        copyToBufferRejectsForeignSource();
        clearColorTextureRejectsForeignTexture();
        clearDepthTextureRejectsForeignTexture();
        clearColorAndDepthTexturesRejectsForeignTextures();
        writeToTextureRejectsForeignTexture();
        copyBufferToTextureRejectsForeignBuffer();
        copyTextureToTextureRejectsForeignSource();
        copyTextureToBufferRemainsUnsupported();
        createFenceRemainsUnsupported();
        writeTimestampRemainsUnsupported();
        transientMemoryRemainsUnsupported();
        operationsRejectClosedDevice();
        deviceStateLifecycle();
        formatConverterCoversEveryGpuFormat();
        formatConverterRejectsNull();
        formatConverterMapsKnownFormats();
        System.out.println("NativeVulkanCommandEncoderTest: PASS");
    }

    // -------------------------------------------------------------------------
    // Constructor guards
    // -------------------------------------------------------------------------

    static void constructorRejectsNullState() {
        requireThrows(NullPointerException.class,
                () -> new NativeVulkanCommandEncoder(null, stubFrame()),
                "null state must be rejected");
    }

    static void constructorRejectsNullFrame() {
        NativeVulkanDeviceState state = new NativeVulkanDeviceState();
        requireThrows(NullPointerException.class,
                () -> new NativeVulkanCommandEncoder(state, null),
                "null frame must be rejected");
    }

    static void fourArgConstructorAcceptsNullStagingDependencies() {
        // Null bootstrap/allocator is the staging-free path; must not throw on construction.
        NativeVulkanCommandEncoder enc =
                new NativeVulkanCommandEncoder(new NativeVulkanDeviceState(), stubFrame(), null, null);
        require(enc != null, "encoder must not be null");
    }

    // -------------------------------------------------------------------------
    // Foreign-resource rejection (runs before any Vulkan call)
    // -------------------------------------------------------------------------

    static void writeToBufferRejectsForeignBuffer() {
        NativeVulkanCommandEncoder enc = encoder();
        GpuBufferSlice foreign = new GpuBufferSlice(new ForeignBuffer(), 0L, 16L);
        ByteBuffer data = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
        requireThrows(IllegalArgumentException.class,
                () -> enc.writeToBuffer(foreign, data),
                "foreign buffer must be rejected by writeToBuffer");
    }

    static void copyToBufferRejectsForeignSource() {
        NativeVulkanCommandEncoder enc = encoder();
        GpuBufferSlice src = new GpuBufferSlice(new ForeignBuffer(), 0L, 16L);
        GpuBufferSlice dst = new GpuBufferSlice(new ForeignBuffer(), 0L, 16L);
        requireThrows(IllegalArgumentException.class,
                () -> enc.copyToBuffer(src, dst),
                "foreign buffer must be rejected by copyToBuffer");
    }

    static void clearColorTextureRejectsForeignTexture() {
        NativeVulkanCommandEncoder enc = encoder();
        requireThrows(IllegalArgumentException.class,
                () -> enc.clearColorTexture(new ForeignTexture(), new Vector4f()),
                "foreign texture must be rejected by clearColorTexture");
    }

    static void clearDepthTextureRejectsForeignTexture() {
        NativeVulkanCommandEncoder enc = encoder();
        requireThrows(IllegalArgumentException.class,
                () -> enc.clearDepthTexture(new ForeignTexture(), 1.0),
                "foreign texture must be rejected by clearDepthTexture");
    }

    static void clearColorAndDepthTexturesRejectsForeignTextures() {
        NativeVulkanCommandEncoder enc = encoder();
        requireThrows(IllegalArgumentException.class,
                () -> enc.clearColorAndDepthTextures(
                        new ForeignTexture(), new Vector4f(), new ForeignTexture(), 1.0),
                "foreign textures must be rejected by clearColorAndDepthTextures");
    }

    static void writeToTextureRejectsForeignTexture() {
        NativeVulkanCommandEncoder enc = encoder();
        ByteBuffer data = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
        requireThrows(IllegalArgumentException.class,
                () -> enc.writeToTexture(new ForeignTexture(), data, 0, 0, 0, 0, 4, 4),
                "foreign texture must be rejected by writeToTexture");
    }

    static void copyBufferToTextureRejectsForeignBuffer() {
        NativeVulkanCommandEncoder enc = encoder();
        GpuBufferSlice src = new GpuBufferSlice(new ForeignBuffer(), 0L, 256L);
        requireThrows(IllegalArgumentException.class,
                () -> enc.copyBufferToTexture(src, 0, 0, 4, 4, new ForeignTexture(), 0, 0, 4, 4, 0, 0),
                "foreign buffer must be rejected by copyBufferToTexture");
    }

    static void copyTextureToTextureRejectsForeignSource() {
        NativeVulkanCommandEncoder enc = encoder();
        requireThrows(IllegalArgumentException.class,
                () -> enc.copyTextureToTexture(
                        new ForeignTexture(), new ForeignTexture(), 0, 0, 0, 0, 0, 4, 4),
                "foreign textures must be rejected by copyTextureToTexture");
    }

    // -------------------------------------------------------------------------
    // Fail-closed stubs for not-yet-implemented operations
    // -------------------------------------------------------------------------

    static void copyTextureToBufferRemainsUnsupported() {
        NativeVulkanCommandEncoder enc = encoder();
        ForeignBuffer dst = new ForeignBuffer();
        requireThrows(UnsupportedOperationException.class,
                () -> enc.copyTextureToBuffer(new ForeignTexture(), dst, 0L, () -> {}, 0),
                "copyTextureToBuffer must fail closed without fence infrastructure");
        requireThrows(UnsupportedOperationException.class,
                () -> enc.copyTextureToBuffer(new ForeignTexture(), dst, 0L, () -> {}, 0, 0, 0, 4, 4),
                "copyTextureToBuffer(region) must fail closed without fence infrastructure");
    }

    static void createFenceRemainsUnsupported() {
        NativeVulkanCommandEncoder enc = encoder();
        requireThrows(UnsupportedOperationException.class,
                enc::createFence,
                "createFence must fail closed");
    }

    static void writeTimestampRemainsUnsupported() {
        NativeVulkanCommandEncoder enc = encoder();
        requireThrows(UnsupportedOperationException.class,
                () -> enc.writeTimestamp(null, 0),
                "writeTimestamp must fail closed");
    }

    static void transientMemoryRemainsUnsupported() {
        NativeVulkanCommandEncoder enc = encoder();
        requireThrows(UnsupportedOperationException.class,
                enc::transientMemory,
                "transientMemory must fail closed");
    }

    // -------------------------------------------------------------------------
    // Closed-device guard
    // -------------------------------------------------------------------------

    static void operationsRejectClosedDevice() {
        NativeVulkanDeviceState state = new NativeVulkanDeviceState();
        NativeVulkanCommandEncoder enc =
                new NativeVulkanCommandEncoder(state, stubFrame(), null, null);
        state.beginClose();
        state.finishClose();
        requireThrows(IllegalStateException.class,
                enc::transientMemory,
                "closed-device encoder must reject all operations");
    }

    // -------------------------------------------------------------------------
    // NativeVulkanDeviceState lifecycle (used by requireOpen() in the encoder)
    // -------------------------------------------------------------------------

    static void deviceStateLifecycle() {
        NativeVulkanDeviceState state = new NativeVulkanDeviceState();
        require(state.isOpen(), "new state must be open");
        state.requireOpen(); // must not throw

        state.markLost("test loss");
        require(state.phase() == NativeVulkanDeviceState.Phase.LOST, "markLost must set LOST phase");
        require("test loss".equals(state.lossReason()), "loss reason must be preserved");
        requireThrows(IllegalStateException.class, state::requireOpen, "lost device must reject work");

        require(state.beginClose(), "beginClose on lost device must return true");
        require(!state.beginClose(), "second beginClose must return false (idempotent)");
        state.finishClose();
        require(state.phase() == NativeVulkanDeviceState.Phase.CLOSED, "finishClose must set CLOSED phase");
        requireThrows(IllegalStateException.class, state::requireOpen, "closed device must reject work");

        // markLost from CLOSED must not resurrect the device
        state.markLost("late loss");
        require(state.phase() == NativeVulkanDeviceState.Phase.CLOSED,
                "markLost from CLOSED must not change phase");

        // abort from OPEN transitions to CLOSED
        NativeVulkanDeviceState s2 = new NativeVulkanDeviceState();
        s2.abort();
        require(s2.phase() == NativeVulkanDeviceState.Phase.CLOSED, "abort from OPEN must set CLOSED");
    }

    // -------------------------------------------------------------------------
    // NativeVulkanFormat coverage
    // -------------------------------------------------------------------------

    static void formatConverterCoversEveryGpuFormat() {
        for (GpuFormat fmt : GpuFormat.values()) {
            int vk;
            try {
                vk = NativeVulkanFormat.vkFormat(fmt);
            } catch (Exception e) {
                throw new AssertionError("vkFormat threw for " + fmt + ": " + e, e);
            }
            require(vk != 0, "vkFormat must not return 0 for " + fmt);
        }
    }

    static void formatConverterRejectsNull() {
        requireThrows(NullPointerException.class,
                () -> NativeVulkanFormat.vkFormat(null),
                "vkFormat must reject null");
    }

    static void formatConverterMapsKnownFormats() {
        require(org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM
                        == NativeVulkanFormat.vkFormat(GpuFormat.RGBA8_UNORM),
                "RGBA8_UNORM must map to VK_FORMAT_R8G8B8A8_UNORM");
        require(org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT
                        == NativeVulkanFormat.vkFormat(GpuFormat.D32_FLOAT),
                "D32_FLOAT must map to VK_FORMAT_D32_SFLOAT");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static NativeVulkanCommandEncoder encoder() {
        return new NativeVulkanCommandEncoder(new NativeVulkanDeviceState(), stubFrame(), null, null);
    }

    /**
     * Builds a frame stub without invoking Vulkan. The tested encoder operations either reject
     * their arguments first or fail closed before consulting the frame.
     */
    private static NativeVulkanFrameSubmission stubFrame() {
        try {
            java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
            return (NativeVulkanFrameSubmission) unsafe.allocateInstance(NativeVulkanFrameSubmission.class);
        } catch (ReflectiveOperationException failure) {
            throw new RuntimeException("Cannot construct stub NativeVulkanFrameSubmission", failure);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void requireThrows(Class<? extends Throwable> type,
                                      Runnable action, String message) {
        try {
            action.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) return;
            throw new AssertionError(message + ": expected " + type.getSimpleName()
                    + " but got " + t.getClass().getSimpleName(), t);
        }
        throw new AssertionError(message + ": expected " + type.getSimpleName() + " but nothing was thrown");
    }

    // -------------------------------------------------------------------------
    // Foreign-resource stubs
    // -------------------------------------------------------------------------

    private static final class ForeignBuffer extends com.mojang.renderpearl.backend.common.BaseGpuBuffer {
        ForeignBuffer() {
            super(GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST, 256L);
        }

        @Override
        public com.mojang.renderpearl.api.buffers.GpuBufferSlice.MappedView map(
                long offset, long length, boolean read, boolean write) {
            throw new UnsupportedOperationException("stub");
        }

        @Override public boolean isClosed() { return false; }
        @Override public void close() {}
    }

    private static final class ForeignTexture extends com.mojang.renderpearl.backend.common.BaseGpuTexture {
        ForeignTexture() {
            super(GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST,
                    "foreign", GpuFormat.RGBA8_UNORM, 16, 16, 1, 1);
        }

        @Override public boolean isClosed() { return false; }
        @Override public void close() {}
    }
}
