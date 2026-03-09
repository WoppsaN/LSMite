package com.petwal.engine;

/**
 * Three-valued result type for key lookups across the layered MemTable + SSTable read path.
 *
 * <ul>
 *   <li>{@code ABSENT} — the key was not found in this layer; continue to the next layer.
 *   <li>{@code DELETED} — a tombstone was found; stop searching and return {@code null} to the
 *       caller.
 *   <li>{@code FOUND} — the key exists with a concrete value; stop searching and return the value.
 * </ul>
 */
public final class Lookup {

  private static final Lookup ABSENT = new Lookup(State.ABSENT, null);
  private static final Lookup DELETED = new Lookup(State.DELETED, null);

  private enum State {
    ABSENT,
    DELETED,
    FOUND
  }

  private final State state;
  private final byte[] value;

  private Lookup(State state, byte[] value) {
    this.state = state;
    this.value = value;
  }

  public static Lookup absent() {
    return ABSENT;
  }

  public static Lookup deleted() {
    return DELETED;
  }

  public static Lookup found(byte[] v) {
    return new Lookup(State.FOUND, v);
  }

  public boolean hasValueOrTombstone() {
    return state == State.FOUND || state == State.DELETED;
  }

  public byte[] value() {
    return value;
  }
}
