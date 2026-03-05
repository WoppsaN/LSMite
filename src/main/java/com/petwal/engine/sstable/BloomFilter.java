package com.petwal.engine.sstable;

import com.google.common.hash.Hashing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Probabilistic Bloom filter targeting ~1% false-positive rate.
 *
 * <h3>Design decisions</h3>
 * <ul>
 *   <li><b>Bit count</b>: {@code m = ceil(-n·ln(0.01)/ln(2)²)} ≈ 9.6 bits/element.</li>
 *   <li><b>Hash functions</b>: {@code k = round((m/n)·ln(2))} via double-hashing:
 *       {@code bit_i = (h1 + i·h2) mod m}, where h1/h2 are the two 64-bit halves of
 *       {@code Guava Hashing.murmur3_128(key)} — the same approach used by Cassandra.</li>
 *   <li><b>Serialization</b>: {@code [numBits:4][k:1][bitArray: ceil(numBits/8) bytes]}.</li>
 *   <li><b>Precondition</b>: {@code n} must be &gt; 0; throws {@link IllegalArgumentException}
 *       otherwise.</li>
 * </ul>
 */
final class BloomFilter {

    private final int numBits;
    private final int k;
    private final byte[] bits;

    /** Constructs a filter sized for {@code n} expected elements at ~1% FPR. */
    public BloomFilter(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("Expected insertions (n) must be > 0");
        }

        final double ln01 = Math.log(0.01);
        final double ln2 = Math.log(2);
        this.numBits = (int) Math.ceil(-n * ln01 / (ln2 * ln2));
        this.k = (int) Math.max(1, Math.round((double) numBits / n * ln2));
        this.bits = new byte[(numBits + 7) / 8];
    }

    private BloomFilter(int numBits, int k, byte[] bits) {
        this.numBits = numBits;
        this.k = k;
        this.bits = bits;
    }

    void add(byte[] key) {
        final ByteBuffer buf = murmur128(key);
        final long h1 = buf.getLong();
        final long h2 = buf.getLong();
        for (int i = 0; i < k; i++) {
            final int bit = (int) (((h1 + (long) i * h2) & Long.MAX_VALUE) % numBits);
            bits[bit / 8] |= (byte) (1 << (bit % 8));
        }
    }

    boolean mightContain(byte[] key) {
        final ByteBuffer buf = murmur128(key);
        final long h1 = buf.getLong();
        final long h2 = buf.getLong();
        for (int i = 0; i < k; i++) {
            final int bit = (int) (((h1 + (long) i * h2) & Long.MAX_VALUE) % numBits);
            if ((bits[bit / 8] & (1 << (bit % 8))) == 0) return false;
        }
        return true;
    }

    private static ByteBuffer murmur128(final byte[] key) {
        final byte[] b = Hashing.murmur3_128().hashBytes(key).asBytes();
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Serializes as {@code [numBits:4][k:1][bitArray]}. */
    byte[] serialize() {
        final ByteBuffer buf = ByteBuffer.allocate(4 + 1 + bits.length);
        buf.putInt(numBits);
        buf.put((byte) k);
        buf.put(bits);
        return buf.array();
    }

    static BloomFilter deserialize(final byte[] data) {
        final ByteBuffer buf = ByteBuffer.wrap(data);
        final int numBits = buf.getInt();
        final int k = Byte.toUnsignedInt(buf.get());
        final byte[] bits = new byte[buf.remaining()];
        buf.get(bits);
        return new BloomFilter(numBits, k, bits);
    }
}
