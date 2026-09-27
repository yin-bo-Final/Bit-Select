package com.bitselect.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

public final class Json {
  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

  private Json() {}

  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  public static Object read(String value) {
    try {
      return value == null ? null : MAPPER.readValue(value, Object.class);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }
}
