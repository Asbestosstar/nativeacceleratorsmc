package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

/** A deterministic validation failure in the backend-neutral renderer contract. */
public final class UrdValidationException extends IllegalStateException {
    public UrdValidationException(String message) {
        super(message);
    }
}
