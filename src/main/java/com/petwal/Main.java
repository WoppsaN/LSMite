package com.petwal;

import com.petwal.api.KeyValueStore;
import com.petwal.engine.LsmStore;

import java.io.IOException;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws IOException {
        Path dbPath = Path.of("demo-store.db");

        System.out.println("=== AppendOnlyStore Demo ===");

        // First session: write some data
        try (KeyValueStore store = new LsmStore(dbPath)) {
            store.put("hello".getBytes(), "world".getBytes());
            store.put("foo".getBytes(), "bar".getBytes());
            store.put("count".getBytes(), "1".getBytes());
            System.out.println("put: hello -> world");
            System.out.println("put: foo -> bar");
            System.out.println("put: count -> 1");

            final byte[] helloVal = store.get("hello".getBytes());
            System.out.println("get: hello -> " + new String(helloVal));

            store.put("count".getBytes(), "2".getBytes());
            System.out.println("put: count -> 2 (overwrite)");

            store.delete("foo".getBytes());
            System.out.println("delete: foo");

            final byte[] fooVal = store.get("foo".getBytes());
            System.out.println("get: foo -> " + fooVal + " (deleted)");
        }

    }
}
