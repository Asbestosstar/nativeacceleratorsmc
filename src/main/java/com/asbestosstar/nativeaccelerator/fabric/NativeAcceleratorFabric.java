package com.asbestosstar.nativeaccelerator.fabric;

import com.asbestosstar.nativeaccelerator.NativeAccelerator;
import net.fabricmc.api.ModInitializer;

public final class NativeAcceleratorFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        NativeAccelerator.initialize();
    }
}
