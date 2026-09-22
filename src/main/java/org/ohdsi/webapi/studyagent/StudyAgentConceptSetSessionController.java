package org.ohdsi.webapi.studyagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.security.MessageDigest;
import org.ohdsi.circe.vocabulary.ConceptSetExpression;
import org.ohdsi.circe.vocabulary.ConceptSetExpressionQueryBuilder;
import org.ohdsi.webapi.security.authz.AuthorizationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
  private final String conceptSetReviewTable;
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
    this.conceptSetReviewTable = ohdsiSchema + ".study_agent_concept_set_review";
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
    Map<String, Object> contextMap = context instanceof Map<?, ?> value
        ? new LinkedHashMap<>((Map<String, Object>) value) : Map.of();
    Integer existingConceptSetId = numericConceptSetId(contextMap.get("concept_set_id"));
    if (existingConceptSetId != null) {
      Map<String, Object> provenance = latestConceptSetProvenance(existingConceptSetId);
      if (!provenance.isEmpty()) contextMap.put("prior_concept_set_provenance", provenance);
    }
    String contextJson;
    try {
      contextJson = objectMapper.writeValueAsString(contextMap);
    } catch (JsonProcessingException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid ui_context");
    }
    UUID sessionId = UUID.randomUUID();
    Instant now = Instant.now();
    writeInTransaction(() -> {
      jdbcTemplate.update(("""
          insert into %s
            (session_id, user_id, concept_set_id, state, narrative, ui_context, assistant_state, created_at, last_active_at)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?)
          """).formatted(conceptSetSessionTable), sessionId, authorizationService.getAuthenticatedPrincipal().getUserId(), existingConceptSetId,
          "interpreting", message, contextJson, "{}", Timestamp.from(now), Timestamp.from(now));
      recordDialogueMessage(sessionId, "user", message, null);
    });
    Map<String, Object> response = requestDialogue(sessionId, message, context, "strategy");
    response.put("narrative", message);
    return response;
  }

  /** Concise, user-owned provenance for a saved concept set; does not resume a prior dialogue. */
  @GetMapping("/context")
  @PreAuthorize("isPermitted('study-agent:concept-set-assist') and (isOwner(#conceptSetId, CONCEPT_SET) or isPermitted('write:conceptset') or hasEntityAccess(#conceptSetId, CONCEPT_SET, WRITE))")
  public Map<String, Object> conceptSetContext(@RequestParam int conceptSetId) {
    Map<String, Object> provenance = latestConceptSetProvenance(conceptSetId);
    return provenance.isEmpty() ? Map.of("available", false) : Map.of("available", true, "provenance", provenance);
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
    if (!"strategy_ready".equals(session.get("state"))
        && !"needs_clarification".equals(session.get("state"))
        && !"proposal_applied".equals(session.get("state"))) {
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
    if (request.get("ui_context") != null && !(request.get("ui_context") instanceof Map<?, ?>)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid Study Agent ui_context");
    }
    if (request.get("ui_context") instanceof Map<?, ?>) {
      context.put("current_concept_set", request.get("ui_context"));
    }
    String updatedContextJson = writeJson(context);
    writeInTransaction(() -> {
      jdbcTemplate.update("update " + conceptSetSessionTable + " set ui_context=?, last_active_at=? where session_id=?",
          updatedContextJson, Timestamp.from(Instant.now()), parsedSessionId);
      recordDialogueMessage(parsedSessionId, "user", message,
          request.get("answers") == null ? null : writeJson(request.get("answers")));
    });
    context.put("conversation_history", recentDialogueMessages(parsedSessionId));
    Map<String, Object> response = requestDialogue(parsedSessionId, message, context, "dialogue");
    response.put("narrative", String.valueOf(session.get("narrative")));
    return response;
  }

  @PostMapping("/{sessionId}/proposal")
  @PreAuthorize("isPermitted('study-agent:concept-set-assist')")
  public Map<String, Object> proposal(@PathVariable String sessionId, @RequestBody Map<String, Object> requestBody) {
    UUID parsedSessionId;
    try { parsedSessionId = UUID.fromString(sessionId); }
    catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid session identifier"); }
    Map<String, Object> session;
    try {
      session = jdbcTemplate.queryForMap("select narrative, ui_context, state, concept_set_id from " + conceptSetSessionTable
          + " where session_id=? and user_id=? and archived_at is null", parsedSessionId,
          authorizationService.getAuthenticatedPrincipal().getUserId());
    } catch (EmptyResultDataAccessException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Study Agent session not found"); }
    if (!"strategy_ready".equals(session.get("state"))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Resolve Study Agent clarification before requesting a proposal");
    }
    if (acpBaseUrl.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Study Agent unavailable");
    try {
      Map<String, Object> context = readObject(session.get("ui_context"));
      Integer existingConceptSetId = numericConceptSetId(session.get("concept_set_id"));
      if (existingConceptSetId != null) {
        context.put("base_expression", savedExpression(existingConceptSetId));
        context.put("extension_mode", true);
      }
      String targetDomain = String.valueOf(requestBody.getOrDefault("target_domain", "")).trim();
      if (targetDomain.isEmpty() || !targetDomain.matches("[A-Za-z ]{1,80}")) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "target domain is required");
      }
      Map<String, Object> payload = Map.of("narrative_statement", session.get("narrative"),
          "clarification_answers", context.getOrDefault("answers", Map.of()),
          "atlas_constraints", context, "target_domain", targetDomain, "candidate_limit", 50);
      HttpRequest request = HttpRequest.newBuilder(URI.create(acpBaseUrl + "/flows/concept_set_proposal"))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload))).build();
      HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) throw new IllegalStateException("acp_status_" + response.statusCode());
      @SuppressWarnings("unchecked") Map<String, Object> proposal = objectMapper.readValue(response.body(), Map.class);
      Integer reviewRevision = persistReviewableProposal(parsedSessionId, proposal);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("session_id", parsedSessionId.toString());
      result.put("proposal", proposal);
      if (reviewRevision != null) result.put("review_revision", reviewRevision);
      return result;
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Study Agent proposal unavailable");
    }
  }

  /**
   * Explicitly accepts one stored proposal revision for review in Atlas Selected/Included.
   * This does not create or save an Atlas concept set; the browser receives the same
   * server-validated expression and initializes its unsaved draft locally.
   */
  @PostMapping("/{sessionId}/proposal/apply")
  @PreAuthorize("isPermitted('study-agent:concept-set-assist')")
  public Map<String, Object> applyProposal(@PathVariable String sessionId, @RequestBody Map<String, Object> requestBody) {
    UUID parsedSessionId;
    try { parsedSessionId = UUID.fromString(sessionId); }
    catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid session identifier"); }
    Object revisionValue = requestBody.get("review_revision");
    if (!(revisionValue instanceof Number)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "review revision is required");
    }
    int revision = ((Number) revisionValue).intValue();
    Map<String, Object> review;
    try {
      review = jdbcTemplate.queryForMap("""
          select r.expression_checksum, r.reviewed_expression, r.acp_validation
          from %s r join %s s on s.session_id=r.session_id
          where r.session_id=? and r.revision=? and s.user_id=? and s.archived_at is null
          """.formatted(conceptSetReviewTable, conceptSetSessionTable), parsedSessionId, revision,
          authorizationService.getAuthenticatedPrincipal().getUserId());
    } catch (EmptyResultDataAccessException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Study Agent proposal review not found");
    }
    Map<String, Object> acpValidation = readObject(review.get("acp_validation"));
    if (!"passed".equals(acpValidation.get("status"))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "ACP technical validation did not pass");
    }
    try {
      String reviewedExpression = String.valueOf(review.get("reviewed_expression"));
      ConceptSetExpression expression = objectMapper.readValue(reviewedExpression, ConceptSetExpression.class);
      if (expression.items == null || expression.items.length == 0) {
        throw new IllegalArgumentException("expression_items_required");
      }
      String sql = new ConceptSetExpressionQueryBuilder().buildExpressionQuery(expression);
      if (sql == null || sql.isBlank()) throw new IllegalArgumentException("circe_expression_sql_empty");
      String webapiValidation = objectMapper.writeValueAsString(Map.of(
          "status", "passed", "validator", "webapi_java_circe", "validated_at", Instant.now().toString()));
      writeInTransaction(() -> {
        jdbcTemplate.update("update " + conceptSetReviewTable + " set webapi_validation=?, approved_at=? where session_id=? and revision=?",
            webapiValidation, Timestamp.from(Instant.now()), parsedSessionId, revision);
        jdbcTemplate.update("update " + conceptSetSessionTable + " set state=?, approved_expression_checksum=?, last_active_at=? where session_id=?",
            "proposal_applied", String.valueOf(review.get("expression_checksum")), Timestamp.from(Instant.now()), parsedSessionId);
      });
      @SuppressWarnings("unchecked") Map<String, Object> expressionJson = objectMapper.readValue(reviewedExpression, Map.class);
      return Map.of(
          "session_id", parsedSessionId.toString(),
          "review_revision", revision,
          "expression_checksum", String.valueOf(review.get("expression_checksum")),
          "expression", expressionJson,
          "validation", Map.of("status", "passed", "validator", "webapi_java_circe"));
    } catch (ResponseStatusException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "WebAPI technical validation did not pass");
    }
  }

  /**
   * Links a normally persisted Atlas concept set to the exact review revision that
   * initialized its unsaved draft. The persisted Selected policy must still match
   * the approved expression; this endpoint never rewrites concept-set rows.
   */
  @PostMapping("/{sessionId}/proposal/finalize")
  @PreAuthorize("isPermitted('study-agent:concept-set-assist') and (isOwner(#conceptSetId, CONCEPT_SET) or isPermitted('write:conceptset') or hasEntityAccess(#conceptSetId, CONCEPT_SET, WRITE))")
  public Map<String, Object> finalizeProposal(
      @PathVariable String sessionId,
      @RequestParam int conceptSetId,
      @RequestBody Map<String, Object> requestBody) {
    UUID parsedSessionId;
    try { parsedSessionId = UUID.fromString(sessionId); }
    catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid session identifier"); }
    Object revisionValue = requestBody.get("review_revision");
    if (!(revisionValue instanceof Number)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "review revision is required");
    }
    int revision = ((Number) revisionValue).intValue();
    Map<String, Object> review;
    try {
      review = jdbcTemplate.queryForMap("""
          select r.reviewed_expression, r.expression_checksum, r.webapi_validation, s.state
          from %s r join %s s on s.session_id=r.session_id
          where r.session_id=? and r.revision=? and s.user_id=? and s.archived_at is null
          """.formatted(conceptSetReviewTable, conceptSetSessionTable), parsedSessionId, revision,
          authorizationService.getAuthenticatedPrincipal().getUserId());
    } catch (EmptyResultDataAccessException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Study Agent proposal review not found");
    }
    if (!"proposal_applied".equals(review.get("state"))
        || !"passed".equals(readObject(review.get("webapi_validation")).get("status"))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Study Agent proposal is not approved for persistence");
    }
    try {
      List<String> approvedPolicies = expressionPolicies(String.valueOf(review.get("reviewed_expression")));
      List<Map<String, Object>> persistedItems = jdbcTemplate.queryForList("""
          select concept_id, is_excluded, include_descendants, include_mapped
          from %s.concept_set_item where concept_set_id=?
          """.formatted(conceptSetSessionTable.substring(0, conceptSetSessionTable.indexOf('.'))), conceptSetId);
      List<String> persistedPolicies = persistedPolicies(persistedItems);
      if (!approvedPolicies.equals(persistedPolicies)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Persisted concept-set expression differs from the approved Study Agent review");
      }
      writeInTransaction(() -> jdbcTemplate.update("update " + conceptSetSessionTable
          + " set concept_set_id=?, last_active_at=? where session_id=?",
          conceptSetId, Timestamp.from(Instant.now()), parsedSessionId));
      return Map.of("session_id", parsedSessionId.toString(), "concept_set_id", conceptSetId,
          "review_revision", revision, "expression_checksum", String.valueOf(review.get("expression_checksum")));
    } catch (ResponseStatusException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Unable to verify persisted Study Agent expression");
    }
  }

  private Map<String, Object> latestConceptSetProvenance(int conceptSetId) {
    try {
      Map<String, Object> session = jdbcTemplate.queryForMap("""
          select narrative, assistant_state, review_revision, last_active_at
          from %s where concept_set_id=? and user_id=? and archived_at is null
          order by last_active_at desc limit 1
          """.formatted(conceptSetSessionTable), conceptSetId,
          authorizationService.getAuthenticatedPrincipal().getUserId());
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("goal", String.valueOf(session.get("narrative")));
      result.put("review_revision", session.get("review_revision"));
      result.put("last_active_at", String.valueOf(session.get("last_active_at")));
      Map<String, Object> dialogue = readObject(session.get("assistant_state"));
      if (dialogue.get("answer") != null) result.put("last_assistant_summary", String.valueOf(dialogue.get("answer")));
      return result;
    } catch (EmptyResultDataAccessException ignored) {
      return Map.of();
    }
  }

  private Integer numericConceptSetId(Object value) {
    if (value instanceof Number number && number.intValue() > 0) return number.intValue();
    try {
      int parsed = Integer.parseInt(String.valueOf(value));
      return parsed > 0 ? parsed : null;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private Map<String, Object> savedExpression(int conceptSetId) {
    String schema = conceptSetSessionTable.substring(0, conceptSetSessionTable.indexOf('.'));
    List<Map<String, Object>> persistedItems = jdbcTemplate.queryForList("""
        select concept_id, is_excluded, include_descendants, include_mapped
        from %s.concept_set_item where concept_set_id=?
        """.formatted(schema), conceptSetId);
    List<Map<String, Object>> items = new java.util.ArrayList<>();
    for (Map<String, Object> item : persistedItems) {
      items.add(Map.of(
          "concept", Map.of("CONCEPT_ID", ((Number) item.get("concept_id")).intValue()),
          "isExcluded", flag(item.get("is_excluded")) == 1,
          "includeDescendants", flag(item.get("include_descendants")) == 1,
          "includeMapped", flag(item.get("include_mapped")) == 1));
    }
    return Map.of("items", items);
  }

  private List<String> expressionPolicies(String expressionJson) throws JsonProcessingException {
    @SuppressWarnings("unchecked") Map<String, Object> expression = objectMapper.readValue(expressionJson, Map.class);
    Object rawItems = expression.get("items");
    if (!(rawItems instanceof List<?> items) || items.isEmpty()) {
      throw new IllegalArgumentException("approved_expression_items_required");
    }
    List<String> policies = new java.util.ArrayList<>();
    for (Object rawItem : items) {
      if (!(rawItem instanceof Map<?, ?> item)) throw new IllegalArgumentException("approved_expression_item_invalid");
      policies.add(policyKey(expressionConceptId(item), null,
          item.get("isExcluded"), item.get("is_excluded"),
          item.get("includeDescendants"), item.get("include_descendants"),
          item.get("includeMapped"), item.get("include_mapped")));
    }
    java.util.Collections.sort(policies);
    return policies;
  }

  private Object expressionConceptId(Map<?, ?> item) {
    Object direct = firstPresent(item, "conceptId", "concept_id", "CONCEPT_ID");
    if (direct != null) return direct;
    if (item.get("concept") instanceof Map<?, ?> concept) {
      return firstPresent(concept, "conceptId", "concept_id", "CONCEPT_ID");
    }
    return null;
  }

  private Object firstPresent(Map<?, ?> values, String... keys) {
    for (String key : keys) {
      Object value = values.get(key);
      if (value != null) return value;
    }
    return null;
  }

  private List<String> persistedPolicies(List<Map<String, Object>> items) {
    List<String> policies = new java.util.ArrayList<>();
    for (Map<String, Object> item : items) {
      policies.add(policyKey(item.get("concept_id"), null,
          item.get("is_excluded"), null,
          item.get("include_descendants"), null,
          item.get("include_mapped"), null));
    }
    java.util.Collections.sort(policies);
    return policies;
  }

  private String policyKey(Object preferredConceptId, Object alternateConceptId,
      Object preferredExcluded, Object alternateExcluded,
      Object preferredDescendants, Object alternateDescendants,
      Object preferredMapped, Object alternateMapped) {
    Object conceptId = preferredConceptId != null ? preferredConceptId : alternateConceptId;
    if (!(conceptId instanceof Number)) throw new IllegalArgumentException("concept_id_required");
    return ((Number) conceptId).longValue() + "|" + flag(preferredExcluded != null ? preferredExcluded : alternateExcluded)
        + "|" + flag(preferredDescendants != null ? preferredDescendants : alternateDescendants)
        + "|" + flag(preferredMapped != null ? preferredMapped : alternateMapped);
  }

  private int flag(Object value) {
    if (value instanceof Boolean bool) return bool ? 1 : 0;
    if (value instanceof Number number) return number.intValue() == 0 ? 0 : 1;
    return Boolean.parseBoolean(String.valueOf(value)) ? 1 : 0;
  }

  private Integer persistReviewableProposal(UUID sessionId, Map<String, Object> proposal) throws JsonProcessingException {
    Map<String, Object> validation = proposal.get("validation") instanceof Map<?, ?> value
        ? new LinkedHashMap<>((Map<String, Object>) value) : Map.of();
    Map<String, Object> expression = validation.get("expression") instanceof Map<?, ?> value
        ? new LinkedHashMap<>((Map<String, Object>) value) : Map.of();
    if (!"passed".equals(validation.get("status")) || !(expression.get("items") instanceof List<?> items) || items.isEmpty()) {
      return null;
    }
    String expressionJson = objectMapper.writeValueAsString(expression);
    String checksum = sha256(expressionJson);
    String proposalJson = objectMapper.writeValueAsString(proposal);
    String acpValidationJson = objectMapper.writeValueAsString(validation);
    String provenanceJson = objectMapper.writeValueAsString(proposal.getOrDefault("candidate_provenance", Map.of()));
    return transactionTemplate.execute(status -> {
      Integer nextRevision = jdbcTemplate.queryForObject("select coalesce(max(revision), 0) + 1 from " + conceptSetReviewTable + " where session_id=?", Integer.class, sessionId);
      jdbcTemplate.update("""
          insert into %s (session_id, revision, expression_checksum, reviewed_expression, review_manifest, acp_validation, vocabulary_provenance)
          values (?, ?, ?, ?, ?, ?, ?)
          """.formatted(conceptSetReviewTable), sessionId, nextRevision, checksum, expressionJson, proposalJson, acpValidationJson, provenanceJson);
      jdbcTemplate.update("update " + conceptSetSessionTable + " set review_revision=?, last_active_at=? where session_id=?",
          nextRevision, Timestamp.from(Instant.now()), sessionId);
      return nextRevision;
    });
  }

  private String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte item : digest) hex.append(String.format("%02x", item));
      return hex.toString();
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException("sha256_unavailable", ex);
    }
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
      for (String key : java.util.List.of("plan", "answer", "current_step_guidance", "cautions", "suggested_next_actions", "follow_up_plan", "questions", "artifact_requests")) {
        if (dialogue.containsKey(key)) safeDialogue.put(key, dialogue.get(key));
      }
      String safeDialogueJson = objectMapper.writeValueAsString(safeDialogue);
      boolean needsClarification = safeDialogue.get("questions") instanceof List<?> questions && !questions.isEmpty();
      String sessionState = needsClarification ? "needs_clarification" : "strategy_ready";
      String assistantAnswer = formatDialogueTranscript(safeDialogue);
      writeInTransaction(() -> {
        jdbcTemplate.update("update " + conceptSetSessionTable + " set state=?, assistant_state=?, last_active_at=? where session_id=?", sessionState, safeDialogueJson, Timestamp.from(Instant.now()), sessionId);
        recordDialogueMessage(sessionId, "assistant", assistantAnswer, safeDialogueJson);
      });
      response.put("state", sessionState);
      response.put("assistant_message", safeDialogue.getOrDefault("answer", ""));
      response.put("dialogue", safeDialogue);
      response.put("dialogue_history", recentDialogueMessages(sessionId));
      response.put("allowed_actions", java.util.List.of("reply", "cancel"));
    } catch (Exception ex) {
      writeInTransaction(() -> jdbcTemplate.update("update " + conceptSetSessionTable + " set state=?, last_active_at=? where session_id=?", "error", Timestamp.from(Instant.now()), sessionId));
      response.put("state", "error");
      response.put("error", Map.of("code", "study_agent_unavailable", "retryable", true));
    }
    return response;
  }

  /** Render ACP narrative once, in the durable dialogue transcript, with no second guidance pane. */
  private String formatDialogueTranscript(Map<String, Object> dialogue) {
    StringBuilder text = new StringBuilder(String.valueOf(dialogue.getOrDefault("answer", "")).trim());
    appendTranscriptSection(text, "Next steps", dialogue.get("current_step_guidance"));
    appendTranscriptSection(text, "Suggested strategy", dialogue.get("plan"));
    appendTranscriptSection(text, "Review considerations", dialogue.get("cautions"));
    appendTranscriptSection(text, "Possible next actions", dialogue.get("suggested_next_actions"));
    return text.toString().trim();
  }

  private void appendTranscriptSection(StringBuilder text, String heading, Object content) {
    if (content == null) return;
    List<?> lines = content instanceof List<?> list ? list : List.of(content);
    if (lines.isEmpty()) return;
    if (!text.isEmpty()) text.append("\n\n");
    text.append(heading).append(":");
    for (Object line : lines) {
      String value = String.valueOf(line).trim();
      if (!value.isEmpty()) text.append("\n- ").append(value);
    }
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
