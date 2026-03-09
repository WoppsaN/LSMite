package com.petwal.engine.memtable;

/**
 * Immutable data carrier for a single key-value record in the MemTable and SSTable flush path.
 *
 * <p>A {@code null} value indicates a tombstone (deleted key), as tested by {@link #isTombstone()}.
 */
public record Entry(byte[] key, byte[] value) {

  public boolean isTombstone() {
    return value == null;
  }
}
