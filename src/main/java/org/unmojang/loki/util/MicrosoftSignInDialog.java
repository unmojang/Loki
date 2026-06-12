package org.unmojang.loki.util;

import org.unmojang.loki.util.logger.NilLogger;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;

final class MicrosoftSignInDialog {

    private static final NilLogger log = NilLogger.get("Loki");

    // Shared handle: lets the polling loop see cancellation and dispose the dialog.
    static final class Handle {
        final Object lock = new Object();
        volatile boolean cancelled;
        volatile JDialog dialog;
    }

    private MicrosoftSignInDialog() {}

    // Flag cancellation and wake the poller so it aborts immediately.
    private static void cancel(Handle handle) {
        synchronized (handle.lock) {
            handle.cancelled = true;
            handle.lock.notifyAll();
        }
    }

    static Handle create(final String verificationUri, final String browseUri, final String userCode) {
        copyToClipboard(userCode);
        openBrowser(browseUri);

        final Handle handle = new Handle();
        if (GraphicsEnvironment.isHeadless()) {
            log.info("Microsoft sign-in: go to " + verificationUri + " and enter code " + userCode);
            return handle;
        }

        Runnable build = new Runnable() {
            public void run() {
                try {
                    handle.dialog = buildDialog(verificationUri, browseUri, userCode, handle);
                } catch (Throwable t) {
                    handle.dialog = null;
                }
            }
        };
        try {
            if (EventQueue.isDispatchThread()) build.run();
            else EventQueue.invokeAndWait(build);
        } catch (Throwable ignored) {}

        if (handle.dialog == null) {
            log.info("Microsoft sign-in: go to " + verificationUri + " and enter code " + userCode);
        }
        return handle;
    }

    static void showAndWait(Handle handle) {
        if (handle == null || handle.dialog == null) return;
        handle.dialog.setVisible(true);
    }

    static void dismiss(Handle handle) {
        if (handle == null || handle.dialog == null) return;
        final JDialog dialog = handle.dialog;
        Runnable close = new Runnable() {
            public void run() {
                try {
                    dialog.setVisible(false);
                    dialog.dispose();
                } catch (Throwable ignored) {}
            }
        };
        if (EventQueue.isDispatchThread()) close.run();
        else EventQueue.invokeLater(close);
    }

    private static JDialog buildDialog(final String verificationUri, final String browseUri,
                                       final String userCode, final Handle handle) {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        JLabel heading = new JLabel("Sign in with your Microsoft account to play");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 14f));
        heading.setAlignmentX(JLabel.LEFT_ALIGNMENT);
        content.add(heading);
        content.add(Box.createVerticalStrut(12));

        JLabel step1 = new JLabel("1. Open this page in your browser:");
        step1.setAlignmentX(JLabel.LEFT_ALIGNMENT);
        content.add(step1);
        content.add(Box.createVerticalStrut(4));

        JTextField urlField = readOnlyField(verificationUri);
        content.add(urlField);
        content.add(Box.createVerticalStrut(12));

        JLabel step2 = new JLabel("2. Enter this code (copied to your clipboard):");
        step2.setAlignmentX(JLabel.LEFT_ALIGNMENT);
        content.add(step2);
        content.add(Box.createVerticalStrut(4));

        JTextField codeField = readOnlyField(userCode);
        codeField.setHorizontalAlignment(SwingConstants.CENTER);
        codeField.setFont(new Font("Monospaced", Font.BOLD, 22));
        content.add(codeField);
        content.add(Box.createVerticalStrut(14));

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setAlignmentX(JLabel.LEFT_ALIGNMENT);
        JButton openButton = new JButton("Open page");
        openButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) { openBrowser(browseUri); }
        });
        JButton copyButton = new JButton("Copy code");
        copyButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) { copyToClipboard(userCode); }
        });
        final JDialog dialog = new JDialog((Frame) null, "Microsoft Sign-In", true);
        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                cancel(handle);
                dialog.dispose();
            }
        });
        buttons.add(openButton);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(copyButton);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(cancelButton);
        content.add(buttons);
        content.add(Box.createVerticalStrut(12));

        JLabel waiting = new JLabel("Waiting for sign-in to complete...");
        waiting.setForeground(waiting.getForeground().darker());
        waiting.setAlignmentX(JLabel.LEFT_ALIGNMENT);
        content.add(waiting);

        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) { cancel(handle); }
            // Repaint launcher windows after their own repaints settle, else logo panel keeps the dialog's pixels.
            public void windowClosed(WindowEvent e) {
                javax.swing.Timer timer = new javax.swing.Timer(150, new java.awt.event.ActionListener() {
                    public void actionPerformed(java.awt.event.ActionEvent ev) {
                        try {
                            for (java.awt.Frame frame : java.awt.Frame.getFrames()) {
                                if (frame.isShowing()) forceFullRepaint(frame);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                timer.setRepeats(false);
                timer.start();
            }
        });
        dialog.getContentPane().add(content, BorderLayout.CENTER);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(380, dialog.getHeight()));
        dialog.setLocationRelativeTo(null);
        dialog.setAlwaysOnTop(true);
        return dialog;
    }

    private static JTextField readOnlyField(String text) {
        JTextField field = new JTextField(text);
        field.setEditable(false);
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, field.getPreferredSize().height));
        field.setAlignmentX(JLabel.LEFT_ALIGNMENT);
        return field;
    }

    private static void forceFullRepaint(java.awt.Component component) {
        if (component instanceof javax.swing.JComponent) {
            javax.swing.JComponent jc = (javax.swing.JComponent) component;
            try {
                javax.swing.RepaintManager.currentManager(jc).markCompletelyDirty(jc);
            } catch (Throwable ignored) {}
            jc.repaint();
            return;
        }
        if (component instanceof java.awt.Container) {
            for (java.awt.Component child : ((java.awt.Container) component).getComponents()) {
                forceFullRepaint(child);
            }
        }
        component.repaint();
    }

    private static void copyToClipboard(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        } catch (Throwable ignored) {}
    }

    private static void openBrowser(String uri) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            String[] command;
            if (os.contains("win")) {
                command = new String[]{ "rundll32", "url.dll,FileProtocolHandler", uri };
            } else if (os.contains("mac")) {
                command = new String[]{ "open", uri };
            } else {
                command = new String[]{ "xdg-open", uri };
            }
            Runtime.getRuntime().exec(command);
        } catch (Throwable ignored) {}
    }
}
