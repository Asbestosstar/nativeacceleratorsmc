package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Correctness-first transient allocator. Resources live until the owning command encoder is closed/submitted. */
final class MetalTransientMemory implements TransientMemory {
    private final MetalDevice device;
    private final List<GpuBuffer> owned = new ArrayList<>();
    MetalTransientMemory(MetalDevice device) { this.device = device; }

    @Override public ByteBuffer allocateCpu(long size, long alignment, long minimumAllocation, long elementSize) {
        int n = checkedSize(Math.max(size, minimumAllocation));
        return ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder());
    }

    @Override public GpuBufferSlice.MappedView allocateStaging(long size,long alignment,int usage,long minimumAllocation,long elementSize) {
        return allocateMapped(size, usage | GpuBuffer.USAGE_MAP_WRITE, minimumAllocation);
    }
    @Override public GpuBufferSlice allocateGpu(long size,long alignment,int usage,long minimumAllocation,long elementSize) {
        MetalGpuBuffer b = new MetalGpuBuffer(device, usage, Math.max(size, minimumAllocation)); owned.add(b); return b.slice(0, size);
    }
    @Override public GpuBufferSlice.MappedView allocateGpuMapped(long size,long alignment,int usage,long minimumAllocation,long elementSize) {
        return allocateMapped(size, usage | GpuBuffer.USAGE_MAP_WRITE, minimumAllocation);
    }
    private GpuBufferSlice.MappedView allocateMapped(long size,int usage,long minimumAllocation) {
        MetalGpuBuffer b = new MetalGpuBuffer(device, usage, Math.max(size, minimumAllocation)); owned.add(b); return b.map(0,size,false,true);
    }

    @Override public GpuBufferSlice uploadStaging(List<ByteBuffer> data,long alignment,int usage,long minimumAllocation,long elementSize) {
        return upload(data, usage, minimumAllocation);
    }
    @Override public GpuBufferSlice uploadGpu(List<ByteBuffer> data,long alignment,int usage,long minimumAllocation,long elementSize) {
        return upload(data, usage, minimumAllocation);
    }
    private GpuBufferSlice upload(List<ByteBuffer> data,int usage,long minimumAllocation) {
        long total=0; for (ByteBuffer b:data) total+=b.remaining();
        int n=checkedSize(Math.max(total,minimumAllocation)); ByteBuffer all=ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder());
        for (ByteBuffer b:data) all.put(b.duplicate()); all.flip();
        MetalGpuBuffer out=new MetalGpuBuffer(device,usage,all); owned.add(out); return out.slice(0,total);
    }
    @Override public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data,long alignment,int usage) { return multi(data,alignment,usage); }
    @Override public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data,long alignment,int usage) { return multi(data,alignment,usage); }
    private List<GpuBufferSlice> multi(List<ByteBuffer> data,long alignment,int usage) {
        if (data.isEmpty()) return List.of();
        long effectiveAlignment=Math.max(1L,alignment);
        long total=0L;
        long[] offsets=new long[data.size()];
        for(int i=0;i<data.size();i++){
            total=alignUp(total,effectiveAlignment); offsets[i]=total; total+=data.get(i).remaining();
        }
        int n=checkedSize(total); ByteBuffer all=ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder());
        for(int i=0;i<data.size();i++){
            all.position(Math.toIntExact(offsets[i])); all.put(data.get(i).duplicate());
        }
        all.position(0).limit(n);
        MetalGpuBuffer buffer=new MetalGpuBuffer(device,usage,all); owned.add(buffer);
        List<GpuBufferSlice> out=new ArrayList<>(data.size());
        for(int i=0;i<data.size();i++) out.add(buffer.slice(offsets[i],data.get(i).remaining()));
        return List.copyOf(out);
    }
    private static long alignUp(long value,long alignment){long mask=alignment-1;return (alignment&(alignment-1))==0?(value+mask)&~mask:((value+alignment-1)/alignment)*alignment;}
    boolean isEmpty() { return owned.isEmpty(); }

    void release() { for (GpuBuffer b:owned) try { b.close(); } catch(Throwable ignored){} owned.clear(); }
    private static int checkedSize(long n){ if(n<=0||n>Integer.MAX_VALUE)throw new IllegalArgumentException("Transient allocation too large: "+n); return (int)n; }
}
