package com.asbestosstar.nativeaccelerator.cache;

import com.asbestosstar.nativeaccelerator.client.ModelPipelineProfiler;
import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32C;

/**
 * One-file persistent blob store used by compiled/startup caches.
 *
 * <p>Existing generations are memory mapped once, so cache hits do not open thousands of tiny files.
 * New blobs are append-only and become reusable immediately in the current process through positional
 * reads; a compact binary index is atomically rewritten at shutdown.  A crash can therefore lose newly
 * learned index entries but can never make an incomplete entry look valid.</p>
 */
public final class PersistentBlobCache implements AutoCloseable {
    private static final int MAGIC = 0x4E414243; // NABC
    private static final int INDEX_VERSION = 1;
    private static final int MAX_ENTRY_BYTES = NativeAcceleratorConfig.intValue("cache.maxEntryBytes", 64 * 1024 * 1024, 1024);

    private final Path directory;
    private final Path dataPath;
    private final Path indexPath;
    private final HashMap<String, Entry> entries = new HashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private FileChannel channel;
    private MappedByteBuffer mapped;
    private long mappedSize;

    public PersistentBlobCache(Path directory, String name) throws IOException {
        this.directory = directory;
        this.dataPath = directory.resolve(name + ".blob");
        this.indexPath = directory.resolve(name + ".idx");
        Files.createDirectories(directory);
        loadIndex();
        this.channel = FileChannel.open(dataPath, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        this.mappedSize = channel.size();
        if (mappedSize > 0L && mappedSize <= Integer.MAX_VALUE) {
            this.mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0L, mappedSize);
        }
    }

    public synchronized byte[] get(String key) {
        Entry entry = entries.get(key);
        if (entry == null || entry.length < 0 || entry.length > MAX_ENTRY_BYTES) return null;
        try {
            byte[] bytes = new byte[entry.length];
            ByteBuffer dst = ByteBuffer.wrap(bytes);
            readFully(entry, dst);
            if (crc(bytes) != entry.crc32c) {
                entries.remove(key);
                dirty.set(true);
                ModelPipelineProfiler.addCount("cache.blob.crc-mismatch", 1);
                return null;
            }
            return bytes;
        } catch (IOException exception) {
            ModelPipelineProfiler.addCount("cache.blob.read-error", 1);
            return null;
        }
    }

    /** Returns a read-only direct slice when the entry came from the startup mapping. */
    public synchronized ByteBuffer view(String key) {
        Entry entry = entries.get(key);
        if (entry == null || mapped == null || entry.offset < 0L
                || entry.offset + entry.length > mappedSize || entry.length > MAX_ENTRY_BYTES) return null;
        ByteBuffer duplicate = mapped.asReadOnlyBuffer();
        duplicate.position(Math.toIntExact(entry.offset));
        duplicate.limit(Math.toIntExact(entry.offset + entry.length));
        return duplicate.slice().asReadOnlyBuffer();
    }

    public synchronized void put(String key, byte[] bytes) {
        if (bytes == null) return;
        put(key, ByteBuffer.wrap(bytes));
    }

    public synchronized void put(String key, ByteBuffer source) {
        if (entries.containsKey(key)) return;
        ByteBuffer src = source.duplicate();
        int length = src.remaining();
        if (length <= 0 || length > MAX_ENTRY_BYTES) return;
        try {
            long offset = channel.size();
            channel.position(offset);
            CRC32C crc = new CRC32C();
            ByteBuffer crcBuffer = src.duplicate();
            if (crcBuffer.hasArray()) {
                crc.update(crcBuffer.array(), crcBuffer.arrayOffset() + crcBuffer.position(), crcBuffer.remaining());
            } else {
                byte[] scratch = new byte[Math.min(64 * 1024, Math.max(1024, crcBuffer.remaining()))];
                while (crcBuffer.hasRemaining()) {
                    int count = Math.min(scratch.length, crcBuffer.remaining());
                    crcBuffer.get(scratch, 0, count);
                    crc.update(scratch, 0, count);
                }
            }
            while (src.hasRemaining()) channel.write(src);
            entries.put(key, new Entry(offset, length, (int)crc.getValue()));
            dirty.set(true);
        } catch (IOException exception) {
            ModelPipelineProfiler.addCount("cache.blob.write-error", 1);
        }
    }

    public synchronized int size() { return entries.size(); }

    /** Cheap membership probe used to avoid staging duplicate deferred writes. */
    public synchronized boolean contains(String key) { return entries.containsKey(key); }

    private void readFully(Entry entry, ByteBuffer dst) throws IOException {
        if (mapped != null && entry.offset + entry.length <= mappedSize) {
            ByteBuffer src = viewForEntry(entry);
            dst.put(src);
            dst.flip();
            return;
        }
        long position = entry.offset;
        while (dst.hasRemaining()) {
            int read = channel.read(dst, position);
            if (read < 0) throw new EOFException("Truncated persistent cache entry");
            position += read;
        }
        dst.flip();
    }

    private ByteBuffer viewForEntry(Entry entry) {
        ByteBuffer duplicate = mapped.asReadOnlyBuffer();
        duplicate.position(Math.toIntExact(entry.offset));
        duplicate.limit(Math.toIntExact(entry.offset + entry.length));
        return duplicate.slice().asReadOnlyBuffer();
    }

    private void loadIndex() {
        if (!Files.isRegularFile(indexPath) || !Files.isRegularFile(dataPath)) return;
        long dataLength;
        try { dataLength = Files.size(dataPath); } catch (IOException ignored) { return; }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(indexPath)))) {
            if (in.readInt() != MAGIC || in.readInt() != INDEX_VERSION) return;
            int count = in.readInt();
            if (count < 0 || count > 2_000_000) return;
            for (int i = 0; i < count; i++) {
                int keyLength = in.readUnsignedShort();
                byte[] keyBytes = in.readNBytes(keyLength);
                if (keyBytes.length != keyLength) return;
                String key = new String(keyBytes, StandardCharsets.UTF_8);
                long offset = in.readLong();
                int length = in.readInt();
                int crc = in.readInt();
                if (offset >= 0L && length >= 0 && offset + (long)length <= dataLength) {
                    entries.put(key, new Entry(offset, length, crc));
                }
            }
        } catch (IOException ignored) {
            entries.clear();
        }
    }

    public synchronized void flushIndex() throws IOException {
        if (!dirty.get()) return;
        Files.createDirectories(directory);
        Path temporary = indexPath.resolveSibling(indexPath.getFileName() + ".tmp");
        ArrayList<Map.Entry<String, Entry>> rows = new ArrayList<>(entries.entrySet());
        rows.sort(Comparator.comparing(Map.Entry::getKey));
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)))) {
            out.writeInt(MAGIC);
            out.writeInt(INDEX_VERSION);
            out.writeInt(rows.size());
            for (Map.Entry<String, Entry> row : rows) {
                byte[] key = row.getKey().getBytes(StandardCharsets.UTF_8);
                if (key.length > 65535) continue;
                out.writeShort(key.length);
                out.write(key);
                Entry entry = row.getValue();
                out.writeLong(entry.offset);
                out.writeInt(entry.length);
                out.writeInt(entry.crc32c);
            }
        }
        try {
            Files.move(temporary, indexPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException noAtomicMove) {
            Files.move(temporary, indexPath, StandardCopyOption.REPLACE_EXISTING);
        }
        dirty.set(false);
    }

    @Override
    public synchronized void close() {
        try { flushIndex(); } catch (IOException ignored) {}
        try { if (channel != null) channel.close(); } catch (IOException ignored) {}
        channel = null;
        mapped = null;
    }

    private static int crc(byte[] bytes) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length);
        return (int)crc.getValue();
    }

    private record Entry(long offset, int length, int crc32c) {}
}
