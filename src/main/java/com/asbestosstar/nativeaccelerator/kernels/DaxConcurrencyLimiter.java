package com.asbestosstar.nativeaccelerator.kernels;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;

import java.util.concurrent.Semaphore;

/** Bounds concurrent DAX submissions so SMT8 worker counts do not turn into hundreds of accelerator requests. */
final class DaxConcurrencyLimiter {
    private static final int MAX = NativeAcceleratorConfig.intValue("dax.maxConcurrent", 32, 1);
    private static final Semaphore PERMITS = new Semaphore(MAX, false);
    private DaxConcurrencyLimiter() {}
    static boolean tryEnter() { return PERMITS.tryAcquire(); }
    static void exit() { PERMITS.release(); }
    static int maximum() { return MAX; }
}
