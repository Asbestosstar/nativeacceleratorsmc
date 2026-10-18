package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderModule;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderReflection;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderSourceSpec;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.GlslProfile;
import com.asbestosstar.nativeaccelerator.renderer.universal.urd.InMemoryUrdBackend;
import com.asbestosstar.nativeaccelerator.renderer.universal.urd.UrdValidationException;

import java.util.List;

public final class UrdResourceLifetimeTest {
    public static void main(String[] args) {
        var device = new InMemoryUrdBackend().createDevice();
        var buffer = device.createBuffer(64);
        if (buffer.size() != 64) throw new AssertionError("buffer size");
        buffer.close();
        buffer.close();
        try {
            buffer.size();
            throw new AssertionError("closed buffer usable");
        } catch (UrdValidationException expected) {
        }
        var source = new ShaderSourceSpec("void main(){}", "test", "460", GlslProfile.VULKAN, "vertex");
        var module = new ShaderModule(source, java.nio.ByteBuffer.allocateDirect(4),
                new ShaderReflection(List.of(), List.of(), List.of(), 0), List.of());
        var pipeline = device.createPipeline(module, module);
        pipeline.close();
        device.close();
        device.close();
        try {
            device.createBuffer(1);
            throw new AssertionError("closed device usable");
        } catch (UrdValidationException expected) {
        }
        System.out.println("UrdResourceLifetimeTest: PASS");
    }
}
