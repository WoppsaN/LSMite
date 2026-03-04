package com.petwal.engine;

import com.petwal.api.KeyValueStore;
import com.petwal.engine.memtable.MemTable;
import com.petwal.engine.sstable.SSTableReader;
import com.petwal.engine.sstable.SSTableWriter;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static java.util.Comparator.comparing;

/**
 * LSM-tree key-value store.
 *
 * <h3>Design decisions</h3>
 * <ul>
 *   <li><b>No WAL</b>: unflushed MemTable data is lost on crash. SSTables are crash-safe
 *       ({@code CREATE_NEW} + {@code force(true)}).</li>
 *   <li><b>MemTable</b>: bounded 4 MiB in-memory write buffer; auto-flushed on overflow.</li>
 *   <li><b>SSTable list</b>: {@code ssTables.get(0)} = newest; prepended on each flush.</li>
 *   <li><b>SSTable naming</b>: {@code sst-00001.sst} — zero-padded so lexicographic order
 *       equals creation order.</li>
 *   <li><b>nextSSTId</b>: {@code max(parsed ids) + 1} on startup — handles gaps correctly.</li>
 *   <li><b>{@code synchronized(this)}</b>: all public methods; protects MemTable + SSTable
 *       list atomicity.</li>
 * </ul>
 *
 * <h3>Read path</h3>
 * <pre>
 * get(key):
 *   r = memTable.get(key)
 *   if !r.isAbsent() → return r.isTombstone() ? null : r.value()
 *   for sst in ssTables:          // newest first
 *     r = sst.get(key)
 *     if !r.isAbsent() → return r.isTombstone() ? null : r.value()
 *   return null
 * </pre>
 */
public final class LsmStore implements KeyValueStore {

    private final Path directory;
    private MemTable memTable = new MemTable();
    private final List<SSTableReader> ssTables = new ArrayList<>(); // index 0 = newest
    private int nextSSTId = 1;

    public LsmStore(final Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
        loadExistingSSTables();
    }

    @Override
    public synchronized void put(byte[] key, byte[] value) throws IOException {
        memTable.put(Arrays.copyOf(key, key.length), value);
        if (memTable.shouldFlush()) {
            flush();
        }
    }

    @Override
    public synchronized byte[] get(byte[] key) throws IOException {
        Lookup result = memTable.get(key);
        if (result.isFoundOrTombStone()) {
            return result.value();
        }

        for (final SSTableReader tableReader : ssTables) {
            result = tableReader.get(key);
            if (result.isFoundOrTombStone()) {
                return result.value();
            }
        }
        return null;
    }

    @Override
    public synchronized void delete(byte[] key) throws IOException {
        memTable.delete(Arrays.copyOf(key, key.length));
        if (memTable.shouldFlush()) flush();
    }

    @Override
    public synchronized void close() throws IOException {
        for (SSTableReader sst : ssTables) sst.close();
    }

    /**
     * Flushes the current MemTable to a new SSTable file.
     * Package-private so tests can trigger flush without writing 4 MiB of data.
     */
    synchronized void flush() throws IOException {
        if (memTable.isEmpty()) return;
        final Path sstPath = directory.resolve(String.format("sst-%05d.sst", nextSSTId++));
        final SSTableReader reader = SSTableWriter.flush(memTable, sstPath);
        ssTables.addFirst(reader); // prepend: newest first
        memTable = new MemTable();
    }

    private void loadExistingSSTables() throws IOException {
        final List<Path> sstFilePaths = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "sst-*.sst")) {
            for (final Path path : stream) {
                sstFilePaths.add(path);
            }
        }
        // Sort ascending by name → creation order (oldest first)
        sstFilePaths.sort(comparing(path -> path.getFileName().toString()));

        int maxId = 0;
        // Build readers list oldest→newest, then reverse so index 0 = newest
        final List<SSTableReader> readers = new ArrayList<>();
        for (final Path path : sstFilePaths) {
            readers.add(SSTableReader.open(path));
            // Parse id from "sst-00001.sst" (4 prefix chars, 4 suffix chars)
            final String name = path.getFileName().toString();
            final int id = Integer.parseInt(name.substring(4, name.length() - 4));
            maxId = Math.max(maxId, id);
        }

        for (int i = readers.size() - 1; i >= 0; i--) {
            ssTables.add(readers.get(i));
        }
        nextSSTId = maxId + 1;
    }
}
