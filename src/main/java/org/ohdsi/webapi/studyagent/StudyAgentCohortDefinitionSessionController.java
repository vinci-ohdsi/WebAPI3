package org.ohdsi.webapi.studyagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ohdsi.webapi.security.authz.AuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * WebAPI boundary for /ohdsi cohort-definition acquisition. It deliberately
 * exposes reviewable ACP output, never ACP/MCP endpoints, credentials, or an
 * automatic cohort save. Drafts return to Atlas's ordinary new-cohort builder.
 */
@RestController
@RequestMapping("/study-agent/v1/cohort-definition-sessions")
public class StudyAgentCohortDefinitionSessionController {
  private static final Logger LOGGER = LoggerFactory.getLogger(StudyAgentCohortDefinitionSessionController.class);
  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;
  private final AuthorizationService authorizationService;
  private final ObjectMapper objectMapper;
  private final boolean enabled;
  private final StudyAgentAcpClient acpClient;
  private final StudyAgentCohortDefinitionReviewService reviewService;
  private final String sessionTable;
  private final String reviewTable;

  public StudyAgentCohortDefinitionSessionController(
      JdbcTemplate jdbcTemplate,
      TransactionTemplate transactionTemplate,
      AuthorizationService authorizationService,
      ObjectMapper objectMapper,
      @Value("${study-agent.cohort-definition.enabled:false}") boolean enabled,
      StudyAgentAcpClient acpClient,
      StudyAgentCohortDefinitionReviewService reviewService,
      @Value("${datasource.ohdsi.schema:public}") String ohdsiSchema) {
    if (!ohdsiSchema.matches("[A-Za-z_][A-Za-z0-9_]*")) {
      throw new IllegalArgumentException("Invalid OHDSI schema identifier");
    }
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = transactionTemplate;
    this.authorizationService = authorizationService;
    this.objectMapper = objectMapper;
    this.enabled = enabled;
    this.acpClient = acpClient;
    this.reviewService = reviewService;
    LOGGER.info("Study Agent cohort-definition authoring enabled={}", enabled);
    this.sessionTable = ohdsiSchema + ".study_agent_cohort_definition_session";
    this.reviewTable = ohdsiSchema + ".study_agent_cohort_definition_review";
  }

  @PostMapping
  @PreAuthorize("isPermitted('study-agent:cohort-definition-assist') and isPermitted('create:cohort-definition')")
  public Map<String, Object> create(@RequestBody Map<String, Object> request) {
    requireEnabled();
    String narrative = string(request.get("narrative"));
    String route = string(request.get("route"));
    if (narrative.isEmpty() || narrative.length() > 4000 || !List.of("ai_search", "library", "make_computable").contains(route)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid Study Agent cohort request");
    }
    UUID sessionId = UUID.randomUUID();
    reviewService.createSession(sessionTable, sessionId, authorizationService.getAuthenticatedPrincipal().getUserId(), route, narrative);
    return Map.of("session_id", sessionId.toString(), "route", route, "narrative", narrative, "state", "started");
  }

  @PostMapping("/{sessionId}/recommendations")
  @PreAuthorize("isPermitted('study-agent:cohort-definition-assist')")
  public Map<String, Object> recommendations(@PathVariable String sessionId, @RequestBody(required = false) Map<String, Object> request) {
    Map<String, Object> session = ownedSession(sessionId, "ai_search");
    int candidateOffset = request != null && request.get("candidate_offset") instanceof Number number ? number.intValue() : 0;
    if (candidateOffset < 0 || candidateOffset > 10000) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid candidate offset");
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("study_intent", session.get("narrative"));
    payload.put("top_k", 20);
    payload.put("candidate_limit", 10);
    payload.put("max_results", 3);
    payload.put("candidate_offset", candidateOffset);
    Map<String, Object> response = acpClient.post("/flows/phenotype_recommendation", payload);
    writeInTransaction(() -> updateState(parseSessionId(sessionId), "recommendations_ready", response));
    return Map.of("session_id", sessionId, "recommendations", response);
  }

  @PostMapping("/{sessionId}/library-search")
  @PreAuthorize("isPermitted('study-agent:cohort-definition-assist')")
  public Map<String, Object> librarySearch(@PathVariable String sessionId, @RequestBody Map<String, Object> request) {
    Map<String, Object> session = ownedSession(sessionId, "library");
    String query = string(request.get("query"));
    if (query.isEmpty() || query.length() > 4000) query = string(session.get("narrative"));
    Map<String, Object> response = acpClient.post("/flows/phenotype_catalog_search", Map.of("query", query, "top_k", 25, "offset", 0));
    writeInTransaction(() -> updateState(parseSessionId(sessionId), "library_results_ready", response));
    return Map.of("session_id", sessionId, "catalog", response);
  }

  /**
   * Starts (or advances) the existing review-gated narrative-to-computable flow.
   * This endpoint intentionally forwards only typed review inputs and never
   * turns a clarification response into an executable draft.
   */
  @PostMapping("/{sessionId}/make-computable")
  @PreAuthorize("isPermitted('study-agent:cohort-definition-assist') and isPermitted('create:cohort-definition')")
  public Map<String, Object> makeComputable(@PathVariable String sessionId, @RequestBody Map<String, Object> request) {
    Map<String, Object> session = ownedSession(sessionId, "make_computable");
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("narrative_statement", session.get("narrative"));
    payload.put("confirmed_scope", Boolean.TRUE.equals(request.get("confirmed_scope")));
    payload.put("scope", request.get("scope") instanceof Map<?, ?> ? request.get("scope") : Map.of());
    payload.put("concept_review_mode", string(request.get("concept_review_mode")).isEmpty() ? "required" : string(request.get("concept_review_mode")));
    payload.put("concept_build_mode", string(request.get("concept_build_mode")).isEmpty() ? "search_only" : string(request.get("concept_build_mode")));
    payload.put("review_delivery", string(request.get("review_delivery")).isEmpty() ? "auto" : string(request.get("review_delivery")));
    payload.put("candidate_limit", request.get("candidate_limit") instanceof Number number ? number.intValue() : 20);
    payload.put("concept_sets", request.get("concept_sets") instanceof List<?> ? request.get("concept_sets") : List.of());
    Map<String, Object> response = acpClient.post("/flows/phenotype_make_computable", payload);
    UUID parsed = parseSessionId(sessionId);
    if ("ok".equals(response.get("status")) && response.get("circe_json") instanceof Map<?, ?> expression
        && expression.get("PrimaryCriteria") instanceof Map<?, ?> && expression.get("ConceptSets") instanceof List<?>) {
      persistComputableDraft(parsed, string(session.get("narrative")), expression, response);
    } else {
      String state = "needs_clarification".equals(response.get("status")) ? "needs_scope_clarification"
          : "needs_concept_review".equals(response.get("status")) ? "needs_concept_review" : "make_computable_response";
      writeInTransaction(() -> updateState(parsed, state, response));
    }
    return Map.of("session_id", sessionId, "flow", response);
  }

  /** Fetches a supported library definition, records its exact provenance, and returns an unsaved Atlas draft. */
  @PostMapping("/{sessionId}/phenotypes/{phenotypeId}/draft")
  @PreAuthorize("isPermitted('study-agent:cohort-definition-assist') and isPermitted('create:cohort-definition')")
  public Map<String, Object> importDraft(@PathVariable String sessionId, @PathVariable String phenotypeId, @RequestBody(required = false) Map<String, Object> request) {
    Map<String, Object> session = ownedSession(sessionId, null);
    String cleanedId = string(phenotypeId);
    if (cleanedId.isEmpty() || cleanedId.length() > 256) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid phenotype identifier");
    Map<String, Object> definition = acpClient.post("/flows/phenotype_definition", Map.of("phenotype_id", cleanedId, "allow_make_computable", true,
        "recommendation_context", request == null ? Map.of() : request));
    if (!"ok".equals(definition.get("status")) || !(definition.get("circe_json") instanceof Map<?, ?> expression)
        || !(expression.get("PrimaryCriteria") instanceof Map<?, ?>) || !(expression.get("ConceptSets") instanceof List<?>)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Selected phenotype does not provide a validated Circe definition");
    }
    UUID parsed = parseSessionId(sessionId);
    String sourceType = "ai_search".equals(session.get("acquisition_route")) ? "recommendation" : "phenotype_library";
    int revision = reviewService.persist(reviewTable, sessionTable, parsed, sourceType, cleanedId, string(definition.get("phenotype_name")), expression, definition);
    return Map.of("session_id", sessionId, "review_revision", revision, "name", string(definition.get("phenotype_name")), "expression", expression, "expression_checksum", sha256(json(expression)));
  }

  @PostMapping("/{sessionId}/cohort-definition/{cohortDefinitionId}")
  @PreAuthorize("isPermitted('study-agent:cohort-definition-assist') and (isOwner(#cohortDefinitionId, COHORT_DEFINITION) or isPermitted('write:cohort-definition') or hasEntityAccess(#cohortDefinitionId, COHORT_DEFINITION, WRITE))")
  public Map<String, Object> linkSavedCohort(@PathVariable String sessionId, @PathVariable Integer cohortDefinitionId) {
    if (cohortDefinitionId == null || cohortDefinitionId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid cohort definition identifier");
    UUID parsed = parseSessionId(sessionId);
    ownedSession(sessionId, null);
    int updated = transactionTemplate.execute(status -> jdbcTemplate.update("update " + reviewTable + " set cohort_definition_id=? where id=(select id from " + reviewTable + " where session_id=? and cohort_definition_id is null order by revision desc limit 1)", cohortDefinitionId, parsed));
    if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Study Agent review is already linked or unavailable");
    return Map.of("session_id", sessionId, "cohort_definition_id", cohortDefinitionId);
  }

  @GetMapping("/{sessionId}/draft")
  @PreAuthorize("isPermitted('study-agent:cohort-definition-assist') and isPermitted('create:cohort-definition')")
  public Map<String, Object> draft(@PathVariable String sessionId) {
    UUID parsed = parseSessionId(sessionId);
    try {
      Map<String, Object> review = jdbcTemplate.queryForMap(("select r.revision, r.phenotype_name, r.reviewed_expression, r.expression_checksum from " + reviewTable + " r join " + sessionTable + " s on s.session_id=r.session_id where r.session_id=? and s.user_id=? and s.archived_at is null order by r.revision desc limit 1"), parsed, authorizationService.getAuthenticatedPrincipal().getUserId());
      return Map.of("session_id", sessionId, "review_revision", review.get("revision"), "name", review.get("phenotype_name"), "expression", objectMapper.readValue(string(review.get("reviewed_expression")), Map.class), "expression_checksum", review.get("expression_checksum"));
    } catch (EmptyResultDataAccessException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Study Agent cohort draft not found");
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Stored Study Agent cohort draft is invalid");
    }
  }

  private void persistComputableDraft(UUID sessionId, String narrative, Map<?, ?> expression, Map<String, Object> response) {
    reviewService.persist(reviewTable, sessionTable, sessionId, "make_computable", null, narrative, expression, response);
  }

  private Map<String, Object> ownedSession(String sessionId, String expectedRoute) {
    requireEnabled();
    UUID parsed = parseSessionId(sessionId);
    Map<String, Object> session = reviewService.findOwnedSession(sessionTable, parsed, authorizationService.getAuthenticatedPrincipal().getUserId());
    if (session == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Study Agent cohort session not found");
    if (expectedRoute != null && !expectedRoute.equals(session.get("acquisition_route"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Study Agent session route does not allow this action");
    return session;
  }

  private void updateState(UUID sessionId, String state, Object assistantState) {
    reviewService.updateState(sessionTable, sessionId, state, json(assistantState));
  }

  private void writeInTransaction(Runnable work) {
    transactionTemplate.executeWithoutResult(status -> work.run());
  }

  private UUID parseSessionId(String sessionId) {
    try { return UUID.fromString(sessionId); }
    catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid session identifier"); }
  }
  private void requireEnabled() { if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
  private String string(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
  private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid Study Agent payload"); } }
  private String sha256(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception ex) { throw new IllegalStateException(ex); } }
}
