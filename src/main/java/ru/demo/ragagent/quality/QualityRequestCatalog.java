package ru.demo.ragagent.quality;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Заявки в службу качества (таблица quality_requests в схеме public).
 * Таблица общая для всех баз знаний, как и реестр knowledge_bases.
 */
@Component
public class QualityRequestCatalog {

    private static final String SELECT = """
            SELECT request_id, kb_id, session_id, question, reason, status, answer, answer_file, answered_by,
                   to_char(answered_at, 'YYYY-MM-DD HH24:MI') AS answered_at,
                   to_char(created_at, 'YYYY-MM-DD HH24:MI') AS created_at,
                   dialogue
              FROM quality_requests
            """;

    /** Сначала открытые, внутри — новые сверху. */
    private static final String ORDER = " ORDER BY (status = 'open') DESC, request_id DESC";

    private static final String SELECT_ALL = SELECT + ORDER;

    private static final String SELECT_OPEN = SELECT + " WHERE status = 'open'" + ORDER;

    private final JdbcTemplate jdbc;

    public QualityRequestCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Создаёт заявку и возвращает её номер; dialogue — история уточнений вопроса (может быть пустой). */
    public long create(String kbId, String sessionId, String question, String reason, String dialogue) {
        Long id = jdbc.queryForObject("""
                INSERT INTO quality_requests (kb_id, session_id, question, reason, dialogue)
                VALUES (?, ?, ?, ?, ?)
                RETURNING request_id
                """, Long.class, kbId, sessionId == null ? "" : sessionId, question, reason, dialogue);
        if (id == null) {
            throw new IllegalStateException("База не вернула номер созданной заявки");
        }
        return id;
    }

    public List<QualityRequestService.RequestInfo> list(boolean openOnly) {
        return jdbc.query(openOnly ? SELECT_OPEN : SELECT_ALL, QualityRequestCatalog::map);
    }

    /** Заявки одной сессии чата, новые сверху. */
    public List<QualityRequestService.RequestInfo> listBySession(String sessionId) {
        return jdbc.query(SELECT + " WHERE session_id = ? ORDER BY request_id DESC",
                QualityRequestCatalog::map, sessionId);
    }

    /** Заявка по номеру; null — заявки нет. */
    public QualityRequestService.RequestInfo find(long requestId) {
        List<QualityRequestService.RequestInfo> found =
                jdbc.query(SELECT + " WHERE request_id = ?", QualityRequestCatalog::map, requestId);
        return found.isEmpty() ? null : found.get(0);
    }

    /** Закрывает заявку ответом службы качества; 0 — заявка уже отвечена или не существует. */
    public int markAnswered(long requestId, String answer, String fileName, String author) {
        return jdbc.update("""
                UPDATE quality_requests
                   SET status = 'answered', answer = ?, answer_file = ?, answered_by = ?, answered_at = now()
                 WHERE request_id = ? AND status = 'open'
                """, answer, fileName, author, requestId);
    }

    /** Удаляет заявку; 0 — заявка не найдена. */
    public int delete(long requestId) {
        return jdbc.update("DELETE FROM quality_requests WHERE request_id = ?", requestId);
    }

    /** Дополняет историю уточнений заявки, пока она открыта; 0 — заявка уже отвечена или не существует. */
    public int updateDialogue(long requestId, String dialogue) {
        return jdbc.update(
                "UPDATE quality_requests SET dialogue = ? WHERE request_id = ? AND status = 'open'", dialogue, requestId);
    }

    /** Удаляет только ещё открытую заявку; 0 — заявка отвечена или не найдена. */
    public int deleteOpen(long requestId) {
        return jdbc.update(
                "DELETE FROM quality_requests WHERE request_id = ? AND status = 'open'", requestId);
    }

    private static QualityRequestService.RequestInfo map(ResultSet rs, int row) throws SQLException {
        return new QualityRequestService.RequestInfo(
                rs.getLong("request_id"), rs.getString("kb_id"), rs.getString("session_id"),
                rs.getString("question"), rs.getString("reason"),
                QualityRequestService.Reason.titleOf(rs.getString("reason")), rs.getString("status"),
                rs.getString("answer"), rs.getString("answer_file"), rs.getString("answered_by"),
                rs.getString("answered_at"), rs.getString("created_at"), rs.getString("dialogue"));
    }
}
