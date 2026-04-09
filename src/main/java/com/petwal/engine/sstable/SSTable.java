package com.petwal.engine.sstable;

import static java.nio.file.StandardOpenOption.READ;

import com.petwal.comparator.ByteArrayComparators;
import com.petwal.engine.Lookup;
import com.petwal.engine.memtable.Entry;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * An immutable, on-disk sorted segment that stores key-value records flushed from a MemTable.
 *
 * <h3>File format</h3>
 *
 * <p>The layout is: {@code [magic:8][data section][bloom section][index section][footer:24]}. See
 * {@link SSTableConstants} for field-level encoding details.
 *
 * <h3>Read path</h3>
 *
 * <p>Lookups first check the Bloom filter to skip SSTables that definitely do not contain the key,
 * then perform a binary search over the in-memory index to find the record's data offset, and
 * finally seek the {@link java.nio.channels.FileChannel} to that offset to read the record.
 *
 * <h3>Thread safety</h3>
 *
 * <p>{@code SSTable} is NOT independently thread-safe. The underlying {@link
 * java.nio.channels.FileChannel} is stateful (position-based reads). Callers must ensure exclusive
 * access, typically via the {@code synchronized} lock on {@link com.petwal.engine.LsmStore}.
 */
public final class SSTable implements Closeable {

  private final Path path;
  private final FileChannel fileChannel;
  private final BloomFilter bloom;
  private final byte[][] indexKeys; // i -> key
  private final long[] indexOffsets; // i -> file offset
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
      if (fileSize < SSTableConstants.FOOTER_SIZE + SSTableConstants.MAGIC_SIZE) {
        throw new IOException("File too small to be a valid SSTable: " + path);
      }

      // Read and validate footer
      final byte[] footerBytes =
          readChannel(
              channel, fileSize - SSTableConstants.FOOTER_SIZE, SSTableConstants.FOOTER_SIZE);
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
      final byte[] indexHeader = readChannel(channel, indexOffset, SSTableConstants.LENGTH_SIZE);
      final int numEntries = ByteBuffer.wrap(indexHeader).getInt();
      final byte[][] indexKeys = new byte[numEntries][];
      final long[] indexOffsets = new long[numEntries];
      long pos = indexOffset + SSTableConstants.LENGTH_SIZE; // skip numEntries header
      for (int i = 0; i < numEntries; i++) {
        final int keyLen =
            ByteBuffer.wrap(readChannel(channel, pos, SSTableConstants.LENGTH_SIZE)).getInt();
        pos += SSTableConstants.LENGTH_SIZE;
        indexKeys[i] = readChannel(channel, pos, keyLen);
        pos += keyLen;
        indexOffsets[i] =
            ByteBuffer.wrap(readChannel(channel, pos, SSTableConstants.OFFSET_SIZE)).getLong();
        pos += SSTableConstants.OFFSET_SIZE;
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
      final int compare = ByteArrayComparators.LEXICOGRAPHIC.compare(indexKeys[middleIndex], key);

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
        final int keyLen =
            ByteBuffer.wrap(readChannel(fileChannel, pos, SSTableConstants.LENGTH_SIZE)).getInt();
        pos += SSTableConstants.LENGTH_SIZE;
        final int valueLen =
            ByteBuffer.wrap(readChannel(fileChannel, pos, SSTableConstants.LENGTH_SIZE)).getInt();
        pos += SSTableConstants.LENGTH_SIZE;
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
