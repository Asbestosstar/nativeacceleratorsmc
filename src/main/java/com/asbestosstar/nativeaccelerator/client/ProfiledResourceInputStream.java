package com.asbestosstar.nativeaccelerator.client;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Times only delegate read calls, separating ZIP/file read+decompression from JSON CPU work. */
final class ProfiledResourceInputStream extends FilterInputStream {
    private long readNanos;
    private long bytes;
    private boolean reported;

    ProfiledResourceInputStream(InputStream delegate) {
        super(delegate);
    }

    @Override
    public int read() throws IOException {
        long started = ModelPipelineProfiler.start();
        try {
            int value = super.read();
            if (value >= 0) bytes++;
            return value;
        } finally {
            if (started != 0L) readNanos += System.nanoTime() - started;
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        long started = ModelPipelineProfiler.start();
        try {
            int count = super.read(buffer, offset, length);
            if (count > 0) bytes += count;
            return count;
        } finally {
            if (started != 0L) readNanos += System.nanoTime() - started;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            if (!reported) {
                reported = true;
                ModelPipelineProfiler.record("resource.read-decompress", readNanos, Math.max(1L, bytes));
                ModelPipelineProfiler.addCount("resource.bytes", bytes);
            }
        }
    }
}

