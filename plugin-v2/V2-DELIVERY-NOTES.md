# Muse Sync 2.0.0 — Delivery Notes

Date: 2026-11-11. Built for the user's Android Studio (build AI-262), Android Studio ONLY.
No VS Code work in this version.

## What's new in 2.0.0

1. **Streaming writes (like the VS Code extension).** Files Muse sends now
   appear in Studio **line by line** — 40 lines per 25 ms tick — with a VFS
   refresh after every tick, so you see the file growing in the open editor.
   The file is auto-opened after the first tick (if "Open new/changed file"
   is on). `lastId` advances **only after the whole file is on disk**; a
   failed tick never advances `lastId`, so the message is retried on the
   next poll. Streaming runs on its own executor, so the 2 s poll loop is
   never blocked by a long file. Non-UTF-8 (binary) files still use the v1
   whole-file write.
2. **Automatic build-error reporting.** The plugin listens to Gradle task
   notifications (the same channel the Build tool window uses). When a
   Gradle task ends in FAILURE, it extracts the error lines (up to 100),
   adds the tail of the output, caps the report at ~20 KB, and POSTs it to
   the relay as action `"error"`, path
   `.muse-sync/errors/build-<timestamp>.txt`. One report per failed task
   only (dedupe). All network I/O is off the EDT.
3. **Manual "Send last build errors" button** in the Muse Sync panel as a
   fallback: sends the last captured build output even if no failure was
   auto-detected. If no build output has been captured yet, it says so
   honestly instead of sending nothing.
4. **Always-on reporting.** A `ProjectManagerListener` creates the sync
   service (and subscribes the error reporter) as soon as a project opens
   — you do not need to open the Muse Sync panel first. Reporting is
   per-project and stops when the project closes.
5. **Status line** in the panel shows the last error-report result
   (sent time + relay id, or the failure reason).

## What is UNCHANGED from v1.0.1

- Plugin id `com.muse.sync`, since-build 242, until-build 262.*, JDK 17,
  no new dependencies, stdlib only.
- All v1 safety rules: open project only, paths with `..` rejected, never
  overwrite a file with unsaved editor changes, Disconnect resets `lastId`.
- Same relay URL + pairing code. **No relay change was needed** — the relay
  accepts any action string, so action `"error"` already works
  (relay_server.py untouched).
- v1.0.1 source is untouched in `../plugin/`. v2 lives in `plugin-v2/`.

## What was NOT tested (read this before installing)

- **Nothing was compiled or run here.** This environment has no Java /
  Android SDK and no Gradle, so `buildPlugin` was never executed here.
  **The user builds it on their PC** (see BUILD-INSTALL-GUIDE.md). The
  first `gradlew buildPlugin` result on the PC is the real test.
- Java syntax was checked only by balance checks (braces/parens/brackets
  all balanced) and careful review — not by a compiler.
- Streaming visibility (lines visibly growing in the editor) was not seen
  on a screen; it is implemented with per-tick VFS refresh, the same
  mechanism v1 already used for whole-file writes.
- **Error capture depends on Gradle task notifications firing** through the
  platform's external-system topic. The listener API used
  (`ExternalSystemTaskNotificationListener`, methods `onTaskOutput` /
  `onFailure` / `onSuccess` / `onEnd`) is the long-stable 2024.2 API, but
  it was verified from the platform docs/source only, not by running it.
  If a build runs outside Gradle tasks (e.g. a command-line Gradle run),
  it is NOT captured — use the manual "Send last build errors" button.
  If the auto-capture does not fire at all, the tool-window status line
  will show it, and the manual button is the fallback; that is an honest
  limit, not a silent failure.

## Honest limits

- If you edit a file in the editor **while it is streaming in**, the
  in-memory document and the on-disk stream can fight; the plugin checks
  for unsaved changes before the first tick, but not on every tick
  (a stream is fast — about 40 lines per 25 ms). Save your work before
  Muse sends a big file.
- Error reports are capped at ~20 KB and keep at most 100 error lines +
  the last ~8 KB of output. Very long build logs are truncated by design.
- The relay sleeps after 15 min idle and restarts wipe its queue (same as
  v1). If the relay slept, wake it first (see the guide) before Connect.
- `lastId` resets on Disconnect (same as v1). If the relay queue was
  wiped, use "Reset position" behaviour: Disconnect, then Connect again.

## Safety: do NOT delete v1.0.1 yet

- **Back up the installed 1.0.1 zip first** (see BUILD-INSTALL-GUIDE.md).
- Install 2.0.0 alongside it only after a test file streams in visibly
  and a failed build produces an error report. Only then remove 1.0.1.
- Both versions use the same plugin id, so Studio will replace 1.0.1
  with 2.0.0 on install — that is why the backup matters.
