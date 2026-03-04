package com.petwal.engine.sstable;

import com.petwal.engine.Lookup;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Comparator;

import static java.nio.file.StandardOpenOption.READ;

/**
 * Reads from an immutable SSTable file.
 *
 * <h3>Open sequence</h3>
 * Reads the fixed-size footer → verifies magic → loads the Bloom filter
 * → loads the full index into heap arrays ({@code byte[][] indexKeys},
 * {@code long[] indexOffsets}).
 *
 * <h3>get(key) sequence</h3>
 * Bloom check → binary search on {@code indexKeys} → positional file read
 * (reads header to learn keyLen/valueLen, then reads value bytes).
 *
 * <h3>Thread-safety</h3>
 * Uses stateful {@code ch.position()} + {@code ch.read()}: safe only under
 * {@link com.petwal.engine.LsmStore}'s {@code synchronized(this)} guard. A concurrent caller
 * would need {@code ch.read(buf, offset)} instead.
 */
public final class SSTableReader implements Closeable {

    private static final int FOOTER_SIZE = 24;

    private static final Comparator<byte[]> KEY_ORDER = (a, b) -> {
        final int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            final int diff = Byte.toUnsignedInt(a[i]) - Byte.toUnsignedInt(b[i]);
            if (diff != 0) return diff;
        }
        return a.length - b.length;
    };

    private final FileChannel fileChannel;
    private final BloomFilter bloom;
    private final byte[][] indexKeys;
    private final long[] indexOffsets;

    private SSTableReader(final FileChannel fileChannel, final BloomFilter bloom,
                          final byte[][] indexKeys, final long[] indexOffsets) {
        this.fileChannel = fileChannel;
        this.bloom = bloom;
        this.indexKeys = indexKeys;
        this.indexOffsets = indexOffsets;
    }

    public static SSTableReader open(final Path path) throws IOException {
        final FileChannel channel = FileChannel.open(path, READ);
        try {
            final long fileSize = channel.size();
            if (fileSize < FOOTER_SIZE + 8) {
                throw new IOException("File too small to be a valid SSTable: " + path);
            }

            // Read and validate footer
            final byte[] footerBytes = readChannel(channel, fileSize - FOOTER_SIZE, FOOTER_SIZE);
            final ByteBuffer footer = ByteBuffer.wrap(footerBytes);
            final long bloomOffset = footer.getLong();
            final long indexOffset = footer.getLong();
            final long magic = footer.getLong();
            if (magic != SSTableConstants.MAGIC) {
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
            long pos = indexOffset + 4; // not final — advanced per entry
            for (int i = 0; i < numEntries; i++) {
                final int keyLen = ByteBuffer.wrap(readChannel(channel, pos, 4)).getInt();
                pos += 4;
                indexKeys[i] = readChannel(channel, pos, keyLen);
                pos += keyLen;
                indexOffsets[i] = ByteBuffer.wrap(readChannel(channel, pos, 8)).getLong();
                pos += 8;
            }

            return new SSTableReader(channel, bloom, indexKeys, indexOffsets);
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
        int lo = 0;
        int hi = indexKeys.length - 1;
        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            final int cmp = KEY_ORDER.compare(indexKeys[mid], key);

            if (cmp < 0) {
                lo = mid + 1;
            }
            else if (cmp > 0){
                hi = mid - 1;
            }
            else{
                return readRecord(indexOffsets[mid]);
            }
        }
        return Lookup.absent();
    }

    private Lookup readRecord(final long dataOffset) throws IOException {
        final byte[] header = readChannel(fileChannel, dataOffset, SSTableConstants.RECORD_HEADER_SIZE);
        final ByteBuffer h = ByteBuffer.wrap(header);
        final int keyLen = h.getInt();
        final int valueLen = h.getInt();

        if (isTombstone(valueLen)) return Lookup.deleted();

        final byte[] value = readChannel(fileChannel, dataOffset + SSTableConstants.RECORD_HEADER_SIZE + keyLen, valueLen);
        return Lookup.found(value);
    }

    @Override
    public void close() throws IOException {
        fileChannel.close();
    }

    private static byte[] readChannel(final FileChannel channel, final long offset, final int len) throws IOException {
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

}
