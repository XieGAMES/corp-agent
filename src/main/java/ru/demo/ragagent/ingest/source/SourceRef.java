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

    /** Текст, созданный в самом приложении (например, ответ службы качества): location хранит сам текст. */
    public static SourceRef text(String text, String displayName) {
        return new SourceRef("text", text, displayName);
    }
}
