package io.blockcompanion.core.link;

import io.blockcompanion.core.sync.Hashes;
import io.blockcompanion.core.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The live link: instance files, the hello with the token, projects written into the library, status and grab. */
class GameLinkTest {
    @TempDir
    Path dir;

    GameLink link;
    final List<String> received = new ArrayList<>();

    final GameLink.Game game = new GameLink.Game() {
        public void projectReceived(String relative, Path file, String projectName, boolean open, String fromApp) {
            received.add(relative + "|" + projectName + "|" + open + "|" + fromApp);
        }

        public InstanceInfo refresh(InstanceInfo info) {
            return info.withUpdate("My World", List.of("C:/packs/Faithful.zip"), List.of(), info.updated(), false);
        }

        public Map<String, Object> status() {
            return Map.of("world", "My World", "placements", List.of());
        }
    };

    @AfterEach
    void close() {
        if (link != null) link.close();
    }

    GameLink start() {
        link = new GameLink(dir.resolve("schematics"), dir.resolve("instances"), InstanceInfo.CLIENT, "Steve", "1.21.1", "fabric", "0.2.0", dir,
                game, m -> { });
        link.start();
        return link;
    }

    /** A test app: a socket with line reading and writing. */
    static final class App implements AutoCloseable {
        final Socket s;
        final BufferedReader in;
        final OutputStream out;

        App(int port) throws IOException {
            s = new Socket(InetAddress.getLoopbackAddress(), port);
            s.setSoTimeout(5000);
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = s.getOutputStream();
        }

        void send(Map<String, Object> m) throws IOException {
            out.write((Json.write(m) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        Map<String, Object> read() throws IOException {
            String line = in.readLine();
            return line == null ? null : Json.object(Json.parse(line));
        }

        public void close() throws IOException {
            s.close();
        }
    }

    @Test
    void instanceFileHasPortTokenAndRefreshedFields() throws IOException {
        start();
        List<InstanceInfo> all = InstanceInfo.list(dir.resolve("instances"));
        assertThat(all).singleElement().satisfies(i -> {
            assertThat(i.port()).isEqualTo(link.info().port()).isPositive();
            assertThat(i.token()).hasSize(48);
            assertThat(i.kind()).isEqualTo("client");
            assertThat(i.world()).isEqualTo("My World");
            assertThat(i.clientPacks()).containsExactly("C:/packs/Faithful.zip");
            assertThat(i.alive(Instant.now())).isTrue();
        });
        link.close();
        InstanceInfo closed = InstanceInfo.list(dir.resolve("instances")).get(0);
        assertThat(closed.closed()).isTrue();
        assertThat(closed.alive(Instant.now())).isFalse();
        link = null;
    }

    @Test
    void wrongTokenIsRefused() throws IOException {
        start();
        try (App app = new App(link.info().port())) {
            app.send(Map.of("type", "hello", "token", "nope", "app", "test"));
            assertThat(app.read()).containsEntry("type", "error");
            assertThat(app.read()).isNull();
        }
    }

    @Test
    void projectIsWrittenAndHandedToTheGame() throws Exception {
        start();
        byte[] data = "fake project".getBytes(StandardCharsets.UTF_8);
        try (App app = new App(link.info().port())) {
            app.send(Map.of("type", "hello", "token", link.info().token(), "app", "Resource Tracker", "version", "1.2.0"));
            Map<String, Object> welcome = app.read();
            assertThat(welcome).containsEntry("type", "welcome");
            assertThat(Json.object(welcome.get("instance"))).doesNotContainKey("token");

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "project");
            m.put("file", "../../Castle: v2.bdproj");
            m.put("name", "Castle");
            m.put("open", true);
            m.put("sha256", Hashes.sha256(data));
            m.put("data", Base64.getEncoder().encodeToString(data));
            app.send(m);
            Map<String, Object> ack = app.read();
            assertThat(ack).containsEntry("type", "received").containsEntry("file", "BlockDesigner/Castle_ v2.bdproj");
            assertThat(Files.readAllBytes(dir.resolve("schematics/BlockDesigner/Castle_ v2.bdproj"))).isEqualTo(data);

            link.tick();
            assertThat(received).containsExactly("BlockDesigner/Castle_ v2.bdproj|Castle|true|Resource Tracker");
            assertThat(link.apps()).containsExactly("Resource Tracker");

            // Status goes out once something changed; grab reaches the app.
            link.statusChanged();
            link.tick();
            Map<String, Object> status = app.read();
            assertThat(status).containsEntry("type", "status").containsEntry("world", "My World");
            assertThat(link.requestGrab()).isTrue();
            assertThat(app.read()).containsEntry("type", "grab");

            app.send(Map.of("type", "ping"));
            assertThat(app.read()).containsEntry("type", "pong");

            m.put("sha256", "00");
            app.send(m);
            assertThat(app.read()).containsEntry("type", "error");
        }
    }

    @Test
    void grabWithoutAppsSaysSo() {
        start();
        assertThat(link.requestGrab()).isFalse();
    }

    @Test
    void cleanFileNames() {
        assertThat(GameLink.cleanFileName("Tower")).isEqualTo("Tower.bdproj");
        assertThat(GameLink.cleanFileName("a/b\\c.schem")).isEqualTo("c.schem");
        assertThat(GameLink.cleanFileName("..")).isEqualTo("Untitled.bdproj");
        assertThat(GameLink.cleanFileName("what?.bdproj")).isEqualTo("what_.bdproj");
    }

    @Test
    void jsonRoundTrips() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", "quote \" slash \\ line\nend\u0001");
        m.put("n", 5);
        m.put("d", 1.5);
        m.put("b", true);
        m.put("z", null);
        m.put("l", List.of(1, "two"));
        String text = Json.write(m);
        Map<String, Object> back = Json.object(Json.parse(text));
        assertThat(back.get("s")).isEqualTo(m.get("s"));
        assertThat(Json.integer(back.get("n"), 0)).isEqualTo(5);
        assertThat(Json.number(back.get("d"), 0)).isEqualTo(1.5);
        assertThat(back.get("b")).isEqualTo(true);
        assertThat(back).containsKey("z");
        assertThat(Json.array(back.get("l"))).hasSize(2);
    }
}
