package org.ohdsi.webapi.studyagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.ohdsi.webapi.security.authz.AuthorizationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * WebAPI-owned boundary for Study Agent concept-set authoring sessions.
 * ACP is intentionally not exposed to browsers from this controller.
 */
@RestController
@RequestMapping("/study-agent/v1/concept-set-sessions")
public class StudyAgentConceptSetSessionController {

  private final JdbcTemplate jdbcTemplate;
  private final AuthorizationService authorizationService;
  private final ObjectMapper objectMapper;
  private final boolean enabled;

  public StudyAgentConceptSetSessionController(
      JdbcTemplate jdbcTemplate,
      AuthorizationService authorizationService,
      ObjectMapper objectMapper,
      @Value("${study-agent.concept-set-search.enabled:false}") boolean enabled) {
    this.jdbcTemplate = jdbcTemplate;
    this.authorizationService = authorizationService;
    this.objectMapper = objectMapper;
    this.enabled = enabled;
  }

  @PostMapping
  @PreAuthorize("isPermitted('study-agent:concept-set-assist')")
  public Map<String, Object> create(@RequestBody Map<String, Object> request) {
    if (!enabled) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    String command = String.valueOf(request.getOrDefault("command", "")).trim();
    String message = String.valueOf(request.getOrDefault("message", "")).trim();
    if (!"/ohdsi".equals(command) || message.isEmpty() || message.length() > 4000) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid Study Agent command or message");
    }
    Object context = request.getOrDefault("ui_context", Map.of());
    String contextJson;
    try {
      contextJson = objectMapper.writeValueAsString(context);
    } catch (JsonProcessingException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid ui_context");
    }
    UUID sessionId = UUID.randomUUID();
    Instant now = Instant.now();
    jdbcTemplate.update("""
        insert into study_agent_concept_set_session
          (session_id, user_id, state, narrative, ui_context, assistant_state, created_at, last_active_at)
        values (?, ?, ?, ?, ?, ?, ?, ?)
        """, sessionId, authorizationService.getAuthenticatedPrincipal().getUserId(),
        "interpreting", message, contextJson, "{}", now, now);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("session_id", sessionId.toString());
    response.put("state", "interpreting");
    response.put("allowed_actions", java.util.List.of("cancel"));
    return response;
  }
}
