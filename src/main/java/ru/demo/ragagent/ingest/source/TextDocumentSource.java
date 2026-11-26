package ru.demo.ragagent.ingest.source;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * Источник «текст»: документ, созданный в самом приложении, — например,
 * ответ службы качества на заявку. Ни файлов, ни сети: текст уже в памяти,
 * поэтому кодировки не важны.
 */
@Component
public class TextDocumentSource implements DocumentSource {

    @Override
    public String type() {
        return "text";
    }

    @Override
    public boolean supports(SourceRef ref) {
        return "text".equals(ref.type());
    }

    @Override
    public List<Document> fetch(SourceRef ref) {
        return List.of(new Document(ref.location()));
    }
}
