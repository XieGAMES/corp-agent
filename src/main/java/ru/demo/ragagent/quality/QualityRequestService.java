package ru.demo.ragagent.quality;

import java.util.List;

import org.springframework.stereotype.Service;

import ru.demo.ragagent.ingest.IngestionPipeline;
import ru.demo.ragagent.ingest.source.SourceRef;
import ru.demo.ragagent.kb.KnowledgeBaseService;

/**
 * Заявки в службу качества: вопросы, на которые агент не смог ответить по регламентам.
 *
 * Агент только создаёт заявку (см. QaService). Дальше служба качества открывает её
 * на своей странице и отвечает. Ответ не остаётся только в заявке: вопрос вместе
 * с ответом становятся документом той базы знаний, где вопрос задали, — и дальше
 * агент отвечает на этот вопрос сам.
 */
@Service
public class QualityRequestService {

    /** Причина, по которой агент не ответил; в заявке хранится её код. */
    public enum Reason {
        /** По вопросу не нашлось ни одного фрагмента регламентов. */
        NO_CONTEXT("В регламентах не нашлось ничего похожего на вопрос"),
        /** Фрагменты нашлись, но ответа в них нет. */
        NOT_IN_DOCUMENTS("В найденных фрагментах регламентов нет ответа"),
        /** Модель ответила, но её ответ не подтверждён дословными цитатами — считается недостоверным. */
        UNVERIFIED("Ответ не подтверждён дословными цитатами из регламента");

        private final String title;

        Reason(String title) {
            this.title = title;
        }

        /** Причина словами — для страницы заявок. */
        public String title() {
            return title;
        }

        /** Причина по коду из базы; неизвестный код показывается как есть. */
        public static String titleOf(String code) {
            for (Reason reason : values()) {
                if (reason.name().equals(code)) {
                    return reason.title();
                }
            }
            return code;
        }
    }

    /** Заявка как её видит служба качества. */
    public record RequestInfo(long requestId, String kbId, String sessionId, String question, String reason,
                              String reasonTitle, String status, String answer, String answerFile,
                              String answeredBy, String answeredAt, String createdAt) {
    }

    /** Итог ответа на заявку: каким документом ответ сохранён в базе знаний. */
    public record AnswerResult(long requestId, String kbId, String fileName, int chunkCount) {
    }

    private static final String STATUS_OPEN = "open";

    /** Тип документа, в который превращается отвеченная заявка. */
    private static final String DOC_TYPE = "ответ службы качества";

    /** Название документа = вопрос; длинный вопрос обрезается. */
    private static final int MAX_TITLE_CHARS = 150;

    private static final int MAX_ANSWER_CHARS = 20_000;

    private final QualityRequestCatalog catalog;
    private final IngestionPipeline pipeline;

    public QualityRequestService(QualityRequestCatalog catalog, IngestionPipeline pipeline) {
        this.catalog = catalog;
        this.pipeline = pipeline;
    }

    /** Создаёт заявку на вопрос, оставшийся без ответа, и возвращает её номер. */
    public long create(String kbId, String sessionId, String question, Reason reason) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Вопрос заявки не может быть пустым");
        }
        String kb = (kbId == null || kbId.isBlank()) ? KnowledgeBaseService.DEFAULT_KB : kbId;
        return catalog.create(kb, sessionId, question, reason.name());
    }

    public List<RequestInfo> list(boolean openOnly) {
        return catalog.list(openOnly);
    }

    /**
     * Сохраняет ответ службы качества: вопрос с ответом загружаются в ту базу знаний,
     * где вопрос задали, и заявка закрывается.
     */
    public AnswerResult answer(long requestId, String answer, String author) {
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("Ответ на заявку не может быть пустым");
        }
        String text = answer.strip();
        if (text.length() > MAX_ANSWER_CHARS) {
            throw new IllegalArgumentException("Ответ на заявку длиннее " + MAX_ANSWER_CHARS + " символов");
        }
        RequestInfo request = catalog.find(requestId);
        if (request == null) {
            throw new IllegalArgumentException("Заявка не найдена: " + requestId);
        }
        if (!STATUS_OPEN.equals(request.status())) {
            throw new IllegalArgumentException("Заявка №" + requestId + " уже отвечена");
        }

        // Чанкеры и эмбеддинги — те же, что у загруженного файла; имя документа зависит
        // только от номера заявки, поэтому повторная загрузка заменила бы старую версию
        String fileName = "Ответ_службы_качества_N" + requestId + ".txt";
        String document = "Вопрос: %s%n%nОтвет: %s".formatted(request.question(), text);
        IngestionPipeline.IngestResult result =
                pipeline.ingest(SourceRef.text(document, fileName), request.kbId(), DOC_TYPE, titleOf(request.question()));

        // Заявку закрываем после загрузки: не удалось сохранить ответ — заявка остаётся открытой
        catalog.markAnswered(requestId, text, result.fileName(), author);
        return new AnswerResult(requestId, request.kbId(), result.fileName(), result.chunkCount());
    }

    /** Удаляет заявку. Документ в базе знаний, если он был, остаётся. */
    public void delete(long requestId) {
        if (catalog.delete(requestId) == 0) {
            throw new IllegalArgumentException("Заявка не найдена: " + requestId);
        }
    }

    private static String titleOf(String question) {
        String title = "Ответ службы качества: " + question.strip();
        return title.length() <= MAX_TITLE_CHARS ? title : title.substring(0, MAX_TITLE_CHARS - 1) + "…";
    }
}
