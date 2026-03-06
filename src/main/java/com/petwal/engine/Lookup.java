package com.petwal.engine;

/**
 * Three-value lookup result distinguishing absent from deleted.
 *
 * <p>A tombstone in a newer layer must stop the search before an older layer returns a stale value.
 * Without a dedicated DELETED state, a MemTable delete followed by a matching SSTable entry would
 * incorrectly surface the old value.
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
