package ru.demo.ragagent.ingest.source;

import java.util.List;

import org.springframework.ai.document.Document;

/**
 * Стратегия получения информации из конкретного типа источников.
 *
 * Пайплайн обработки (нарезка на чанки, эмбеддинги, сохранение) не знает,
 * откуда пришёл текст: новый способ получения (веб-страницы, Confluence,
 * почта, сетевая папка) добавляется реализацией этого интерфейса —
 * существующий код не меняется.
 */
public interface DocumentSource {

    /** Идентификатор типа источника, например "file", "web", "confluence". */
    String type();

    /** Может ли этот источник обработать данную ссылку. */
    boolean supports(SourceRef ref);

    /** Извлекает текст источника в виде Spring AI Document (до нарезки на чанки). */
    List<Document> fetch(SourceRef ref);
}
