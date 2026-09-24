package org.ohdsi.webapi.studyagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Typed WebAPI boundary for ACP calls; ACP is never exposed to browser clients. */
@Component
public class StudyAgentAcpClient {
  private final ObjectMapper objectMapper;
  private final String baseUrl;
  private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public StudyAgentAcpClient(ObjectMapper objectMapper, @Value("${study-agent.acp.base-url:}") String baseUrl) {
    this.objectMapper = objectMapper;
    this.baseUrl = baseUrl;
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> post(String path, Map<String, Object> payload) {
    if (baseUrl.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Study Agent unavailable");
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(60))
          .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload))).build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Study Agent request unavailable");
      return objectMapper.readValue(response.body(), Map.class);
    } catch (ResponseStatusException ex) { throw ex;
    } catch (Exception ex) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Study Agent request unavailable"); }
  }
}
