package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderModule;

import java.nio.ByteBuffer;

public interface UrdDevice extends UrdResource {
    UrdCapabilities capabilities();
    UrdBuffer createBuffer(long size);
    UrdTexture createTexture(int width, int height);
    UrdPipeline createPipeline(ShaderModule vertex, ShaderModule fragment);
    UrdCommandList createCommandList();

    /**
     * Executes a synchronized readback owned by this device. The operation is deliberately on the
     * device rather than on a texture: command encoders and completion fences own the transfer.
     */
    ByteBuffer readback(UrdTexture texture);
}
