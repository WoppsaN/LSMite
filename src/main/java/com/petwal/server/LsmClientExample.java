package com.petwal.server;

import java.io.*;
import java.net.Socket;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Self-contained demo client that connects to a running {@link LsmServer} and exercises
 * the line-based protocol.
 *
 * <p>Usage: {@code java LsmClientExample [host] [port]}
 * <br>Defaults: host = localhost, port = 7777
 */
public final class LsmClientExample {

    public static void main(String[] args) throws IOException {
        final String host = "localhost";
        final int port = 7777;

        System.out.printf("Connecting to %s:%d%n%n", host, port);

        try (Socket socket = new Socket(host, port);
             final PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), UTF_8), true);
             final BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8))) {

            send(out, in, "PUT greeting hello world");  // value with space preserved
            send(out, in, "PUT count 42");
            send(out, in, "GET greeting");              // → hello world
            send(out, in, "GET count");                 // → 42
            send(out, in, "GET missing");               // → NOT_FOUND
            send(out, in, "DELETE count");              // → OK
            send(out, in, "GET count");                 // → NOT_FOUND
            send(out, in, "PUT keyonly");               // → ERROR PUT requires key and value
            send(out, in, "UNKNOWN cmd");               // → ERROR unknown command: UNKNOWN
        }
    }

    private static void send(final PrintWriter out, final BufferedReader in, final String cmd) throws IOException {
        out.println(cmd);
        System.out.printf(">> %s%n<< %s%n%n", cmd, in.readLine());
    }
}
