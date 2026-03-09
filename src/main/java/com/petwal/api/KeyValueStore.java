package com.petwal.api;

import java.io.Closeable;
import java.io.IOException;

/**
 * Public interface for the key-value storage engine.
 *
 * <p>All keys and values are raw byte arrays. A {@code null} return from {@link #get} means the
 * key does not exist (or has been deleted). Implementations must be {@link java.io.Closeable} to
 * allow proper resource cleanup.
 */
public interface KeyValueStore extends Closeable {

  void put(byte[] key, byte[] value) throws IOException;

  byte[] get(byte[] key) throws IOException;

  void delete(byte[] key) throws IOException;

}
