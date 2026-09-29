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
  private final String specificationTable;
  private final StudyAgentCohortSpecificationService specificationService;

  public StudyAgentCohortDefinitionSessionController(
      JdbcTemplate jdbcTemplate,
      TransactionTemplate transactionTemplate,
      AuthorizationService authorizationService,
      ObjectMapper objectMapper,
      @Value("${study-agent.cohort-definition.enabled:false}") boolean enabled,
      StudyAgentAcpClient acpClient,
      StudyAgentCohortDefinitionReviewService reviewService,
      StudyAgentCohortSpecificationService specificationService,
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
    this.specificationService = specificationService;
    LOGGER.info("Study Agent cohort-definition authoring enabled={}", enabled);
    this.sessionTable = ohdsiSchema + ".study_agent_cohort_definition_session";
    this.reviewTable = ohdsiSchema + ".study_agent_cohort_definition_review";
    this.specificationTable = ohdsiSchema + ".study_agent_cohort_definition_specification";
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

  /** Lists the current user's unfinished multi-component authoring plans. */
  @GetMapping("/specifications")
  @PreAuthorize("isPermitted('study-agent:cohort-definition-assist') and isPermitted('create:cohort-definition')")
  public Map<String, Object> listActiveSpecifications() {
    requireEnabled();
    Long userId = authorizationService.getAuthenticatedPrincipal().getUserId();
    List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
        select s.session_id, s.narrative, sp.revision, sp.state, sp.specification, sp.created_at
        from %s s
        join %s sp on sp.session_id=s.session_id
          and sp.revision=(select max(current_sp.revision) from %s current_sp where current_sp.session_id=s.session_id)
        where s.user_id=? and s.archived_at is null and s.acquisition_route='make_computable'
          and sp.state in ('needs_component_review', 'needs_logic_review', 'ready_for_projection')
        order by sp.created_at desc
        limit 20
        """.formatted(sessionTable, specificationTable, specificationTable), userId);
    List<Map<String, Object>> plans = new java.util.ArrayList<>();
    for (Map<String, Object> row : rows) {
      Map<String, Object> specification;
      try {
        specification = objectMapper.readValue(string(row.get("specification")), Map.class);
      } catch (Exception ex) {
        LOGGER.warn("Skipping unreadable Study Agent cohort specification {}", row.get("session_id"));
        continue;
      }
      Object rawSlots = specification.get("concept_set_slots");
      int slotCount = rawSlots instanceof List<?> slots ? slots.size() : 0;
      int reviewedCount = rawSlots instanceof List<?> slots
          ? (int) slots.stream().filter(slot -> "reviewed".equals(map(slot).get("asset_status"))).count() : 0;
      Map<String, Object> plan = new LinkedHashMap<>();
      plan.put("session_id", String.valueOf(row.get("session_id")));
      plan.put("narrative", string(row.get("narrative")));
      plan.put("revision", row.get("revision"));
      plan.put("state", string(row.get("state")));
      plan.put("concept_set_slot_count", slotCount);
      plan.put("reviewed_slot_count", reviewedCount);
      plan.put("updated_at", String.valueOf(row.get("created_at")));
      plans.add(plan);
    }
    return Map.of("plans", plans);
  }

  /**
   * Archives an unfinished plan without deleting its review revisions or
   * concept-set snapshots. Archived plans are intentionally excluded from
   * normal resume and provenance lookups.
   */
  @PostMapping("/{sessionId}/archive")
  @PreAuthorize("isPermitted('study-agent:cohort-definition-assist') and isPermitted('create:cohort-definition')")
  public Map<String, Object> archiveSpecification(@PathVariable String sessionId) {
    UUID parsed = parseSessionId(sessionId);
    ownedSession(sessionId, "make_computable");
    Map<String, Object> latest = specificationService.findLatest(specificationTable, parsed);
    if (latest == null || !List.of("needs_component_review", "needs_logic_review", "ready_for_projection").contains(string(latest.get("state")))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an unfinished cohort plan can be archived");
    }
    int updated = transactionTemplate.execute(status -> jdbcTemplate.update(
        "update " + sessionTable + " set state=?, archived_at=?, last_active_at=? where session_id=? and user_id=? and archived_at is null",
        "archived", Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), parsed,
        authorizationService.getAuthenticatedPrincipal().getUserId()));
    if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Cohort plan is no longer available to archive");
    return Map.of("session_id", sessionId, "state", "archived");
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
    Map<String, Object> requestedScope = map(request.get("scope"));
    boolean scopeConfirmed = Boolean.TRUE.equals(request.get("confirmed_scope"))
        && !string(requestedScope.get("index_event")).isEmpty();
    String specificationState = null;
    if ("ok".equals(response.get("status")) && response.get("circe_json") instanceof Map<?, ?> expression
        && expression.get("PrimaryCriteria") instanceof Map<?, ?> && expression.get("ConceptSets") instanceof List<?>) {
      persistComputableDraft(parsed, string(session.get("narrative")), expression, response);
      specificationState = scopeConfirmed ? "draft_ready" : null;
    } else {
      String state = "needs_clarification".equals(response.get("status")) ? "needs_scope_clarification"
          : "needs_concept_review".equals(response.get("status")) ? "needs_concept_review" : "make_computable_response";
      writeInTransaction(() -> updateState(parsed, state, response));
      if (scopeConfirmed && "needs_concept_review".equals(state)) {
        boolean hasCandidateSlice = response.get("concept_candidates") instanceof List<?> candidates && !candidates.isEmpty();
        specificationState = hasCandidateSlice ? state : "needs_candidate_retrieval";
      }
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("session_id", sessionId);
    result.put("flow", response);
    if (specificationState != null) {
      Map<String, Object> specification = specificationService.buildSingleIndexSpecification(
          string(session.get("narrative")), requestedScope, request.get("concept_sets") instanceof List<?> sets ? sets : List.of(), specificationState);
      result.put("cohort_specification", specificationService.persist(specificationTable, parsed, specification, specificationState, "draft_ready".equals(specificationState)));
    }
    return result;
  }

  /**
   * Creates a review-only multi-component plan. The endpoint does not retrieve
   * concepts or emit Circe; each named concept-set asset must be reviewed by a
   * subsequent linked workbench task.
   */
  @PostMapping("/{sessionId}/specification")
  @PreAuthorize("isPermitted('study-agent:cohort-definition-assist') and isPermitted('create:cohort-definition')")
  public Map<String, Object> createMultiComponentSpecification(@PathVariable String sessionId, @RequestBody Map<String, Object> request) {
    Map<String, Object> session = ownedSession(sessionId, "make_computable");
    Map<String, Object> scope = map(request.get("scope"));
    if (string(scope.get("index_event")).isEmpty() || !(request.get("components") instanceof List<?> rawComponents) || rawComponents.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A primary index event and one or more cohort components are required");
    }
    List<Map<String, Object>> components = new java.util.ArrayList<>();
    for (Object raw : rawComponents) {
      Map<String, Object> component = map(raw);
      String role = string(component.get("criterion_role"));
      String label = string(component.get("label"));
      String domain = string(component.get("domain"));
      String relationship = string(component.get("relationship"));
      if (label.isEmpty() || label.length() > 256 || domain.isEmpty()
          || !List.of("supporting", "exclusion", "visit_restriction").contains(role)
          || !List.of("required_with_index", "exclude_at_index", "overlaps_index").contains(relationship)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cohort component");
      }
      components.add(Map.of("label", label, "domain", domain, "criterion_role", role, "relationship", relationship));
    }
    UUID parsed = parseSessionId(sessionId);
    Map<String, Object> specification = specificationService.buildMultiComponentSpecification(string(session.get("narrative")), scope, components);
    Map<String, Object> persisted = specificationService.persist(specificationTable, parsed, specification, "needs_component_review", false);
    writeInTransaction(() -> updateState(parsed, "needs_component_review", persisted));
    return Map.of("session_id", sessionId, "cohort_specification", persisted);
  }

  @GetMapping("/{sessionId}/specification")
  @PreAuthorize("isPermitted('study-agent:cohort-definition-assist')")
  public Map<String, Object> specification(@PathVariable String sessionId) {
    ownedSession(sessionId, null);
    Map<String, Object> specification = specificationService.findLatest(specificationTable, parseSessionId(sessionId));
    if (specification == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Study Agent cohort specification not found");
    // Plans reviewed before projection support was introduced remain resumable.
    // Enrich their read model without mutating the accepted historical revision.
    if ("ready_for_projection".equals(string(specification.get("state")))) {
      Map<String, Object> body = objectMapper.convertValue(specification.get("specification"), LinkedHashMap.class);
      if (!body.containsKey("projection")) {
        body.put("projection", specificationService.assessProjection(body));
        specification = new LinkedHashMap<>(specification);
        specification.put("specification", body);
      }
    }
    return Map.of("session_id", sessionId, "cohort_specification", specification);
  }

  /**
   * Attaches a server-read snapshot of a saved Atlas concept set to a cohort
   * specification slot. The browser supplies only identifiers; it cannot
   * supply or alter the policy expression captured by this provenance link.
   */
  @PostMapping("/{sessionId}/specification/slots/{slotId}/concept-set/{conceptSetId}")
  @PreAuthorize("isPermitted('study-agent:cohort-definition-assist') and isPermitted('create:cohort-definition') and (isOwner(#conceptSetId, CONCEPT_SET) or isPermitted('read:conceptset') or isPermitted('write:conceptset') or hasEntityAccess(#conceptSetId, CONCEPT_SET, READ) or hasEntityAccess(#conceptSetId, CONCEPT_SET, WRITE))")
  public Map<String, Object> attachConceptSetSnapshot(
      @PathVariable String sessionId, @PathVariable String slotId, @PathVariable Integer conceptSetId) {
    if (conceptSetId == null || conceptSetId <= 0 || slotId == null || !slotId.matches("[A-Za-z0-9_-]{1,128}")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid concept-set attachment");
    }
    UUID parsed = parseSessionId(sessionId);
    ownedSession(sessionId, "make_computable");
    Map<String, Object> latest = specificationService.findLatest(specificationTable, parsed);
    if (latest == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Study Agent cohort specification not found");
    Map<String, Object> snapshot = savedConceptSetSnapshot(conceptSetId);
    Map<String, Object> updated;
    try {
      updated = specificationService.attachReviewedConceptSet(map(latest.get("specification")), slotId, snapshot);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
    }
    String state = string(updated.get("state"));
    Map<String, Object> persisted = specificationService.persist(specificationTable, parsed, updated, state, false);
    writeInTransaction(() -> updateState(parsed, state, persisted));
    return Map.of("session_id", sessionId, "concept_set_id", conceptSetId, "cohort_specification", persisted);
  }

  /**
   * Persists an explicit, non-emitting review of criterion bindings and simple
   * cohort logic. This endpoint never converts a reviewed specification into
   * Circe JSON; that projection has its own supported-construct gate.
   */
  @PostMapping("/{sessionId}/specification/logic-review")
  @PreAuthorize("isPermitted('study-agent:cohort-definition-assist') and isPermitted('create:cohort-definition')")
  public Map<String, Object> confirmSpecificationLogic(@PathVariable String sessionId, @RequestBody Map<String, Object> request) {
    UUID parsed = parseSessionId(sessionId);
    ownedSession(sessionId, "make_computable");
    Map<String, Object> latest = specificationService.findLatest(specificationTable, parsed);
    if (latest == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Study Agent cohort specification not found");
    if (!"needs_logic_review".equals(string(latest.get("state")))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Cohort logic is not ready for review");
    }
    Map<String, Object> updated;
    try {
      updated = specificationService.confirmLogicReview(map(latest.get("specification")), request);
    } catch (IllegalArgumentException | IllegalStateException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
    Map<String, Object> persisted = specificationService.persist(specificationTable, parsed, updated, "ready_for_projection", false);
    writeInTransaction(() -> updateState(parsed, "ready_for_projection", persisted));
    return Map.of("session_id", sessionId, "cohort_specification", persisted);
  }

  /** Reopens a reviewed plan so the user can explicitly revise its cohort-only bindings. */
  @PostMapping("/{sessionId}/specification/reopen-logic-review")
  @PreAuthorize("isPermitted('study-agent:cohort-definition-assist') and isPermitted('create:cohort-definition')")
  public Map<String, Object> reopenSpecificationLogic(@PathVariable String sessionId) {
    UUID parsed = parseSessionId(sessionId);
    ownedSession(sessionId, "make_computable");
    Map<String, Object> latest = specificationService.findLatest(specificationTable, parsed);
    if (latest == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Study Agent cohort specification not found");
    if (!"ready_for_projection".equals(string(latest.get("state")))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a reviewed cohort plan can be reopened for logic revision");
    }
    Map<String, Object> reopened = specificationService.reopenLogicReview(map(latest.get("specification")));
    Map<String, Object> persisted = specificationService.persist(specificationTable, parsed, reopened, "needs_logic_review", false);
    writeInTransaction(() -> updateState(parsed, "needs_logic_review", persisted));
    return Map.of("session_id", sessionId, "cohort_specification", persisted);
  }

  /**
   * Projects only a fully reviewed, explicitly supported cohort specification.
   * The accepted concept-set snapshots are reconstructed server-side; the
   * browser cannot substitute policy rows or request a lossy projection.
   */
  @PostMapping("/{sessionId}/specification/project")
  @PreAuthorize("isPermitted('study-agent:cohort-definition-assist') and isPermitted('create:cohort-definition')")
  public Map<String, Object> projectSpecification(@PathVariable String sessionId) {
    UUID parsed = parseSessionId(sessionId);
    Map<String, Object> session = ownedSession(sessionId, "make_computable");
    Map<String, Object> latest = specificationService.findLatest(specificationTable, parsed);
    if (latest == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Study Agent cohort specification not found");
    if (!"ready_for_projection".equals(string(latest.get("state")))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Cohort plan is not ready for projection");
    }
    Map<String, Object> specification = map(latest.get("specification"));
    Map<String, Object> input;
    try {
      input = specificationService.projectionInput(specification);
    } catch (IllegalStateException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("narrative_statement", string(session.get("narrative")));
    payload.put("confirmed_scope", true);
    payload.put("scope", input.get("scope"));
    payload.put("concept_review_mode", "provided_only");
    payload.put("concept_build_mode", "search_only");
    payload.put("review_delivery", "inline");
    payload.put("candidate_limit", 1);
    payload.put("concept_sets", input.get("concept_sets"));
    Map<String, Object> response = acpClient.post("/flows/phenotype_make_computable", payload);
    if (!"ok".equals(response.get("status")) || !(response.get("circe_json") instanceof Map<?, ?> expression)
        || !(expression.get("PrimaryCriteria") instanceof Map<?, ?>) || !(expression.get("ConceptSets") instanceof List<?>)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "The reviewed cohort plan could not be projected to a validated Circe definition");
    }
    persistComputableDraft(parsed, string(session.get("narrative")), expression, response);
    Map<String, Object> projected = objectMapper.convertValue(specification, LinkedHashMap.class);
    projected.put("state", "draft_ready");
    projected.put("unresolved_decisions", List.of());
    projected.put("unsupported_constructs", List.of());
    projected.put("projection", Map.of("supported", true, "mode", "condition_visit_overlap", "status", "emitted", "emitted_at", Instant.now().toString()));
    Map<String, Object> persisted = specificationService.persist(specificationTable, parsed, projected, "draft_ready", true);
    writeInTransaction(() -> updateState(parsed, "draft_ready", persisted));
    return Map.of("session_id", sessionId, "cohort_specification", persisted,
        "name", string(session.get("narrative")), "expression", expression,
        "expression_checksum", sha256(json(expression)));
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

  private Map<String, Object> savedConceptSetSnapshot(int conceptSetId) {
    String schema = sessionTable.substring(0, sessionTable.indexOf('.'));
    Map<String, Object> set;
    try {
      set = jdbcTemplate.queryForMap("select concept_set_name from " + schema + ".concept_set where concept_set_id=?", conceptSetId);
    } catch (EmptyResultDataAccessException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Concept set was not found");
    }
    List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
        select concept_id, is_excluded, include_descendants, include_mapped
        from %s.concept_set_item where concept_set_id=? order by concept_set_item_id
        """.formatted(schema), conceptSetId);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Concept set must contain at least one policy row before attachment");
    List<Map<String, Object>> items = new java.util.ArrayList<>();
    for (Map<String, Object> row : rows) {
      items.add(Map.of(
          "concept", Map.of("CONCEPT_ID", ((Number) row.get("concept_id")).intValue()),
          "isExcluded", flag(row.get("is_excluded")),
          "includeDescendants", flag(row.get("include_descendants")),
          "includeMapped", flag(row.get("include_mapped"))));
    }
    Map<String, Object> expression = Map.of("items", items);
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("source", "atlas_concept_set_snapshot");
    snapshot.put("concept_set_id", conceptSetId);
    snapshot.put("name", string(set.get("concept_set_name")));
    snapshot.put("policy_item_count", items.size());
    snapshot.put("accepted_expression", expression);
    snapshot.put("expression_checksum", sha256(json(expression)));
    snapshot.put("snapshot_at", Timestamp.from(Instant.now()).toInstant().toString());
    return snapshot;
  }

  private boolean flag(Object value) {
    return value instanceof Number number ? number.intValue() != 0 : Boolean.TRUE.equals(value);
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
  @SuppressWarnings("unchecked")
  private Map<String, Object> map(Object value) { return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of(); }
  private String string(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
  private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid Study Agent payload"); } }
  private String sha256(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception ex) { throw new IllegalStateException(ex); } }
}
