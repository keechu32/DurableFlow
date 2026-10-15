package io.durableflow.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class DomainTest {
  TaskSpec task(String id, String... deps) {
    return new TaskSpec(id, "uppercase", List.of(deps), Map.of(), 3, 60);
  }

  void valid(TaskSpec... tasks) {
    new DefinitionRequest("example", List.of(tasks)).validate();
  }

  @Test
  void acceptsDisconnectedDags() {
    assertDoesNotThrow(() -> valid(task("a"), task("b"), task("c", "a")));
  }

  @Test
  void rejectsCyclesMissingAndDuplicates() {
    for (var tasks :
        List.of(
            List.of(task("a", "a")),
            List.of(task("a", "b"), task("b", "a")),
            List.of(task("a", "missing")),
            List.of(task("a"), task("a")),
            List.of(task("a"), task("b", "a", "a"))))
      assertThrows(FlowException.class, () -> new DefinitionRequest("example", tasks).validate());
  }

  @Test
  void rejectsEnormousAndEmptyGraphs() {
    assertThrows(FlowException.class, () -> valid());
    assertThrows(
        FlowException.class,
        () ->
            new DefinitionRequest(
                    "example",
                    java.util.stream.IntStream.range(0, 1001).mapToObj(i -> task("t" + i)).toList())
                .validate());
  }

  @Test
  void workflowTransitionMatrixIsExplicit() {
    var allowed =
        Set.of(
            "PENDING:RUNNING",
            "PENDING:CANCELLING",
            "RUNNING:SUCCEEDED",
            "RUNNING:FAILED",
            "RUNNING:CANCELLING",
            "CANCELLING:CANCELLED");
    for (var from : WorkflowStatus.values())
      for (var to : WorkflowStatus.values())
        if (allowed.contains(from + ":" + to))
          assertDoesNotThrow(() -> from.requireTransitionTo(to));
        else assertThrows(IllegalStateException.class, () -> from.requireTransitionTo(to));
  }

  @Test
  void taskTransitionMatrixIsExplicit() {
    var allowed =
        Map.of(
            TaskStatus.BLOCKED,
            Set.of(TaskStatus.READY, TaskStatus.CANCELLED),
            TaskStatus.READY,
            Set.of(TaskStatus.LEASED, TaskStatus.CANCELLED),
            TaskStatus.LEASED,
            Set.of(
                TaskStatus.RUNNING,
                TaskStatus.RETRY_WAIT,
                TaskStatus.DEAD_LETTERED,
                TaskStatus.CANCELLED),
            TaskStatus.RUNNING,
            Set.of(
                TaskStatus.SUCCEEDED,
                TaskStatus.FAILED,
                TaskStatus.RETRY_WAIT,
                TaskStatus.DEAD_LETTERED,
                TaskStatus.CANCELLED),
            TaskStatus.FAILED,
            Set.of(TaskStatus.RETRY_WAIT, TaskStatus.DEAD_LETTERED),
            TaskStatus.RETRY_WAIT,
            Set.of(TaskStatus.READY, TaskStatus.CANCELLED),
            TaskStatus.DEAD_LETTERED,
            Set.of(TaskStatus.READY));
    for (var from : TaskStatus.values())
      for (var to : TaskStatus.values())
        if (allowed.getOrDefault(from, Set.of()).contains(to))
          assertDoesNotThrow(() -> from.requireTransitionTo(to));
        else assertThrows(IllegalStateException.class, () -> from.requireTransitionTo(to));
  }

  @Test
  void snapshotsNestedJsonIncludingNulls() {
    var nested = new HashMap<String, Object>();
    nested.put("text", "before");
    nested.put("optional", null);
    var task = new TaskSpec("a", "uppercase", List.of(), Map.of("value", nested), 3, 60);
    nested.put("text", "after");
    assertEquals("before", ((Map<?, ?>) task.input().get("value")).get("text"));
    assertThrows(UnsupportedOperationException.class, () -> task.input().put("new", true));
    assertTrue(((Map<?, ?>) task.input().get("value")).containsKey("optional"));
  }
}
