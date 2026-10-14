package net.sourceforge.kolmafia.swingui.panel;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import net.sourceforge.kolmafia.opencode.OpenCodeRuntimeManager;
import net.sourceforge.kolmafia.opencode.OpenCodeRuntimeManager.AgentMode;
import net.sourceforge.kolmafia.opencode.OpenCodeRuntimeManager.Health;

public class OpenCodePanel extends JPanel {
  private static final String[] ACTIVITY_FRAMES = {"working.", "working..", "working...", "working...."};

  private final JLabel status = new JLabel("OpenCode: checking...");
  private final JLabel activity = new JLabel();
  private final JTextArea transcript = new JTextArea();
  private final JTextField prompt = new JTextField();
  private final JButton send = new JButton("send");
  private final JButton start = new JButton("check");
  private final JButton stop = new JButton("reset");
  private final JButton refresh = new JButton("refresh");
  private final JButton newSession = new JButton("new session");
  private final JComboBox<AgentMode> agentMode = new JComboBox<>(AgentMode.values());
  private final Timer activityTimer;
  private int activityFrame;

  public OpenCodePanel() {
    super(new BorderLayout(6, 6));

    this.activityTimer =
        new Timer(
            350,
            e -> {
              this.activityFrame = (this.activityFrame + 1) % ACTIVITY_FRAMES.length;
              this.activity.setText(ACTIVITY_FRAMES[this.activityFrame]);
            });
    this.activityTimer.setRepeats(true);

    this.transcript.setEditable(false);
    this.transcript.setLineWrap(true);
    this.transcript.setWrapStyleWord(true);
    this.transcript.append(
        "Kolmaf-AI bootstrap v0\n"
            + "OpenCode runs as a local child process. This console does not grant KoL execution authority.\n\n");

    JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
    controls.add(this.start);
    controls.add(this.stop);
    controls.add(this.refresh);
    controls.add(this.newSession);
    controls.add(new JLabel("Agent:"));
    controls.add(this.agentMode);
    controls.add(this.activity);
    controls.add(this.status);

    JPanel entry = new JPanel(new BorderLayout(4, 0));
    entry.add(this.prompt, BorderLayout.CENTER);
    entry.add(this.send, BorderLayout.EAST);

    this.add(controls, BorderLayout.NORTH);
    this.add(new JScrollPane(this.transcript), BorderLayout.CENTER);
    this.add(entry, BorderLayout.SOUTH);

    this.start.addActionListener(e -> startRuntime());
    this.stop.addActionListener(e -> stopRuntime());
    this.refresh.addActionListener(e -> refreshStatus());
    this.newSession.addActionListener(e -> createNewSession());
    this.agentMode.addActionListener(
        e -> append("runtime", "Agent mode: " + selectedAgent() + "."));
    this.send.addActionListener(e -> sendPrompt());
    this.prompt.addActionListener(e -> sendPrompt());

    refreshStatus();
  }

  private void startRuntime() {
    setBusy(true);
    append("runtime", "Checking local OpenCode CLI...");
    runAsync(
        OpenCodeRuntimeManager::start,
        health -> {
          setBusy(false);
          updateStatus(health);
          append("runtime", "OpenCode CLI is ready.");
        });
  }

  private void stopRuntime() {
    setBusy(true);
    runAsync(
        () -> {
          OpenCodeRuntimeManager.stop();
          return OpenCodeRuntimeManager.health();
        },
        health -> {
          setBusy(false);
          updateStatus(health);
          append("runtime", "Cleared the current OpenCode conversation.");
        });
  }

  private void refreshStatus() {
    runAsync(OpenCodeRuntimeManager::health, this::updateStatus);
  }

  private void createNewSession() {
    AgentMode mode = selectedAgent();
    setBusy(true);
    runAsync(
        () -> OpenCodeRuntimeManager.newSession(mode),
        ignored -> {
          setBusy(false);
          append("runtime", "Started a new " + mode + " conversation.");
          refreshStatus();
        });
  }

  private void sendPrompt() {
    String text = this.prompt.getText().trim();
    if (text.isEmpty()) {
      return;
    }

    AgentMode mode = selectedAgent();
    this.prompt.setText("");
    append("you", text);
    setBusy(true);

    runAsync(
        () -> OpenCodeRuntimeManager.sendPrompt(text, mode),
        response -> {
          setBusy(false);
          append("OpenCode[" + mode + "]", response);
          refreshStatus();
        });
  }

  private void updateStatus(Health health) {
    if (health.healthy()) {
      String version = health.version().isBlank() ? "" : " v" + health.version();
      this.status.setText("OpenCode" + version + ": ready (local CLI)");
      this.send.setEnabled(true);
      this.newSession.setEnabled(true);
    } else {
      this.status.setText("OpenCode: stopped");
      this.send.setEnabled(false);
      this.newSession.setEnabled(false);
    }
  }

  private void setBusy(boolean busy) {
    this.start.setEnabled(!busy);
    this.stop.setEnabled(!busy);
    this.refresh.setEnabled(!busy);
    this.prompt.setEnabled(!busy);
    this.agentMode.setEnabled(!busy);

    if (busy) {
      this.newSession.setEnabled(false);
      this.send.setEnabled(false);
      this.activityFrame = 0;
      this.activity.setText(ACTIVITY_FRAMES[this.activityFrame]);
      this.activityTimer.restart();
    } else {
      this.activityTimer.stop();
      this.activity.setText("");
    }
  }

  private AgentMode selectedAgent() {
    Object selected = this.agentMode.getSelectedItem();
    return selected instanceof AgentMode mode ? mode : AgentMode.PLAN;
  }

  private void append(String who, String text) {
    this.transcript.append(who + "> " + text + System.lineSeparator() + System.lineSeparator());
    this.transcript.setCaretPosition(this.transcript.getDocument().getLength());
  }

  private <T> void runAsync(Callable<T> task, Consumer<T> done) {
    new SwingWorker<T, Void>() {
      @Override
      protected T doInBackground() throws Exception {
        return task.call();
      }

      @Override
      protected void done() {
        try {
          done.accept(get());
        } catch (Exception e) {
          setBusy(false);
          append("error", rootMessage(e));
          refreshStatus();
        }
      }
    }.execute();
  }

  private static String rootMessage(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current.getMessage() == null ? current.toString() : current.getMessage();
  }
}
