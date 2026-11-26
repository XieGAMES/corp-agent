package ru.demo.ragagent.ingest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import ru.demo.ragagent.ingest.source.SourceRef;
import ru.demo.ragagent.kb.KnowledgeBaseService;

/**
 * HTTP-слой загрузки документов: принимает multipart или URL, формирует
 * ссылку на источник ({@link SourceRef}) и делегирует пайплайну.
 * Все операции выполняются в выбранной базе знаний (kbId, по умолчанию — основная).
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final IngestionPipeline pipeline;
    private final DocumentCatalog catalog;
    private final KnowledgeBaseService kbService;

    public DocumentController(IngestionPipeline pipeline, DocumentCatalog catalog,
                              KnowledgeBaseService kbService) {
        this.pipeline = pipeline;
        this.catalog = catalog;
        this.kbService = kbService;
    }

    /** Загрузка файла в базу знаний (multipart/form-data). */
    @PostMapping
    public IngestionPipeline.IngestResult upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "docType", defaultValue = "документ") String docType,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "kbId", required = false) String kbId) throws Exception {

        String original = (file.getOriginalFilename() == null || file.getOriginalFilename().isBlank())
                ? "document.bin"
                : Path.of(file.getOriginalFilename()).getFileName().toString();

        // Временный файл — веб-концерн контроллера; источник читает по SourceRef
        Path tmp = Files.createTempFile("rag-upload-", ".bin");
        Files.write(tmp, file.getBytes());
        try {
            return pipeline.ingest(SourceRef.file(tmp, original), kbId, docType, title);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public record WebUploadRequest(String url, String docType, String title, String kbId) {
    }

    /**
     * Загрузка документа из веб-источника: страница или файл по URL.
     * {"url": "https://...", "docType": "документ", "title": "...", "kbId": "default"}
     */
    @PostMapping("/web")
    public IngestionPipeline.IngestResult uploadFromWeb(@RequestBody WebUploadRequest request) {
        String url = (request.url() == null) ? "" : request.url().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("Поддерживаются только адреса http:// и https://");
        }
        return pipeline.ingest(new SourceRef("web", url, displayNameFromUrl(url)),
                request.kbId(), request.docType(), request.title());
    }

    /** Имя по URL: последний сегмент пути (файл/страница), иначе домен. */
    private static String displayNameFromUrl(String url) {
        String path = url.replaceAll("[?#].*$", "");
        int slash = path.lastIndexOf('/');
        String name = (slash >= 0 && slash < path.length() - 1) ? path.substring(slash + 1) : "";
        if (name.isBlank()) {
            name = url.replaceAll("^https?://", "").replaceAll("[/#?].*$", "");
        }
        return java.net.URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Каталог документов выбранной базы знаний. */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(value = "kbId", required = false) String kbId) {
        return catalog.listAll(kbService.schemaOf(kbId));
    }

    /** Удаление документа из выбранной базы знаний. */
    @DeleteMapping
    public Map<String, Object> delete(@RequestParam("file") String fileName,
                                      @RequestParam(value = "kbId", required = false) String kbId) {
        pipeline.delete(fileName, kbId);
        return Map.of("deleted", fileName);
    }
}
