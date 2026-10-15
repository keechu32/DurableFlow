package io.durableflow.model;

import java.util.*;

/** Immutable JSON snapshots prevent caller mutation after validation. */
public final class JsonPayload {
  private JsonPayload() {}

  public static Map<String, Object> snapshot(Map<String, Object> value) {
    var result = new LinkedHashMap<String, Object>();
    value.forEach((k, v) -> result.put(k, freeze(v, 0)));
    return Collections.unmodifiableMap(result);
  }

  private static Object freeze(Object value, int depth) {
    if (depth > 64) throw FlowException.invalid("JSON nesting exceeds 64 levels");
    if (value instanceof Map<?, ?> map) {
      var out = new LinkedHashMap<String, Object>();
      map.forEach(
          (k, v) -> {
            if (!(k instanceof String)) throw FlowException.invalid("JSON keys must be strings");
            out.put((String) k, freeze(v, depth + 1));
          });
      return Collections.unmodifiableMap(out);
    }
    if (value instanceof List<?> list) {
      var out = new ArrayList<Object>();
      for (var v : list) out.add(freeze(v, depth + 1));
      return Collections.unmodifiableList(out);
    }
    if (value == null
        || value instanceof String
        || value instanceof Number
        || value instanceof Boolean) return value;
    throw FlowException.invalid("Unsupported JSON value");
  }
}
