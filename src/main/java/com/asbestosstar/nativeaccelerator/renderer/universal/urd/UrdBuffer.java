package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

import java.nio.ByteBuffer;

public interface UrdBuffer extends UrdResource {
    long size();
    void upload(long offset, ByteBuffer data);
}
