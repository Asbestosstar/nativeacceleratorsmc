package com.asbestosstar.nativeaccelerator.forge;

import com.asbestosstar.nativeaccelerator.NativeAccelerator;
import net.minecraftforge.fml.common.Mod;

/** Thin Forge entrypoint. Loader-specific logic intentionally stays out of the core. */
@Mod(NativeAccelerator.MOD_ID)
public final class NativeAcceleratorForge {
    public NativeAcceleratorForge() {
        NativeAccelerator.initialize();
    }
}
