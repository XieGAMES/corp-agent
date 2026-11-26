package ru.demo.ragagent.ingest.source;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

/**
 * Источник «веб»: страница или файл по HTTP/HTTPS.
 *
 * Скачивание — встроенным java.net.http.HttpClient (без внешних зависимостей),
 * разбор содержимого — тот же Apache Tika: он понимает и HTML-страницы,
 * и скачанные по ссылке PDF/DOCX/TXT.
 */
@Component
public class WebDocumentSource implements DocumentSource {

    private static final long MAX_SIZE_BYTES = 25_000_000L; // защита от гигантских файлов

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Override
    public String type() {
        return "web";
    }

    @Override
    public boolean supports(SourceRef ref) {
        return "web".equals(ref.type());
    }

    @Override
    public List<Document> fetch(SourceRef ref) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(ref.location()))
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "RAG-Agent/0.1 (document ingestion)")
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Некорректный URL: " + ref.location(), e);
        }

        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось загрузить " + ref.location() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Загрузка прервана: " + ref.location(), e);
        }

        if (response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " при загрузке " + ref.location());
        }

        byte[] body = response.body();
        if (body.length == 0) {
            throw new IllegalStateException("Пустой ответ от " + ref.location());
        }
        if (body.length > MAX_SIZE_BYTES) {
            throw new IllegalStateException("Файл больше 25 МБ, отклонён: " + ref.location());
        }

        // Текстовые файлы (часто в CP1251 без BOM) разбираем сами —
        // Tika без ICU4J вернёт для них пустой текст
        if (TextCharsetUtil.isPlainText(ref.displayName())) {
            Path tmp = null;
            try {
                tmp = Files.createTempFile("rag-web-", ".txt");
                Files.write(tmp, body);
                return List.of(new Document(TextCharsetUtil.readText(tmp)));
            } catch (IOException e) {
                throw new IllegalStateException("Не удалось обработать файл с " + ref.location(), e);
            } finally {
                if (tmp != null) {
                    try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
                }
            }
        }

        // Имя файла подсказывает Tika формат (важно для ссылок на .pdf/.docx)
        ByteArrayResource resource = new ByteArrayResource(body) {
            @Override
            public String getFilename() {
                return ref.displayName();
            }
        };
        return new TikaDocumentReader(resource).get();
    }
}
