package io.durableflow.persistence;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class Json {
  private final JsonMapper mapper = JsonMapper.builder().build();

  public String write(Object value) {
    return mapper.writeValueAsString(value);
  }

  public <T> T read(String value, Class<T> type) {
    return mapper.readValue(value, type);
  }

  public Object value(String value) {
    return value == null ? null : mapper.readValue(value, Object.class);
  }
}
