package com.asbestosstar.nativeaccelerator.renderer.metal;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/** Synchronous resource staging helpers used by maps and small one-off uploads. */
final class MetalTransfers {
    private MetalTransfers() {}

    static void uploadBuffer(long device, long destination, long destinationOffset, ByteBuffer source) {
        ByteBuffer bytes = source.duplicate();
        int size = bytes.remaining();
        if (size == 0) return;

        Object create = null;
        Object src = null;
        Object dst = null;
        long transfer = 0L;
        long command = 0L;
        long copy = 0L;
        try {
            create = MetalInterop.calloc("SDL_GPUTransferBufferCreateInfo");
            MetalInterop.set(create, "usage", MetalInterop.sdl("SDL_GPU_TRANSFERBUFFERUSAGE_UPLOAD"));
            MetalInterop.set(create, "size", size);
            transfer = MetalInterop.sdlLong("SDL_CreateGPUTransferBuffer", device, create);
            require(transfer, "create upload transfer buffer");

            long mapped = ((Number)MetalInterop.sdlCall("SDL_MapGPUTransferBuffer", device, transfer, false)).longValue();
            require(mapped, "map upload transfer buffer");
            MemoryUtil.memByteBuffer(mapped, size).put(bytes);
            MetalInterop.sdlCall("SDL_UnmapGPUTransferBuffer", device, transfer);

            command = MetalInterop.sdlLong("SDL_AcquireGPUCommandBuffer", device);
            require(command, "acquire upload command buffer");
            copy = MetalInterop.sdlLong("SDL_BeginGPUCopyPass", command);
            require(copy, "begin upload copy pass");

            src = MetalInterop.calloc("SDL_GPUTransferBufferLocation");
            MetalInterop.set(src, "transfer_buffer", transfer);
            MetalInterop.set(src, "offset", 0);
            dst = MetalInterop.calloc("SDL_GPUBufferRegion");
            MetalInterop.set(dst, "buffer", destination);
            MetalInterop.set(dst, "offset", destinationOffset);
            MetalInterop.set(dst, "size", size);
            MetalInterop.sdlCall("SDL_UploadToGPUBuffer", copy, src, dst, false);
            MetalInterop.sdlCall("SDL_EndGPUCopyPass", copy);
            copy = 0L;
            submit(command);
            command = 0L;
        } finally {
            if (copy != 0L) safe("SDL_EndGPUCopyPass", copy);
            if (command != 0L) safe("SDL_CancelGPUCommandBuffer", command);
            if (transfer != 0L) safe("SDL_ReleaseGPUTransferBuffer", device, transfer);
            MetalInterop.free(dst);
            MetalInterop.free(src);
            MetalInterop.free(create);
        }
    }

    static void uploadTexture(long device, MetalGpuTexture texture, ByteBuffer source,
                              int mipLevel, int x, int y, int width, int height, int depthOrLayers) {
        ByteBuffer bytes = source.duplicate();
        int size = bytes.remaining();
        if (size == 0) return;

        Object create = null;
        Object src = null;
        Object dst = null;
        long transfer = 0L;
        long command = 0L;
        long copy = 0L;
        try {
            create = MetalInterop.calloc("SDL_GPUTransferBufferCreateInfo");
            MetalInterop.set(create, "usage", MetalInterop.sdl("SDL_GPU_TRANSFERBUFFERUSAGE_UPLOAD"));
            MetalInterop.set(create, "size", size);
            transfer = MetalInterop.sdlLong("SDL_CreateGPUTransferBuffer", device, create);
            require(transfer, "create texture upload transfer buffer");

            long mapped = ((Number)MetalInterop.sdlCall("SDL_MapGPUTransferBuffer", device, transfer, false)).longValue();
            require(mapped, "map texture upload transfer buffer");
            MemoryUtil.memByteBuffer(mapped, size).put(bytes);
            MetalInterop.sdlCall("SDL_UnmapGPUTransferBuffer", device, transfer);

            command = MetalInterop.sdlLong("SDL_AcquireGPUCommandBuffer", device);
            require(command, "acquire texture upload command buffer");
            copy = MetalInterop.sdlLong("SDL_BeginGPUCopyPass", command);
            require(copy, "begin texture upload copy pass");

            src = MetalInterop.calloc("SDL_GPUTextureTransferInfo");
            MetalInterop.set(src, "transfer_buffer", transfer);
            MetalInterop.set(src, "offset", 0);
            MetalInterop.set(src, "pixels_per_row", width);
            MetalInterop.set(src, "rows_per_layer", height);

            dst = MetalInterop.calloc("SDL_GPUTextureRegion");
            MetalInterop.set(dst, "texture", texture.handle());
            MetalInterop.set(dst, "mip_level", mipLevel);
            MetalInterop.set(dst, "layer", 0);
            MetalInterop.set(dst, "x", x);
            MetalInterop.set(dst, "y", y);
            MetalInterop.set(dst, "z", 0);
            MetalInterop.set(dst, "w", width);
            MetalInterop.set(dst, "h", height);
            MetalInterop.set(dst, "d", Math.max(1, depthOrLayers));

            MetalInterop.sdlCall("SDL_UploadToGPUTexture", copy, src, dst, false);
            MetalInterop.sdlCall("SDL_EndGPUCopyPass", copy);
            copy = 0L;
            submit(command);
            command = 0L;
        } finally {
            if (copy != 0L) safe("SDL_EndGPUCopyPass", copy);
            if (command != 0L) safe("SDL_CancelGPUCommandBuffer", command);
            if (transfer != 0L) safe("SDL_ReleaseGPUTransferBuffer", device, transfer);
            MetalInterop.free(dst);
            MetalInterop.free(src);
            MetalInterop.free(create);
        }
    }

    static ByteBuffer downloadTexture(long device, MetalGpuTexture texture, int mipLevel, int x, int y, int width, int height) {
        int bytesPerPixel = texture.getFormat().blockSize();
        int size = Math.multiplyExact(Math.multiplyExact(width, height), bytesPerPixel);
        Object create = null, src = null, dst = null;
        long transfer = 0L, command = 0L, copy = 0L;
        try {
            create = MetalInterop.calloc("SDL_GPUTransferBufferCreateInfo");
            MetalInterop.set(create, "usage", MetalInterop.sdl("SDL_GPU_TRANSFERBUFFERUSAGE_DOWNLOAD"));
            MetalInterop.set(create, "size", size);
            transfer = MetalInterop.sdlLong("SDL_CreateGPUTransferBuffer", device, create);
            require(transfer, "create texture download transfer buffer");
            command = MetalInterop.sdlLong("SDL_AcquireGPUCommandBuffer", device); require(command, "acquire download command buffer");
            copy = MetalInterop.sdlLong("SDL_BeginGPUCopyPass", command); require(copy, "begin download copy pass");
            src = MetalInterop.calloc("SDL_GPUTextureRegion");
            MetalInterop.set(src,"texture",texture.handle()); MetalInterop.set(src,"mip_level",mipLevel); MetalInterop.set(src,"layer",0);
            MetalInterop.set(src,"x",x);MetalInterop.set(src,"y",y);MetalInterop.set(src,"z",0);MetalInterop.set(src,"w",width);MetalInterop.set(src,"h",height);MetalInterop.set(src,"d",1);
            dst = MetalInterop.calloc("SDL_GPUTextureTransferInfo");
            MetalInterop.set(dst,"transfer_buffer",transfer);MetalInterop.set(dst,"offset",0);MetalInterop.set(dst,"pixels_per_row",width);MetalInterop.set(dst,"rows_per_layer",height);
            MetalInterop.sdlCall("SDL_DownloadFromGPUTexture",copy,src,dst);
            MetalInterop.sdlCall("SDL_EndGPUCopyPass",copy); copy=0L;
            submit(command); command=0L;
            Object idle=MetalInterop.sdlCall("SDL_WaitForGPUIdle",device);
            if(idle instanceof Boolean ok && !ok) throw new IllegalStateException("SDL_WaitForGPUIdle failed: "+MetalInterop.lastSdlError());
            long mapped=((Number)MetalInterop.sdlCall("SDL_MapGPUTransferBuffer",device,transfer,false)).longValue(); require(mapped,"map download transfer buffer");
            ByteBuffer out=ByteBuffer.allocateDirect(size); out.put(MemoryUtil.memByteBuffer(mapped,size).duplicate()).flip();
            MetalInterop.sdlCall("SDL_UnmapGPUTransferBuffer",device,transfer);
            return out;
        } finally {
            if(copy!=0L)safe("SDL_EndGPUCopyPass",copy); if(command!=0L)safe("SDL_CancelGPUCommandBuffer",command);
            if(transfer!=0L)safe("SDL_ReleaseGPUTransferBuffer",device,transfer); MetalInterop.free(dst);MetalInterop.free(src);MetalInterop.free(create);
        }
    }

    static void copyTexture(long device, MetalGpuTexture source, MetalGpuTexture destination, int mipLevel, int destX, int destY, int sourceX, int sourceY, int width, int height) {
        long command=0L,copy=0L; Object src=null,dst=null;
        try {
            command=MetalInterop.sdlLong("SDL_AcquireGPUCommandBuffer",device);require(command,"acquire texture copy command buffer");
            copy=MetalInterop.sdlLong("SDL_BeginGPUCopyPass",command);require(copy,"begin texture copy pass");
            src=MetalInterop.calloc("SDL_GPUTextureLocation"); dst=MetalInterop.calloc("SDL_GPUTextureLocation");
            for(Object o:new Object[]{src,dst}){MetalInterop.set(o,"mip_level",mipLevel);MetalInterop.set(o,"layer",0);MetalInterop.set(o,"z",0);}
            MetalInterop.set(src,"texture",source.handle());MetalInterop.set(src,"x",sourceX);MetalInterop.set(src,"y",sourceY);
            MetalInterop.set(dst,"texture",destination.handle());MetalInterop.set(dst,"x",destX);MetalInterop.set(dst,"y",destY);
            MetalInterop.sdlCall("SDL_CopyGPUTextureToTexture",copy,src,dst,width,height,1,false); MetalInterop.sdlCall("SDL_EndGPUCopyPass",copy);copy=0L; submit(command);command=0L;
        } finally { if(copy!=0L)safe("SDL_EndGPUCopyPass",copy);if(command!=0L)safe("SDL_CancelGPUCommandBuffer",command);MetalInterop.free(dst);MetalInterop.free(src);}
    }

    static void submit(long command) {
        Object result = MetalInterop.sdlCall("SDL_SubmitGPUCommandBuffer", command);
        if (result instanceof Boolean ok && !ok) {
            throw new IllegalStateException("SDL_SubmitGPUCommandBuffer failed: " + MetalInterop.lastSdlError());
        }
    }

    private static void require(long handle, String action) {
        if (handle == 0L) throw new IllegalStateException("Failed to " + action + ": " + MetalInterop.lastSdlError());
    }

    private static void safe(String method, Object... args) {
        try {
            MetalInterop.sdlCall(method, args);
        } catch (Throwable ignored) {
        }
    }
}
