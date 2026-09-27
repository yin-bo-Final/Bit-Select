package com.bitselect.ai;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;

public final class Prompts {
  private Prompts() {}

  public static String read(String name) {
    try (var in = new ClassPathResource("prompts/" + name + ".md").getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("PROMPT_MISSING:" + name);
    }
  }
}
