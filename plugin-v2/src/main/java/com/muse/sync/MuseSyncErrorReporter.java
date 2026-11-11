package com.muse.sync;

import com.intellij.execution.process.ProcessOutputType;
import com.intellij.openapi.externalSystem.model.task.ExternalTaskId;
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemTaskNotificationListener;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Muse Sync 2.0.0 — automatic build-error reporting.
 *
 * Subscribes to the platform's external-system task notifications (the same
 * channel the Build tool window uses for Gradle tasks). It accumulates each
 * task's output, and when a task ends in FAILURE it extracts the error lines
 * and POSTs them to the relay as action "error" (see MuseSyncService).
 * One report per failed task only (dedupe by task id). All network I/O
 * happens off the EDT inside MuseSyncService.postErrorReport, and never
 * while this reporter's lock is held.
 *
 * Honest limits (see V2-DELIVERY-NOTES.md):
 *  - This only sees tasks that publish through the external-system
 *    notification topic. A build launched some other way (e.g. a raw
 *    command-line Gradle run outside Studio) is NOT captured; use the
 *    "Send last build errors" button in the tool window in that case.
 *  - The interface methods below are the long-stable signatures of
 *    ExternalSystemTaskNotificationListener (2024.2 / build 242). The code
 *    was NOT compiled here (no Java/Android SDK in this environment);
 *    if a signature ever drifts, the build error will say so plainly.
 */
public class MuseSyncErrorReporter implements ExternalSystemTaskNotificationListener {
    /** Per-task output cap; the full log is never needed, only the errors. */
    private static final int MAX_TASK_OUTPUT_CHARS = 64 * 1024;
    /** Reported task ids remembered for dedupe (bounded). */
    private static final int MAX_REMEMBERED_TASKS = 50;

    private final MuseSyncService service;
    private final Object lock = new Object();
    private MessageBusConnection connection;

    /** Output accumulated per running task, keyed by task id string. */
    private final Map<String, StringBuilder> buffers = new HashMap<>();
    /** Task ids that already ended in failure (reported or not). */
    private final Set<String> failed = new HashSet<>();
    /** Task ids that already ended in success. */
    private final Set<String> succeeded = new HashSet<>();
    /** Task ids already reported once (dedupe), oldest-first. */
    private final LinkedHashSet<String> reported = new LinkedHashSet<>();
    /** The most recently finished (or running) task's output, for the manual button. */
    private String lastTaskLabel = "";
    private String lastCapturedText = "";

    public MuseSyncErrorReporter(MuseSyncService service) {
        this.service = service;
    }

    /** Subscribe on the PROJECT message bus (not the application bus). */
    public void subscribe(Project project) {
        try {
            connection = project.getMessageBus().connect(project);
            connection.subscribe(ExternalSystemTaskNotificationListener.TOPIC, this);
        } catch (Throwable t) {
            // If the topic is unavailable on this IDE build, reporting stays
            // off and the manual button is the fallback; never crash Studio.
            service.setErrorReportStatus("Auto error reporting unavailable on this Studio build: " + t.getMessage());
        }
    }

    public void dispose() {
        try {
            if (connection != null) connection.disconnect();
        } catch (Throwable ignored) {
        }
        connection = null;
    }

    @Override
    public void onTaskOutput(@NotNull ExternalTaskId id, @NotNull String text,
                             @NotNull ProcessOutputType type) {
        synchronized (lock) {
            String key = taskKey(id);
            StringBuilder buf = buffers.get(key);
            if (buf == null) {
                buf = new StringBuilder();
                buffers.put(key, buf);
            }
            if (buf.length() < MAX_TASK_OUTPUT_CHARS) {
                int room = MAX_TASK_OUTPUT_CHARS - buf.length();
                buf.append(text.length() <= room ? text : text.substring(0, room));
            }
            lastTaskLabel = labelOf(id);
            lastCapturedText = buf.toString();
        }
    }

    @Override
    public void onSuccess(@NotNull ExternalTaskId id) {
        synchronized (lock) {
            String key = taskKey(id);
            succeeded.add(key);
            StringBuilder buf = buffers.remove(key);
            if (buf != null) {
                lastTaskLabel = labelOf(id);
                lastCapturedText = buf.toString();
            }
        }
    }

    @Override
    public void onFailure(@NotNull ExternalTaskId id, @NotNull String error) {
        // Build the report under the lock, POST it after releasing the lock.
        String report = null;
        synchronized (lock) {
            String key = taskKey(id);
            failed.add(key);
            StringBuilder buf = buffers.remove(key);
            String output = buf == null ? "" : buf.toString();
            lastTaskLabel = labelOf(id);
            lastCapturedText = output;
            report = buildReport(key, labelOf(id), error, output, "auto");
        }
        if (report != null) {
            service.postErrorReport(report, "auto");
        }
    }

    @Override
    public void onEnd(@NotNull ExternalTaskId id) {
        // Fallback for builds where onFailure never fires: if the task ended
        // without a recorded success and its output looks like a failure,
        // report it. Dedupe (buildReport returns null if already reported).
        String report = null;
        synchronized (lock) {
            String key = taskKey(id);
            if (!succeeded.contains(key) && !failed.contains(key)) {
                StringBuilder buf = buffers.remove(key);
                String output = buf == null ? "" : buf.toString();
                if (looksLikeFailure(output)) {
                    failed.add(key);
                    lastTaskLabel = labelOf(id);
                    lastCapturedText = output;
                    report = buildReport(key, labelOf(id),
                            "(no failure message; output mentions a failure)", output, "auto");
                }
            } else {
                buffers.remove(key);
            }
        }
        if (report != null) {
            service.postErrorReport(report, "auto");
        }
    }

    /** Manual button: send the last captured output even if no failure was raised. */
    public void sendLastCaptured(boolean manual) {
        String report;
        synchronized (lock) {
            String text = lastCapturedText;
            if (text == null || text.isEmpty()) {
                service.setErrorReportStatus("Nothing to send: no build output captured yet — run a build first");
                return;
            }
            String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            StringBuilder r = new StringBuilder();
            r.append("Muse Sync build-error report (manual send)\n");
            r.append("Task: ").append(lastTaskLabel.isEmpty() ? "(unknown)" : lastTaskLabel).append("\n");
            r.append("Captured: ").append(when).append("\n\n");
            appendErrorLines(r, text);
            r.append("\n--- output tail ---\n");
            appendTail(r, text, 8192);
            report = r.toString();
        }
        service.postErrorReport(report, "manual");
    }

    // --- internals (all called with lock held, except postErrorReport) ---

    /** Returns the report text, or null when this task was already reported. */
    private String buildReport(String key, String label, String failureMessage, String output, String source) {
        if (reported.contains(key)) return null; // one report per failed task only
        reported.add(key);
        while (reported.size() > MAX_REMEMBERED_TASKS) {
            reported.remove(reported.iterator().next());
        }
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        StringBuilder report = new StringBuilder();
        report.append("Muse Sync build-error report (").append(source).append(")\n");
        report.append("Task: ").append(label).append("\n");
        report.append("Failed: ").append(when).append("\n");
        if (failureMessage != null && !failureMessage.isEmpty()) {
            report.append("Failure: ").append(failureMessage).append("\n");
        }
        report.append("\n--- error lines ---\n");
        appendErrorLines(report, output);
        report.append("\n--- output tail ---\n");
        appendTail(report, output, 8192);
        return report.toString();
    }

    /** Lines that look like compiler/build errors, first ones first. */
    private static void appendErrorLines(StringBuilder out, String text) {
        int found = 0;
        for (String line : text.split("\n")) {
            if (isErrorLine(line)) {
                out.append(line).append('\n');
                if (++found >= 100) break;
            }
        }
        if (found == 0) out.append("(no error lines found in the captured output)\n");
    }

    private static boolean isErrorLine(String line) {
        String l = line.toLowerCase();
        if (l.contains("error:") || l.contains("failed") || l.contains("exception")) return true;
        if (line.startsWith("e: ") || line.startsWith("e:")) return true;
        // file:line:col: style, e.g. MainActivity.kt:23:5: error ...
        if (line.matches("^[^:]+:\\d+:\\d+:.*")) return true;
        return false;
    }

    private static boolean looksLikeFailure(String output) {
        if (output == null || output.isEmpty()) return false;
        String l = output.toLowerCase();
        return l.contains("build failed") || l.contains("execution failed")
                || l.contains("error:") || l.contains(": e: ");
    }

    private static void appendTail(StringBuilder out, String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            out.append("(no output captured)\n");
            return;
        }
        if (text.length() > maxChars) {
            out.append("... (truncated, showing last ").append(maxChars).append(" chars)\n");
            out.append(text.substring(text.length() - maxChars));
        } else {
            out.append(text);
        }
        if (!text.endsWith("\n")) out.append('\n');
    }

    private static String taskKey(ExternalTaskId id) {
        try {
            String s = id.getId();
            return s == null ? id.toString() : s;
        } catch (Throwable t) {
            return id.toString();
        }
    }

    private static String labelOf(ExternalTaskId id) {
        try {
            return String.valueOf(id.getProjectPath()) + " :: " + taskKey(id);
        } catch (Throwable t) {
            return taskKey(id);
        }
    }
}
