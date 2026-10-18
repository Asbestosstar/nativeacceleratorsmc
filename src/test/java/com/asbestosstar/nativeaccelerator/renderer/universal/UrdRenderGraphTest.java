package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.universal.urd.UrdRenderGraph;
import com.asbestosstar.nativeaccelerator.renderer.universal.urd.UrdValidationException;

import java.util.List;

public final class UrdRenderGraphTest {
    public static void main(String[] args) {
        UrdRenderGraph graph = new UrdRenderGraph().resource("color").resource("depth")
                .pass("opaque", List.of(), List.of("color", "depth"))
                .pass("post", List.of("color"), List.of());
        if (graph.compile().size() != 2) throw new AssertionError("graph order");
        try {
            new UrdRenderGraph().resource("color").pass("bad", List.of("color"), List.of("color"));
            throw new AssertionError("read/write hazard accepted");
        } catch (UrdValidationException expected) {
        }
        System.out.println("UrdRenderGraphTest: PASS");
    }
}
