package net.sourceforge.kolmafia.opencode;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class OpenCodeRuntimeManagerTest {
  @Test
  void extractsAssistantTextAndIgnoresReasoning() {
    String payload =
        """
        {
          "data": [
            {
              "type": "assistant",
              "content": [
                {"type": "reasoning", "text": "private scratch"},
                {"type": "text", "text": "Hello"},
                {"type": "text", "text": "KoLmafia"}
              ]
            }
          ]
        }
        """;

    assertThat(OpenCodeRuntimeManager.extractAssistantText(payload), is("Hello\nKoLmafia"));
  }

  @Test
  void returnsEmptyStringWhenThereIsNoAssistantMessage() {
    String payload =
        """
        {
          "data": [
            {
              "type": "user",
              "content": [{"type": "text", "text": "hello"}]
            }
          ]
        }
        """;

    assertThat(OpenCodeRuntimeManager.extractAssistantText(payload), is(""));
  }
}
