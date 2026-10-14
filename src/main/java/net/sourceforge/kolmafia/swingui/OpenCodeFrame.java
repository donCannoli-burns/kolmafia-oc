package net.sourceforge.kolmafia.swingui;

import javax.swing.JTabbedPane;
import net.sourceforge.kolmafia.swingui.panel.OpenCodePanel;

public class OpenCodeFrame extends GenericFrame {
  public OpenCodeFrame() {
    super("Kolmaf-AI");
    this.setCenterComponent(new OpenCodePanel());
  }

  @Override
  public JTabbedPane getTabbedPane() {
    return null;
  }

  @Override
  public boolean shouldAddStatusBar() {
    return false;
  }

  @Override
  public boolean useSidePane() {
    return true;
  }
}
