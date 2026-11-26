package ru.demo.ragagent.ingest.source;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

/**
 * Источник «локальный файл»: PDF/DOCX/TXT/MD/RTF/HTML через Apache Tika.
 */
@Component
public class FileSystemDocumentSource implements DocumentSource {

    @Override
    public String type() {
        return "file";
    }

    @Override
    public boolean supports(SourceRef ref) {
        return "file".equals(ref.type());
    }

    @Override
    public List<Document> fetch(SourceRef ref) {
        Path path = Path.of(ref.location());
        // Текстовые файлы читаем сами: у Tika без ICU4J не определяется
        // кодировка, и .txt в CP1251 извлекается пустой строкой
        if (TextCharsetUtil.isPlainText(ref.displayName())) {
            try {
                return List.of(new Document(TextCharsetUtil.readText(path)));
            } catch (IOException e) {
                throw new IllegalStateException("Не удалось прочитать файл: " + ref.location(), e);
            }
        }
        // Остальное (PDF/DOCX/RTF/HTML) — через Tika
        return new TikaDocumentReader(new FileSystemResource(path.toFile())).get();
    }
}
