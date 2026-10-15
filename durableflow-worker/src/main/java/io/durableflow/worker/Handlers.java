package io.durableflow.worker;

import io.durableflow.model.Lease;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public final class Handlers {
  public Set<String> types() {
    return Set.of("uppercase", "sha256", "delay", "json-transform");
  }

  public Map<String, Object> execute(Lease lease) throws InterruptedException {
    var input = lease.input();
    return switch (lease.type()) {
      case "uppercase" -> Map.of("value", text(input).toUpperCase(Locale.ROOT));
      case "sha256" -> Map.of("value", sha(text(input)));
      case "delay" -> {
        Object milliseconds = input.get("milliseconds");
        if (!(milliseconds instanceof Number number)
            || number.longValue() < 0
            || number.longValue() > 30000) throw new IllegalArgumentException("Invalid delay");
        Thread.sleep(number.longValue());
        yield Map.of("delayed", number.longValue());
      }
      case "json-transform" -> transform(input);
      default -> throw new IllegalArgumentException("Unsupported handler");
    };
  }

  private String text(Map<String, Object> input) {
    Object value = input.get("text");
    if (!(value instanceof String text) || text.length() > 16384)
      throw new IllegalArgumentException("Invalid text");
    return text;
  }

  private String sha(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  private Map<String, Object> transform(Map<String, Object> input) {
    if (!(input.get("value") instanceof Map<?, ?> value))
      throw new IllegalArgumentException("Expected object");
    Object operation = input.get("operation");
    var out = new LinkedHashMap<String, Object>();
    if ("pick".equals(operation) && input.get("fields") instanceof List<?> fields) {
      for (var field : fields) {
        if (!(field instanceof String key)) throw new IllegalArgumentException("Invalid field");
        if (value.containsKey(key)) out.put(key, value.get(key));
      }
    } else if ("count-keys".equals(operation)) out.put("count", value.size());
    else if ("rename".equals(operation) && input.get("mapping") instanceof Map<?, ?> mapping) {
      value.forEach(
          (k, v) -> {
            Object target = mapping.get(k);
            String key = target == null ? k.toString() : target.toString();
            if (out.containsKey(key))
              throw new IllegalArgumentException("Duplicate destination key");
            out.put(key, v);
          });
    } else throw new IllegalArgumentException("Unsupported transform");
    return out;
  }
}
