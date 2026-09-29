package org.ohdsi.webapi.studyagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable, review-gated cohort authoring model. It intentionally records the
 * clinical/workflow plan rather than Circe JSON so that concept-set assets,
 * their criterion bindings, and cohort logic can evolve independently.
 */
@Service
public class StudyAgentCohortSpecificationService {
  private static final String SCHEMA_VERSION = "1";
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public StudyAgentCohortSpecificationService(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  /**
   * Produces the current one-index-set projection. Later multi-component
   * authoring adds slots and bindings to this same shape; it does not overload
   * the concept-set asset with cohort-only usage context.
   */
  public Map<String, Object> buildSingleIndexSpecification(
      String narrative, Map<String, Object> scope, List<?> conceptSets, String state) {
    String indexEvent = text(scope.get("index_event"));
    Map<String, Object> criterionDomains = map(scope.get("criterion_domains"));
    String domain = text(criterionDomains.get(indexEvent));
    if (domain.isEmpty() && conceptSets.size() == 1 && conceptSets.get(0) instanceof Map<?, ?> raw) {
      domain = text(raw.get("domain"));
    }

    Map<String, Object> slot = new LinkedHashMap<>();
    slot.put("slot_id", "primary-index-1");
    slot.put("label", indexEvent);
    slot.put("asset_status", "draft_ready".equals(state) ? "reviewed" : state);
    slot.put("asset", assetSummary(conceptSets));

    Map<String, Object> binding = new LinkedHashMap<>();
    binding.put("binding_id", "primary-index-entry");
    binding.put("concept_set_slot_id", "primary-index-1");
    binding.put("criterion_role", "primary_index");
    binding.put("domain", domain);

    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("event_label", indexEvent);
    entry.put("limit", text(scope.get("entry_limit")));
    entry.put("index_day_boundary", text(scope.get("index_day_boundary")));
    Map<String, Object> observation = new LinkedHashMap<>();
    observation.put("prior_days", scope.get("prior_observation"));
    Map<String, Object> exit = new LinkedHashMap<>();
    exit.put("strategy", scope.get("exit_strategy"));
    Map<String, Object> logic = new LinkedHashMap<>();
    logic.put("boolean_groups", List.of(Map.of("group_id", "primary-entry", "operator", "ALL", "binding_ids", List.of("primary-index-entry"))));
    logic.put("entry", entry);
    logic.put("observation", observation);
    logic.put("exit", exit);
    logic.put("temporal_relationships", List.of());

    List<String> unresolved = new ArrayList<>();
    if ("needs_candidate_retrieval".equals(state)) {
      unresolved.add("No local candidate slice was returned. Refine the index event or use Atlas search before reviewing a concept-set policy.");
    } else if (!"draft_ready".equals(state)) {
      unresolved.add("Review and accept the policy for the primary index concept-set asset.");
    }
    Map<String, Object> specification = new LinkedHashMap<>();
    specification.put("schema_version", SCHEMA_VERSION);
    specification.put("narrative", narrative);
    specification.put("state", state);
    specification.put("concept_set_slots", List.of(slot));
    specification.put("criterion_bindings", List.of(binding));
    specification.put("cohort_logic", logic);
    specification.put("unresolved_decisions", unresolved);
    specification.put("unsupported_constructs", List.of());
    return specification;
  }

  /** Builds a non-emitting multi-component plan from explicit user-reviewed roles. */
  public Map<String, Object> buildMultiComponentSpecification(
      String narrative, Map<String, Object> scope, List<Map<String, Object>> components) {
    Map<String, Object> specification = buildSingleIndexSpecification(narrative, scope, List.of(), "needs_component_review");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> slots = new ArrayList<>((List<Map<String, Object>>) specification.get("concept_set_slots"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> bindings = new ArrayList<>((List<Map<String, Object>>) specification.get("criterion_bindings"));
    List<String> unresolved = new ArrayList<>();
    unresolved.add("Review and accept a concept-set policy for the primary index concept-set asset.");

    int ordinal = 0;
    for (Map<String, Object> component : components) {
      ordinal++;
      String role = text(component.get("criterion_role"));
      String slotId = role + "-" + ordinal;
      Map<String, Object> slot = new LinkedHashMap<>();
      slot.put("slot_id", slotId);
      slot.put("label", text(component.get("label")));
      slot.put("asset_status", "needs_concept_review");
      slot.put("asset", Map.of("source", "review_pending", "domain", text(component.get("domain")), "policy_item_count", 0));
      slots.add(slot);

      Map<String, Object> binding = new LinkedHashMap<>();
      binding.put("binding_id", slotId + "-binding");
      binding.put("concept_set_slot_id", slotId);
      binding.put("criterion_role", role);
      binding.put("domain", text(component.get("domain")));
      binding.put("relationship", text(component.get("relationship")));
      bindings.add(binding);
      unresolved.add("Review and accept a concept-set policy for " + text(component.get("label")) + ".");
    }
    Map<String, Object> logic = map(specification.get("cohort_logic"));
    logic.put("component_plan_status", "needs_component_review");
    logic.put("temporal_relationships", components.stream().map(component -> Map.of(
        "component_label", text(component.get("label")),
        "relationship", text(component.get("relationship")))).toList());
    specification.put("state", "needs_component_review");
    specification.put("concept_set_slots", slots);
    specification.put("criterion_bindings", bindings);
    specification.put("cohort_logic", logic);
    specification.put("unresolved_decisions", unresolved);
    return specification;
  }

  @Transactional
  public Map<String, Object> persist(String table, UUID sessionId, Map<String, Object> specification, String state, boolean accepted) {
    try {
      String payload = mapper.writeValueAsString(specification);
      String checksum = checksum(payload);
      Integer prior = jdbc.queryForObject("select coalesce(max(revision), 0) from " + table + " where session_id=?", Integer.class, sessionId);
      int revision = (prior == null ? 0 : prior) + 1;
      jdbc.update("insert into " + table + " (session_id, revision, state, specification, specification_checksum, created_at, accepted_at) values (?, ?, ?, ?, ?, ?, ?)",
          sessionId, revision, state, payload, checksum, Timestamp.from(Instant.now()), accepted ? Timestamp.from(Instant.now()) : null);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("revision", revision);
      result.put("state", state);
      result.put("specification", specification);
      result.put("specification_checksum", checksum);
      return result;
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to persist Study Agent cohort specification", ex);
    }
  }

  public Map<String, Object> findLatest(String table, UUID sessionId) {
    try {
      Map<String, Object> row = jdbc.queryForMap("select revision, state, specification, specification_checksum, created_at, accepted_at from " + table + " where session_id=? order by revision desc limit 1", sessionId);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("revision", row.get("revision"));
      result.put("state", row.get("state"));
      result.put("specification", mapper.readValue(text(row.get("specification")), Map.class));
      result.put("specification_checksum", row.get("specification_checksum"));
      result.put("created_at", row.get("created_at"));
      result.put("accepted_at", row.get("accepted_at"));
      return result;
    } catch (EmptyResultDataAccessException ex) {
      return null;
    } catch (Exception ex) {
      throw new IllegalStateException("Stored Study Agent cohort specification is invalid", ex);
    }
  }

  /**
   * Records the exact saved concept-set expression accepted for one cohort
   * building block. The copy is intentionally embedded in the specification:
   * later edits to the reusable concept-set asset must never rewrite an
   * already reviewed cohort plan.
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> attachReviewedConceptSet(
      Map<String, Object> sourceSpecification, String slotId, Map<String, Object> assetSnapshot) {
    Map<String, Object> specification = mapper.convertValue(sourceSpecification, LinkedHashMap.class);
    Object rawSlots = specification.get("concept_set_slots");
    if (!(rawSlots instanceof List<?> rawSlotList)) {
      throw new IllegalArgumentException("Cohort specification has no concept-set slots");
    }

    boolean found = false;
    List<Map<String, Object>> slots = new ArrayList<>();
    for (Object rawSlot : rawSlotList) {
      Map<String, Object> slot = mapper.convertValue(rawSlot, LinkedHashMap.class);
      if (slotId.equals(text(slot.get("slot_id")))) {
        slot.put("asset_status", "reviewed");
        slot.put("asset", assetSnapshot);
        found = true;
      }
      slots.add(slot);
    }
    if (!found) throw new IllegalArgumentException("Cohort specification slot was not found");

    boolean allReviewed = slots.stream().allMatch(slot -> "reviewed".equals(text(slot.get("asset_status"))));
    String state = allReviewed ? "needs_logic_review" : "needs_component_review";
    specification.put("state", state);
    specification.put("concept_set_slots", slots);

    List<String> unresolved = new ArrayList<>();
    for (Map<String, Object> slot : slots) {
      if (!"reviewed".equals(text(slot.get("asset_status")))) {
        unresolved.add("Review and accept a concept-set policy for " + text(slot.get("label")) + ".");
      }
    }
    if (allReviewed) {
      unresolved.add("Confirm the criterion bindings and cohort logic before generating a computable cohort draft.");
    }
    specification.put("unresolved_decisions", unresolved);
    Map<String, Object> logic = mapper.convertValue(specification.getOrDefault("cohort_logic", Map.of()), LinkedHashMap.class);
    logic.put("component_plan_status", allReviewed ? "needs_logic_review" : "needs_component_review");
    specification.put("cohort_logic", logic);
    return specification;
  }

  /**
   * Records explicit review of the cohort-only bindings and simple logic. This
   * deliberately produces a reviewed specification rather than Circe: a later
   * projection increment must translate only supported constructs.
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> confirmLogicReview(
      Map<String, Object> sourceSpecification, Map<String, Object> review) {
    Map<String, Object> specification = mapper.convertValue(sourceSpecification, LinkedHashMap.class);
    Object rawSlots = specification.get("concept_set_slots");
    Object rawBindings = specification.get("criterion_bindings");
    if (!(rawSlots instanceof List<?> slotList) || !(rawBindings instanceof List<?> bindingList)) {
      throw new IllegalArgumentException("Cohort specification is missing concept-set slots or criterion bindings");
    }
    boolean allReviewed = slotList.stream()
        .map(slot -> mapper.convertValue(slot, LinkedHashMap.class))
        .allMatch(slot -> "reviewed".equals(text(slot.get("asset_status"))));
    if (!allReviewed) throw new IllegalStateException("All concept-set policies must be reviewed before cohort logic can be confirmed");
    if (!Boolean.TRUE.equals(review.get("confirm_bindings")) || !Boolean.TRUE.equals(review.get("confirm_logic"))) {
      throw new IllegalArgumentException("Both binding and cohort-logic confirmations are required");
    }

    String supportingOperator = text(review.get("supporting_operator"));
    if (supportingOperator.isEmpty()) supportingOperator = "ALL";
    if (!List.of("ALL", "ANY").contains(supportingOperator)) {
      throw new IllegalArgumentException("Supporting-criteria operator must be ALL or ANY");
    }
    Map<String, Object> requestedRelationships = map(review.get("relationships"));
    Map<String, Object> requestedSupportingWindows = map(review.get("supporting_windows"));
    List<Map<String, Object>> bindings = new ArrayList<>();
    List<String> supportingBindingIds = new ArrayList<>();
    List<String> exclusionBindingIds = new ArrayList<>();
    for (Object rawBinding : bindingList) {
      Map<String, Object> binding = mapper.convertValue(rawBinding, LinkedHashMap.class);
      String id = text(binding.get("binding_id"));
      String role = text(binding.get("criterion_role"));
      if (!"primary_index".equals(role)) {
        String relationship = text(requestedRelationships.get(id));
        if (relationship.isEmpty()) relationship = text(binding.get("relationship"));
        if (!List.of("required_with_index", "exclude_at_index", "overlaps_index").contains(relationship)) {
          throw new IllegalArgumentException("Every non-primary criterion needs a supported relationship to the index event");
        }
        if (("visit_restriction".equals(role) && !"overlaps_index".equals(relationship))
            || ("exclusion".equals(role) && !"exclude_at_index".equals(relationship))
            || ("supporting".equals(role) && !"required_with_index".equals(relationship))) {
          throw new IllegalArgumentException("The selected relationship is not valid for this cohort criterion role");
        }
        binding.put("relationship", relationship);
        if ("supporting".equals(role)) {
          Map<String, Object> window = map(requestedSupportingWindows.get(id));
          Integer startDays = integer(window.get("start_days"));
          Integer endDays = integer(window.get("end_days"));
          if (startDays == null || endDays == null || startDays > endDays || endDays > 0) {
            throw new IllegalArgumentException("Supporting-condition windows require integer start and end days with start <= end <= 0");
          }
          binding.put("supporting_condition_window", Map.of("start_days", startDays, "end_days", endDays, "anchor", "index_start"));
        }
      }
      if ("supporting".equals(role) || "visit_restriction".equals(role)) supportingBindingIds.add(id);
      if ("exclusion".equals(role)) exclusionBindingIds.add(id);
      bindings.add(binding);
    }

    Map<String, Object> logic = mapper.convertValue(specification.getOrDefault("cohort_logic", Map.of()), LinkedHashMap.class);
    List<Map<String, Object>> groups = new ArrayList<>();
    groups.add(Map.of("group_id", "primary-entry", "operator", "ALL", "binding_ids", List.of("primary-index-entry")));
    if (!supportingBindingIds.isEmpty()) {
      groups.add(Map.of("group_id", "supporting-criteria", "operator", supportingOperator, "binding_ids", supportingBindingIds));
    }
    if (!exclusionBindingIds.isEmpty()) {
      groups.add(Map.of("group_id", "exclusions", "operator", "ANY", "binding_ids", exclusionBindingIds));
    }
    Map<String, Object> requestedExit = map(review.get("exit_strategy"));
    Object exitStrategy = review.containsKey("exit_strategy") ? review.get("exit_strategy") : map(logic.get("exit")).get("strategy");
    if (exitStrategy instanceof Map<?, ?> rawExit) {
      Map<String, Object> fixedExit = map(rawExit);
      if (!"fixed".equals(text(fixedExit.get("type")))
          || !List.of("startDate", "endDate").contains(text(fixedExit.get("index")))
          || integer(fixedExit.get("offset_days")) == null) {
        throw new IllegalArgumentException("Fixed exits require startDate or endDate and an integer offset");
      }
      exitStrategy = Map.of("type", "fixed", "index", text(fixedExit.get("index")), "offset_days", integer(fixedExit.get("offset_days")));
    } else if (!List.of("observation", "end_of_observation").contains(text(exitStrategy))) {
      throw new IllegalArgumentException("Exit strategy must be observation, end_of_observation, or a fixed exit");
    }
    Map<String, Object> exit = map(logic.get("exit"));
    exit.put("strategy", exitStrategy);
    logic.put("exit", exit);
    logic.put("boolean_groups", groups);
    List<Map<String, Object>> temporal = new ArrayList<>();
    for (Map<String, Object> binding : bindings) {
      if (!"primary_index".equals(text(binding.get("criterion_role")))) {
        temporal.add(Map.of("binding_id", text(binding.get("binding_id")), "relationship", text(binding.get("relationship"))));
      }
    }
    logic.put("temporal_relationships", temporal);
    logic.put("component_plan_status", "logic_reviewed");
    logic.put("review", Map.of(
        "binding_confirmation", true,
        "cohort_logic_confirmation", true,
        "supporting_operator", supportingOperator,
        "reviewed_at", Instant.now().toString()));

    specification.put("criterion_bindings", bindings);
    specification.put("cohort_logic", logic);
    specification.put("state", "ready_for_projection");
    specification.put("unresolved_decisions", List.of());
    specification.put("projection", assessProjection(specification));
    specification.put("unsupported_constructs", List.of(
        "No executable cohort draft has been emitted. Projection is available only for explicitly supported reviewed bindings."));
    return specification;
  }

  /** Reopens an accepted logic review without changing accepted concept-set snapshots. */
  @SuppressWarnings("unchecked")
  public Map<String, Object> reopenLogicReview(Map<String, Object> sourceSpecification) {
    Map<String, Object> specification = mapper.convertValue(sourceSpecification, LinkedHashMap.class);
    specification.put("state", "needs_logic_review");
    specification.put("unresolved_decisions", List.of("Revise and reconfirm the criterion bindings and cohort logic before projection."));
    specification.remove("projection");
    Map<String, Object> logic = mapper.convertValue(specification.getOrDefault("cohort_logic", Map.of()), LinkedHashMap.class);
    logic.put("component_plan_status", "needs_logic_review");
    specification.put("cohort_logic", logic);
    return specification;
  }

  /**
   * Conservative first multi-component projection surface. It preserves the
   * semantics already implemented by the ACP emitter: a Condition index event
   * with exactly one reviewed Visit restriction that overlaps the index event.
   * Other reviewed plans remain durable review artifacts until they gain a
   * purpose-built projection, rather than being flattened or guessed at.
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> assessProjection(Map<String, Object> specification) {
    List<?> slots = specification.get("concept_set_slots") instanceof List<?> value ? value : List.of();
    List<?> bindings = specification.get("criterion_bindings") instanceof List<?> value ? value : List.of();
    if (slots.stream().map(slot -> mapper.convertValue(slot, LinkedHashMap.class))
        .anyMatch(slot -> !"reviewed".equals(text(slot.get("asset_status"))))) {
      return Map.of("supported", false, "reason", "Every concept-set asset must be policy reviewed before projection.");
    }
    if (bindings.size() != 2) {
      return Map.of("supported", false, "reason", "This first projection supports one Condition index and one overlapping Visit restriction only.");
    }
    Map<String, Object> primary = null;
    Map<String, Object> restriction = null;
    for (Object raw : bindings) {
      Map<String, Object> binding = mapper.convertValue(raw, LinkedHashMap.class);
      if ("primary_index".equals(text(binding.get("criterion_role")))) primary = binding;
      else restriction = binding;
    }
    if (primary != null && restriction != null
        && "Condition".equals(text(primary.get("domain")))
        && "visit_restriction".equals(text(restriction.get("criterion_role")))
        && "Visit".equals(text(restriction.get("domain")))
        && "overlaps_index".equals(text(restriction.get("relationship")))) {
      return Map.of("supported", true, "mode", "condition_visit_overlap", "summary", "Condition index event restricted to an overlapping Visit event.");
    }
    Map<String, Object> supportingWindow = restriction == null ? Map.of() : map(restriction.get("supporting_condition_window"));
    Integer startDays = integer(supportingWindow.get("start_days"));
    Integer endDays = integer(supportingWindow.get("end_days"));
    if (primary != null && restriction != null
        && "Drug".equals(text(primary.get("domain")))
        && "supporting".equals(text(restriction.get("criterion_role")))
        && "Condition".equals(text(restriction.get("domain")))
        && "required_with_index".equals(text(restriction.get("relationship")))
        && startDays != null && endDays != null && startDays <= endDays && endDays <= 0) {
      return Map.of("supported", true, "mode", "drug_supporting_condition", "summary", "Drug index event with reviewed supporting Condition evidence in the confirmed pre-index window.");
    }
    return Map.of("supported", false, "reason", "This reviewed plan needs an explicit supported projection. Currently supported: a Condition index with an overlapping Visit restriction, or a Drug index with supporting Condition evidence in a confirmed on/before-index window.");
  }

  /** Builds the exact typed ACP request from accepted snapshots; browser data is never reused. */
  @SuppressWarnings("unchecked")
  public Map<String, Object> projectionInput(Map<String, Object> specification) {
    Map<String, Object> assessment = assessProjection(specification);
    if (!Boolean.TRUE.equals(assessment.get("supported"))) throw new IllegalStateException(text(assessment.get("reason")));
    List<Map<String, Object>> slots = ((List<?>) specification.get("concept_set_slots")).stream()
        .map(slot -> (Map<String, Object>) mapper.convertValue(slot, Map.class)).toList();
    List<Map<String, Object>> bindings = ((List<?>) specification.get("criterion_bindings")).stream()
        .map(binding -> (Map<String, Object>) mapper.convertValue(binding, Map.class)).toList();
    Map<String, Object> logic = map(specification.get("cohort_logic"));
    Map<String, Object> entry = map(logic.get("entry"));
    Map<String, Object> observation = map(logic.get("observation"));
    Map<String, Object> exit = map(logic.get("exit"));
    List<Map<String, Object>> conceptSets = new ArrayList<>();
    for (Map<String, Object> binding : bindings) {
      String slotId = text(binding.get("concept_set_slot_id"));
      Map<String, Object> slot = slots.stream().filter(candidate -> slotId.equals(text(candidate.get("slot_id")))).findFirst()
          .orElseThrow(() -> new IllegalStateException("A criterion binding has no reviewed concept-set asset"));
      Map<String, Object> asset = map(slot.get("asset"));
      Map<String, Object> expression = map(asset.get("accepted_expression"));
      Object rawItems = expression.get("items");
      if (!(rawItems instanceof List<?> itemList) || itemList.isEmpty()) throw new IllegalStateException("A reviewed concept-set snapshot has no policy items");
      List<Map<String, Object>> items = new ArrayList<>();
      for (Object rawItem : itemList) {
        Map<String, Object> item = mapper.convertValue(rawItem, LinkedHashMap.class);
        Map<String, Object> concept = map(item.get("concept"));
        Object conceptId = concept.get("CONCEPT_ID");
        if (!(conceptId instanceof Number number)) throw new IllegalStateException("A reviewed concept-set snapshot has an invalid concept ID");
        items.add(Map.of("concept_id", number.intValue(), "domain", text(binding.get("domain")),
            "is_excluded", Boolean.TRUE.equals(item.get("isExcluded")),
            "include_descendants", Boolean.TRUE.equals(item.get("includeDescendants")),
            "include_mapped", Boolean.TRUE.equals(item.get("includeMapped"))));
      }
      conceptSets.add(Map.of("name", text(slot.get("label")), "domain", text(binding.get("domain")), "items", items));
    }
    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("index_event", text(entry.get("event_label")));
    Map<String, Object> criterionDomains = new LinkedHashMap<>();
    for (Map<String, Object> binding : bindings) {
      String slotId = text(binding.get("concept_set_slot_id"));
      Map<String, Object> slot = slots.stream().filter(candidate -> slotId.equals(text(candidate.get("slot_id")))).findFirst().orElse(Map.of());
      criterionDomains.put(text(slot.get("label")), text(binding.get("domain")));
    }
    scope.put("criterion_domains", criterionDomains);
    scope.put("entry_limit", text(entry.get("limit")));
    scope.put("prior_observation", observation.get("prior_days"));
    scope.put("index_day_boundary", text(entry.get("index_day_boundary")));
    scope.put("windows", "none");
    scope.put("exit_strategy", exit.get("strategy"));
    if ("condition_visit_overlap".equals(text(assessment.get("mode")))) {
      scope.put("visit_overlap", true);
      scope.put("visit_overlap_mode", "attrition");
    } else if ("drug_supporting_condition".equals(text(assessment.get("mode")))) {
      Map<String, Object> supportingBinding = bindings.stream().filter(binding -> "supporting".equals(text(binding.get("criterion_role")))).findFirst()
          .orElseThrow(() -> new IllegalStateException("Supporting condition binding was not found"));
      String supportingSlotId = text(supportingBinding.get("concept_set_slot_id"));
      Map<String, Object> supportingSlot = slots.stream().filter(slot -> supportingSlotId.equals(text(slot.get("slot_id")))).findFirst()
          .orElseThrow(() -> new IllegalStateException("Supporting condition asset was not found"));
      Map<String, Object> window = map(supportingBinding.get("supporting_condition_window"));
      scope.put("supporting_condition_occurrence", Map.of("concept_set", text(supportingSlot.get("label")),
          "start_days", integer(window.get("start_days")), "end_days", integer(window.get("end_days")), "anchor", "index_start"));
      scope.put("multi_domain_entry_policy", "supporting_evidence_only");
    }
    return Map.of("scope", scope, "concept_sets", conceptSets);
  }

  private Integer integer(Object value) {
    if (value instanceof Number number) return number.intValue();
    try { return value == null ? null : Integer.valueOf(String.valueOf(value)); }
    catch (NumberFormatException ex) { return null; }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(Object value) {
    return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
  }

  private Map<String, Object> assetSummary(List<?> conceptSets) {
    if (conceptSets.size() != 1 || !(conceptSets.get(0) instanceof Map<?, ?> raw)) {
      return Map.of("source", "review_pending", "policy_item_count", 0);
    }
    Map<String, Object> source = map(raw);
    Map<String, Object> asset = new LinkedHashMap<>();
    asset.put("source", "inline_review");
    asset.put("name", text(source.get("name")));
    asset.put("domain", text(source.get("domain")));
    Object items = source.get("items");
    asset.put("policy_item_count", items instanceof List<?> list ? list.size() : 0);
    if (items instanceof List<?> list && !list.isEmpty()) asset.put("accepted_policy", list);
    return asset;
  }

  private String checksum(String payload) throws Exception {
    return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
  }

  private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
