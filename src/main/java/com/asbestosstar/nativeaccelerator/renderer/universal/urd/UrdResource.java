package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

/** Common ownership contract for URD resources. */
public interface UrdResource extends AutoCloseable {
    boolean isClosed();

    @Override
    void close();

    default void requireOpen() {
        if (isClosed()) throw new UrdValidationException("resource is closed");
    }
}
