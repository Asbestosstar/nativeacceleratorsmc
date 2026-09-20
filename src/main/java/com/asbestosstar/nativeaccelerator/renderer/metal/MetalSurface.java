package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BooleanSupplier;

final class MetalSurface implements GpuSurfaceBackend {
    private final MetalDevice device;
    private final long window;
    private final BooleanSupplier iconified;
    private GpuSurface.Configuration config;
    private boolean acquired;
    private boolean suboptimal;
    private long presentSequence;

    MetalSurface(MetalDevice device,long window,BooleanSupplier iconified) {
        this.device=device;this.window=window;this.iconified=iconified;
        MetalTrace.log("SURFACE_CREATE", "window=" + MetalTrace.hex(window) + " device=" + MetalTrace.hex(device.handle()));
        Object ok=MetalInterop.sdlCall("SDL_ClaimWindowForGPUDevice",device.handle(),window);
        if(ok instanceof Boolean b && !b) throw new IllegalStateException("SDL_ClaimWindowForGPUDevice failed: "+MetalInterop.lastSdlError());
    }

    @Override public void configure(GpuSurface.Configuration config) throws SurfaceException {
        this.config=config;
        MetalTrace.log("SURFACE_CONFIGURE", "window=" + MetalTrace.hex(window) + " size=" + config.width() + "x" + config.height() + " mode=" + config.presentMode());
        int mode=presentMode(config.presentMode());
        Object ok=MetalInterop.sdlCall("SDL_SetGPUSwapchainParameters",device.handle(),window,MetalInterop.sdl("SDL_GPU_SWAPCHAINCOMPOSITION_SDR"),mode);
        if(ok instanceof Boolean b && !b)throw new SurfaceException("SDL_SetGPUSwapchainParameters failed: "+MetalInterop.lastSdlError());
        suboptimal=false;
    }
    @Override public boolean isSuboptimal(){return suboptimal;}
    @Override public void acquireNextTexture() throws SurfaceException { if(config==null)throw new SurfaceException("Metal surface is not configured");acquired=true; MetalTrace.log("SURFACE_ACQUIRE_REQUEST", "nextFrame=" + (presentSequence+1) + " configured=" + config.width() + "x" + config.height()); }
    @Override public void blitFromTexture(CommandEncoderBackend encoderBackend,GpuTextureView source) {
        if(!acquired)throw new IllegalStateException("Surface not acquired");
        if(!(encoderBackend instanceof MetalCommandEncoder encoder)||!(source instanceof MetalTextureView view))throw new IllegalArgumentException("Foreign backend resource");
        if(iconified.getAsBoolean()) return;
        try(MemoryStack stack=MemoryStack.stackPush()){
            long frame=++presentSequence; MetalTrace.frame(frame); MetalTrace.log("FRAME_BEGIN", "frame=" + frame + " source=" + MetalTrace.hex(view.metalTexture().handle()) + " sourceSize=" + view.getWidth(0) + "x" + view.getHeight(0));
            if(device.waitForSwapchainBeforeAcquire()){
                long waitNs=System.nanoTime(); Object waited=MetalInterop.sdlCall("SDL_WaitForGPUSwapchain",device.handle(),window); MetalTrace.log("SWAPCHAIN_WAIT", "frame=" + frame + " ns=" + (System.nanoTime()-waitNs) + " result=" + waited);
                if(waited instanceof Boolean b && !b){
                    throw new IllegalStateException("SDL_WaitForGPUSwapchain failed before frame "+frame+": "+MetalInterop.lastSdlError());
                }
            }
            PointerBuffer texture=stack.mallocPointer(1);IntBuffer w=stack.mallocInt(1),h=stack.mallocInt(1);
            long waitStart=MetalPerfCounters.tic();
            MetalTrace.log("SWAPCHAIN_ACQUIRE_BEGIN", "frame=" + frame + " encoder=" + encoder.traceEncoderId() + " cmd=" + MetalTrace.hex(encoder.commandHandle()));
            Object ok=MetalInterop.sdlCall("SDL_WaitAndAcquireGPUSwapchainTexture",encoder.commandHandle(),window,texture,w,h);
            MetalPerfCounters.swapAcquire(waitStart);
            if(ok instanceof Boolean b && !b)throw new IllegalStateException("SDL_WaitAndAcquireGPUSwapchainTexture failed: "+MetalInterop.lastSdlError());
            long swap=texture.get(0); MetalTrace.log("SWAPCHAIN_ACQUIRE_END", "frame=" + frame + " result=" + ok + " texture=" + MetalTrace.hex(swap) + " size=" + w.get(0) + "x" + h.get(0) + " ns=" + (MetalPerfCounters.tic()-waitStart));
            if(swap==0L){
                MetalTrace.log("SWAPCHAIN_EMPTY", "frame=" + frame);
                return;
            }
            int width=w.get(0),height=h.get(0);suboptimal=config!=null&&(width!=config.width()||height!=config.height());
            long blitStart=MetalPerfCounters.tic();
            encoder.presentToSwapchain(view,swap,width,height,frame);
            MetalPerfCounters.blit(blitStart);
        }
    }
    @Override public void present(){ acquired=false; MetalPerfCounters.present(); MetalTrace.log("SURFACE_PRESENT_MARK", "frame=" + presentSequence); /* SDL presents automatically when the acquiring command buffer is submitted. */ }
    @Override public Collection<GpuSurface.PresentMode> supportedPresentModes(){
        List<GpuSurface.PresentMode> out=new ArrayList<>();out.add(GpuSurface.PresentMode.FIFO);
        if(supports("SDL_GPU_PRESENTMODE_MAILBOX"))out.add(GpuSurface.PresentMode.MAILBOX);
        if(supports("SDL_GPU_PRESENTMODE_IMMEDIATE"))out.add(GpuSurface.PresentMode.IMMEDIATE);
        return List.copyOf(out);
    }
    private boolean supports(String constant){try{return MetalInterop.sdlBool("SDL_WindowSupportsGPUPresentMode",device.handle(),window,MetalInterop.sdl(constant));}catch(Throwable ignored){return false;}}
    private static int presentMode(GpuSurface.PresentMode mode){return MetalInterop.sdl(switch(mode){case FIFO, FIFO_RELAXED->"SDL_GPU_PRESENTMODE_VSYNC";case MAILBOX->"SDL_GPU_PRESENTMODE_MAILBOX";case IMMEDIATE->"SDL_GPU_PRESENTMODE_IMMEDIATE";});}
    @Override public void close(){try{MetalInterop.sdlCall("SDL_WaitForGPUSwapchain",device.handle(),window);}catch(Throwable ignored){}MetalInterop.sdlCall("SDL_ReleaseWindowFromGPUDevice",device.handle(),window);}
}
