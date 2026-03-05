package com.petwal;

import com.petwal.engine.LsmStore;
import com.petwal.server.LsmServer;

import java.nio.file.Path;

public class Main {

    private static final int DEFAULT_PORT = 7777;
    private static final int THREAD_POOL_SIZE = 10;
    private static final String DEFAULT_DATA_DIR = "lsmite-data";

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        String dataDir = args.length > 1 ? args[1] : DEFAULT_DATA_DIR;

        LsmStore store = new LsmStore(Path.of(dataDir));
        LsmServer server = new LsmServer(store, port, THREAD_POOL_SIZE);

        // Invoked on SIGINT (Ctrl+C) or SIGTERM
        Runtime.getRuntime()
                .addShutdownHook(new Thread(() -> {
                    System.out.println("[Main] Shutdown signal received.");
                    try {
                        server.stop();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }, "lsm-shutdown"));

        server.start();
        server.awaitTermination(); // block main thread until stop() is called
    }
}
