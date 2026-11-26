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
