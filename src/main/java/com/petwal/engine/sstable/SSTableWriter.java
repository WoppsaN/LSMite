package com.petwal.engine.sstable;

import com.petwal.engine.memtable.Entry;
import com.petwal.engine.memtable.MemTable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.nio.file.StandardOpenOption.*;

/**
 * Flushes a {@link MemTable} to an immutable, sorted SSTable file.
 *
 * <h3>File format</h3>
 * <pre>
 * [magic:8]       "SSTABLE\0" = 0x5353_5441_424C_4500L
 * [Data section]  sorted records: [keyLen:4][valueLen:4 or -1][key][value]
 * [Bloom section] [numBits:4][k:1][bitArray: ceil(numBits/8) bytes]
 * [Index section] [numEntries:4] then per-entry: [keyLen:4][key][dataOffset:8]
 * [Footer:24]     [bloomOffset:8][indexOffset:8][magic:8]
 * </pre>
 *
 * <h3>Design decisions</h3>
 * <ul>
 *   <li><b>Two-pass single-buffer write</b>: first pass collects entries and computes
 *       all offsets; second pass fills one {@link ByteBuffer} written atomically.
 *       Bounded by the MemTable threshold (4 MiB), so {@code int} capacity is safe.</li>
 *   <li><b>{@code CREATE_NEW} + {@code force(true)}</b>: guarantees immutability
 *       (no accidental overwrites) and durability.</li>
 *   <li><b>Dense index</b>: one entry per key. Valid since the MemTable is bounded.</li>
 * </ul>
 */
public final class SSTableWriter {

    private SSTableWriter() {}

    /**
     * Writes all entries from {@code memTable} to {@code path} in sorted order
     * and returns an {@link SSTableReader} open on the newly created file.
     */
    public static SSTableReader flush(final MemTable memTable, final Path path) throws IOException {
        // Collect entries (already sorted by MemTable's TreeMap)
        final List<Entry> entries = new ArrayList<>();
        for (final Entry e : memTable.sortedEntries()) {
            entries.add(e);
        }

        // Build Bloom filter
        final BloomFilter bloom = new BloomFilter(entries.size());
        for (final Entry e : entries) {
            bloom.add(e.key());
        }

        // First pass: compute per-record data offsets
        final long[] dataOffsets = new long[entries.size()];
        long dataSize = 8L; // starts after magic; not final — accumulated in loop
        for (int i = 0; i < entries.size(); i++) {
            dataOffsets[i] = dataSize;
            final Entry e = entries.get(i);
            dataSize += SSTableConstants.RECORD_HEADER_SIZE + e.key().length;
            if (!e.isTombstone()) {
                dataSize += e.value().length;
            }
        }

        // Bloom section
        final byte[] bloomBytes = bloom.serialize();
        final long bloomOffset = dataSize;

        // Index section
        final long indexOffset = bloomOffset + bloomBytes.length;
        long indexSize = 4L; // numEntries int; not final — accumulated in loop
        for (final Entry e : entries) {
            indexSize += 4 + e.key().length + 8; // keyLen + key + dataOffset
        }

        // Footer: [bloomOffset:8][indexOffset:8][magic:8] = 24 bytes
        final long totalSize = indexOffset + indexSize + 24;

        // Second pass: fill buffer
        final ByteBuffer buf = ByteBuffer.allocate((int) totalSize);

        // Magic
        buf.putLong(SSTableConstants.MAGIC);

        // Data section
        for (final Entry e : entries) {
            buf.putInt(e.key().length);
            buf.putInt(e.isTombstone() ? SSTableConstants.TOMBSTONE : e.value().length);
            buf.put(e.key());
            if (!e.isTombstone()) {
                buf.put(e.value());
            }
        }

        // Bloom section
        buf.put(bloomBytes);

        // Index section
        buf.putInt(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            final Entry e = entries.get(i);
            buf.putInt(e.key().length);
            buf.put(e.key());
            buf.putLong(dataOffsets[i]);
        }

        // Footer
        buf.putLong(bloomOffset);
        buf.putLong(indexOffset);
        buf.putLong(SSTableConstants.MAGIC);

        // Write atomically, then sync
        buf.flip();
        try (FileChannel ch = FileChannel.open(path, WRITE, CREATE_NEW)) {
            while (buf.hasRemaining()){
                ch.write(buf);
            }
            ch.force(true);
        }

        return SSTableReader.open(path);
    }
}
