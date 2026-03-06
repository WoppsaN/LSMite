package com.petwal.engine.sstable;

import static java.nio.file.StandardOpenOption.*;

import com.petwal.engine.memtable.Entry;
import com.petwal.engine.memtable.MemTable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Flushes a {@link MemTable} to an immutable, sorted SSTable file.
 *
 * <h3>File format</h3>
 *
 * <pre>
 * [magic:8]       "SSTABLE\0" = 0x5353_5441_424C_4500L
 * [Data section]  sorted records: [keyLen:4][valueLen:4 or -1][key][value]
 * [Bloom section] [numBits:4][k:1][bitArray: ceil(numBits/8) bytes]
 * [Index section] [numEntries:4] then per-entry: [keyLen:4][key][dataOffset:8]
 * [Footer:24]     [bloomOffset:8][indexOffset:8][magic:8]
 * </pre>
 *
 * <h3>Design decisions</h3>
 *
 * <ul>
 *   <li><b>Two-pass single-buffer write</b>: first pass collects entries and computes all offsets;
 *       second pass fills one {@link ByteBuffer} written atomically. Bounded by the MemTable
 *       threshold (4 MiB), so {@code int} capacity is safe.
 *   <li><b>{@code CREATE_NEW} + {@code force(true)}</b>: guarantees immutability (no accidental
 *       overwrites) and durability.
 *   <li><b>Dense index</b>: one entry per key. Valid since the MemTable is bounded.
 * </ul>
 */
public final class SSTableWriter {

  private SSTableWriter() {}

  /**
   * Writes all entries from {@code memTable} to {@code path} in sorted order and returns an {@link
   * SSTable} open on the newly created file.
   */
  public static SSTable flush(final MemTable memTable, final Path path) throws IOException {
    // Collect entries (already sorted by MemTable's TreeMap)
    final List<Entry> entries = new ArrayList<>();
    for (final Entry e : memTable.sortedEntries()) {
      entries.add(e);
    }

    // Build Bloom filter
    final BloomFilter bloomFilter = new BloomFilter(entries.size());
    for (final Entry e : entries) {
      bloomFilter.add(e.key());
    }

    // First pass: compute per-record data offsets
    final long[] dataOffsets = new long[entries.size()];
    long currentOffset = SSTableConstants.MAGIC_SIZE;
    for (int i = 0; i < entries.size(); i++) {
      dataOffsets[i] = currentOffset;
      final Entry e = entries.get(i);

      final int keyLen = e.key().length;
      final int valueLen = e.isTombstone() ? 0 : e.value().length;
      currentOffset += SSTableConstants.RECORD_HEADER_SIZE + keyLen + valueLen;
    }

    // Bloom section
    final byte[] bloomBytes = bloomFilter.serialize();
    final long bloomOffset = currentOffset;

    // Index section
    final long indexOffset = bloomOffset + bloomBytes.length;
    long indexCurrent = SSTableConstants.INDEX_KEY_LENGTH;
    for (final Entry e : entries) {
      indexCurrent +=
          SSTableConstants.INDEX_KEY_LENGTH
              + e.key().length
              + SSTableConstants.OFFSET_LENGTH; // keyLen + key + dataOffset
    }

    // Footer: [bloomOffset:8][indexOffset:8][magic:8] = 24 bytes
    final long totalSize = indexOffset + indexCurrent + SSTableConstants.FOOTER_LENGTH;

    // Second pass: fill buffer
    final ByteBuffer buf = ByteBuffer.allocate((int) totalSize);

    // Magic
    buf.putLong(SSTableConstants.MAGIC_VALUE);

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
    buf.putLong(SSTableConstants.MAGIC_VALUE);

    // Write atomically, then sync
    buf.flip();
    try (FileChannel ch = FileChannel.open(path, WRITE, CREATE_NEW)) {
      while (buf.hasRemaining()) {
        ch.write(buf);
      }
      ch.force(true);
    }

    return SSTable.open(path);
  }
}
