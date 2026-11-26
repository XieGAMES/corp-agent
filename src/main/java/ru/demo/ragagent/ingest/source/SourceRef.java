package ru.demo.ragagent.ingest.source;

/**
 * Ссылка на источник информации: где взять документ и как его назвать
 * в метаданных и каталоге. Например: локальный файл, URL веб-страницы,
 * страница Confluence, письмо с вложением.
 */
public record SourceRef(String type, String location, String displayName) {

    public static SourceRef file(java.nio.file.Path path, String displayName) {
        return new SourceRef("file", path.toString(), displayName);
    }
}
