-- Каталог документов (справочная таблица).
-- Сами чанки с эмбеддингами создаёт Spring AI: таблица vector_store
-- (id uuid, content text, metadata json, embedding vector(размерность модели)) + HNSW-индекс.
CREATE TABLE IF NOT EXISTS documents (
    document_id  BIGSERIAL PRIMARY KEY,
    file_name    TEXT NOT NULL UNIQUE,
    title        TEXT,
    doc_type     TEXT NOT NULL DEFAULT 'документ',
    chunk_count  INTEGER NOT NULL DEFAULT 0,
    loaded_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Реестр баз знаний: одна база = одна схема PostgreSQL (изоляция на уровне таблиц).
-- Дефолтная база 'default' использует схему public (совместимо с существующими данными).
CREATE TABLE IF NOT EXISTS knowledge_bases (
    kb_id       TEXT PRIMARY KEY,
    schema_name TEXT NOT NULL UNIQUE,
    title       TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO knowledge_bases (kb_id, schema_name, title)
VALUES ('default', 'public', 'Основная база знаний')
ON CONFLICT (kb_id) DO NOTHING;

-- Отметка модели эмбеддингов, которой получены векторы базы знаний: «модель|размерность».
-- Пока отметки нет (векторы получены неизвестной моделью), база при первом обращении
-- переиндексируется текущей моделью (EmbeddingReindexer).
ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS embedding_model TEXT;

-- Заявки в службу качества: вопросы, на которые агент не ответил по регламентам
-- (reason — причина кодом, см. QualityRequestService.Reason). Таблица общая для всех
-- баз знаний, как и knowledge_bases. Ответ службы на заявку становится документом
-- той базы знаний, где задали вопрос; answer_file — имя этого документа.
CREATE TABLE IF NOT EXISTS quality_requests (
    request_id   BIGSERIAL PRIMARY KEY,
    kb_id        TEXT NOT NULL,
    session_id   TEXT NOT NULL DEFAULT '',
    question     TEXT NOT NULL,
    reason       TEXT NOT NULL,
    status       TEXT NOT NULL DEFAULT 'open',
    answer       TEXT,
    answer_file  TEXT,
    answered_by  TEXT,
    answered_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- История уточнений вопроса (уточнение агента → ответ пользователя), если перед заявкой агент
-- уточнял вопрос: ответ зависел от условий, которых в вопросе не было. Пусто — уточнений не было.
ALTER TABLE quality_requests ADD COLUMN IF NOT EXISTS dialogue TEXT;
