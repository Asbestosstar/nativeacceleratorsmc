package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendEquation;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LWJGL Vulkan graphics pipeline compiled from a RenderPearl {@link BackendRenderPipeline.CreateInfo}.
 *
 * <p>This class compiles:
 * <ul>
 *   <li>Two {@code VkShaderModule} objects (vertex + fragment) that are destroyed immediately
 *       after pipeline creation — the driver retains the SPIR-V through the pipeline object.</li>
 *   <li>A {@code VkDescriptorSetLayout} per uniform binding group.</li>
 *   <li>A {@code VkPipelineLayout} that aggregates the descriptor set layouts and any push-constant
 *       range.</li>
 *   <li>A {@code VkRenderPass} compatible with the declared color targets and depth/stencil state;
 *       the pass uses {@code LOAD_OP_LOAD}/{@code STORE_OP_STORE} to match RenderPearl semantics.</li>
 *   <li>The final {@code VkPipeline} via {@code vkCreateGraphicsPipelines}.</li>
 * </ul>
 *
 * <p>Reflection ({@link SpvModule#reflect()}) is attempted for each shader module. If reflection
 * is unavailable or throws, the pipeline falls back to an undecorated compile; the backend must
 * still function because the game manages descriptor bindings through its own Vulkan path.</p>
 *
 * <p>Every Vulkan call that fails is immediately translated to an
 * {@link UnsupportedOperationException} to keep the backend fail-closed: a broken pipeline must
 * never silently produce a null handle that later corrupts a command buffer.</p>
 *
 * <p>This class is not thread-safe. It must be constructed on the thread that owns the
 * {@link NativeVulkanDeviceBootstrap}.</p>
 *
 * <p><strong>Backend / readiness gate:</strong> this class is not enabled or registered anywhere.
 * {@link NativeVulkanDevice#compilePipeline} still rejects all calls; only
 * {@link NativeVulkanPipelineCompiler} exposes this class to the device once the complete-adapter
 * gate passes.</p>
 */
public final class NativeVulkanRenderPipeline implements BackendRenderPipeline {

    // ---- Vulkan handles --------------------------------------------------------------------- //

    private final NativeVulkanDeviceState state;
    private final VkDevice device;

    /** Live {@code VkDescriptorSetLayout[]} parallel to {@code createInfo.uniforms()}. May be empty. */
    private final long[] descriptorSetLayouts;
    /** Live {@code VkPipelineLayout}. */
    private final long pipelineLayout;
    /** Compatible {@code VkRenderPass} synthesized from the color/depth state. */
    private final long renderPass;
    /** The compiled {@code VkPipeline}. */
    private final long pipeline;

    // ---- Metadata for command-encoding ------------------------------------------------------ //

    private final String name;
    private final int pushConstantSize;
    private final List<BindGroupLayout.UniformDescription> uniforms;
    private final boolean triangleFan;
    private final AtomicBoolean closed = new AtomicBoolean();

    // ---- Static layout metadata (written during compilation) -------------------------------- //

    /** Cached vertex binding descriptions, stored for re-use in command-encoding diagnostics. */
    private final int vertexBindingCount;
    /** Number of color targets declared in the CreateInfo; used by RenderPass selection. */
    private final int colorTargetCount;
    /** True when a depth/stencil attachment was declared. */
    private final boolean hasDepthStencil;
    private final List<GpuFormat> colorTargetFormats;
    private final GpuFormat depthFormat;

    // -----------------------------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------------------------

    /**
     * Compiles a complete Vulkan graphics pipeline.
     *
     * @param bootstrap live Vulkan bootstrap — must remain open for the life of this pipeline
     * @param state     device lifecycle state shared with all resources
     * @param createInfo RenderPearl pipeline descriptor — may not be null
     * @throws UnsupportedOperationException if any Vulkan call fails or CreateInfo is incomplete
     */
    public NativeVulkanRenderPipeline(NativeVulkanDeviceBootstrap bootstrap,
                                      NativeVulkanDeviceState state,
                                      BackendRenderPipeline.CreateInfo createInfo) {
        this.state = java.util.Objects.requireNonNull(state, "state");
        this.device = java.util.Objects.requireNonNull(bootstrap, "bootstrap").device();
        java.util.Objects.requireNonNull(createInfo, "createInfo");

        this.name = String.valueOf(createInfo.name());
        this.pushConstantSize = createInfo.pushConstantsSize();
        this.uniforms = List.copyOf(createInfo.uniforms());
        this.triangleFan = createInfo.primitiveTopology() == PrimitiveTopology.TRIANGLE_FAN;
        this.colorTargetCount = createInfo.colorTargetStates().size();
        this.colorTargetFormats = createInfo.colorTargetStates().stream()
                .map(target -> target == null ? null : target.format()).toList();
        this.hasDepthStencil = createInfo.depthStencilState() != null;
        this.depthFormat = createInfo.depthStencilState() == null ? null : GpuFormat.D32_FLOAT;
        this.vertexBindingCount = createInfo.vertexBuffers().size();

        BackendRenderPipeline.CreateInfo.Shader vsDef = null;
        BackendRenderPipeline.CreateInfo.Shader fsDef = null;
        for (BackendRenderPipeline.CreateInfo.Shader s : createInfo.shaders()) {
            if (s.module().type() == ShaderType.VERTEX) vsDef = s;
            else if (s.module().type() == ShaderType.FRAGMENT) fsDef = s;
        }
        if (vsDef == null || fsDef == null) {
            throw new UnsupportedOperationException(
                    "NativeVulkanRenderPipeline: CreateInfo \"" + name
                            + "\" requires both a vertex and a fragment shader");
        }

        // Use try/finally blocks ordered from innermost to outermost so no partially-allocated
        // state escapes. Handles that are consumed by a later step are zeroed immediately.
        long[] setLayouts = new long[0];
        long layout = VK10.VK_NULL_HANDLE;
        long pass = VK10.VK_NULL_HANDLE;
        long pipe = VK10.VK_NULL_HANDLE;

        try {
            setLayouts = NativeVulkanPipelineCompiler.createDescriptorSetLayouts(device, uniforms);
            layout = NativeVulkanPipelineCompiler.createPipelineLayout(device, setLayouts, pushConstantSize);
            pass = NativeVulkanPipelineCompiler.createRenderPass(
                    device, createInfo.colorTargetStates(), createInfo.depthStencilState());
            pipe = NativeVulkanPipelineCompiler.createGraphicsPipeline(
                    device, layout, pass, createInfo, vsDef, fsDef);
        } catch (Throwable failure) {
            destroyPartial(device, setLayouts, layout, pass, pipe);
            throw failure;
        }

        this.descriptorSetLayouts = setLayouts;
        this.pipelineLayout = layout;
        this.renderPass = pass;
        this.pipeline = pipe;
    }

    // -----------------------------------------------------------------------------------------
    // Public accessors (for command encoding, not yet wired to NativeVulkanDevice)
    // -----------------------------------------------------------------------------------------

    /** Returns the compiled {@code VkPipeline} handle. */
    public long pipelineHandle() {
        requireOpen();
        return pipeline;
    }

    /** Returns the {@code VkPipelineLayout} handle. */
    public long pipelineLayoutHandle() {
        requireOpen();
        return pipelineLayout;
    }

    /** Returns the compatible {@code VkRenderPass} handle. */
    public long renderPassHandle() {
        requireOpen();
        return renderPass;
    }

    /** Returns a defensive copy of the descriptor set layout handles. */
    public long[] descriptorSetLayoutHandles() {
        requireOpen();
        return descriptorSetLayouts.clone();
    }

    public String name() { return name; }
    public int pushConstantSize() { return pushConstantSize; }
    public List<BindGroupLayout.UniformDescription> uniforms() { return uniforms; }
    public boolean isTriangleFan() { return triangleFan; }
    public int colorTargetCount() { return colorTargetCount; }
    public boolean hasDepthStencil() { return hasDepthStencil; }
    GpuFormat colorTargetFormat(int index) { return colorTargetFormats.get(index); }
    GpuFormat depthFormat() { return depthFormat; }

    // -----------------------------------------------------------------------------------------
    // BackendRenderPipeline
    // -----------------------------------------------------------------------------------------

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        NativeVulkanDeviceState.Phase phase = state.phase();
        if (phase == NativeVulkanDeviceState.Phase.CLOSED) return;
        destroyPartial(device, descriptorSetLayouts, pipelineLayout, renderPass, pipeline);
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    private void requireOpen() {
        state.requireOpen();
        if (closed.get()) throw new IllegalStateException("NativeVulkanRenderPipeline is closed: " + name);
    }

    private static void destroyPartial(VkDevice device,
                                        long[] setLayouts,
                                        long layout,
                                        long pass,
                                        long pipe) {
        if (pipe != VK10.VK_NULL_HANDLE) {
            try { VK10.vkDestroyPipeline(device, pipe, null); } catch (Throwable ignored) {}
        }
        if (pass != VK10.VK_NULL_HANDLE) {
            try { VK10.vkDestroyRenderPass(device, pass, null); } catch (Throwable ignored) {}
        }
        if (layout != VK10.VK_NULL_HANDLE) {
            try { VK10.vkDestroyPipelineLayout(device, layout, null); } catch (Throwable ignored) {}
        }
        for (long sl : setLayouts) {
            if (sl != VK10.VK_NULL_HANDLE) {
                try { VK10.vkDestroyDescriptorSetLayout(device, sl, null); } catch (Throwable ignored) {}
            }
        }
    }
}
