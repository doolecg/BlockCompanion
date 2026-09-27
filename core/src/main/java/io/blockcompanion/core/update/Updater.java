package io.blockcompanion.core.update;

import io.blockcompanion.core.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the GitHub repository's latest release for a newer version of the mod, downloads the jar for this loader and
 * Minecraft version next to the running one, and swaps them when the game quits. Works off the game thread; the state is
 * read by the settings screen and the join message.
 *
 * <p>The new jar is downloaded as {@code <name>.jar.download} (loaders ignore it). When the game quits, the running jar
 * is renamed away and the download takes its place; where the system keeps the running jar locked, a small script
 * finishes the swap once the game has exited. At the next start, leftovers of an earlier swap are cleaned up.
 */
public final class Updater {
    public enum State {
        IDLE, CHECKING, UP_TO_DATE, NO_RELEASE, AVAILABLE, DOWNLOADING, READY, FAILED
    }

    public record Asset(String name, String url, long size) {
    }

    public record Release(String version, String page, String notes, List<Asset> assets) {
    }

    private static final String DOWNLOAD_SUFFIX = ".download";
    private static final String OLD_SUFFIX = ".old";

    private final String repo, loader, mc, current;
    private final Path modJar;
    private final Consumer<String> log;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BlockCompanion updates");
        t.setDaemon(true);
        return t;
    });

    private volatile State state = State.IDLE;
    private volatile String message = "";
    private volatile Release latest;
    private volatile Asset asset;
    private volatile double progress;
    private volatile long lastCheck;
    private volatile Path downloaded;
    private boolean hookAdded;

    /**
     * @param repo    {@code owner/name} on GitHub
     * @param modJar  the running mod jar, or null when it isn't a jar file (a development run): then updates are only
     *                shown, never installed
     * @param log     where to note problems
     */
    public Updater(String repo, String loader, String mc, String currentVersion, Path modJar, Consumer<String> log) {
        this.repo = repo;
        this.loader = loader;
        this.mc = mc;
        this.current = currentVersion;
        this.modJar = modJar != null && Files.isRegularFile(modJar) && modJar.toString().endsWith(".jar") ? modJar : null;
        this.log = log;
        cleanUp();
    }

    public State state() {
        return state;
    }

    /** A line for the settings screen describing the state. */
    public String message() {
        return message;
    }

    public Release latest() {
        return latest;
    }

    public String currentVersion() {
        return current;
    }

    /** 0 to 1 while downloading. */
    public double progress() {
        return progress;
    }

    /** Whether a found update can be downloaded and installed here (a jar for this loader and version, a real mod jar). */
    public boolean canInstall() {
        return modJar != null && asset != null;
    }

    /** Whether a check is due: never checked, or the last one was {@code intervalMillis} ago. */
    public boolean due(long now, long intervalMillis) {
        State s = state;
        if (s == State.CHECKING || s == State.DOWNLOADING || s == State.READY) return false;
        return lastCheck == 0 || now - lastCheck >= intervalMillis;
    }

    /** Looks for a newer release in the background; {@code then} runs on the worker thread with the result. */
    public void check(Runnable then) {
        State s = state;
        if (s == State.CHECKING || s == State.DOWNLOADING || s == State.READY) return;
        state = State.CHECKING;
        message = "Checking for updates...";
        lastCheck = System.currentTimeMillis();
        worker.execute(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + repo + "/releases/latest"))
                        .timeout(Duration.ofSeconds(20)).header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "BlockCompanion/" + current).GET().build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (res.statusCode() == 404) {
                    latest = null;
                    asset = null;
                    state = State.NO_RELEASE;
                    message = "No release published yet.";
                } else if (res.statusCode() != 200) {
                    throw new IOException("GitHub answered " + res.statusCode());
                } else {
                    Release r = parse(res.body());
                    latest = r;
                    asset = pick(r, loader, mc);
                    if (compareVersions(r.version(), current) > 0) {
                        state = State.AVAILABLE;
                        message = "Version " + r.version() + " is available (you have " + current + ")."
                                + (asset == null ? " There is no jar for " + loader + " " + mc + " in it." : "");
                    } else {
                        state = State.UP_TO_DATE;
                        message = "Up to date (" + current + ").";
                    }
                }
            } catch (Exception e) {
                state = State.FAILED;
                message = "Could not check for updates: " + describe(e);
                log.accept("Update check failed: " + e);
            }
            if (then != null) then.run();
        });
    }

    /** Downloads the found release's jar next to the running one; it is installed when the game quits. */
    public void download(Runnable then) {
        if (state != State.AVAILABLE || !canInstall()) return;
        Asset a = asset;
        state = State.DOWNLOADING;
        progress = 0;
        message = "Downloading " + a.name() + "...";
        worker.execute(() -> {
            Path dir = modJar.getParent();
            Path part = dir.resolve(a.name() + DOWNLOAD_SUFFIX + ".part");
            Path target = dir.resolve(a.name() + DOWNLOAD_SUFFIX);
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(a.url())).timeout(Duration.ofMinutes(5))
                        .header("Accept", "application/octet-stream").header("User-Agent", "BlockCompanion/" + current).GET().build();
                HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (res.statusCode() != 200) throw new IOException("GitHub answered " + res.statusCode());
                long total = res.headers().firstValueAsLong("Content-Length").orElse(a.size());
                try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(part)) {
                    byte[] buf = new byte[64 * 1024];
                    long done = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) progress = Math.min(1, done / (double) total);
                    }
                }
                if (a.size() > 0 && Files.size(part) != a.size()) throw new IOException("the download is incomplete");
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                downloaded = target;
                installOnExit();
                progress = 1;
                state = State.READY;
                message = "Version " + latest.version() + " is downloaded and installs when you quit the game.";
            } catch (Exception e) {
                try {
                    Files.deleteIfExists(part);
                } catch (IOException ignored) {
                }
                state = State.AVAILABLE;
                message = "Download failed: " + describe(e) + ". Try again, or get it from the release page.";
                log.accept("Update download failed: " + e);
            }
            if (then != null) then.run();
        });
    }

    private synchronized void installOnExit() {
        if (hookAdded) return;
        hookAdded = true;
        Runtime.getRuntime().addShutdownHook(new Thread(this::install, "BlockCompanion update install"));
    }

    /** Swaps the jars, or leaves a script to do it once the game has exited. */
    private void install() {
        Path next = downloaded;
        if (next == null || !Files.isRegularFile(next)) return;
        Path jar = next.resolveSibling(next.getFileName().toString().substring(0, next.getFileName().toString().length() - DOWNLOAD_SUFFIX.length()));
        try {
            Files.move(modJar, modJar.resolveSibling(modJar.getFileName() + OLD_SUFFIX), StandardCopyOption.REPLACE_EXISTING);
            Files.move(next, jar, StandardCopyOption.REPLACE_EXISTING);
            return;
        } catch (IOException locked) {
            // The running jar is locked (Windows): finish after the game has exited.
        }
        try {
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            Path script = Files.createTempFile("blockcompanion-update", windows ? ".cmd" : ".sh");
            String text = windows ? windowsScript(modJar, next, jar) : unixScript(modJar, next, jar);
            Files.writeString(script, text, StandardCharsets.UTF_8);
            ProcessBuilder pb = windows
                    ? new ProcessBuilder("cmd", "/c", "start", "\"\"", "/min", "cmd", "/c", script.toString())
                    : new ProcessBuilder("sh", script.toString());
            pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            log.accept("Could not schedule the update install: " + e);
        }
    }

    static String windowsScript(Path old, Path next, Path jar) {
        return "@echo off\r\n"
                + "set n=0\r\n"
                + ":wait\r\n"
                + "set /a n+=1\r\n"
                + "timeout /t 1 /nobreak >nul\r\n"
                + "del /f /q \"" + old + "\" >nul 2>&1\r\n"
                + "if exist \"" + old + "\" if %n% lss 120 goto wait\r\n"
                + "if not exist \"" + old + "\" move /y \"" + next + "\" \"" + jar + "\" >nul\r\n"
                + "(goto) 2>nul & del \"%~f0\"\r\n";
    }

    static String unixScript(Path old, Path next, Path jar) {
        return "#!/bin/sh\n"
                + "sleep 2\n"
                + "rm -f '" + old.toString().replace("'", "'\\''") + "'\n"
                + "mv -f '" + next.toString().replace("'", "'\\''") + "' '" + jar.toString().replace("'", "'\\''") + "'\n"
                + "rm -f \"$0\"\n";
    }

    /**
     * Removes what an earlier swap left: renamed old jars, half downloads. A finished download that was never swapped
     * in (the swap script didn't run) is installed again at the next quit.
     */
    private void cleanUp() {
        if (modJar == null) return;
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(modJar.getParent())) {
            for (Path p : dir) {
                String name = p.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).startsWith("blockcompanion-")) continue;
                if (name.endsWith(".jar" + OLD_SUFFIX) || name.endsWith(DOWNLOAD_SUFFIX + ".part")) Files.deleteIfExists(p);
                else if (name.endsWith(".jar" + DOWNLOAD_SUFFIX)) {
                    downloaded = p;
                    state = State.READY;
                    message = "An update is downloaded and installs when you quit the game.";
                    installOnExit();
                }
            }
        } catch (IOException e) {
            log.accept("Could not tidy up earlier updates: " + e);
        }
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    // ---- pure helpers -----------------------------------------------------------------------------------------------

    /** Reads GitHub's "latest release" answer. */
    @SuppressWarnings("unchecked")
    public static Release parse(String json) throws IOException {
        if (!(Json.parse(json) instanceof Map<?, ?> m)) throw new IOException("Not a release");
        String tag = String.valueOf(m.get("tag_name"));
        String page = m.get("html_url") instanceof String s ? s : "";
        String notes = m.get("body") instanceof String s ? s : "";
        List<Asset> assets = new ArrayList<>();
        if (m.get("assets") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> a)) continue;
                Map<String, Object> am = (Map<String, Object>) a;
                if (!(am.get("name") instanceof String name) || !(am.get("browser_download_url") instanceof String url)) continue;
                long size = am.get("size") instanceof Number n ? n.longValue() : -1;
                assets.add(new Asset(name, url, size));
            }
        }
        return new Release(stripV(tag), page, notes, List.copyOf(assets));
    }

    /** The jar for this loader and Minecraft version: {@code blockcompanion-<loader>-<mc>-<version>.jar}. */
    public static Asset pick(Release r, String loader, String mc) {
        String prefix = ("blockcompanion-" + loader + "-" + mc + "-").toLowerCase(Locale.ROOT);
        for (Asset a : r.assets()) {
            String n = a.name().toLowerCase(Locale.ROOT);
            if (n.startsWith(prefix) && n.endsWith(".jar") && !n.endsWith("-sources.jar") && !n.endsWith("-dev.jar")) {
                // "1.21.1-" must not match "1.21.10-...": the rest has to start with the version itself.
                String rest = n.substring(prefix.length());
                if (!rest.isEmpty() && Character.isDigit(rest.charAt(0))) return a;
            }
        }
        return null;
    }

    private static final Pattern PART = Pattern.compile("(\\d+)|([A-Za-z]+)");

    /**
     * Compares versions like {@code 1.2.10} and {@code 1.2.9}, numerically part by part; a pre-release
     * ({@code 1.3.0-beta.2}) comes before its release. A leading {@code v} is ignored.
     */
    public static int compareVersions(String a, String b) {
        // Build metadata (+...) doesn't count.
        a = stripV(a).split("\\+", 2)[0];
        b = stripV(b).split("\\+", 2)[0];
        String[] ma = a.split("-", 2), mb = b.split("-", 2);
        String[] na = ma[0].split("\\."), nb = mb[0].split("\\.");
        for (int i = 0; i < Math.max(na.length, nb.length); i++) {
            long x = i < na.length ? number(na[i]) : 0, y = i < nb.length ? number(nb[i]) : 0;
            if (x != y) return Long.compare(x, y);
        }
        boolean preA = ma.length > 1, preB = mb.length > 1;
        if (preA != preB) return preA ? -1 : 1;
        if (!preA) return 0;
        Matcher x = PART.matcher(ma[1]), y = PART.matcher(mb[1]);
        while (true) {
            boolean hx = x.find(), hy = y.find();
            if (!hx || !hy) return hx ? 1 : hy ? -1 : 0;
            int c = x.group(1) != null && y.group(1) != null ? Long.compare(Long.parseLong(x.group(1)), Long.parseLong(y.group(1)))
                    : x.group().compareToIgnoreCase(y.group());
            if (c != 0) return c;
        }
    }

    private static long number(String s) {
        int end = 0;
        while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
        return end == 0 ? 0 : Long.parseLong(s.substring(0, Math.min(end, 18)));
    }

    private static String stripV(String s) {
        s = s == null ? "" : s.trim();
        return s.startsWith("v") || s.startsWith("V") ? s.substring(1) : s;
    }

    /** Opens a web page in the system browser, without Minecraft's own helpers (they differ between versions). */
    public static void openInBrowser(String url) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        ProcessBuilder pb = os.contains("win") ? new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url)
                : os.contains("mac") ? new ProcessBuilder("open", url) : new ProcessBuilder("xdg-open", url);
        try {
            pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException ignored) {
        }
    }
}
