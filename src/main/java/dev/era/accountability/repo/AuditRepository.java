package dev.era.accountability.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepository {

    private final JdbcClient jdbc;

    public AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String actor, String action, String entity, Long entityId, String detailJson) {
        jdbc.sql("""
                        INSERT INTO audit_log (actor, action, entity, entity_id, detail_json)
                        VALUES (:actor, :action, :entity, :entityId, CAST(:detail AS JSONB))
                        """)
                .param("actor", actor)
                .param("action", action)
                .param("entity", entity)
                .param("entityId", entityId)
                .param("detail", detailJson)
                .update();
    }
}
