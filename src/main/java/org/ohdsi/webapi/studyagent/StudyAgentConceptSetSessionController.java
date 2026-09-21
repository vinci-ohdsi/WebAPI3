package org.ohdsi.webapi.studyagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ohdsi.webapi.security.authz.AuthorizationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * WebAPI-owned boundary for Study Agent concept-set authoring sessions.
 * ACP is intentionally not exposed to browsers from this controller.
 */
@RestController
@RequestMapping("/study-agent/v1/concept-set-sessions")
public class StudyAgentConceptSetSessionController {

  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;
  private final AuthorizationService authorizationService;
  private final ObjectMapper objectMapper;
  private final boolean enabled;
  private final String acpBaseUrl;
  private final String conceptSetSessionTable;
  private final String dialogueMessageTable;

  public StudyAgentConceptSetSessionController(
      JdbcTemplate jdbcTemplate,
      TransactionTemplate transactionTemplate,
      AuthorizationService authorizationService,
      ObjectMapper objectMapper,
      @Value("${study-agent.concept-set-search.enabled:false}") boolean enabled,
      @Value("${study-agent.acp.base-url:}") String acpBaseUrl,
      @Value("${datasource.ohdsi.schema:public}") String ohdsiSchema) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = transactionTemplate;
    this.authorizationService = authorizationService;
    this.objectMapper = objectMapper;
    this.enabled = enabled;
    this.acpBaseUrl = acpBaseUrl;
    if (!ohdsiSchema.matches("[A-Za-z_][A-Za-z0-9_]*")) {
      throw new IllegalArgumentException("Invalid OHDSI schema identifier");
    }
    this.conceptSetSessionTable = ohdsiSchema + ".study_agent_concept_set_session";
    this.dialogueMessageTable = ohdsiSchema + ".study_agent_concept_set_dialogue_message";
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
    writeInTransaction(() -> {
      jdbcTemplate.update(("""
          insert into %s
            (session_id, user_id, state, narrative, ui_context, assistant_state, created_at, last_active_at)
          values (?, ?, ?, ?, ?, ?, ?, ?)
          """).formatted(conceptSetSessionTable), sessionId, authorizationService.getAuthenticatedPrincipal().getUserId(),
          "interpreting", message, contextJson, "{}", Timestamp.from(now), Timestamp.from(now));
      recordDialogueMessage(sessionId, "user", message, null);
    });
    return requestDialogue(sessionId, message, context, "strategy");
  }

  @PostMapping("/{sessionId}/messages")
  @PreAuthorize("isPermitted('study-agent:concept-set-assist')")
  public Map<String, Object> reply(
      @PathVariable String sessionId, @RequestBody Map<String, Object> request) {
    if (!enabled) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    UUID parsedSessionId;
    try {
      parsedSessionId = UUID.fromString(sessionId);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid session identifier");
    }
    String message = String.valueOf(request.getOrDefault("message", "")).trim();
    if (message.isEmpty() || message.length() > 4000) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid Study Agent message");
    }
    Map<String, Object> session;
    try {
      session = jdbcTemplate.queryForMap(
          "select narrative, ui_context, assistant_state, state from " + conceptSetSessionTable
              + " where session_id=? and user_id=? and archived_at is null",
          parsedSessionId, authorizationService.getAuthenticatedPrincipal().getUserId());
    } catch (EmptyResultDataAccessException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Study Agent session not found");
    }
    if (!"strategy_ready".equals(session.get("state"))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Study Agent session cannot accept a reply");
    }
    Map<String, Object> context = readObject(session.get("ui_context"));
    context.put("initial_narrative", session.get("narrative"));
    context.put("prior_dialogue", readObject(session.get("assistant_state")));
    context.put("conversation_turn", "follow_up");
    if (request.get("answers") != null && !(request.get("answers") instanceof Map<?, ?>)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid Study Agent answers");
    }
    if (request.get("answers") instanceof Map<?, ?>) {
      context.put("answers", request.get("answers"));
    }
    writeInTransaction(() -> recordDialogueMessage(parsedSessionId, "user", message,
        request.get("answers") == null ? null : writeJson(request.get("answers"))));
    context.put("conversation_history", recentDialogueMessages(parsedSessionId));
    return requestDialogue(parsedSessionId, message, context, "dialogue");
  }

  private Map<String, Object> requestDialogue(
      UUID sessionId, String message, Object context, String currentStep) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("session_id", sessionId.toString());
    if (acpBaseUrl.isBlank()) {
      response.put("state", "error");
      response.put("error", Map.of("code", "study_agent_unavailable", "retryable", false));
      return response;
    }
    try {
      Map<String, Object> acpRequest = Map.of(
          "user_prompt", message, "current_context", context, "current_step", currentStep);
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
      String safeDialogueJson = objectMapper.writeValueAsString(safeDialogue);
      String assistantAnswer = String.valueOf(safeDialogue.getOrDefault("answer", ""));
      writeInTransaction(() -> {
        jdbcTemplate.update("update " + conceptSetSessionTable + " set state=?, assistant_state=?, last_active_at=? where session_id=?", "strategy_ready", safeDialogueJson, Timestamp.from(Instant.now()), sessionId);
        recordDialogueMessage(sessionId, "assistant", assistantAnswer, safeDialogueJson);
      });
      response.put("state", "strategy_ready");
      response.put("assistant_message", safeDialogue.getOrDefault("answer", ""));
      response.put("dialogue", safeDialogue);
      response.put("allowed_actions", java.util.List.of("reply", "cancel"));
    } catch (Exception ex) {
      writeInTransaction(() -> jdbcTemplate.update("update " + conceptSetSessionTable + " set state=?, last_active_at=? where session_id=?", "error", Timestamp.from(Instant.now()), sessionId));
      response.put("state", "error");
      response.put("error", Map.of("code", "study_agent_unavailable", "retryable", true));
    }
    return response;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readObject(Object value) {
    if (!(value instanceof String json) || json.isBlank()) {
      return new LinkedHashMap<>();
    }
    try {
      Object parsed = objectMapper.readValue(json, Map.class);
      if (parsed instanceof Map<?, ?> map) {
        return new LinkedHashMap<>((Map<String, Object>) map);
      }
    } catch (JsonProcessingException ignored) {
      // Persisted context is advisory to ACP; a malformed historical value is not browser input.
    }
    return new LinkedHashMap<>();
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid Study Agent answers");
    }
  }

  private void recordDialogueMessage(
      UUID sessionId, String actor, String message, String structuredPayload) {
    jdbcTemplate.update("insert into " + dialogueMessageTable
            + " (session_id, actor, message, structured_payload, created_at) values (?, ?, ?, ?, ?)",
        sessionId, actor, message, structuredPayload, Timestamp.from(Instant.now()));
  }

  private List<Map<String, Object>> recentDialogueMessages(UUID sessionId) {
    return jdbcTemplate.queryForList("""
        select actor, message
        from (
          select id, actor, message
          from %s
          where session_id=?
          order by id desc
          limit 12
        ) recent_messages
        order by id
        """.formatted(dialogueMessageTable), sessionId);
  }

  private void writeInTransaction(Runnable work) {
    transactionTemplate.executeWithoutResult(status -> work.run());
  }
}
