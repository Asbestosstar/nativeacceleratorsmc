package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendEquation;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
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
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
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
import org.lwjgl.vulkan.VkSubpassDescription;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;

/**
 * Stateless factory for Vulkan pipeline compilation, called only by
 * {@link NativeVulkanRenderPipeline}.
 *
 * <p>Every public method in this class is responsible for exactly one Vulkan object. Callers
 * are responsible for destroying any partially-created objects when a later step fails; this
 * class does not perform cleanup on failure because it does not know which earlier handles are
 * live.</p>
 *
 * <p>All allocations go through LWJGL {@link MemoryStack} within each method scope so no
 * heap allocation survives the call.</p>
 *
 * <h3>Descriptor set layout mapping</h3>
 * RenderPearl uniforms carry a {@link UniformType}:
 * <ul>
 *   <li>{@code COMBINED_IMAGE_SAMPLER} → {@code VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER}</li>
 *   <li>{@code UNIFORM_BUFFER} → {@code VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER}</li>
 *   <li>{@code TEXEL_BUFFER} → {@code VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER}</li>
 * </ul>
 * Each uniform occupies one binding, one descriptor, visible to both shader stages.
 *
 * <h3>RenderPass synthesis</h3>
 * The compatible pass uses {@code LOAD_OP_LOAD}/{@code STORE_OP_STORE} for every attachment
 * so it can attach to an already-populated framebuffer, matching how RenderPearl issues draw
 * calls into an existing swapchain/offscreen image.
 */
final class NativeVulkanPipelineCompiler {

    private NativeVulkanPipelineCompiler() {}

    // -----------------------------------------------------------------------------------------
    // Descriptor set layouts
    // -----------------------------------------------------------------------------------------

    /** Creates one descriptor set layout at set 0 with bindings matching flattened uniform indices. */
    static long[] createDescriptorSetLayouts(VkDevice device,
                                              List<BindGroupLayout.UniformDescription> uniforms) {
        if (uniforms.isEmpty()) return new long[0];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(uniforms.size(), stack);
            for (int i = 0; i < uniforms.size(); i++) {
                BindGroupLayout.UniformDescription uniform = uniforms.get(i);
                bindings.get(i)
                        .binding(i)
                        .descriptorType(descriptorType(uniform.type()))
                        .descriptorCount(1)
                        .stageFlags(VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT)
                        .pImmutableSamplers(null);
            }
            VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings);
            LongBuffer out = stack.mallocLong(1);
            int status = VK10.vkCreateDescriptorSetLayout(device, info, null, out);
            if (status != VK10.VK_SUCCESS) {
                throw new UnsupportedOperationException("vkCreateDescriptorSetLayout failed with " + status);
            }
            return new long[] { out.get(0) };
        }
    }


    // -----------------------------------------------------------------------------------------
    // Pipeline layout
    // -----------------------------------------------------------------------------------------

    /**
     * Creates a {@code VkPipelineLayout} from the given descriptor set layouts and an optional
     * push-constant range covering both shader stages.
     *
     * @param setLayouts       descriptor set layout handles (may be empty array)
     * @param pushConstantSize byte size of the push-constant block; 0 means no push constants
     * @return newly-created pipeline layout handle; caller owns and must destroy it
     */
    static long createPipelineLayout(VkDevice device, long[] setLayouts, int pushConstantSize) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer setLayoutBuf = null;
            if (setLayouts.length > 0) {
                setLayoutBuf = stack.mallocLong(setLayouts.length);
                for (long h : setLayouts) setLayoutBuf.put(h);
                setLayoutBuf.flip();
            }

            VkPipelineLayoutCreateInfo.Buffer pushRangeBuf = null;
            VkPushConstantRange.Buffer pushRange = null;
            if (pushConstantSize > 0) {
                pushRange = VkPushConstantRange.calloc(1, stack)
                        .stageFlags(VK10.VK_SHADER_STAGE_VERTEX_BIT
                                | VK10.VK_SHADER_STAGE_FRAGMENT_BIT)
                        .offset(0)
                        .size(pushConstantSize);
            }

            VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(setLayoutBuf)
                    .pPushConstantRanges(pushRange);

            LongBuffer out = stack.mallocLong(1);
            int status = VK10.vkCreatePipelineLayout(device, info, null, out);
            if (status != VK10.VK_SUCCESS) {
                throw new UnsupportedOperationException(
                        "vkCreatePipelineLayout failed with " + status);
            }
            return out.get(0);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Render pass
    // -----------------------------------------------------------------------------------------

    /**
     * Synthesizes a minimal compatible {@code VkRenderPass} from the declared color targets and
     * depth/stencil state.
     *
     * <p>Color and depth attachments enter and leave in their attachment-optimal layouts. Load/store
     * preserves RenderPearl attachment contents; the command encoder must explicitly transition
     * each image before beginning the pass.</p>
     *
     * @return newly-created render-pass handle; caller owns and must destroy it
     */
    static long createRenderPass(VkDevice device,
                                  List<ColorTargetState> colorTargets,
                                  DepthStencilState depthStencilState) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int colorCount = colorTargets.size();
            boolean hasDepth = depthStencilState != null;
            if (colorCount == 0) {
                throw new UnsupportedOperationException(
                        "Native Vulkan currently requires at least one color target");
            }
            int totalAttachments = colorCount + (hasDepth ? 1 : 0);

            VkAttachmentDescription.Buffer attachments =
                    VkAttachmentDescription.calloc(totalAttachments, stack);

            // Color attachments
            for (int i = 0; i < colorCount; i++) {
                ColorTargetState target = colorTargets.get(i);
                if (target == null || target.format() == null) {
                    throw new UnsupportedOperationException(
                            "Native Vulkan color target " + i + " requires an explicit format");
                }
                int fmt = NativeVulkanFormat.vkFormat(target.format());
                attachments.get(i)
                        .format(fmt)
                        .samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(VK10.VK_ATTACHMENT_LOAD_OP_LOAD)
                        .storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE)
                        .stencilLoadOp(VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                        .stencilStoreOp(VK10.VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .finalLayout(VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            }

            // Depth attachment
            if (hasDepth) {
                attachments.get(colorCount)
                        .format(VK10.VK_FORMAT_D32_SFLOAT)
                        .samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(VK10.VK_ATTACHMENT_LOAD_OP_LOAD)
                        .storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE)
                        .stencilLoadOp(VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                        .stencilStoreOp(VK10.VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                        .finalLayout(VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            }

            // Subpass color references
            VkAttachmentReference.Buffer colorRefs = null;
            if (colorCount > 0) {
                colorRefs = VkAttachmentReference.calloc(colorCount, stack);
                for (int i = 0; i < colorCount; i++) {
                    colorRefs.get(i)
                            .attachment(i)
                            .layout(VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                }
            }

            // Subpass depth reference
            VkAttachmentReference depthRef = null;
            if (hasDepth) {
                depthRef = VkAttachmentReference.calloc(stack)
                        .attachment(colorCount)
                        .layout(VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            }

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK10.VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(colorCount)
                    .pColorAttachments(colorRefs)
                    .pDepthStencilAttachment(depthRef);

            VkRenderPassCreateInfo rpInfo = VkRenderPassCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(attachments)
                    .pSubpasses(subpass);

            LongBuffer out = stack.mallocLong(1);
            int status = VK10.vkCreateRenderPass(device, rpInfo, null, out);
            if (status != VK10.VK_SUCCESS) {
                throw new UnsupportedOperationException(
                        "vkCreateRenderPass failed with " + status);
            }
            return out.get(0);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Graphics pipeline
    // -----------------------------------------------------------------------------------------

    /**
     * Creates the {@code VkPipeline}.
     *
     * <p>Two {@code VkShaderModule} objects are created and destroyed within this call; the
     * driver retains the SPIR-V through the pipeline object itself.</p>
     *
     * @return newly-created pipeline handle; caller owns and must destroy it
     */
    static long createGraphicsPipeline(VkDevice device,
                                        long pipelineLayout,
                                        long renderPass,
                                        BackendRenderPipeline.CreateInfo createInfo,
                                        BackendRenderPipeline.CreateInfo.Shader vsDef,
                                        BackendRenderPipeline.CreateInfo.Shader fsDef) {
        long vsModule = VK10.VK_NULL_HANDLE;
        long fsModule = VK10.VK_NULL_HANDLE;
        try {
            vsModule = createShaderModule(device, vsDef.module());
            fsModule = createShaderModule(device, fsDef.module());
            return buildPipeline(device, pipelineLayout, renderPass,
                    createInfo, vsModule, vsDef.entryPoint(),
                    fsModule, fsDef.entryPoint());
        } finally {
            if (vsModule != VK10.VK_NULL_HANDLE) {
                try { VK10.vkDestroyShaderModule(device, vsModule, null); }
                catch (Throwable ignored) {}
            }
            if (fsModule != VK10.VK_NULL_HANDLE) {
                try { VK10.vkDestroyShaderModule(device, fsModule, null); }
                catch (Throwable ignored) {}
            }
        }
    }

    // ---- Shader module ---------------------------------------------------------------------- //

    private static long createShaderModule(VkDevice device, SpvModule module) {
        if (module.type() != ShaderType.VERTEX && module.type() != ShaderType.FRAGMENT) {
            throw new UnsupportedOperationException("Native Vulkan graphics pipelines only support vertex/fragment stages");
        }
        ByteBuffer spv = module.spv();
        if (spv == null || !spv.hasRemaining()) {
            throw new UnsupportedOperationException(
                    "SpvModule for " + module.type() + " returned no SPIR-V bytes");
        }
        if (spv.remaining() % 4 != 0) {
            throw new UnsupportedOperationException(
                    "SPIR-V byte count " + spv.remaining() + " is not a multiple of 4");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(spv.duplicate());
            LongBuffer out = stack.mallocLong(1);
            int status = VK10.vkCreateShaderModule(device, info, null, out);
            if (status != VK10.VK_SUCCESS) {
                throw new UnsupportedOperationException(
                        "vkCreateShaderModule failed with " + status
                                + " for stage " + module.type());
            }
            return out.get(0);
        }
    }

    // ---- Full pipeline ---------------------------------------------------------------------- //

    private static long buildPipeline(VkDevice device,
                                       long pipelineLayout,
                                       long renderPass,
                                       BackendRenderPipeline.CreateInfo createInfo,
                                       long vsModule, String vsEntry,
                                       long fsModule, String fsEntry) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Shader stages
            VkPipelineShaderStageCreateInfo.Buffer stages =
                    VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK10.VK_SHADER_STAGE_VERTEX_BIT)
                    .module(vsModule)
                    .pName(stack.UTF8(vsEntry));
            stages.get(1)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK10.VK_SHADER_STAGE_FRAGMENT_BIT)
                    .module(fsModule)
                    .pName(stack.UTF8(fsEntry));

            // Vertex input
            List<BackendRenderPipeline.CreateInfo.VertexBuffer> vbs = createInfo.vertexBuffers();
            List<BackendRenderPipeline.CreateInfo.AttribBinding> attrs = createInfo.attribBindings();
            VkVertexInputBindingDescription.Buffer bindingDescs = null;
            VkVertexInputAttributeDescription.Buffer attrDescs = null;
            if (!vbs.isEmpty()) {
                bindingDescs = VkVertexInputBindingDescription.calloc(vbs.size(), stack);
                for (int i = 0; i < vbs.size(); i++) {
                    BackendRenderPipeline.CreateInfo.VertexBuffer vb = vbs.get(i);
                    if (vb.stepRate() != 0 && vb.stepRate() != 1) {
                        throw new UnsupportedOperationException(
                                "Native Vulkan vertex step rate requires vertex-attribute-divisor support: "
                                        + vb.stepRate());
                    }
                    bindingDescs.get(i)
                            .binding(vb.bufferSlot())
                            .stride(vb.stride())
                            .inputRate(vb.stepRate() == 0
                                    ? VK10.VK_VERTEX_INPUT_RATE_VERTEX
                                    : VK10.VK_VERTEX_INPUT_RATE_INSTANCE);
                }
            }
            if (!attrs.isEmpty()) {
                attrDescs = VkVertexInputAttributeDescription.calloc(attrs.size(), stack);
                for (int i = 0; i < attrs.size(); i++) {
                    BackendRenderPipeline.CreateInfo.AttribBinding a = attrs.get(i);
                    attrDescs.get(i)
                            .location(a.location())
                            .binding(a.bufferSlot())
                            .format(NativeVulkanFormat.vkFormat(a.format()))
                            .offset(a.offset());
                }
            }
            VkPipelineVertexInputStateCreateInfo vertexInput =
                    VkPipelineVertexInputStateCreateInfo.calloc(stack)
                            .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                            .pVertexBindingDescriptions(bindingDescs)
                            .pVertexAttributeDescriptions(attrDescs);

            // Input assembly
            VkPipelineInputAssemblyStateCreateInfo inputAssembly =
                    VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                            .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                            .topology(vkTopology(createInfo.primitiveTopology()))
                            .primitiveRestartEnable(false);

            // Viewport / scissor — dynamic
            VkPipelineViewportStateCreateInfo viewportState =
                    VkPipelineViewportStateCreateInfo.calloc(stack)
                            .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                            .viewportCount(1)
                            .scissorCount(1);

            // Rasterizer
            DepthStencilState ds = createInfo.depthStencilState();
            VkPipelineRasterizationStateCreateInfo rasterizer =
                    VkPipelineRasterizationStateCreateInfo.calloc(stack)
                            .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                            .depthClampEnable(false)
                            .rasterizerDiscardEnable(false)
                            .polygonMode(vkPolygonMode(createInfo.polygonMode()))
                            .lineWidth(1.0f)
                            .cullMode(createInfo.cull()
                                    ? VK10.VK_CULL_MODE_BACK_BIT
                                    : VK10.VK_CULL_MODE_NONE)
                            .frontFace(VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE)
                            .depthBiasEnable(ds != null
                                    && (ds.depthBiasConstant() != 0f || ds.depthBiasScaleFactor() != 0f))
                            .depthBiasConstantFactor(ds != null ? ds.depthBiasConstant() : 0f)
                            .depthBiasClamp(0f)
                            .depthBiasSlopeFactor(ds != null ? ds.depthBiasScaleFactor() : 0f);

            // Multisample
            VkPipelineMultisampleStateCreateInfo multisampling =
                    VkPipelineMultisampleStateCreateInfo.calloc(stack)
                            .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                            .sampleShadingEnable(false)
                            .rasterizationSamples(VK10.VK_SAMPLE_COUNT_1_BIT);

            // Depth / stencil
            VkPipelineDepthStencilStateCreateInfo depthStencil =
                    VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                            .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                            .depthTestEnable(ds != null)
                            .depthWriteEnable(ds != null && ds.writeDepth())
                            .depthCompareOp(ds != null ? vkCompareOp(ds.depthTest())
                                    : VK10.VK_COMPARE_OP_ALWAYS)
                            .depthBoundsTestEnable(false)
                            .stencilTestEnable(false);

            // Color blend
            List<ColorTargetState> colorTargets = createInfo.colorTargetStates();
            int colorCount = colorTargets.size();
            VkPipelineColorBlendAttachmentState.Buffer blendAttachments =
                    VkPipelineColorBlendAttachmentState.calloc(Math.max(colorCount, 1), stack);
            for (int i = 0; i < colorCount; i++) {
                ColorTargetState ct = colorTargets.get(i);
                if (ct == null) {
                    blendAttachments.get(i)
                            .blendEnable(false)
                            .colorWriteMask(VK10.VK_COLOR_COMPONENT_R_BIT
                                    | VK10.VK_COLOR_COMPONENT_G_BIT
                                    | VK10.VK_COLOR_COMPONENT_B_BIT
                                    | VK10.VK_COLOR_COMPONENT_A_BIT);
                    continue;
                }
                int writeMask = rpWriteMaskToVk(ct.writeMask());
                if (ct.blendFunction().isPresent()) {
                    applyBlend(blendAttachments.get(i), ct.blendFunction().get(), writeMask);
                } else {
                    blendAttachments.get(i)
                            .blendEnable(false)
                            .colorWriteMask(writeMask);
                }
            }
            if (colorCount == 0) {
                // Pipeline with no color outputs (depth-only); supply one disabled attachment.
                blendAttachments.get(0).blendEnable(false).colorWriteMask(0);
            }
            VkPipelineColorBlendStateCreateInfo colorBlending =
                    VkPipelineColorBlendStateCreateInfo.calloc(stack)
                            .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                            .logicOpEnable(false)
                            .logicOp(VK10.VK_LOGIC_OP_COPY)
                            .pAttachments(blendAttachments);

            // Dynamic state: viewport and scissor are mandatory for a RenderPearl backend.
            int[] dynamicStates = {
                    VK10.VK_DYNAMIC_STATE_VIEWPORT,
                    VK10.VK_DYNAMIC_STATE_SCISSOR
            };
            VkPipelineDynamicStateCreateInfo dynamicState =
                    VkPipelineDynamicStateCreateInfo.calloc(stack)
                            .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                            .pDynamicStates(stack.ints(dynamicStates));

            // Assemble
            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo =
                    VkGraphicsPipelineCreateInfo.calloc(1, stack)
                            .sType(VK10.VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                            .pStages(stages)
                            .pVertexInputState(vertexInput)
                            .pInputAssemblyState(inputAssembly)
                            .pViewportState(viewportState)
                            .pRasterizationState(rasterizer)
                            .pMultisampleState(multisampling)
                            .pDepthStencilState(depthStencil)
                            .pColorBlendState(colorBlending)
                            .pDynamicState(dynamicState)
                            .layout(pipelineLayout)
                            .renderPass(renderPass)
                            .subpass(0)
                            .basePipelineHandle(VK10.VK_NULL_HANDLE)
                            .basePipelineIndex(-1);

            LongBuffer out = stack.mallocLong(1);
            int status = VK10.vkCreateGraphicsPipelines(
                    device, VK10.VK_NULL_HANDLE, pipelineInfo, null, out);
            if (status != VK10.VK_SUCCESS) {
                throw new UnsupportedOperationException(
                        "vkCreateGraphicsPipelines failed with " + status
                                + " for pipeline \"" + createInfo.name() + "\"");
            }
            return out.get(0);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Enum conversions
    // -----------------------------------------------------------------------------------------

    static int descriptorType(UniformType type) {
        if (type == null) {
            throw new UnsupportedOperationException(
                    "NativeVulkanPipelineCompiler: null UniformType is not supported");
        }
        return switch (type) {
            case COMBINED_IMAGE_SAMPLER -> VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            case UNIFORM_BUFFER         -> VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case TEXEL_BUFFER           -> VK10.VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER;
        };
    }

    static int vkTopology(PrimitiveTopology topology) {
        if (topology == null) {
            throw new UnsupportedOperationException(
                    "NativeVulkanPipelineCompiler: null PrimitiveTopology");
        }
        return switch (topology) {
            case POINTS        -> VK10.VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
            case LINES,
                 DEBUG_LINES   -> VK10.VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
            case DEBUG_LINE_STRIP -> VK10.VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
            case TRIANGLES,
                 QUADS         -> VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            case TRIANGLE_STRIP -> VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
            case TRIANGLE_FAN  -> VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN;
        };
    }

    static int vkPolygonMode(PolygonMode mode) {
        if (mode == null) return VK10.VK_POLYGON_MODE_FILL;
        return switch (mode) {
            case FILL      -> VK10.VK_POLYGON_MODE_FILL;
            case WIREFRAME -> VK10.VK_POLYGON_MODE_LINE;
        };
    }

    static int vkCompareOp(CompareOp op) {
        if (op == null) return VK10.VK_COMPARE_OP_ALWAYS;
        return switch (op) {
            case ALWAYS_PASS          -> VK10.VK_COMPARE_OP_ALWAYS;
            case NEVER_PASS           -> VK10.VK_COMPARE_OP_NEVER;
            case LESS_THAN            -> VK10.VK_COMPARE_OP_LESS;
            case LESS_THAN_OR_EQUAL   -> VK10.VK_COMPARE_OP_LESS_OR_EQUAL;
            case EQUAL                -> VK10.VK_COMPARE_OP_EQUAL;
            case NOT_EQUAL            -> VK10.VK_COMPARE_OP_NOT_EQUAL;
            case GREATER_THAN_OR_EQUAL -> VK10.VK_COMPARE_OP_GREATER_OR_EQUAL;
            case GREATER_THAN         -> VK10.VK_COMPARE_OP_GREATER;
        };
    }

    static int vkBlendFactor(BlendFactor f) {
        if (f == null) throw new UnsupportedOperationException(
                "NativeVulkanPipelineCompiler: null BlendFactor");
        return switch (f) {
            case ZERO                    -> VK10.VK_BLEND_FACTOR_ZERO;
            case ONE                     -> VK10.VK_BLEND_FACTOR_ONE;
            case SRC_COLOR               -> VK10.VK_BLEND_FACTOR_SRC_COLOR;
            case ONE_MINUS_SRC_COLOR     -> VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case DST_COLOR               -> VK10.VK_BLEND_FACTOR_DST_COLOR;
            case ONE_MINUS_DST_COLOR     -> VK10.VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case SRC_ALPHA               -> VK10.VK_BLEND_FACTOR_SRC_ALPHA;
            case ONE_MINUS_SRC_ALPHA     -> VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case DST_ALPHA               -> VK10.VK_BLEND_FACTOR_DST_ALPHA;
            case ONE_MINUS_DST_ALPHA     -> VK10.VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case CONSTANT_COLOR          -> VK10.VK_BLEND_FACTOR_CONSTANT_COLOR;
            case ONE_MINUS_CONSTANT_COLOR -> VK10.VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
            case CONSTANT_ALPHA          -> VK10.VK_BLEND_FACTOR_CONSTANT_ALPHA;
            case ONE_MINUS_CONSTANT_ALPHA -> VK10.VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA;
            case SRC_ALPHA_SATURATE      -> VK10.VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
        };
    }

    static int vkBlendOp(BlendOp op) {
        if (op == null) throw new UnsupportedOperationException(
                "NativeVulkanPipelineCompiler: null BlendOp");
        return switch (op) {
            case ADD              -> VK10.VK_BLEND_OP_ADD;
            case SUBTRACT         -> VK10.VK_BLEND_OP_SUBTRACT;
            case REVERSE_SUBTRACT -> VK10.VK_BLEND_OP_REVERSE_SUBTRACT;
            case MIN              -> VK10.VK_BLEND_OP_MIN;
            case MAX              -> VK10.VK_BLEND_OP_MAX;
        };
    }

    // -----------------------------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------------------------

    private static void applyBlend(VkPipelineColorBlendAttachmentState state,
                                    com.mojang.renderpearl.api.pipeline.BlendFunction blend,
                                    int writeMask) {
        BlendEquation color = blend.color();
        BlendEquation alpha = blend.alpha();
        state.blendEnable(true)
                .srcColorBlendFactor(vkBlendFactor(color.sourceFactor()))
                .dstColorBlendFactor(vkBlendFactor(color.destFactor()))
                .colorBlendOp(vkBlendOp(color.op()))
                .srcAlphaBlendFactor(vkBlendFactor(alpha.sourceFactor()))
                .dstAlphaBlendFactor(vkBlendFactor(alpha.destFactor()))
                .alphaBlendOp(vkBlendOp(alpha.op()))
                .colorWriteMask(writeMask);
    }

    /**
     * Converts RenderPearl's 4-bit write mask (R=1, G=2, B=4, A=8 based on Vulkan convention
     * used by ColorTargetState) to Vulkan {@code VkColorComponentFlags}.
     */
    private static int rpWriteMaskToVk(int mask) {
        int flags = 0;
        if ((mask & ColorTargetState.WRITE_RED)   != 0) flags |= VK10.VK_COLOR_COMPONENT_R_BIT;
        if ((mask & ColorTargetState.WRITE_GREEN)  != 0) flags |= VK10.VK_COLOR_COMPONENT_G_BIT;
        if ((mask & ColorTargetState.WRITE_BLUE)   != 0) flags |= VK10.VK_COLOR_COMPONENT_B_BIT;
        if ((mask & ColorTargetState.WRITE_ALPHA)  != 0) flags |= VK10.VK_COLOR_COMPONENT_A_BIT;
        return flags;
    }
}
