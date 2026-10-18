package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

/** Format routing shared by discovery, runtime dispatch, and plain-Java tests. */
public final class ShaderpackPreparationRouter {
    private ShaderpackPreparationRouter() {
    }

    public static ShaderpackPreparationRoute route(ShaderpackDescriptor descriptor) {
        if (descriptor == null) throw new IllegalArgumentException("descriptor is null");
        return descriptor.conventionalProfile().format() == ShaderpackFormat.NATIVE_ACCELERATOR_MANIFEST
                ? ShaderpackPreparationRoute.NATIVE_ACCELERATOR_FIXTURE
                : ShaderpackPreparationRoute.CONVENTIONAL;
    }
}
