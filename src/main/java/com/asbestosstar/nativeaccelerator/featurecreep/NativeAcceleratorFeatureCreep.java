package com.asbestosstar.nativeaccelerator.featurecreep;

import com.asbestosstar.nativeaccelerator.NativeAccelerator;

/** FeatureCreep flat-loader entrypoint selected by fcflat.properties. */
public final class NativeAcceleratorFeatureCreep {
    private NativeAcceleratorFeatureCreep() {}

    public static void main(String[] args) {
        NativeAccelerator.initialize();
    }
}
