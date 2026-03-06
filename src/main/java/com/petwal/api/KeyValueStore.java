package com.petwal.api;

import java.io.Closeable;
import java.io.IOException;

/**
 * Stable public API surface for key-value storage.
 *
 * <p>Implementations vary in durability, ordering, and concurrency guarantees. Thread-safety is not
 * specified at this level; each implementation documents its own guarantees.
 */
public interface KeyValueStore extends Closeable {

  void put(byte[] key, byte[] value) throws IOException;

  byte[] get(byte[] key) throws IOException;

  void delete(byte[] key) throws IOException;
}
