package com.asbestosstar.nativeaccelerator.neoforge;

import com.asbestosstar.nativeaccelerator.NativeAccelerator;
import net.neoforged.fml.common.Mod;

/** Thin NeoForge entrypoint. Loader-specific logic intentionally stays out of the core. */
@Mod(NativeAccelerator.MOD_ID)
public final class NativeAcceleratorNeoForge {
    public NativeAcceleratorNeoForge() {
        NativeAccelerator.initialize();
    }
}
