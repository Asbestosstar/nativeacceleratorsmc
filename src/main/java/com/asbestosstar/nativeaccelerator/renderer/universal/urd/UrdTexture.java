package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

import java.nio.ByteBuffer;

public interface UrdTexture extends UrdResource {
    int width();
    int height();
    void readback(CommandReadbackTarget target);

    interface CommandReadbackTarget {
        void accept(ByteBuffer pixels);
    }
}
