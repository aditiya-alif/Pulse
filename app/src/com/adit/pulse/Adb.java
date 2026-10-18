// language: Java, file: Adb.java, target: Android 16 (API 36)
// A minimal ADB client that talks to the local adb server over TCP.
//
// Why a raw client instead of the adb binary for every read: the binary prints a
// banner, and a fresh process per sample would cost ~500 ms each. The server,
// once up, speaks a small framed protocol on 127.0.0.1, and a shell opened over
// it STREAMS - one connection, many lines, low latency. That is what makes a
// 0.5 s cadence possible.
//
// This app runs its OWN server on 5038 and talks only to that. It deliberately
// never falls back to 5037, which is where LADB (or a desktop adb) listens:
// attaching there would borrow that server's key and pairing, and the whole
// point of pairing here is that this app owns its connection.
package com.adit.pulse;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

final class Adb {
    static final int OWN_PORT = 5038;
    private static final int CONNECT_TIMEOUT_MS = 1500;
    private static final int READ_TIMEOUT_MS = 15000;

    private static volatile int ownPort = OWN_PORT;
    private static volatile String serial;

    private Adb() { }

    static void useOwnServer(int port) { ownPort = port; }

    /** Forget the cached serial. Call after (re)connecting: the address changes. */
    static void reset() {
        serial = null;
    }

    // ---------------------------------------------------------------- protocol

    /**
     * Find the transport serial of an attached device, or null.
     *
     * WHEN SEVERAL ARE ATTACHED, THE LOOPBACK PORT WINS. `adb tcpip 5555` can leave
     * the server holding both the loopback entry and a wireless one, and taking
     * whichever came first meant the app silently ran its shell over the wrong
     * transport. What identifies loopback is the PORT (5555), not the host: the
     * wireless link is ALSO reached as `localhost:<random port>`, so a host prefix
     * alone cannot tell them apart.
     */
    private static String findSerial() {
        String cached = serial;
        if (cached != null && !cached.isEmpty()) return cached;
        try {
            String out = host(ownPort, "devices");
            String first = null;
            for (String line : out.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("List")) continue;
                String[] parts = line.split("\\s+");
                if (parts.length < 2 || !"device".equals(parts[1])) continue;
                String s = normalise(parts[0]);
                if (first == null) first = s;
                if (isLoopbackPort(s)) { serial = s; return serial; }
            }
            if (first != null) { serial = first; return serial; }
        } catch (IOException ignored) { }
        return null;
    }

    /** True when this serial names the fixed loopback port (5555). */
    private static boolean isLoopbackPort(String s) {
        if (s == null) return false;
        if (s.startsWith("emulator-")) {
            try {
                return Integer.parseInt(s.substring("emulator-".length())) + 1 == 5555;
            } catch (Throwable ignored) { return false; }
        }
        int colon = s.lastIndexOf(':');
        if (colon < 0) return false;
        try {
            return Integer.parseInt(s.substring(colon + 1)) == 5555;
        } catch (Throwable ignored) { return false; }
    }

    /**
     * Turn a `host:devices` line into a serial `host:transport:` accepts.
     *
     * The server prefixes every serial with a 4-hex transport id and no
     * separator, so a wireless device prints as `0019192.168.1.6:32923`. Passing
     * that whole string to transport: asks for a device that does not exist. An
     * address (ip:port / localhost:port) is unambiguous, so it is extracted
     * directly; otherwise the 4-hex prefix is dropped only when what remains is
     * still a plausible USB serial.
     */
    private static String normalise(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{1,3}(?:\\.\\d{1,3}){3}:\\d+|localhost:\\d+|127\\.0\\.0\\.1:\\d+)")
                .matcher(s);
        if (m.find()) return m.group(1);
        if (s.length() >= 4 + 6 && isHex4(s.substring(0, 4))) return s.substring(4);
        return s;
    }

    private static boolean isHex4(String s) {
        if (s.length() != 4) return false;
        for (int i = 0; i < 4; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')))
                return false;
        }
        return true;
    }

    /** True when a device is reachable. Cheap: one devices query. */
    static boolean connected() {
        return findSerial() != null;
    }

    /**
     * The address of the connected device (e.g. localhost:34153), or null.
     *
     * This is the already-normalised serial, so callers that want to SHOW the
     * connection do not have to strip the 4-hex transport prefix and the trailing
     * state word off a raw `devices` line themselves.
     */
    static String address() {
        return findSerial();
    }

    /**
     * Run a one-shot shell command and return its output, or null.
     *
     * RETRIES ONCE with a freshly resolved device. The transport serial is cached,
     * and that cache goes stale the moment the adb server restarts: the wireless
     * debugging port changes, but the cached address still points at the old one,
     * so the transport is refused and the command returns null. Every screen then
     * reads "--" for no visible reason. Clearing the cache and resolving again on
     * the first failure turns that into a self-healing call instead of a dead one.
     */
    static String shell(String cmd) {
        for (int attempt = 0; attempt < 2; attempt++) {
            String s = findSerial();
            if (s == null) return null;
            try (Socket sock = connect(ownPort)) {
                write(sock, "host:transport:" + s);
                expectOkay(sock);
                write(sock, "shell:" + cmd);
                expectOkay(sock);
                return drain(sock);
            } catch (IOException e) {
                serial = null;   // stale address; resolve again on the next attempt
                Diag.log("adb shell failed (attempt " + (attempt + 1) + "): " + cmd, e);
            }
        }
        return null;
    }

    /**
     * Open a long-lived shell that streams. Used by the sampler: the command is a
     * loop, and each iteration's stdout line arrives here without a new process.
     * Caller must close the returned session.
     *
     * The socket carries a READ TIMEOUT rather than blocking forever. The sampler
     * loop reads this socket in a `while (readLine() != null)` loop, so a socket
     * that never times out means a transport that has gone quiet hangs the loop
     * for good: no samples, no reconnect, and the overlay freezes until the app is
     * restarted. A full-screen screen recording does exactly that - it starves the
     * device's dump services and the sampler's output stops. A timeout turns that
     * silence into an EOF the loop can recover from.
     */
    static Session open(String cmd) throws IOException {
        String s = findSerial();
        if (s == null) throw new IOException("no adb device");
        Socket sock = connect(ownPort);
        // The socket must be closed if ANY of the handshake steps fails, or the
        // file descriptor leaks. The sampler reconnects on a flaky link, so a leak
        // here compounds: one descriptor per failed attempt, and the process runs
        // out of descriptors after a few hours. try/catch rather than
        // try-with-resources because the socket is HANDED OFF to the Session on
        // success - closing it there would close the live session.
        try {
            sock.setSoTimeout(SESSION_READ_TIMEOUT_MS);
            write(sock, "host:transport:" + s);
            expectOkay(sock);
            write(sock, "shell:" + cmd);
            expectOkay(sock);
            return new Session(sock);
        } catch (IOException e) {
            try { sock.close(); } catch (Throwable ignored) { }
            throw e;
        }
    }

    /**
     * How long a streaming session may sit silent before it is treated as dead.
     *
     * The sampler emits a line every 0.5 s, so several seconds of silence is not a
     * slow device - it is a stalled or broken transport.
     */
    private static final int SESSION_READ_TIMEOUT_MS = 6000;

    /** A streaming shell session. */
    static final class Session implements java.io.Closeable {
        private final Socket socket;
        private final InputStream in;

        Session(Socket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
        }

        /**
         * Read one '\n'-terminated line, or null when the stream is done.
         *
         * Reads byte by byte rather than via a BufferedReader: the shell protocol
         * multiplexes raw bytes, and a buffered reader would swallow data on a
         * partial line.
         *
         * A WALL-CLOCK DEADLINE guards the whole line, not just each read. The
         * socket's SoTimeout only fires when no byte arrives at all, so a stream
         * that keeps dribbling bytes - or one whose producer died while the kernel
         * still holds buffered data and never sends EOF - leaves `in.read()`
         * returning happily forever and the loop never notices. That was the
         * freeze: the sampler's shell process was gone, the socket was still
         * "open", and the overlay sat on its last values. The deadline bounds a
         * line regardless of how the bytes trickle.
         */
        String readLine() {
            StringBuilder sb = new StringBuilder(160);
            long deadline = System.currentTimeMillis() + SESSION_READ_TIMEOUT_MS;
            try {
                while (true) {
                    if (System.currentTimeMillis() > deadline) return null;
                    int c = in.read();
                    if (c < 0) return sb.length() == 0 ? null : sb.toString();
                    if (c == '\n') return sb.toString();
                    if (c != '\r') sb.append((char) c);
                }
            } catch (IOException e) {
                return null;
            }
        }

        @Override public void close() {
            try { socket.close(); } catch (Throwable ignored) { }
        }
    }

    // ---------------------------------------------------------------- plumbing

    private static String host(int port, String cmd) throws IOException {
        try (Socket s = connect(port)) {
            write(s, "host:" + cmd);
            expectOkay(s);
            return drain(s);
        }
    }

    private static Socket connect(int port) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS);
        s.setSoTimeout(READ_TIMEOUT_MS);
        return s;
    }

    private static void write(Socket s, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        OutputStream os = s.getOutputStream();
        os.write(String.format("%04x", body.length).getBytes(StandardCharsets.US_ASCII));
        os.write(body);
        os.flush();
    }

    private static void expectOkay(Socket s) throws IOException {
        byte[] b = new byte[4];
        new DataInputStream(s.getInputStream()).readFully(b);
        String st = new String(b, StandardCharsets.US_ASCII);
        if (!"OKAY".equals(st)) throw new IOException("adb " + st);
    }

    private static String drain(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(4096);
        byte[] buf = new byte[8192];
        try {
            int k;
            while ((k = in.read(buf)) > 0) bos.write(buf, 0, k);
        } catch (java.net.SocketTimeoutException ignored) { }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
