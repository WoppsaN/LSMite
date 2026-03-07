package com.petwal.engine.sstable;

import static java.nio.file.StandardOpenOption.READ;

import com.petwal.engine.Lookup;
import com.petwal.engine.memtable.Entry;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Reads from an immutable SSTable file.
 *
 * <h3>Open sequence</h3>
 *
 * Reads the fixed-size footer → verifies magic → loads the Bloom filter → loads the full index into
 * heap arrays ({@code byte[][] indexKeys}, {@code long[] indexOffsets}).
 *
 * <h3>get(key) sequence</h3>
 *
 * Bloom check → binary search on {@code indexKeys} → positional file read (reads header to learn
 * keyLen/valueLen, then reads value bytes).
 *
 * <h3>Thread-safety</h3>
 *
 * Uses stateful {@code ch.position()} + {@code ch.read()}: safe only under {@link
 * com.petwal.engine.LsmStore}'s {@code synchronized(this)} guard. A concurrent caller would need
 * {@code ch.read(buf, offset)} instead.
 */
public final class SSTable implements Closeable {

  // For comparing SSTable keys
  private static final Comparator<byte[]> BYTE_ARRAY_COMPARATOR =
      (a, b) -> {
        final int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
          final int diff = Byte.toUnsignedInt(a[i]) - Byte.toUnsignedInt(b[i]);
          if (diff != 0) return diff;
        }
        return a.length - b.length;
      };

  private final Path path;
  private final FileChannel fileChannel;
  private final BloomFilter bloom;
  private final byte[][] indexKeys;
  private final long[] indexOffsets;
  private final long bloomOffset;

  private SSTable(
      final Path path,
      final FileChannel fileChannel,
      final BloomFilter bloom,
      final byte[][] indexKeys,
      final long[] indexOffsets,
      final long bloomOffset) {

    this.path = path;
    this.fileChannel = fileChannel;
    this.bloom = bloom;
    this.indexKeys = indexKeys;
    this.indexOffsets = indexOffsets;
    this.bloomOffset = bloomOffset;
  }

  public Path getPath() {
    return path;
  }

  public static SSTable open(final Path path) throws IOException {
    final FileChannel channel = FileChannel.open(path, READ);
    try {
      final long fileSize = channel.size();
      if (fileSize < SSTableConstants.FOOTER_SIZE + 8) {
        throw new IOException("File too small to be a valid SSTable: " + path);
      }

      // Read and validate footer
      final byte[] footerBytes = readChannel(channel, fileSize - SSTableConstants.FOOTER_SIZE, SSTableConstants.FOOTER_SIZE);
      final ByteBuffer footer = ByteBuffer.wrap(footerBytes);
      final long bloomOffset = footer.getLong();
      final long indexOffset = footer.getLong();
      final long magic = footer.getLong();
      if (magic != SSTableConstants.MAGIC_VALUE) {
        throw new IOException("Invalid SSTable magic in footer: " + path);
      }

      // Load Bloom filter
      final int bloomSize = (int) (indexOffset - bloomOffset);
      final byte[] bloomBytes = readChannel(channel, bloomOffset, bloomSize);
      final BloomFilter bloom = BloomFilter.deserialize(bloomBytes);

      // Load index
      final byte[] indexHeader = readChannel(channel, indexOffset, 4);
      final int numEntries = ByteBuffer.wrap(indexHeader).getInt();
      final byte[][] indexKeys = new byte[numEntries][];
      final long[] indexOffsets = new long[numEntries];
      long pos = indexOffset + 4; // skip header
      for (int i = 0; i < numEntries; i++) {
        final int keyLen = ByteBuffer.wrap(readChannel(channel, pos, 4)).getInt();
        pos += 4;
        indexKeys[i] = readChannel(channel, pos, keyLen);
        pos += keyLen;
        indexOffsets[i] = ByteBuffer.wrap(readChannel(channel, pos, 8)).getLong();
        pos += 8;
      }

      return new SSTable(path, channel, bloom, indexKeys, indexOffsets, bloomOffset);
    } catch (IOException e) {
      channel.close();
      throw e;
    }
  }

  public Lookup get(final byte[] key) throws IOException {
    if (!bloom.mightContain(key)) {
      return Lookup.absent();
    }

    // Binary search over sorted index keys
    int rangeStart = 0;
    int rangeEnd = indexKeys.length - 1;
    while (rangeStart <= rangeEnd) {
      final int middleIndex = (rangeStart + rangeEnd) / 2;
      final int compare = BYTE_ARRAY_COMPARATOR.compare(indexKeys[middleIndex], key);

      if (compare < 0) {
        rangeStart = middleIndex + 1;
      } else if (compare > 0) {
        rangeEnd = middleIndex - 1;
      } else {
        return readRecord(indexOffsets[middleIndex]);
      }
    }
    return Lookup.absent();
  }

  /**
   * Returns an {@link Iterable} that streams all entries in sorted key order by scanning the data
   * section sequentially. Each call produces a fresh iterator starting from the beginning.
   *
   * <p>IO errors during iteration are wrapped in {@link UncheckedIOException}.
   */
  public Iterable<Entry> entries() {
    return SSTableIterator::new;
  }

  private Lookup readRecord(final long dataOffset) throws IOException {
    final byte[] header = readChannel(fileChannel, dataOffset, SSTableConstants.RECORD_HEADER_SIZE);
    final ByteBuffer headerBuffer = ByteBuffer.wrap(header);
    final int keyLen = headerBuffer.getInt();
    final int valueLen = headerBuffer.getInt();

    if (isTombstone(valueLen)) {
      return Lookup.deleted();
    }

    final byte[] value =
        readChannel(
            fileChannel, dataOffset + SSTableConstants.RECORD_HEADER_SIZE + keyLen, valueLen);
    return Lookup.found(value);
  }

  @Override
  public void close() throws IOException {
    fileChannel.close();
  }

  private static byte[] readChannel(final FileChannel channel, final long offset, final int len)
      throws IOException {
    final ByteBuffer buf = ByteBuffer.allocate(len);
    channel.position(offset);
    while (buf.hasRemaining()) {
      final int n = channel.read(buf);
      if (n == -1) {
        throw new IOException("Unexpected EOF at offset " + offset);
      }
    }
    return buf.array();
  }

  private static boolean isTombstone(int valueLen) {
    return valueLen == SSTableConstants.TOMBSTONE;
  }

  private final class SSTableIterator implements Iterator<Entry> {

    private long pos = SSTableConstants.MAGIC_SIZE; // Data section starts after the magic record

    @Override
    public boolean hasNext() {
      return pos < bloomOffset; // Where bloom section starts, data section ends
    }

    @Override
    public Entry next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }

      try {
        final int keyLen = ByteBuffer.wrap(readChannel(fileChannel, pos, 4)).getInt();
        pos += 4;
        final int valueLen = ByteBuffer.wrap(readChannel(fileChannel, pos, 4)).getInt();
        pos += 4;
        final byte[] key = readChannel(fileChannel, pos, keyLen);
        pos += keyLen;
        final byte[] value;
        if (valueLen == SSTableConstants.TOMBSTONE) {
          value = SSTableConstants.TOMBSTONE_BYTES;
        } else {
          value = readChannel(fileChannel, pos, valueLen);
          pos += valueLen;
        }
        return new Entry(key, value);
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

}
