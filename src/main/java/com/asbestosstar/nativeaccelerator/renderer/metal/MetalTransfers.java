package com.asbestosstar.nativeaccelerator.renderer.metal;

import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDLGPU;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/** Synchronous resource staging helpers used by maps and small one-off uploads. */
final class MetalTransfers {
    private MetalTransfers() {}

    record BufferUpload(long destination, long destinationOffset, ByteBuffer bytes) {
        BufferUpload {
            if (destination == 0L) throw new IllegalArgumentException("destination");
            if (bytes == null) throw new NullPointerException("bytes");
        }
    }

    /**
     * Pack many CPU buffer updates into one SDL transfer buffer and one already-open copy pass.
     * This avoids one native transfer allocation/map/unmap cycle per mapped Minecraft buffer.
     */
    static void encodeUploadBuffersInPass(long device, long copyPass, java.util.List<BufferUpload> uploads) {
        if (uploads.isEmpty()) return;
        final int alignment = 16;
        int total = 0;
        int[] offsets = new int[uploads.size()];
        for (int i = 0; i < uploads.size(); i++) {
            total = alignUp(total, alignment);
            offsets[i] = total;
            total = Math.addExact(total, uploads.get(i).bytes().remaining());
        }
        if (total == 0) return;

        Object create = null;
        long transfer = 0L;
        try {
            create = MetalInterop.calloc("SDL_GPUTransferBufferCreateInfo");
            MetalInterop.set(create, "usage", MetalInterop.sdl("SDL_GPU_TRANSFERBUFFERUSAGE_UPLOAD"));
            MetalInterop.set(create, "size", total);
            transfer = MetalInterop.sdlLong("SDL_CreateGPUTransferBuffer", device, create);
            require(transfer, "create batched upload transfer buffer");

            long mapped = ((Number)MetalInterop.sdlCall("SDL_MapGPUTransferBuffer", device, transfer, false)).longValue();
            require(mapped, "map batched upload transfer buffer");
            ByteBuffer mappedBytes = MemoryUtil.memByteBuffer(mapped, total);
            for (int i = 0; i < uploads.size(); i++) {
                ByteBuffer source = uploads.get(i).bytes().duplicate();
                ByteBuffer destination = mappedBytes.duplicate();
                destination.position(offsets[i]).limit(offsets[i] + source.remaining());
                destination.put(source);
            }
            MetalInterop.sdlCall("SDL_UnmapGPUTransferBuffer", device, transfer);

            for (int i = 0; i < uploads.size(); i++) {
                BufferUpload upload = uploads.get(i);
                int size = upload.bytes().remaining();
                if (size == 0) continue;
                Object src = null, dst = null;
                try {
                    src = MetalInterop.calloc("SDL_GPUTransferBufferLocation");
                    MetalInterop.set(src, "transfer_buffer", transfer);
                    MetalInterop.set(src, "offset", offsets[i]);
                    dst = MetalInterop.calloc("SDL_GPUBufferRegion");
                    MetalInterop.set(dst, "buffer", upload.destination());
                    MetalInterop.set(dst, "offset", upload.destinationOffset());
                    MetalInterop.set(dst, "size", size);
                    MetalInterop.sdlCall("SDL_UploadToGPUBuffer", copyPass, src, dst, false);
                } finally {
                    MetalInterop.free(dst);
                    MetalInterop.free(src);
                }
            }
        } finally {
            if (transfer != 0L) safe("SDL_ReleaseGPUTransferBuffer", device, transfer);
            MetalInterop.free(create);
        }
    }

    private static int alignUp(int value, int alignment) {
        int mask = alignment - 1;
        return Math.addExact(value, mask) & ~mask;
    }

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
                              int mipLevel, int layer, int x, int y, int width, int height) {
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
            MetalInterop.set(dst, "layer", layer);
            MetalInterop.set(dst, "x", x);
            MetalInterop.set(dst, "y", y);
            MetalInterop.set(dst, "z", 0);
            MetalInterop.set(dst, "w", width);
            MetalInterop.set(dst, "h", height);
            MetalInterop.set(dst, "d", 1);

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
            long fence = SDLGPU.SDL_SubmitGPUCommandBufferAndAcquireFence(command);
            command=0L;
            require(fence,"submit texture download with fence");
            long waitStart=MetalPerfCounters.tic();
            try (MemoryStack stack=MemoryStack.stackPush()) {
                PointerBuffer fences=stack.mallocPointer(1).put(0,fence);
                if(!SDLGPU.SDL_WaitForGPUFences(device,true,fences))
                    throw new IllegalStateException("SDL_WaitForGPUFences failed: "+MetalInterop.lastSdlError());
            } finally {
                MetalPerfCounters.fenceWait(waitStart);
                SDLGPU.SDL_ReleaseGPUFence(device,fence);
            }
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


    /** Encode one buffer upload into an already-open copy pass. */
    static void encodeUploadBufferInPass(long device, long copyPass, long destination,
                                         long destinationOffset, ByteBuffer source) {
        ByteBuffer bytes = source.duplicate();
        int size = bytes.remaining();
        if (size == 0) return;

        Object create = null;
        Object src = null;
        Object dst = null;
        long transfer = 0L;
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

            src = MetalInterop.calloc("SDL_GPUTransferBufferLocation");
            MetalInterop.set(src, "transfer_buffer", transfer);
            MetalInterop.set(src, "offset", 0);
            dst = MetalInterop.calloc("SDL_GPUBufferRegion");
            MetalInterop.set(dst, "buffer", destination);
            MetalInterop.set(dst, "offset", destinationOffset);
            MetalInterop.set(dst, "size", size);
            MetalInterop.sdlCall("SDL_UploadToGPUBuffer", copyPass, src, dst, false);
        } finally {
            // SDL release is deferred until the GPU no longer references the transfer buffer.
            if (transfer != 0L) safe("SDL_ReleaseGPUTransferBuffer", device, transfer);
            MetalInterop.free(dst);
            MetalInterop.free(src);
            MetalInterop.free(create);
        }
    }

    /** Encode one texture upload on the caller's command buffer without an intermediate submit. */
    static void encodeUploadTexture(long device, long command, MetalGpuTexture texture, ByteBuffer source,
                                    int mipLevel, int layer, int x, int y, int width, int height) {
        ByteBuffer bytes = source.duplicate();
        int size = bytes.remaining();
        if (size == 0) return;
        Object create = null, src = null, dst = null;
        long transfer = 0L, copy = 0L;
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
            MetalInterop.set(dst, "layer", layer);
            MetalInterop.set(dst, "x", x);
            MetalInterop.set(dst, "y", y);
            MetalInterop.set(dst, "z", 0);
            MetalInterop.set(dst, "w", width);
            MetalInterop.set(dst, "h", height);
            MetalInterop.set(dst, "d", 1);
            MetalInterop.sdlCall("SDL_UploadToGPUTexture", copy, src, dst, false);
            MetalInterop.sdlCall("SDL_EndGPUCopyPass", copy);
            copy = 0L;
        } finally {
            if (copy != 0L) safe("SDL_EndGPUCopyPass", copy);
            if (transfer != 0L) safe("SDL_ReleaseGPUTransferBuffer", device, transfer);
            MetalInterop.free(dst); MetalInterop.free(src); MetalInterop.free(create);
        }
    }

    static void encodeCopyBuffer(long command, MetalGpuBuffer source, long sourceOffset,
                                 MetalGpuBuffer destination, long destinationOffset, long size) {
        if (size == 0L) return;
        if (size > Integer.MAX_VALUE) throw new IllegalArgumentException("SDL GPU copy exceeds Uint32 range: " + size);
        long copy = 0L; Object src = null, dst = null;
        try {
            copy = MetalInterop.sdlLong("SDL_BeginGPUCopyPass", command);
            require(copy, "begin buffer copy pass");
            src = MetalInterop.calloc("SDL_GPUBufferLocation");
            dst = MetalInterop.calloc("SDL_GPUBufferLocation");
            MetalInterop.set(src, "buffer", source.handle()); MetalInterop.set(src, "offset", sourceOffset);
            MetalInterop.set(dst, "buffer", destination.handle()); MetalInterop.set(dst, "offset", destinationOffset);
            MetalInterop.sdlCall("SDL_CopyGPUBufferToBuffer", copy, src, dst, (int)size, false);
            MetalInterop.sdlCall("SDL_EndGPUCopyPass", copy); copy = 0L;
        } finally {
            if (copy != 0L) safe("SDL_EndGPUCopyPass", copy);
            MetalInterop.free(dst); MetalInterop.free(src);
        }
    }

    static void encodeCopyTexture(long command, MetalGpuTexture source, MetalGpuTexture destination,
                                  int mipLevel, int destX, int destY, int sourceX, int sourceY,
                                  int width, int height) {
        long copy = 0L; Object src = null, dst = null;
        try {
            copy = MetalInterop.sdlLong("SDL_BeginGPUCopyPass", command);
            require(copy, "begin texture copy pass");
            src = MetalInterop.calloc("SDL_GPUTextureLocation");
            dst = MetalInterop.calloc("SDL_GPUTextureLocation");
            for (Object o : new Object[]{src, dst}) {
                MetalInterop.set(o, "mip_level", mipLevel); MetalInterop.set(o, "layer", 0); MetalInterop.set(o, "z", 0);
            }
            MetalInterop.set(src, "texture", source.handle()); MetalInterop.set(src, "x", sourceX); MetalInterop.set(src, "y", sourceY);
            MetalInterop.set(dst, "texture", destination.handle()); MetalInterop.set(dst, "x", destX); MetalInterop.set(dst, "y", destY);
            MetalInterop.sdlCall("SDL_CopyGPUTextureToTexture", copy, src, dst, width, height, 1, false);
            MetalInterop.sdlCall("SDL_EndGPUCopyPass", copy); copy = 0L;
        } finally {
            if (copy != 0L) safe("SDL_EndGPUCopyPass", copy);
            MetalInterop.free(dst); MetalInterop.free(src);
        }
    }

    static void submit(long command) {
        if (!SDLGPU.SDL_SubmitGPUCommandBuffer(command)) {
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
