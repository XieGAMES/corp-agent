package ru.demo.ragagent.kb;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Реестр баз знаний. Одна база знаний = одна схема PostgreSQL:
 * у каждой свои таблицы vector_store и documents + свой HNSW-индекс,
 * поэтому данные полностью изолированы друг от друга.
 *
 * Векторное хранилище (PgVectorStore) строится на схему лениво и кэшируется.
 * Дефолтная база "default" работает в схеме public — совместима с данными,
 * загруженными до появления мультибазовости.
 *
 * Перед первым использованием за запуск база сверяется с текущей моделью эмбеддингов:
 * если модель сменилась, чанки базы переиндексируются (EmbeddingReindexer).
 */
@Service
public class KnowledgeBaseService {

    public static final String DEFAULT_KB = "default";
    public static final String DEFAULT_SCHEMA = "public";

    /** Имя схемы генерируем сами; проверка защищает SQL от инъекций через идентификатор. */
    private static final Pattern SAFE_SCHEMA = Pattern.compile("^[a-z][a-z0-9_]{2,62}$");

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingReindexer reindexer;

    /** Размерность из конфигурации — резерв на случай, когда модель эмбеддингов недоступна. */
    private final int configuredDimensions;
    private final Map<String, VectorStore> stores = new ConcurrentHashMap<>();

    /** Схемы, уже сверённые с текущей моделью эмбеддингов в этом запуске. */
    private final Set<String> checked = ConcurrentHashMap.newKeySet();

    /** Блокировка на схему: сверку и переиндексацию одной базы нельзя выполнять параллельно. */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public KnowledgeBaseService(JdbcTemplate jdbc,
                                EmbeddingModel embeddingModel,
                                EmbeddingReindexer reindexer,
                                @Value("${spring.ai.vectorstore.pgvector.dimensions:1024}") int configuredDimensions) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
        this.reindexer = reindexer;
        this.configuredDimensions = configuredDimensions;
    }

    public record KbInfo(String kbId, String schemaName, String title, String createdAt) {
    }

    /** Создаёт новую изолированную базу знаний (новую схему PostgreSQL). */
    public KbInfo create(String title) {
        String cleanTitle = (title == null || title.isBlank()) ? "Новая база знаний" : title.trim();
        String schema;
        do {
            schema = "kb_" + UUID.randomUUID().toString().substring(0, 8);
        } while (!isSchemaFree(schema));

        jdbc.execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
        // Каталог документов новой базы: та же структура, что в schema.sql для public
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS "%s".documents (
                    document_id  BIGSERIAL PRIMARY KEY,
                    file_name    TEXT NOT NULL UNIQUE,
                    title        TEXT,
                    doc_type     TEXT NOT NULL DEFAULT 'документ',
                    chunk_count  INTEGER NOT NULL DEFAULT 0,
                    loaded_at    TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """.formatted(schema));
        jdbc.update("INSERT INTO knowledge_bases (kb_id, schema_name, title) VALUES (?, ?, ?)",
                schema, schema, cleanTitle);
        storeFor(schema); // сразу создаём vector_store + HNSW-индекс в новой схеме
        return new KbInfo(schema, schema, cleanTitle, null);
    }

    public List<KbInfo> list() {
        return jdbc.query(
                "SELECT kb_id, schema_name, title, to_char(created_at, 'YYYY-MM-DD HH24:MI') FROM knowledge_bases ORDER BY created_at",
                (rs, i) -> new KbInfo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
    }

    public Map<String, Object> stats(String kbId) {
        String schema = schemaOf(kbId);
        Integer documents = jdbc.queryForObject(
                "SELECT count(*) FROM \"" + schema + "\".documents", Integer.class);
        Integer chunks = jdbc.queryForObject(
                "SELECT count(*) FROM \"" + schema + "\".vector_store", Integer.class);
        return Map.of("kbId", kbId == null || kbId.isBlank() ? DEFAULT_KB : kbId,
                "documents", documents == null ? 0 : documents,
                "chunks", chunks == null ? 0 : chunks);
    }

    /** Удаляет базу знаний вместе со схемой и всеми её данными. */
    public void delete(String kbId) {
        if (DEFAULT_KB.equals(kbId)) {
            throw new IllegalArgumentException("Основную базу знаний удалить нельзя");
        }
        String schema = schemaOf(kbId);
        jdbc.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
        jdbc.update("DELETE FROM knowledge_bases WHERE kb_id = ?", kbId);
        stores.remove(schema);
        checked.remove(schema);
        locks.remove(schema);
    }

    /** Схема PostgreSQL для базы знаний (проверяет существование и безопасность имени). */
    public String schemaOf(String kbId) {
        String id = (kbId == null || kbId.isBlank()) ? DEFAULT_KB : kbId;
        if (DEFAULT_KB.equals(id)) {
            return DEFAULT_SCHEMA;
        }
        List<String> schemas = jdbc.queryForList(
                "SELECT schema_name FROM knowledge_bases WHERE kb_id = ?", String.class, id);
        if (schemas.isEmpty()) {
            throw new IllegalArgumentException("База знаний не найдена: " + id);
        }
        String schema = schemas.get(0);
        if (!SAFE_SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Недопустимое имя схемы: " + schema);
        }
        return schema;
    }

    /**
     * Векторное хранилище базы знаний (создаётся при первом обращении, кэшируется).
     * Перед первым использованием за запуск база сверяется с текущей моделью эмбеддингов
     * и при её смене переиндексируется.
     */
    public VectorStore storeFor(String kbId) {
        String schema = schemaOf(kbId);
        if (!checked.contains(schema)) {
            synchronized (locks.computeIfAbsent(schema, s -> new Object())) {
                if (!checked.contains(schema)) {
                    checkEmbeddingModel(kbId, schema);
                }
            }
        }
        Integer dims = reindexer.dimensions();
        return stores.computeIfAbsent(schema, s -> buildStore(s, dims != null ? dims : configuredDimensions));
    }

    /**
     * Один раз за запуск на базу: сверяет векторы с моделью эмбеддингов.
     * Если модель недоступна, сверка откладывается до следующего обращения.
     */
    private void checkEmbeddingModel(String kbId, String schema) {
        Integer dims = reindexer.dimensions();
        if (dims == null) {
            return;
        }
        reindexer.ensureUpToDate(kbId, schema, dims, d -> buildStore(schema, d));
        checked.add(schema);
    }

    /** Хранилище схемы: создаёт таблицу vector_store и HNSW-индекс размерности dims. */
    private PgVectorStore buildStore(String schema, int dims) {
        PgVectorStore store = PgVectorStore.builder(jdbc, embeddingModel)
                .schemaName(schema)
                .vectorTableName("vector_store")
                .dimensions(dims)                         // размерность модели эмбеддингов
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)                   // создаст таблицу+индекс в своей схеме
                .build();
        try {
            store.afterPropertiesSet();                   // вне Spring-контекста вызываем вручную
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось инициализировать хранилище схемы " + schema, e);
        }
        return store;
    }

    private boolean isSchemaFree(String schema) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_bases WHERE schema_name = ?", Integer.class, schema);
        return count != null && count == 0;
    }
}
