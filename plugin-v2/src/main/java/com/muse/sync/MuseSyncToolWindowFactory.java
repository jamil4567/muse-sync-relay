package com.muse.sync;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * Tool window: "Muse Sync" panel inside Android Studio (v2.0.0).
 * Adds the "Send last build errors" manual button and the error-report
 * status line on top of the v1 Connect/Disconnect panel.
 */
public class MuseSyncToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        MuseSyncSettings settings = MuseSyncSettings.getInstance();
        MuseSyncService service = MuseSyncService.forProject(project);

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel status = new JLabel("Disconnected");
        JLabel errorStatus = new JLabel(service.getErrorReportStatus());
        JTextField relayField = new JTextField(settings.getRelayUrl(), 30);
        JTextField codeField = new JTextField(settings.getPairingCode(), 30);
        JCheckBox autoOpen = new JCheckBox("Open new/changed file in editor", settings.isAutoOpen());
        JButton connect = new JButton("Connect");
        JButton disconnect = new JButton("Disconnect");
        JButton sendErrors = new JButton("Send last build errors");
        sendErrors.setToolTipText("Sends the last captured build output to Muse via the relay, "
                + "even if no build failure was detected automatically.");

        connect.addActionListener(e -> {
            settings.setRelayUrl(relayField.getText().trim());
            settings.setPairingCode(codeField.getText().trim());
            settings.setAutoOpen(autoOpen.isSelected());
            service.start(relayField.getText().trim(), codeField.getText().trim());
            status.setText(service.getStatus());
        });
        disconnect.addActionListener(e -> {
            service.stop();
            status.setText(service.getStatus());
        });
        sendErrors.addActionListener(e -> {
            service.sendLastBuildErrors();
            errorStatus.setText(service.getErrorReportStatus());
        });

        // Refresh both status labels every 2 seconds while the window is open.
        new Timer(2000, e -> {
            status.setText(service.getStatus());
            errorStatus.setText(service.getErrorReportStatus());
        }).start();

        panel.add(new JLabel("Relay URL (from your deployed relay server):"));
        panel.add(relayField);
        panel.add(Box.createVerticalStrut(8));
        panel.add(new JLabel("Pairing code (same code you give to Muse):"));
        panel.add(codeField);
        panel.add(Box.createVerticalStrut(8));
        panel.add(autoOpen);
        panel.add(Box.createVerticalStrut(12));
        panel.add(connect);
        panel.add(Box.createVerticalStrut(6));
        panel.add(disconnect);
        panel.add(Box.createVerticalStrut(12));
        panel.add(new JLabel("Status:"));
        panel.add(status);
        panel.add(Box.createVerticalStrut(8));
        panel.add(new JLabel("Build error reports:"));
        panel.add(errorStatus);
        panel.add(Box.createVerticalStrut(6));
        panel.add(sendErrors);
        panel.add(Box.createVerticalStrut(12));
        panel.add(new JLabel("<html>When connected, files Muse sends appear line by line<br/>"
                + "in the open project, like typed here. When a Gradle build<br/>"
                + "fails, its errors are sent to Muse automatically.</html>"));

        ContentFactory factory = ContentFactory.getInstance();
        Content content = factory.createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);
    }
}
