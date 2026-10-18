package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

public interface UrdCommandList extends UrdResource {
    void beginPass(String name, UrdTexture colorTarget);
    void setPipeline(UrdPipeline pipeline);
    void setVertexBuffer(int slot, UrdBuffer buffer, long offset);
    void draw(int vertexCount, int firstVertex);
    void endPass();
    void submit();
    boolean isSubmitted();

    @Override
    void close();
}
