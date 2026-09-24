package org.ohdsi.webapi.studyagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Transactional persistence for reviewed cohort drafts. */
@Service
public class StudyAgentCohortDefinitionReviewService {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final ObjectMapper mapper;

  public StudyAgentCohortDefinitionReviewService(JdbcTemplate jdbc, @Qualifier("transactionTemplate") TransactionTemplate transaction, ObjectMapper mapper) {
    this.jdbc = jdbc; this.transaction = transaction; this.mapper = mapper;
  }

  public Map<String, Object> findOwnedSession(String sessionTable, UUID sessionId, Long userId) {
    try { return jdbc.queryForMap("select narrative, acquisition_route from " + sessionTable + " where session_id=? and user_id=? and archived_at is null", sessionId, userId); }
    catch (EmptyResultDataAccessException ex) { return null; }
  }

  /** Returns the latest review linked to a saved cohort for its original author. */
  public Map<String, Object> findOwnedProvenance(String reviewTable, String sessionTable, Integer cohortDefinitionId, Long userId) {
    try {
      return jdbc.queryForMap(
          "select r.revision, r.source_type, r.phenotype_id, r.phenotype_name, r.computability_status, "
              + "r.expression_checksum, r.reviewed_expression, d.expression as current_expression, "
              + "r.created_at, r.approved_at, s.acquisition_route, s.narrative "
              + "from " + reviewTable + " r join " + sessionTable + " s on s.session_id=r.session_id "
              + "left join " + sessionTable.replace("study_agent_cohort_definition_session", "cohort_definition_details") + " d on d.id=r.cohort_definition_id "
              + "where r.cohort_definition_id=? and s.user_id=? and s.archived_at is null "
              + "order by r.revision desc limit 1",
          cohortDefinitionId, userId);
    } catch (EmptyResultDataAccessException ex) {
      return null;
    }
  }

  public void createSession(String sessionTable, UUID sessionId, Long userId, String route, String narrative) {
    transaction.executeWithoutResult(status -> jdbc.update("insert into " + sessionTable + " (session_id, user_id, state, acquisition_route, narrative, assistant_state, created_at, last_active_at) values (?, ?, ?, ?, ?, ?, ?, ?)", sessionId, userId, "started", route, narrative, "{}", Timestamp.from(Instant.now()), Timestamp.from(Instant.now())));
  }

  public void updateState(String sessionTable, UUID sessionId, String state, String assistantState) {
    transaction.executeWithoutResult(status -> jdbc.update("update " + sessionTable + " set state=?, assistant_state=?, last_active_at=? where session_id=?", state, assistantState, Timestamp.from(Instant.now()), sessionId));
  }

  public int persist(String reviewTable, String sessionTable, UUID sessionId, String sourceType, String phenotypeId,
      String name, Map<?, ?> expression, Map<String, Object> provenance) {
    return transaction.execute(status -> {
      try {
        String expressionJson = mapper.writeValueAsString(expression);
        String provenanceJson = mapper.writeValueAsString(provenance);
        String checksum = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expressionJson.getBytes(StandardCharsets.UTF_8)));
        Integer prior = jdbc.queryForObject("select coalesce(max(revision), 0) from " + reviewTable + " where session_id=?", Integer.class, sessionId);
        jdbc.update("insert into " + reviewTable + " (session_id, revision, source_type, phenotype_id, phenotype_name, computability_status, reviewed_expression, expression_checksum, provenance, created_at, approved_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", sessionId, (prior == null ? 0 : prior) + 1, sourceType, phenotypeId, name, "circe_available", expressionJson, checksum, provenanceJson, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        jdbc.update("update " + sessionTable + " set state=?, assistant_state=?, last_active_at=? where session_id=?", "draft_ready", provenanceJson, Timestamp.from(Instant.now()), sessionId);
        return (prior == null ? 0 : prior) + 1;
      } catch (Exception ex) { throw new IllegalStateException("Unable to persist Study Agent review", ex); }
    });
  }
}
