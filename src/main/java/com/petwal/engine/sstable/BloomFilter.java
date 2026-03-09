package com.petwal.engine.sstable;

import com.google.common.hash.Hashing;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A classic Bloom filter used to guard SSTable index lookups.
 *
 * <h3>Parameters</h3>
 *
 * <p>Given the expected number of insertions {@code n}, the number of bits {@code m} and the
 * number of hash functions {@code k} are chosen to target approximately 1% false-positive rate
 * using the standard formulae: {@code m = ceil(-n * ln(0.01) / (ln2)^2)} and
 * {@code k = round(m/n * ln2)}.
 *
 * <h3>Hashing</h3>
 *
 * <p>Murmur3-128 double-hashing is used: {@code bit_i = (hash1 + i * hash2) % numBits}. This
 * avoids the cost of computing {@code k} independent hash functions while maintaining good
 * uniformity.
 *
 * <h3>Serialisation</h3>
 *
 * <p>Format: {@code [numBits:4][k:1][bitArray: ceil(numBits/8) bytes]}.
 */
final class BloomFilter {

  private final int numBits;
  private final int k;
  private final byte[] bits;

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
    final long hash1 = buf.getLong();
    final long hash2 = buf.getLong();
    for (int i = 0; i < k; i++) {
      final int bit = (int) (((hash1 + (long) i * hash2) & Long.MAX_VALUE) % numBits);
      bits[bit / 8] |= (byte) (1 << (bit % 8));
    }
  }

  boolean mightContain(byte[] key) {
    final ByteBuffer buf = murmur128(key);
    final long hash1 = buf.getLong();
    final long hash2 = buf.getLong();
    for (int i = 0; i < k; i++) {
      final int bit = (int) (((hash1 + (long) i * hash2) & Long.MAX_VALUE) % numBits);
      if ((bits[bit / 8] & (1 << (bit % 8))) == 0){
        return false;
      }
    }
    return true;
  }

  private static ByteBuffer murmur128(final byte[] key) {
    final byte[] b = Hashing.murmur3_128()
        .hashBytes(key)
        .asBytes();
    return ByteBuffer.wrap(b)
        .order(ByteOrder.LITTLE_ENDIAN);
  }

  byte[] serialize() {
    final ByteBuffer buf = ByteBuffer.allocate(SSTableConstants.LENGTH_SIZE + 1 + bits.length);
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
