package io.durableflow.api;

import io.durableflow.persistence.Audit;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class AuditController {
  private final Audit audit;

  public AuditController(Audit audit) {
    this.audit = audit;
  }

  @GetMapping("/api/v1/audit-events")
  public List<Map<String, Object>> events(
      @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
    return audit.list(limit, offset);
  }
}
