package ru.demo.ragagent.kb;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Автоматическая переиндексация базы знаний при смене модели эмбеддингов.
 *
 * Вектор имеет смысл только для той модели, которой получен: после смены модели
 * (или её размерности) поиск по старым векторам либо падает, либо возвращает мусор.
 * Поэтому при первом обращении к базе знаний сверяется отметка «модель|размерность»
 * (столбец knowledge_bases.embedding_model) и при расхождении все чанки заново
 * прогоняются через эмбеддинги. Текст чанков хранится в самой таблице vector_store,
 * так что исходные файлы не нужны.
 *
 * Безопасность: сначала запрашиваются все эмбеддинги (долгая часть, ходит в API),
 * и только затем в одной транзакции старая таблица удаляется, создаётся новая
 * и заполняется. При любой ошибке транзакция откатывается — база остаётся в прежнем виде.
 * Вызывается из {@link KnowledgeBaseService#storeFor}; он же не даёт двум потокам
 * переиндексировать одну базу параллельно.
 */
@Component
public class EmbeddingReindexer {

    /** Текстов за один запрос к API эмбеддингов. */
    private static final int EMBED_BATCH_SIZE = 16;
    /** Строк за один пакет INSERT. */
    private static final int INSERT_BATCH_SIZE = 500;

    private static final String TABLE = "vector_store";

    /** Схема уже проверена в KnowledgeBaseService.schemaOf; это страховка — класс сам подставляет имя в SQL. */
    private static final Pattern SAFE_SCHEMA = Pattern.compile("^[a-z][a-z0-9_]{2,62}$");

    private static final Logger log = LoggerFactory.getLogger(EmbeddingReindexer.class);

    /** Создаёт хранилище — таблицу vector_store и HNSW-индекс — так же, как при обычном запуске. */
    @FunctionalInterface
    public interface StoreFactory {
        void create(int dimensions);
    }

    /** Чанк старой таблицы: метаданные переносятся как есть, без разбора. */
    private record Chunk(UUID id, String content, String metadata) {
    }

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;
    private final TransactionTemplate tx;
    private final String model;

    /** Размерность, полученная от модели; null, пока модель не ответила. */
    private volatile Integer dimensions;

    public EmbeddingReindexer(JdbcTemplate jdbc,
                              EmbeddingModel embeddingModel,
                              PlatformTransactionManager transactionManager,
                              // имя модели — источник истины для отметки; должно совпадать с тем, что шлёт Spring AI
                              @Value("${spring.ai.openai.embedding.options.model}") String model) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
        this.tx = new TransactionTemplate(transactionManager);
        this.model = model;
    }

    /**
     * Размерность эмбеддингов текущей модели: один пробный запрос за запуск.
     * null — модель недоступна; неудача не запоминается, следующее обращение попробует снова.
     */
    public Integer dimensions() {
        Integer known = this.dimensions;
        return known != null ? known : probe();
    }

    private synchronized Integer probe() {
        if (this.dimensions == null) {
            try {
                this.dimensions = embeddingModel.dimensions();
            } catch (RuntimeException e) {
                log.warn("Модель эмбеддингов недоступна, сверка баз знаний отложена: {}",
                        e.getClass().getSimpleName());
                return null;
            }
        }
        return this.dimensions;
    }

    /**
     * Приводит векторы базы знаний в соответствие текущей модели эмбеддингов.
     *
     * @param dims размерность текущей модели ({@link #dimensions()})
     * @return true — чанки были переиндексированы
     */
    public boolean ensureUpToDate(String kbId, String schema, int dims, StoreFactory storeFactory) {
        if (!SAFE_SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Недопустимое имя схемы: " + schema);
        }
        String stamp = model + "|" + dims;

        // Таблицы ещё нет: хранилище создаст её под текущую модель
        if (!tableExists(schema)) {
            tx.executeWithoutResult(status -> writeStamp(kbId, stamp));
            return false;
        }
        if (stamp.equals(readStamp(kbId))) {
            return false;
        }

        String oldType = embeddingColumnType(schema);
        List<Chunk> chunks = jdbc.query("""
                        SELECT id, content, metadata::text FROM "%s".%s
                         WHERE content IS NOT NULL AND btrim(content) <> ''
                        """.formatted(schema, TABLE),
                (rs, i) -> new Chunk(rs.getObject("id", UUID.class), rs.getString("content"), rs.getString("metadata")));

        // Пустая таблица со старой структурой: удаляем, хранилище создаст заново
        if (chunks.isEmpty()) {
            tx.executeWithoutResult(status -> {
                jdbc.execute("DROP TABLE \"%s\".%s".formatted(schema, TABLE));
                writeStamp(kbId, stamp);
            });
            log.info("База знаний «{}»: пустая таблица {} пересоздана под модель {}", kbId, oldType, model);
            return true;
        }

        // Долгая часть — до любых изменений в базе: эмбеддинги всех чанков
        List<float[]> vectors = embedAll(chunks, dims);

        tx.executeWithoutResult(status -> {
            jdbc.execute("DROP TABLE \"%s\".%s".formatted(schema, TABLE));
            storeFactory.create(dims);
            insert(schema, chunks, vectors);
            writeStamp(kbId, stamp);
        });
        log.info("База знаний «{}» переиндексирована: {} чанков, {} → vector({}), модель {}",
                kbId, chunks.size(), oldType, dims, model);
        return true;
    }

    /** Эмбеддинги всех чанков небольшими партиями; ошибка API отменяет всю переиндексацию. */
    private List<float[]> embedAll(List<Chunk> chunks, int dims) {
        List<float[]> vectors = new ArrayList<>(chunks.size());
        for (int from = 0; from < chunks.size(); from += EMBED_BATCH_SIZE) {
            List<String> texts = new ArrayList<>();
            for (Chunk chunk : chunks.subList(from, Math.min(from + EMBED_BATCH_SIZE, chunks.size()))) {
                texts.add(chunk.content());
            }
            List<float[]> batch = embeddingModel.embed(texts);
            if (batch.size() != texts.size()) {
                throw new IllegalStateException(
                        "Модель эмбеддингов вернула %d векторов на %d текстов".formatted(batch.size(), texts.size()));
            }
            for (float[] vector : batch) {
                if (vector.length != dims) {
                    throw new IllegalStateException("Модель эмбеддингов вернула вектор размерности %d вместо %d"
                            .formatted(vector.length, dims));
                }
            }
            vectors.addAll(batch);
        }
        return vectors;
    }

    private void insert(String schema, List<Chunk> chunks, List<float[]> vectors) {
        String sql = "INSERT INTO \"%s\".%s (id, content, metadata, embedding) VALUES (?, ?, ?::json, ?::vector)"
                .formatted(schema, TABLE);
        for (int from = 0; from < chunks.size(); from += INSERT_BATCH_SIZE) {
            int start = from;
            int end = Math.min(from + INSERT_BATCH_SIZE, chunks.size());
            jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    Chunk chunk = chunks.get(start + i);
                    ps.setObject(1, chunk.id());
                    ps.setString(2, chunk.content());
                    ps.setString(3, chunk.metadata());
                    ps.setString(4, vectorLiteral(vectors.get(start + i)));
                }

                @Override
                public int getBatchSize() {
                    return end - start;
                }
            });
        }
    }

    /** Строковая запись вектора для приведения к типу vector: «[0.1,0.2,...]». */
    private static String vectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    private boolean tableExists(String schema) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                Long.class, schema, TABLE);
        return count != null && count > 0;
    }

    /** Тип столбца с векторами, например «vector(768)» — только для сообщений в журнале. */
    private String embeddingColumnType(String schema) {
        List<String> types = jdbc.queryForList("""
                        SELECT format_type(a.atttypid, a.atttypmod) FROM pg_attribute a
                         WHERE a.attrelid = to_regclass(?) AND a.attname = 'embedding'
                        """, String.class, schema + "." + TABLE);
        return types.isEmpty() ? "векторы" : types.get(0);
    }

    /** Отметка «модель|размерность» базы знаний; null — неизвестно, какой моделью получены векторы. */
    private String readStamp(String kbId) {
        List<String> stamps = jdbc.queryForList(
                "SELECT embedding_model FROM knowledge_bases WHERE kb_id = ?", String.class, kbId);
        return stamps.isEmpty() ? null : stamps.get(0);
    }

    private void writeStamp(String kbId, String stamp) {
        jdbc.update("UPDATE knowledge_bases SET embedding_model = ? WHERE kb_id = ?", stamp, kbId);
    }
}
