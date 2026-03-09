package com.petwal.engine.sstable;

import static java.nio.file.StandardOpenOption.*;

import com.petwal.engine.memtable.Entry;
import com.petwal.engine.memtable.MemTable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Static utility for writing MemTable contents and merging existing SSTables to disk.
 *
 * <h3>Two-pass write</h3>
 *
 * <p>The flush path makes a first pass to compute all section offsets and sizes, then assembles a
 * single {@link java.nio.ByteBuffer} and writes it atomically with {@code CREATE_NEW} +
 * {@code FileChannel.force(true)}. This prevents partially-written files from becoming visible.
 *
 * <h3>Merge (compaction)</h3>
 *
 * <p>The merge path performs a k-way merge over all existing SSTables using a {@link
 * java.util.PriorityQueue}, deduplicates by key (newest generation wins), drops tombstones, and
 * flushes the resulting entry list as a single new SSTable. The old files are deleted before
 * returning.
 */
public final class SSTableWriter {

  private SSTableWriter() {}

  public static SSTable flush(final MemTable memTable, final Path path) throws IOException {
    // Collect entries (already sorted by MemTable's TreeMap)
    final List<Entry> entries = new ArrayList<>();
    for (final Entry e : memTable.entries()) {
      entries.add(e);
    }

    return flush(path, entries);
  }

  private static SSTable flush(Path path, List<Entry> entries) throws IOException {

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
    long indexCurrent = SSTableConstants.LENGTH_SIZE; // numEntries header
    for (final Entry e : entries) {
      indexCurrent += SSTableConstants.LENGTH_SIZE + e.key().length + SSTableConstants.OFFSET_SIZE; // keyLen + key + dataOffset
    }

    // Footer: [bloomOffset:8][indexOffset:8][magic:8] = 24 bytes
    final long totalSize = indexOffset + indexCurrent + SSTableConstants.FOOTER_SIZE;

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

  public static SSTable merge(final List<SSTable> ssTables, final Path newPath) throws IOException {
    final PriorityQueue<QueueEntry> priorityQueue = new PriorityQueue<>();
    int generation = 0;
    for (final SSTable table : ssTables.reversed()) {
      final Iterator<Entry> iterator = table.entries().iterator();
      if (iterator.hasNext()) {
        final QueueEntry entry = new QueueEntry(iterator.next(), generation++, iterator);
        priorityQueue.add(entry);
      }
    }

    final List<Entry> entries = new ArrayList<>();
    byte[] lastKey = {};
    while (!priorityQueue.isEmpty()) {
      final QueueEntry queueEntry = priorityQueue.poll();
      byte[] key = queueEntry.entry.key();
      byte[] value = queueEntry.entry.value();

      if (!isEqualBytes(lastKey, queueEntry.entry().key()) && !isTombstoneBytes(value)) {
        entries.add(new Entry(queueEntry.entry().key(), queueEntry.entry.value()));
      }

      if (queueEntry.tableIterator.hasNext()) {
        final Entry entry = queueEntry.tableIterator.next();
        priorityQueue.add(
            new QueueEntry(entry, queueEntry.generation(), queueEntry.tableIterator()));
      }
      lastKey = key;
    }

    for (final SSTable table : ssTables) {
      table.close();
      Files.delete(table.getPath());
    }

    return flush(newPath, entries);
  }

  private static boolean isTombstoneBytes(byte[] key) {
    return Arrays.equals(SSTableConstants.TOMBSTONE_BYTES, key);
  }

  private static boolean isEqualBytes(byte[] lastKey, byte[] key) {
    return Arrays.equals(lastKey, key);
  }

  private record QueueEntry(Entry entry, int generation, Iterator<Entry> tableIterator)
      implements Comparable<QueueEntry> {

    @Override
    public int compareTo(QueueEntry queueEntry) {
      final int cmp = Arrays.compareUnsigned(entry.key(), queueEntry.entry.key());
      if (cmp != 0) {
        return cmp;
      }

      return queueEntry.generation() - generation();
    }
  }
}
