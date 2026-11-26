package ru.demo.ragagent.ingest;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Каталог документов (таблица documents в схеме конкретной базы знаний).
 * Имя схемы проверяется в KnowledgeBaseService до попадания в SQL.
 */
@Component
public class DocumentCatalog {

    private final JdbcTemplate jdbc;

    public DocumentCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Добавляет или обновляет запись о документе (по уникальному имени файла). */
    public void upsert(String schema, String fileName, String title, String docType, int chunkCount) {
        jdbc.update("""
                INSERT INTO "%s".documents (file_name, title, doc_type, chunk_count, loaded_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (file_name) DO UPDATE
                   SET title = EXCLUDED.title,
                       doc_type = EXCLUDED.doc_type,
                       chunk_count = EXCLUDED.chunk_count,
                       loaded_at = now()
                """.formatted(schema), fileName, title, docType, chunkCount);
    }

    public void delete(String schema, String fileName) {
        jdbc.update("DELETE FROM \"%s\".documents WHERE file_name = ?".formatted(schema), fileName);
    }

    public List<Map<String, Object>> listAll(String schema) {
        return jdbc.queryForList(
                "SELECT file_name, title, doc_type, chunk_count, loaded_at FROM \"%s\".documents ORDER BY loaded_at DESC"
                        .formatted(schema));
    }
}
