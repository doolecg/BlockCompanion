package io.blockcompanion.core.link;

import io.blockcompanion.core.util.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The game's end of the live link to BlockDesigner: a TCP server on the loopback address only, speaking one JSON object
 * per line (see {@code docs/link-protocol.md}). An app must send {@code hello} with the instance's token first; anything
 * else closes the connection. Messages arrive on the connection's own thread; the game hands them to its main thread.
 */
public final class LinkServer implements AutoCloseable {
    public static final int PROTOCOL = 1;
    /** Longest line accepted (a project sent as base64 is one line). */
    public static final int MAX_LINE = 96 * 1024 * 1024;

    /** What the game does with the link. Called on the link's threads. */
    public interface Handler {
        /** An app said hello with the right token; answer with the welcome. */
        Map<String, Object> welcome(Connection c);

        void message(Connection c, Map<String, Object> message);

        void closed(Connection c);
    }

    private final String token;
    private final Handler handler;
    private final Consumer<String> log;
    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    private ServerSocket socket;
    private volatile boolean closed;

    public LinkServer(String token, Handler handler, Consumer<String> log) {
        this.token = token;
        this.handler = handler;
        this.log = log;
    }

    /** A random token for an instance file. */
    public static String newToken() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /**
     * Opens a port on 127.0.0.1 chosen by the system and starts accepting; returns the port. Always IPv4: the JVM's
     * loopback address is {@code ::1} when it prefers IPv6 (some launchers and mods set that), and apps dial 127.0.0.1.
     */
    public int start() throws IOException {
        socket = new ServerSocket(0, 8, InetAddress.getByAddress("localhost", new byte[]{127, 0, 0, 1}));
        Thread t = new Thread(this::acceptLoop, "BlockCompanion link");
        t.setDaemon(true);
        t.start();
        return socket.getLocalPort();
    }

    public int port() {
        return socket == null ? 0 : socket.getLocalPort();
    }

    /** Apps connected and past the hello. */
    public List<Connection> connections() {
        return connections.stream().filter(c -> c.ready).toList();
    }

    public void broadcast(Map<String, Object> message) {
        for (Connection c : connections()) c.send(message);
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket s = socket.accept();
                s.setTcpNoDelay(true);
                Connection c = new Connection(s);
                connections.add(c);
                Thread t = new Thread(c::readLoop, "BlockCompanion link reader");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!closed) log.accept("Link: accept failed: " + e);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        for (Connection c : connections) c.close();
        connections.clear();
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }

    /** One app connected to the game. */
    public final class Connection {
        private final Socket socket;
        private final OutputStream out;
        private volatile boolean ready;
        private volatile String app = "?";
        private volatile String appVersion = "";
        private volatile Instant since = Instant.now();
        /** What the app last said about itself ({@code app-status}): its open project and whether Live is on. */
        private volatile String project = "";
        private volatile boolean live;

        private Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.out = socket.getOutputStream();
        }

        /** The app's name from its hello, e.g. "BlockDesigner Resource Tracker". */
        public String app() {
            return app;
        }

        public String appVersion() {
            return appVersion;
        }

        public boolean ready() {
            return ready;
        }

        /** When the app said hello. */
        public Instant since() {
            return since;
        }

        /** The project the app has open, from its {@code app-status}; empty when it hasn't said. */
        public String project() {
            return project;
        }

        /** The app sends every change (its Live button), from its {@code app-status}. */
        public boolean live() {
            return live;
        }

        void appStatus(String project, boolean live) {
            this.project = project == null ? "" : project;
            this.live = live;
        }

        public void send(Map<String, Object> message) {
            byte[] line = (Json.write(message) + "\n").getBytes(StandardCharsets.UTF_8);
            synchronized (out) {
                try {
                    out.write(line);
                    out.flush();
                } catch (IOException e) {
                    close();
                }
            }
        }

        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // gone
            }
        }

        private void readLoop() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8), 1 << 16)) {
                String line;
                while ((line = readLine(in)) != null) {
                    if (line.isBlank()) continue;
                    Map<String, Object> m = Json.object(Json.parse(line));
                    String type = Json.string(m.get("type"), "");
                    if (!ready) {
                        if (!type.equals("hello") || !sameToken(Json.string(m.get("token"), ""))) {
                            send(Map.of("type", "error", "message", "Wrong or missing token"));
                            break;
                        }
                        app = Json.string(m.get("app"), "app");
                        appVersion = Json.string(m.get("version"), "");
                        since = Instant.now();
                        ready = true;
                        send(handler.welcome(this));
                        continue;
                    }
                    if (type.equals("ping")) {
                        send(Map.of("type", "pong"));
                        continue;
                    }
                    handler.message(this, m);
                }
            } catch (SocketException e) {
                // closed
            } catch (IOException | RuntimeException e) {
                if (!closed) log.accept("Link: " + app + " sent something unreadable: " + e);
            } finally {
                close();
                connections.remove(this);
                if (ready) handler.closed(this);
            }
        }

        private boolean sameToken(String given) {
            return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A line of at most {@link #MAX_LINE} characters, or null at the end. */
    static String readLine(BufferedReader in) throws IOException {
        StringBuilder b = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return b.toString();
            if (c != '\r') b.append((char) c);
            if (b.length() > MAX_LINE) throw new IOException("Line too long");
        }
        return b.isEmpty() ? null : b.toString();
    }
}
