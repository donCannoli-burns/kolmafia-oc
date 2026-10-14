package net.sourceforge.kolmafia.opencode;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class OpenCodeRuntimeManagerTest {
  @Test
  void parsesAttachedRunTextAndSession() {
    String output =
        """
        {"type":"step_start","timestamp":1,"sessionID":"ses_abc","part":{"type":"step-start"}}
        {"type":"text","timestamp":2,"sessionID":"ses_abc","part":{"type":"text","text":"KoLmafia OpenCode bridge alive"}}
        {"type":"step_finish","timestamp":3,"sessionID":"ses_abc","part":{"type":"step-finish"}}
        """;

    var result = OpenCodeRuntimeManager.parseRunOutput(output);

    assertThat(result.sessionId(), is("ses_abc"));
    assertThat(result.text(), is("KoLmafia OpenCode bridge alive"));
    assertThat(result.diagnostics(), is(""));
  }

  @Test
  void keepsNonJsonOutputAsDiagnostics() {
    String output =
        """
        warning: attached client diagnostic
        {"type":"error","timestamp":2,"sessionID":"ses_abc","error":"provider failed"}
        """;

    var result = OpenCodeRuntimeManager.parseRunOutput(output);

    assertThat(result.sessionId(), is("ses_abc"));
    assertThat(result.text(), is(""));
    assertThat(
        result.diagnostics(),
        is("warning: attached client diagnostic" + System.lineSeparator() + "provider failed"));
  }

  @Test
  void combinesCompletedTextParts() {
    String output =
        """
        {"type":"text","timestamp":1,"sessionID":"ses_abc","part":{"type":"text","text":"Hello"}}
        {"type":"text","timestamp":2,"sessionID":"ses_abc","part":{"type":"text","text":"KoLmafia"}}
        """;

    var result = OpenCodeRuntimeManager.parseRunOutput(output);

    assertThat(result.text(), is("Hello" + System.lineSeparator() + "KoLmafia"));
  }
}
