package com.bitselect.ai;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public final class QwenTokens {
  private QwenTokens() {}

  private static final Encoding ENCODING = load();

  private static Encoding load() {
    try (var raw = QwenTokens.class.getResourceAsStream("/tokenizer/qwen3.tiktoken.gz");
        var reader =
            new BufferedReader(
                new InputStreamReader(
                    new GZIPInputStream(Objects.requireNonNull(raw)), StandardCharsets.UTF_8))) {
      Pattern pattern = Pattern.compile(reader.readLine(), Pattern.UNICODE_CHARACTER_CLASS);
      Map<byte[], Integer> ranks = new HashMap<>();
      String line;
      while ((line = reader.readLine()) != null) {
        int at = line.lastIndexOf(' ');
        ranks.put(
            Base64.getDecoder().decode(line.substring(0, at)),
            Integer.parseInt(line.substring(at + 1)));
      }
      var registry = Encodings.newLazyEncodingRegistry();
      registry.registerGptBytePairEncoding(
          new GptBytePairEncodingParams("bit-qwen3", pattern, ranks, Map.of()));
      return registry.getEncoding("bit-qwen3").orElseThrow();
    } catch (Exception e) {
      throw new IllegalStateException("QWEN_TOKENIZER_UNAVAILABLE", e);
    }
  }

  public static int count(String text) {
    return text == null ? 0 : ENCODING.countTokensOrdinary(text);
  }

  public static int extend(String text, int at, int direction, int tokenBudget) {
    int boundary = at;
    while (direction < 0 ? boundary > 0 : boundary < text.length()) {
      int next = text.offsetByCodePoints(boundary, direction);
      String part = direction < 0 ? text.substring(next, at) : text.substring(at, next);
      if (count(part) > tokenBudget) break;
      boundary = next;
    }
    return boundary;
  }
}
