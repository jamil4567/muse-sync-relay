# Muse Sync 2.0.0 — Build & Install Guide (user's PC)

Run these in **PowerShell** on the user's PC. No card, no new software:
the plugin builds with the Android Studio's own bundled Java (JBR).

## 0. Back up v1.0.1 FIRST (do this before anything else)

In Android Studio: **Settings → Plugins → Installed → Muse Sync →
gear icon → (note the version 1.0.1)**. Keep a copy of the old zip:

```powershell
Copy-Item "H:\muse-studio-sync\plugin\build\distributions\muse-sync-1.0.1.zip" "H:\muse-studio-sync\muse-sync-1.0.1-backup.zip"
```

If that exact zip name is different, copy whatever `muse-sync-*.zip` is in
`H:\muse-studio-sync\plugin\build\distributions\`. **Do NOT uninstall
1.0.1 yet** — only remove it after 2.0.0 is tested (see step 5).

## 1. Get the v2 source onto the PC

Copy the whole `plugin-v2` folder from Muse's delivery into
`H:\muse-studio-sync\plugin-v2` (same place as the v1 `plugin` folder,
next to it — not inside it).

The v2 folder has NO Gradle wrapper of its own. Copy the wrapper files
from the v1 folder:

```powershell
Copy-Item "H:\muse-studio-sync\plugin\gradlew.bat" "H:\muse-studio-sync\plugin-v2\gradlew.bat"
Copy-Item "H:\muse-studio-sync\plugin\gradle" "H:\muse-studio-sync\plugin-v2\gradle" -Recurse
```

(If the v1 folder has no `gradlew.bat` either, run the build from inside
Android Studio instead: open the `plugin-v2` folder as a project in
Studio and use the Gradle tool window task `buildPlugin`.)

## 2. Wake the relay (it sleeps after 15 min idle)

In a browser, open `https://muse-sync-relay-414bb.containers.snapdeploy.app/health`
until it answers `{"ok": true}` (may take ~30–70 seconds after a wake).
If it does not answer, wake it first:

```powershell
curl.exe -s -X POST "https://snapdeploy.dev/api/public/wake/muse-sync-relay-414bb"
```

then try `/health` again.

## 3. Build the plugin

```powershell
cd H:\muse-studio-sync\plugin-v2
.\gradlew.bat buildPlugin
```

- First build downloads the IntelliJ platform files — it takes a few
  minutes. Keep the PC awake and the internet on.
- Success looks like: `BUILD SUCCESSFUL`.
- The installable zip lands in:
  `H:\muse-studio-sync\plugin-v2\build\distributions\Muse Sync-2.0.0.zip`

If the build fails, **do not fix it by hand** — send Muse the exact
error text (or with 2.0.0 installed later, the auto-report will send it).

## 4. Install 2.0.0 in Android Studio

1. Open Android Studio with any project.
2. **Settings (Ctrl+Alt+S) → Plugins → gear icon (⚙) → Install Plugin
   from Disk…**
3. Pick `H:\muse-studio-sync\plugin-v2\build\distributions\Muse Sync-2.0.0.zip`.
4. Restart Studio when it asks.

## 5. Test (in this order — do not skip)

1. Open your project (e.g. the app project in `H:\New folder (2)`).
2. Open the **Muse Sync** panel on the right. Relay URL and pairing code
   should already be filled in (same as v1).
3. Press **Connect**. Status must say `Connected - watching for changes`.
4. **Streaming test:** ask Muse to send a test file. You should see the
   file's lines **grow line by line** in the editor, not appear all at
   once. Check the whole file arrives (last line present).
5. **Error-report test:** run a build that fails (or break one line on
   purpose and press Run). The panel's **"Build error reports"** line
   should change to `Last error report sent (auto) at HH:MM:SS (relay id N)`.
   If it does not, press **"Send last build errors"** and check the line
   again. Tell Muse the result either way.
6. Only when steps 4–5 pass: remove the old 1.0.1 (Settings → Plugins →
   Muse Sync → Uninstall is NOT needed — installing 2.0.0 already
   replaced it; just delete the backup zip later if you want).

## If something goes wrong

- **Build fails:** copy the first error lines and send them to Muse.
- **"Plugin requires build 242.* or older"** or install refused: tell
  Muse the exact message.
- **Files do not arrive:** Disconnect, then Connect again (resets the
  sync position). Check the relay `/health` page answers `{"ok": true}`.
- **Error reports never send:** the status line says why (e.g. "connect
  first"). If auto-send never fires, the manual button is the fallback —
  tell Muse, with the Build tool window's failure text.
