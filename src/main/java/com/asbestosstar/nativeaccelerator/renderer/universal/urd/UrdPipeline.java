package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderModule;

public interface UrdPipeline extends UrdResource {
    ShaderModule vertexShader();
    ShaderModule fragmentShader();
}
