package org.ohdsi.webapi.studyagent;

import com.fasterxml.jackson.core.type.TypeReference;
import org.ohdsi.analysis.Utils;
import org.ohdsi.circe.cohortdefinition.CohortExpression;
import java.util.LinkedHashMap;
import java.util.Map;
import org.ohdsi.webapi.security.authz.AuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Read-only, user-owned provenance for a cohort initialized through /ohdsi. */
@RestController
@RequestMapping("/study-agent/v1/cohort-definitions")
public class StudyAgentCohortDefinitionProvenanceController {
  private final AuthorizationService authorizationService;
  private final StudyAgentCohortDefinitionReviewService reviewService;
  private final JdbcTemplate jdbcTemplate;
  private final StudyAgentAcpClient acpClient;
  private final ObjectMapper objectMapper;
  private final boolean enabled;
  private final String sessionTable;
  private final String reviewTable;

  public StudyAgentCohortDefinitionProvenanceController(
      AuthorizationService authorizationService,
      StudyAgentCohortDefinitionReviewService reviewService,
      JdbcTemplate jdbcTemplate,
      StudyAgentAcpClient acpClient,
      ObjectMapper objectMapper,
      @Value("${study-agent.cohort-definition.enabled:false}") boolean enabled,
      @Value("${datasource.ohdsi.schema:public}") String ohdsiSchema) {
    if (!ohdsiSchema.matches("[A-Za-z_][A-Za-z0-9_]*")) {
      throw new IllegalArgumentException("Invalid OHDSI schema identifier");
    }
    this.authorizationService = authorizationService;
    this.reviewService = reviewService;
    this.jdbcTemplate = jdbcTemplate;
    this.acpClient = acpClient;
    this.objectMapper = objectMapper;
    this.enabled = enabled;
    this.sessionTable = ohdsiSchema + ".study_agent_cohort_definition_session";
    this.reviewTable = ohdsiSchema + ".study_agent_cohort_definition_review";
  }

  @GetMapping("/{cohortDefinitionId}/provenance")
  @PreAuthorize("isPermitted('study-agent:cohort-definition-assist') and (isOwner(#cohortDefinitionId, COHORT_DEFINITION) or isPermitted('read:cohort-definition') or hasEntityAccess(#cohortDefinitionId, COHORT_DEFINITION, READ))")
  public Map<String, Object> provenance(@PathVariable Integer cohortDefinitionId) {
    if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    if (cohortDefinitionId == null || cohortDefinitionId <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid cohort definition identifier");
    }
    Map<String, Object> row = reviewService.findOwnedProvenance(
        reviewTable, sessionTable, cohortDefinitionId,
        authorizationService.getAuthenticatedPrincipal().getUserId());
    if (row == null) return Map.of("linked", false);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("linked", true);
    result.put("review_revision", row.get("revision"));
    result.put("source_type", row.get("source_type"));
    result.put("acquisition_route", row.get("acquisition_route"));
    result.put("phenotype_id", row.get("phenotype_id"));
    result.put("phenotype_name", row.get("phenotype_name"));
    result.put("computability_status", row.get("computability_status"));
    result.put("expression_checksum", row.get("expression_checksum"));
    result.put("expression_matches_review", sameExpression(row.get("reviewed_expression"), row.get("current_expression")));
    result.put("narrative", row.get("narrative"));
    result.put("created_at", row.get("created_at"));
    result.put("approved_at", row.get("approved_at"));
    return result;
  }

  /**
   * Sends only the saved cohort-definition JSON to ACP for an advisory critique.
   * It deliberately does not accept browser-provided expressions or patch requests.
   */
  @PostMapping("/{cohortDefinitionId}/review")
  @PreAuthorize("isPermitted('study-agent:cohort-definition-assist') and (isOwner(#cohortDefinitionId, COHORT_DEFINITION) or isPermitted('read:cohort-definition') or hasEntityAccess(#cohortDefinitionId, COHORT_DEFINITION, READ))")
  public Map<String, Object> reviewCurrentDefinition(@PathVariable Integer cohortDefinitionId) {
    if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    if (cohortDefinitionId == null || cohortDefinitionId <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid cohort definition identifier");
    }
    try {
      String expression = jdbcTemplate.queryForObject(
          "select expression from " + sessionTable.replace("study_agent_cohort_definition_session", "cohort_definition_details") + " where id=?",
          String.class, cohortDefinitionId);
      @SuppressWarnings("unchecked")
      Map<String, Object> cohort = objectMapper.readValue(expression, Map.class);
      Map<String, Object> critique = acpClient.post("/flows/cohort_critique_general_design", Map.of("cohort", cohort));
      return Map.of("cohort_definition_id", cohortDefinitionId, "review", normalizeCritique(critique));
    } catch (EmptyResultDataAccessException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "cohort definition expression not found");
    } catch (ResponseStatusException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Unable to review cohort definition");
    }
  }

  /** ACP can return a critique directly or beneath standard transport/tool wrappers. */
  @SuppressWarnings("unchecked")
  private Map<String, Object> normalizeCritique(Map<String, Object> response) {
    Map<String, Object> contentResult = parseCritiqueContent(response.get("content"));
    if (contentResult != null) return contentResult;
    if (isCritique(response)) return response;
    for (String wrapperKey : new String[] {"result", "full_result", "response", "data", "review"}) {
      Object nested = response.get(wrapperKey);
      if (nested instanceof Map<?, ?> nestedMap) {
        Map<String, Object> normalized = normalizeCritique((Map<String, Object>) nestedMap);
        if (isCritique(normalized)) return normalized;
      }
    }
    return response;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parseCritiqueContent(Object content) {
    if (!(content instanceof String text)) return null;
    String json = text.trim().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
    try {
      Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
      return isCritique(parsed) ? parsed : null;
    } catch (Exception ex) {
      return null;
    }
  }

  private boolean isCritique(Map<String, Object> value) {
    return value != null && (value.containsKey("plan") || value.containsKey("findings")
        || value.containsKey("patches") || value.containsKey("risk_notes"));
  }

  /**
   * Atlas and ACP can emit equivalent Circe JSON with different optional fields.
   * Canonicalize both through WebAPI's cohort serializer before comparing, and
   * fail closed when either stored expression cannot be parsed.
   */
  private boolean sameExpression(Object reviewed, Object current) {
    if (reviewed == null || current == null) return false;
    try {
      return canonicalExpression(String.valueOf(reviewed)).equals(canonicalExpression(String.valueOf(current)));
    } catch (Exception ex) {
      return false;
    }
  }

  private String canonicalExpression(String expression) {
    CohortExpression parsed = Utils.deserialize(expression, new TypeReference<CohortExpression>() {});
    return Utils.serialize(parsed);
  }
}
