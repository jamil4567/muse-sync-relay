package com.muse.sync;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManagerListener;
import org.jetbrains.annotations.NotNull;

/**
 * Muse Sync 2.0.0 — makes error reporting always-on: as soon as a project
 * opens, the per-project MuseSyncService is created, which subscribes the
 * build-error reporter to the project's message bus. The user does not need
 * to open the Muse Sync tool window first.
 *
 * On project close the service (poll loop, streaming executor, error
 * executor, reporter subscription) is disposed.
 */
public class MuseSyncProjectListener implements ProjectManagerListener {

    @Override
    public void projectOpened(@NotNull Project project) {
        try {
            MuseSyncService.forProject(project);
        } catch (Throwable ignored) {
            // Never let sync setup break project opening.
        }
    }

    @Override
    public void projectClosing(@NotNull Project project) {
        try {
            MuseSyncService service = MuseSyncService.removeProject(project);
            if (service != null) {
                service.dispose();
            }
        } catch (Throwable ignored) {
        }
    }
}
