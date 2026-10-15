package org.ohdsi.webapi.studyagent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Bounded Atlas landing-page bridge for advisory /ohdsi dialogue.
 *
 * <p>Atlas supplies a compact declared UI context and remains responsible for
 * permissions and navigation. ACP returns advice only; it cannot inspect
 * WebAPI resources, create an artifact, or call WebAPI back.</p>
 */
@RestController
@RequestMapping("/study-agent/v1/atlas-assistant")
public class StudyAgentAtlasAssistantController {
  private final boolean enabled;
  private final StudyAgentAcpClient acpClient;

  public StudyAgentAtlasAssistantController(
      @Value("${study-agent.cohort-definition.enabled:false}") boolean enabled,
      StudyAgentAcpClient acpClient) {
    this.enabled = enabled;
    this.acpClient = acpClient;
  }

  @PostMapping("/dialogue")
  @PreAuthorize("isPermitted('study-agent:cohort-definition-assist')")
  public Map<String, Object> dialogue(@RequestBody Map<String, Object> request) {
    if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    String message = string(request.get("message"));
    if (message.isEmpty() || message.length() > 2000) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid /ohdsi request");
    }

    // This is a client capability declaration, not a request for ACP to read
    // sources or mutate Atlas. Source-specific facts will be added only through
    // explicit, permission-filtered WebAPI summaries in a later slice.
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("client", "Atlas");
    context.put("page", "landing");
    context.put("interaction_profile", Map.of(
        "advisory_only", true,
        "automatic_artifact_changes", false,
        "available_actions", List.of("start_cohort_authoring")));
    context.put("data_source_context", "No source-specific facts were loaded for this request.");
    Map<String, Object> response = acpClient.post("/flows/workflow_context_dialogue", Map.of(
        "user_prompt", message,
        "study_intent", message,
        "workflow_type", "atlas_cohort_authoring_entry",
        "current_step", "landing",
        "current_role", "atlas_cohort_author",
        "current_context", context));
    return Map.of("dialogue", landingDialogue(response.get("dialogue")));
  }

  private static Map<String, Object> landingDialogue(Object value) {
    Map<String, Object> dialogue = new LinkedHashMap<>(map(value));
    // ACP is intentionally client-neutral. This presentation guard prevents an
    // advisory answer from naming a stale, non-existent Atlas control.
    for (String field : List.of("plan", "answer")) {
      Object text = dialogue.get(field);
      if (text instanceof String) {
        dialogue.put(field, ((String) text).replaceAll(
            "(?i)(?:the )?start cohort authoring button", "one of the options below"));
      }
    }
    return dialogue;
  }

  private static String string(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return value instanceof Map<?, ?> candidate ? (Map<String, Object>) candidate : Map.of();
  }
}
