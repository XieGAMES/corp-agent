package ru.demo.ragagent.qa;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import ru.demo.ragagent.kb.KnowledgeBaseService;

/**
 * Рантайм-контур (RAG), как на схеме:
 * Chat Request -> Retrieve (поиск чанков в Vector Store) -> Augment (контекст + вопрос в промпт)
 * -> Generate (чат-модель) -> Chat Response.
 *
 * Для веб-чата ведётся память диалога (ChatMemory) на пару (база знаний, сессия):
 * история последних вопросов-ответов добавляется в промпт, поэтому агент понимает
 * уточняющие вопросы («а кто вторая подпись?»).
 * Диалоги разных баз знаний изолированы друг от друга.
 */
@Service
public class QaService {

    /**
     * Системная инструкция — то же правило, что на схеме RAG:
     * отвечать только по контексту, иначе честно сказать, что ответа нет.
     */
    private static final String SYSTEM_PROMPT = """
            Ты — ассистент сотрудников компании, отвечаешь на вопросы по внутренним документам \
            (регламентам, приказам, инструкциям, договорам).
            Правила:
            1. Отвечай ТОЛЬКО на основе приведённого контекста. Не выдумывай факты.
            2. Если ответа в контексте нет, скажи прямо: «По имеющимся документам ответ не найден» \
            и посоветуй, к кому или куда обратиться.
            3. Указывай источник: название документа, пункт/раздел, если они есть в контексте.
            4. Отвечай на русском языке, кратко и по делу.
            5. Учитывай предыдущие сообщения диалога: местоимения («он», «этот документ») \
            относись к тому, о чём говорили раньше.
            6. Если вопрос о конкретном факте (цвет, число, имя, срок) — сначала найди в контексте \
            точное предложение с ответом и отвечай строго по нему, можно с короткой цитатой.
            """;

    private static final String DEFAULT_SESSION = "default";

    private final KnowledgeBaseService kbService;
    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final int topK;
    private final double similarityThreshold;

    public QaService(ChatModel chatModel,
                     KnowledgeBaseService kbService,
                     ChatMemory chatMemory,
                     @Value("${app.rag.top-k:5}") int topK,
                     @Value("${app.rag.similarity-threshold:0.35}") double similarityThreshold) {
        this.kbService = kbService;
        this.chatClient = ChatClient.builder(chatModel).build();
        this.chatMemory = chatMemory;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
    }

    public record SourceInfo(String file, String title, String docType, Double similarity) {
    }

    public record Answer(String answer, List<SourceInfo> sources, boolean found) {
    }

    /** Вопрос без сессии и базы знаний (REST-совместимость: Postman, Bruno, скрипты). */
    public Answer ask(String question) {
        return ask(question, DEFAULT_SESSION, KnowledgeBaseService.DEFAULT_KB);
    }

    /** Вопрос без базы знаний — в дефолтную. */
    public Answer ask(String question, String sessionId) {
        return ask(question, sessionId, KnowledgeBaseService.DEFAULT_KB);
    }

    /** Вопрос в рамках диалога определённой базы знаний. */
    public Answer ask(String question, String sessionId, String kbId) {
        VectorStore store = kbService.storeFor(kbId);
        String memoryKey = memoryKey(kbId, sessionId);

        // <<Retrieve>>: топ-K ближайших по смыслу чанков из pgvector (HNSW, косинусная близость)
        List<Document> hits = store.similaritySearch(SearchRequest.builder()
                .query(question)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .build());

        List<Message> history = chatMemory.get(memoryKey);

        String answer;
        if (hits.isEmpty()) {
            answer = "По имеющимся документам ответ не найден. "
                    + "Уточните вопрос или обратитесь к администратору базы знаний.";
            remember(memoryKey, question, answer);
            return new Answer(answer, List.of(), false);
        }

        // <<Augment>>: собираем найденные чанки в контекст
        StringBuilder context = new StringBuilder();
        List<SourceInfo> sources = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            Document hit = hits.get(i);
            Map<String, Object> meta = hit.getMetadata();
            String title = String.valueOf(meta.getOrDefault("title", ""));
            String file = String.valueOf(meta.getOrDefault("file", ""));
            context.append("[%d] %s (файл: %s)%n%s%n%n".formatted(i + 1, title, file, hit.getText()));
            sources.add(new SourceInfo(file, title,
                    String.valueOf(meta.getOrDefault("doc_type", "")), similarity(meta)));
        }

        String userMessage = "Контекст из корпоративных документов:%n%s%nВопрос: %s".formatted(context, question);

        // <<Generate>>: инструкция + история диалога + контекст текущего вопроса
        answer = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .messages(history)
                .user(userMessage)
                .call()
                .content();

        remember(memoryKey, question, answer);
        return new Answer(answer, sources, true);
    }

    /** Очистка диалога сессии в выбранной базе знаний (кнопка «Новый чат»). */
    public void clearSession(String sessionId, String kbId) {
        chatMemory.clear(memoryKey(kbId, sessionId));
    }

    /** Память диалога изолирована на пару (база знаний, сессия). */
    private static String memoryKey(String kbId, String sessionId) {
        String kb = (kbId == null || kbId.isBlank()) ? KnowledgeBaseService.DEFAULT_KB : kbId;
        String session = (sessionId == null || sessionId.isBlank()) ? DEFAULT_SESSION : sessionId;
        return kb + ":" + session;
    }

    private void remember(String memoryKey, String question, String answer) {
        chatMemory.add(memoryKey, new UserMessage(question));
        chatMemory.add(memoryKey, new AssistantMessage(answer));
    }

    /** pgvector возвращает косинусное расстояние в метаданных; сходство = 1 - расстояние. */
    private static Double similarity(Map<String, Object> meta) {
        Object distance = meta.get("distance");
        return (distance instanceof Number n) ? 1.0 - n.doubleValue() : null;
    }
}
