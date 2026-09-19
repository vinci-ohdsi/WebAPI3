package org.ohdsi.webapi.studyagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
  private final String acpBaseUrl;

  public StudyAgentConceptSetSessionController(
      JdbcTemplate jdbcTemplate,
      AuthorizationService authorizationService,
      ObjectMapper objectMapper,
      @Value("${study-agent.concept-set-search.enabled:false}") boolean enabled,
      @Value("${study-agent.acp.base-url:}") String acpBaseUrl) {
    this.jdbcTemplate = jdbcTemplate;
    this.authorizationService = authorizationService;
    this.objectMapper = objectMapper;
    this.enabled = enabled;
    this.acpBaseUrl = acpBaseUrl;
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
    if (acpBaseUrl.isBlank()) {
      response.put("state", "error");
      response.put("error", Map.of("code", "study_agent_unavailable", "retryable", false));
      return response;
    }
    try {
      Map<String, Object> acpRequest = Map.of("user_prompt", message, "current_context", context);
      HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(acpBaseUrl + "/flows/concept_set_authoring"))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(acpRequest))).build();
      HttpResponse<String> acpResponse = HttpClient.newHttpClient().send(httpRequest, HttpResponse.BodyHandlers.ofString());
      if (acpResponse.statusCode() != 200) throw new IllegalStateException("acp_status_" + acpResponse.statusCode());
      @SuppressWarnings("unchecked") Map<String, Object> payload = objectMapper.readValue(acpResponse.body(), Map.class);
      @SuppressWarnings("unchecked") Map<String, Object> dialogue = payload.get("dialogue") instanceof Map ? (Map<String, Object>) payload.get("dialogue") : Map.of();
      Map<String, Object> safeDialogue = new LinkedHashMap<>();
      for (String key : java.util.List.of("plan", "answer", "current_step_guidance", "cautions", "suggested_next_actions", "follow_up_plan", "artifact_requests")) {
        if (dialogue.containsKey(key)) safeDialogue.put(key, dialogue.get(key));
      }
      jdbcTemplate.update("update study_agent_concept_set_session set state=?, assistant_state=?, last_active_at=? where session_id=?", "strategy_ready", objectMapper.writeValueAsString(safeDialogue), Instant.now(), sessionId);
      response.put("state", "strategy_ready");
      response.put("assistant_message", safeDialogue.getOrDefault("answer", ""));
      response.put("dialogue", safeDialogue);
      response.put("allowed_actions", java.util.List.of("reply", "cancel"));
    } catch (Exception ex) {
      jdbcTemplate.update("update study_agent_concept_set_session set state=?, last_active_at=? where session_id=?", "error", Instant.now(), sessionId);
      response.put("state", "error");
      response.put("error", Map.of("code", "study_agent_unavailable", "retryable", true));
    }
    return response;
  }
}
