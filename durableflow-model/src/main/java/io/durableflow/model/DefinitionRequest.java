package io.durableflow.model;

import java.util.*;

public record DefinitionRequest(String name, List<TaskSpec> tasks) {
  public DefinitionRequest {
    tasks = tasks == null ? List.of() : List.copyOf(tasks);
  }

  public void validate() {
    if (name == null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}"))
      throw FlowException.invalid("name must be 1–128 identifier characters");
    if (tasks.isEmpty() || tasks.size() > 1000)
      throw FlowException.invalid("A definition must contain 1–1000 tasks");
    var byId = new HashMap<String, TaskSpec>();
    int edges = 0;
    for (var t : tasks) {
      if (t == null || t.id() == null || !t.id().matches("[a-zA-Z0-9_-]{1,128}"))
        throw FlowException.invalid("Invalid task identifier");
      if (t.type() == null || !t.type().matches("[a-zA-Z0-9_-]{1,64}"))
        throw FlowException.invalid("Invalid task type");
      if (t.maxAttempts() < 1
          || t.maxAttempts() > 20
          || t.timeoutSeconds() < 1
          || t.timeoutSeconds() > 3600)
        throw FlowException.invalid("Attempt or timeout limit exceeded");
      if (byId.putIfAbsent(t.id(), t) != null)
        throw FlowException.invalid("Duplicate task identifier");
      if (new HashSet<>(t.dependsOn()).size() != t.dependsOn().size())
        throw FlowException.invalid("Duplicate dependency");
      edges += t.dependsOn().size();
    }
    if (edges > 10000) throw FlowException.invalid("Too many dependency edges");
    var remaining = new HashMap<String, Integer>();
    var children = new HashMap<String, List<String>>();
    for (var t : tasks) {
      remaining.put(t.id(), t.dependsOn().size());
      for (var parent : t.dependsOn()) {
        if (!byId.containsKey(parent)) throw FlowException.invalid("Missing dependency reference");
        children.computeIfAbsent(parent, k -> new ArrayList<>()).add(t.id());
      }
    }
    var ready = new ArrayDeque<String>();
    remaining.forEach(
        (id, count) -> {
          if (count == 0) ready.add(id);
        });
    int visited = 0;
    while (!ready.isEmpty()) {
      var id = ready.remove();
      visited++;
      for (var c : children.getOrDefault(id, List.of())) {
        if (remaining.compute(c, (k, v) -> v - 1) == 0) ready.add(c);
      }
    }
    if (visited != tasks.size()) throw FlowException.invalid("Dependency graph contains a cycle");
  }
}
