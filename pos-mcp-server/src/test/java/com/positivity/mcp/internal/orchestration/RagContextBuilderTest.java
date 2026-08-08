package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class RagContextBuilderTest {

  @Test
  void buildsCompactContextFromNonBlankDocumentsOnly() {
    List<Document> documents = List.of(
        new Document("First document"),
        new Document("   "),
        new Document("Second document"),
        new Document("Third document"));

    String context = RagContextBuilder.build(documents);

    assertThat(context)
        .contains("[1] First document")
        .contains("[2] Second document")
        .contains("[3] Third document")
        .doesNotContain("[4]");
  }
}
