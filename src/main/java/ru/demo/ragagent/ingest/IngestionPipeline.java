package ru.demo.ragagent.ingest;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import ru.demo.ragagent.ingest.source.DocumentSource;
import ru.demo.ragagent.ingest.source.SourceRef;
import ru.demo.ragagent.kb.KnowledgeBaseService;

/**
 * Пайплайн обработки: преобразование и сохранение информации в базу знаний.
 * Сам не знает, откуда пришёл текст — получение делегируется
 * стратегии {@link DocumentSource} (файл, веб и т. д.).
 *
 * База знаний выбирается параметром kbId: каждая база изолирована
 * (своя схема PostgreSQL), см. {@link KnowledgeBaseService}.
 */
@Service
public class IngestionPipeline {

    private final List<DocumentSource> sources;
    private final DocumentTransformer splitter;
    private final KnowledgeBaseService kbService;
    private final DocumentCatalog catalog;

    public IngestionPipeline(List<DocumentSource> sources,
                             DocumentTransformer splitter,
                             KnowledgeBaseService kbService,
                             DocumentCatalog catalog) {
        this.sources = sources;
        this.splitter = splitter;
        this.kbService = kbService;
        this.catalog = catalog;
    }

    public record IngestResult(String fileName, String docType, int chunkCount) {
    }

    /**
     * Загружает документ в выбранную базу знаний. Повторная загрузка того же
     * файла заменяет старую версию — защита от «устаревшей информации».
     */
    public IngestResult ingest(SourceRef ref, String kbId, String docType, String title) {
        DocumentSource source = resolve(ref);
        String fileName = ref.displayName();
        String docTitle = (title == null || title.isBlank()) ? fileName : title;
        String schema = kbService.schemaOf(kbId);
        VectorStore store = kbService.storeFor(kbId);

        // <<Read>>: источник извлекает текст
        List<Document> documents = source.fetch(ref);

        // <<Split>>: чанкер по выбору конфига (semantic — по абзацам/предложениям, token — по токенам)
        List<Document> chunks = splitter.apply(documents);

        if (chunks.isEmpty()) {
            throw new IllegalStateException("""
                    Из документа «%s» не удалось извлечь текст. Возможные причины: файл пуст, \
                    это скан без текстового слоя (нужен OCR) или формат не поддерживается. \
                    Ни документ, ни каталог не были изменены.""".formatted(fileName));
        }

        // Метаданные каждого чанка: по ним ищем источники и фильтруем выдачу
        for (Document chunk : chunks) {
            chunk.getMetadata().put("file", fileName);
            chunk.getMetadata().put("doc_type", docType);
            chunk.getMetadata().put("title", docTitle);
        }

        // Замена старой версии: удаляем чанки этого же файла
        store.delete(new FilterExpressionBuilder().eq("file", fileName).build());

        // <<Write>>: эмбеддинги + запись в pgvector выбранной базы
        store.add(chunks);

        catalog.upsert(schema, fileName, docTitle, docType, chunks.size());
        return new IngestResult(fileName, docType, chunks.size());
    }

    /** Удаляет документ из выбранной базы знаний и из её каталога. */
    public void delete(String fileName, String kbId) {
        String schema = kbService.schemaOf(kbId);
        kbService.storeFor(kbId).delete(new FilterExpressionBuilder().eq("file", fileName).build());
        catalog.delete(schema, fileName);
    }

    private DocumentSource resolve(SourceRef ref) {
        return sources.stream()
                .filter(s -> s.supports(ref))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Нет источника, поддерживающего тип: " + ref.type()));
    }
}
