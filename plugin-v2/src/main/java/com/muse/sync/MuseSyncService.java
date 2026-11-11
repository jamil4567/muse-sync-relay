package com.muse.sync;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Muse Sync 2.0.0: polls the relay server for file changes and applies them
 * inside Android Studio, with VS Code-style VISIBLE STREAMING writes and
 * automatic build-error reporting to the relay.
 *
 * Streaming (v2): a create/update message is written to disk progressively,
 * 40 lines per 25 ms tick, with a VFS refresh after every tick, so the open
 * editor shows the file growing line by line. The file is auto-opened after
 * the first tick (if auto-open is on). lastId advances ONLY after the whole
 * file is on disk; a failed tick never advances lastId, so the message is
 * retried on the next poll. Streaming runs on its own executor so the 2 s
 * poll loop is never blocked. Files that are not valid UTF-8 text fall back
 * to the v1 whole-file write.
 *
 * Error reporting (v2): MuseSyncErrorReporter listens to Gradle task
 * notifications; when a task ends in FAILURE it POSTs the error text to the
 * relay as action "error" (see postErrorReport). A manual "Send last build
 * errors" button in the tool window re-sends the last captured output.
 *
 * Safety rules (kept from v1):
 *  - Only files inside the currently open project are ever written.
 *  - Paths with ".." are rejected.
 *  - A file with unsaved changes in the editor is NOT overwritten
 *    (the message is skipped, same as v1).
 *  - No new dependencies; JDK 17 standard library only.
 */
public class MuseSyncService {
    private static final String USER_AGENT = "Mozilla/5.0 MuseSync/1.0";
    /** Lines written per streaming tick, and the pause between ticks. */
    private static final int STREAM_LINES_PER_TICK = 40;
    private static final long STREAM_TICK_MS = 25;
    /** Max size of one error report sent to the relay. */
    private static final int MAX_ERROR_REPORT_BYTES = 20 * 1024;

    /** One service per open project. */
    private static final java.util.Map<Project, MuseSyncService> BY_PROJECT =
            Collections.synchronizedMap(new WeakHashMap<>());

    public static MuseSyncService forProject(Project project) {
        synchronized (BY_PROJECT) {
            MuseSyncService s = BY_PROJECT.get(project);
            if (s == null) {
                s = new MuseSyncService(project);
                BY_PROJECT.put(project, s);
            }
            return s;
        }
    }

    public static MuseSyncService removeProject(Project project) {
        synchronized (BY_PROJECT) {
            return BY_PROJECT.remove(project);
        }
    }

    private final Project project;
    private final ScheduledExecutorService pollExecutor =
            Executors.newSingleThreadScheduledExecutor(daemonFactory("MuseSync-poll"));
    /** Streaming runs here so a long file never blocks the 2 s poll loop. */
    private final ExecutorService streamExecutor =
            Executors.newSingleThreadExecutor(daemonFactory("MuseSync-stream"));
    /** Error-report POSTs run here, never on the EDT. */
    private final ExecutorService errorExecutor =
            Executors.newSingleThreadExecutor(daemonFactory("MuseSync-errors"));
    private final MuseSyncErrorReporter errorReporter;
    /** Message ids whose streaming job is queued or running. */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    private ScheduledFuture<?> task;
    private volatile long lastId = 0;
    private volatile String status = "Disconnected";
    private volatile String errorReportStatus = "No error report sent yet";
    private volatile String relayUrl = "";
    private volatile String pairingCode = "";
    private volatile boolean connected = false;

    public MuseSyncService(Project project) {
        this.project = project;
        this.errorReporter = new MuseSyncErrorReporter(this);
        this.errorReporter.subscribe(project);
    }

    public Project getProject() { return project; }
    public String getStatus() { return status; }
    public String getErrorReportStatus() { return errorReportStatus; }
    public MuseSyncErrorReporter getErrorReporter() { return errorReporter; }
    public String getRelayUrl() { return relayUrl; }
    public String getPairingCode() { return pairingCode; }

    void setErrorReportStatus(String s) { errorReportStatus = s; }

    public synchronized void start(String relayUrl, String pairingCode) {
        stop();
        this.relayUrl = relayUrl == null ? "" : relayUrl.trim();
        this.pairingCode = pairingCode == null ? "" : pairingCode.trim();
        connected = true;
        status = "Connecting...";
        task = pollExecutor.scheduleWithFixedDelay(
                () -> poll(this.relayUrl, this.pairingCode), 0, 2, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (task != null) task.cancel(true);
        task = null;
        connected = false;
        lastId = 0;
        status = "Disconnected";
    }

    /** Called when the project closes: stop everything for this project. */
    public synchronized void dispose() {
        stop();
        pollExecutor.shutdownNow();
        streamExecutor.shutdownNow();
        errorExecutor.shutdownNow();
        errorReporter.dispose();
    }

    private void poll(String relayUrl, String pairingCode) {
        try {
            String url = relayUrl.replaceAll("/+$", "")
                    + "/poll?code=" + URLEncoder.encode(pairingCode, StandardCharsets.UTF_8)
                    + "&since=" + lastId;
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (conn.getResponseCode() != 200) { status = "Relay error: " + conn.getResponseCode(); return; }
            String body;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                body = r.lines().collect(Collectors.joining("\n"));
            }
            status = "Connected - watching for changes";
            // Message shape: {"id":12,"action":"create","path":"app/src/.../Main.kt","content_b64":"..."}
            for (String msg : splitMessages(body)) {
                long id = longField(msg, "id");
                if (id <= lastId) continue;
                if (inFlight.contains(id)) continue; // streaming already in progress
                String action = stringField(msg, "action");
                String path = stringField(msg, "path");
                String contentB64 = stringField(msg, "content_b64");
                if (path == null || path.isEmpty()) { lastId = id; continue; }
                if (path.contains("..")) { lastId = id; continue; } // rejected, skip
                boolean handled = handleMessage(id, action, path, contentB64);
                if (!handled) {
                    // A synchronous write failed: stop here, retry on the next poll.
                    status = "Write failed for " + path + " - will retry";
                    return;
                }
                // For streaming messages handleMessage returns true but lastId is
                // advanced only by the stream job when the whole file is written.
            }
        } catch (Exception e) {
            status = "Not connected: " + e.getMessage();
        }
    }

    /**
     * Returns true when the message is fully handled synchronously, or when a
     * streaming job was successfully queued for it. Returns false only when a
     * synchronous write failed (caller stops the poll loop and retries later).
     */
    private boolean handleMessage(long id, String action, String relativePath, String contentB64) {
        if ("delete".equals(action)) {
            boolean ok = applyDelete(relativePath);
            if (ok) lastId = id;
            return ok;
        }
        byte[] bytes;
        try {
            bytes = (contentB64 == null || contentB64.isEmpty())
                    ? new byte[0] : Base64.getDecoder().decode(contentB64);
        } catch (IllegalArgumentException badB64) {
            lastId = id; // Bad payload: skip it rather than retrying forever.
            return true;
        }
        // Never overwrite work the user has not saved yet (same as v1).
        if (hasUnsavedChanges(relativePath)) {
            lastId = id; // Skip permanently, as v1 does.
            return true;
        }
        // Binary / non-UTF-8 content: whole-file write, as in v1.
        String text = tryDecodeUtf8(bytes);
        if (text == null) {
            boolean ok = applyWholeWrite(relativePath, bytes);
            if (ok) lastId = id;
            return ok;
        }
        inFlight.add(id);
        streamExecutor.execute(() -> streamWrite(id, relativePath, text));
        return true;
    }

    private boolean applyDelete(String relativePath) {
        String base = project.getBasePath();
        if (base == null) return false;
        try {
            Files.deleteIfExists(new File(base, relativePath).toPath());
            File parent = new File(base, relativePath).getParentFile();
            if (parent != null) LocalFileSystem.getInstance().refreshAndFindFileByIoFile(parent);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** v1 whole-file write (used for binary files). Returns false on failure. */
    private boolean applyWholeWrite(String relativePath, byte[] bytes) {
        String base = project.getBasePath();
        if (base == null) return false;
        File target = new File(base, relativePath);
        try {
            File parentDir = target.getParentFile();
            if (parentDir != null) parentDir.mkdirs();
            Files.write(target.toPath(), bytes,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target);
            if (vf != null && MuseSyncSettings.getInstance().isAutoOpen()) {
                ApplicationManager.getApplication().invokeLater(() ->
                        FileEditorManager.getInstance(project).openFile(vf, true));
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Streams one text file to disk, 40 lines per 25 ms tick. Advances lastId
     * only after the whole file is written. Never advances lastId on failure.
     */
    private void streamWrite(long id, String relativePath, String text) {
        try {
            // Ordering guard: only the next expected message may advance lastId.
            // If an earlier message failed (or we disconnected), drop this job;
            // the poll loop will fetch it again.
            if (!connected || id != lastId + 1) {
                inFlight.remove(id);
                return;
            }
            String base = project.getBasePath();
            if (base == null) { inFlight.remove(id); return; }
            File target = new File(base, relativePath);
            File parentDir = target.getParentFile();
            if (parentDir != null) parentDir.mkdirs();

            // Re-check right before the first write: never clobber unsaved work.
            if (hasUnsavedChanges(relativePath)) {
                lastId = id; // skip permanently, as v1 does
                inFlight.remove(id);
                return;
            }

            // Line-start offsets, so the progressive writes are byte-identical
            // to the final text (original line endings preserved).
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') starts.add(i + 1);
            }
            int lineCount = starts.size();
            int shown = 0;
            boolean opened = false;
            while (shown < lineCount) {
                if (!connected || project.isDisposed() || Thread.currentThread().isInterrupted()) {
                    // Aborted: do NOT advance lastId, the poll loop retries.
                    status = "Streaming interrupted for " + relativePath + " - will retry";
                    inFlight.remove(id);
                    return;
                }
                shown = Math.min(shown + STREAM_LINES_PER_TICK, lineCount);
                int end = (shown < lineCount) ? starts.get(shown) : text.length();
                try {
                    Files.write(target.toPath(), text.substring(0, end).getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE);
                } catch (Throwable t) {
                    status = "Write failed for " + relativePath + " - will retry";
                    inFlight.remove(id);
                    return;
                }
                VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target);
                status = "Streaming " + relativePath + " (" + shown + "/" + lineCount + " lines)";
                if (!opened && vf != null && MuseSyncSettings.getInstance().isAutoOpen()) {
                    opened = true;
                    final VirtualFile toOpen = vf;
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (!project.isDisposed()) {
                            FileEditorManager.getInstance(project).openFile(toOpen, true);
                        }
                    });
                }
                if (shown >= lineCount) break;
                try {
                    Thread.sleep(STREAM_TICK_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    status = "Streaming interrupted for " + relativePath + " - will retry";
                    inFlight.remove(id);
                    return;
                }
            }
            // Whole file is on disk: only now advance lastId.
            if (connected && id == lastId + 1) {
                lastId = id;
            }
            inFlight.remove(id);
            status = "Connected - watching for changes";
        } catch (Throwable t) {
            status = "Write failed for " + relativePath + " - will retry";
            inFlight.remove(id);
        }
    }

    private boolean hasUnsavedChanges(String relativePath) {
        String base = project.getBasePath();
        if (base == null) return false;
        try {
            VirtualFile existing = LocalFileSystem.getInstance().findFileByIoFile(new File(base, relativePath));
            if (existing != null) {
                Document doc = FileDocumentManager.getInstance().getDocument(existing);
                if (doc != null && FileDocumentManager.getInstance().isDocumentUnsaved(doc)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // If the VFS check itself fails, do not block the write.
        }
        return false;
    }

    /** Returns the decoded text, or null when the bytes are not valid UTF-8. */
    private static String tryDecodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    // --- error reporting -------------------------------------------------

    /** Manual fallback: send the last captured build output, even if no failure was raised. */
    public void sendLastBuildErrors() {
        errorExecutor.execute(() -> errorReporter.sendLastCaptured(true));
    }

    /** POSTs one error report to the relay as action "error". Runs off the EDT. */
    void postErrorReport(String reportText, String source) {
        String url = relayUrl;
        String code = pairingCode;
        if (url == null || url.isEmpty() || code == null || code.isEmpty()) {
            // Fall back to whatever is stored in settings (e.g. reporter fired
            // before the user pressed Connect in this session).
            url = MuseSyncSettings.getInstance().getRelayUrl();
            code = MuseSyncSettings.getInstance().getPairingCode();
        }
        if (url == null || url.isEmpty() || code == null || code.isEmpty()) {
            errorReportStatus = "Error report NOT sent (" + source + "): connect first (relay URL/code missing)";
            return;
        }
        try {
            byte[] raw = reportText.getBytes(StandardCharsets.UTF_8);
            if (raw.length > MAX_ERROR_REPORT_BYTES) {
                // Keep the head (error lines) and cut the tail.
                raw = java.util.Arrays.copyOf(raw, MAX_ERROR_REPORT_BYTES);
                reportText = new String(raw, StandardCharsets.UTF_8);
                raw = reportText.getBytes(StandardCharsets.UTF_8);
            }
            String ts = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            String path = ".muse-sync/errors/build-" + ts + ".txt";
            String json = "{\"code\":\"" + jsonEscape(code) + "\","
                    + "\"action\":\"error\","
                    + "\"path\":\"" + path + "\","
                    + "\"content_b64\":\"" + Base64.getEncoder().encodeToString(raw) + "\"}";
            HttpURLConnection conn = (HttpURLConnection)
                    new URL(url.replaceAll("/+$", "") + "/push").openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int rc = conn.getResponseCode();
            if (rc != 200) {
                errorReportStatus = "Error report FAILED (" + source + "): relay HTTP " + rc;
                return;
            }
            String resp;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                resp = r.lines().collect(Collectors.joining("\n"));
            }
            long pushedId = longField(resp, "id");
            String when = new SimpleDateFormat("HH:mm:ss").format(new Date());
            errorReportStatus = "Last error report sent (" + source + ") at " + when
                    + (pushedId > 0 ? " (relay id " + pushedId + ")" : "");
        } catch (Throwable t) {
            errorReportStatus = "Error report FAILED (" + source + "): " + t.getMessage();
        }
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static ThreadFactory daemonFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    // --- tiny JSON helpers for the relay protocol only ---
    static java.util.List<String> splitMessages(String body) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int start = body.indexOf("\"messages\"");
        if (start < 0) return out;
        int arr = body.indexOf('[', start);
        int end = body.lastIndexOf(']');
        if (arr < 0 || end < 0 || end <= arr) return out;
        String inner = body.substring(arr + 1, end).trim();
        if (inner.isEmpty()) return out;
        // Messages do not contain "},{" inside base64, so this split is safe here.
        for (String part : inner.split("\\},\\s*\\{")) {
            String m = part.trim();
            if (!m.startsWith("{")) m = "{" + m;
            if (!m.endsWith("}")) m = m + "}";
            out.add(m);
        }
        return out;
    }

    // The relay sends JSON with a space after every colon (Python json.dumps:
    // {"id": 1, "path": "..."}), so the parser must skip whitespace after
    // the colon instead of expecting "\":"name":"". (v1.2 fix, kept here.)
    static String stringField(String json, String name) {
        int i = json.indexOf("\"" + name + "\"");
        if (i < 0) return "";
        int colon = json.indexOf(':', i + name.length() + 2);
        if (colon < 0) return "";
        int s = colon + 1;
        while (s < json.length() && Character.isWhitespace(json.charAt(s))) s++;
        if (s >= json.length() || json.charAt(s) != '"') return "";
        s++;
        StringBuilder out = new StringBuilder();
        while (s < json.length()) {
            char c = json.charAt(s);
            if (c == '\\' && s + 1 < json.length()) {
                char n = json.charAt(s + 1);
                if (n == '"') { out.append('"'); s += 2; continue; }
                if (n == '\\') { out.append('\\'); s += 2; continue; }
                if (n == 'n') { out.append('\n'); s += 2; continue; }
                out.append(n); s += 2; continue;
            }
            if (c == '"') break;
            out.append(c);
            s++;
        }
        return out.toString();
    }

    static long longField(String json, String name) {
        int i = json.indexOf("\"" + name + "\"");
        if (i < 0) return 0;
        int colon = json.indexOf(':', i + name.length() + 2);
        if (colon < 0) return 0;
        int s = colon + 1;
        while (s < json.length() && Character.isWhitespace(json.charAt(s))) s++;
        int e = s;
        while (e < json.length() && (Character.isDigit(json.charAt(e)) || json.charAt(e) == '-')) e++;
        if (e == s) return 0;
        try { return Long.parseLong(json.substring(s, e)); } catch (Exception ex) { return 0; }
    }
}
