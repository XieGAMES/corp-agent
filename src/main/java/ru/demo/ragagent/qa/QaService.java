package ru.demo.ragagent.qa;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import ru.demo.ragagent.kb.KnowledgeBaseService;
import ru.demo.ragagent.quality.QualityRequestService;
import ru.demo.ragagent.quality.QualityRequestService.Reason;

/**
 * Рантайм-контур (RAG), как на схеме:
 * Chat Request -> Retrieve (поиск чанков в Vector Store) -> Augment (контекст + вопрос в промпт)
 * -> Generate (чат-модель) -> Chat Response.
 *
 * Главное правило агента — то же, что на схеме RAG: отвечать только по регламентам
 * и не отвечать наугад. Модель возвращает не текст, а структуру {found, answer, quotes},
 * и ответ показывается пользователю только после проверки (QuoteVerifier): цитаты должны
 * дословно быть в найденных фрагментах, числа ответа — взяты из них. Если ответа нет
 * или он не подтверждён, создаётся заявка в службу качества (QualityRequestService).
 *
 * Для веб-чата ведётся память диалога (ChatMemory) на пару (база знаний, сессия):
 * история последних вопросов-ответов добавляется в промпт, поэтому агент понимает
 * уточняющие вопросы («а кто вторая подпись?»).
 * Диалоги разных баз знаний изолированы друг от друга.
 *
 * Если однозначный ответ дать нельзя — в контексте разные ответы для разных условий, а в вопросе
 * этих условий нет, — агент не выбирает вариант за пользователя, а задаёт уточняющий вопрос
 * и отвечает после ответа пользователя. Уточняющий диалог держится на ту же пару, что и память;
 * число раундов ограничено. Когда уточнения исчерпаны или не помогли, создаётся заявка в службу
 * качества вместе с историей уточнений — но диалог не закрывается: пользователь может уточнить
 * и после отказа, тогда агент ответит сам. Диалог закончен, когда вопрос решён, пользователь
 * сменил тему (newTopic) или диалог сброшен.
 */
@Service
public class QaService {

    /** Преобразователь ответа модели: выдаёт JSON-схему для промпта и разбирает JSON в запись. */
    private static final BeanOutputConverter<Grounded> CONVERTER = new BeanOutputConverter<>(Grounded.class);

    /**
     * Системная инструкция. Ответ модели должен быть JSON-вида
     * {found, answer, quotes, clarificationQuestion, clarificationOptions};
     * формат дописывает Spring AI (CONVERTER).
     */
    private static final String SYSTEM_PROMPT = """
            Ты — ассистент сотрудников компании. Ты отвечаешь на вопросы только по процессам, \
            которые описаны в регламентах компании.
            Контекст в сообщении пользователя — это фрагменты регламентов, найденные по вопросу.
            Правила:
            1. Отвечай ТОЛЬКО на основе контекста. Ничего не добавляй от себя: ни сроков, ни сумм, \
            ни имён, ни контактов, которых нет в контексте. Чего нет в контексте — того нет в ответе.
            2. Прежде чем отвечать, проверь, что ответ однозначен. Задай один короткий уточняющий \
            вопрос, если: а) ответ зависит от того, чего нет в вопросе, и в контексте разные ответы \
            для разных условий (тип договора, сумма, роль, этап и т. п.); или б) вопрос нельзя \
            однозначно отнести к одному месту регламента (подходят разные процессы или документы).
            Тогда: found = false, answer = "", quotes = [], clarificationQuestion = уточняющий \
            вопрос, clarificationOptions = от 2 до 4 коротких вариантов ответа, если их можно назвать \
            по контексту, иначе пустой список. Не выбирай вариант за пользователя и не перечисляй \
            все варианты как ответ. В уточняющем вопросе и вариантах не должно быть ничего, чего нет \
            в контексте: числа — только из контекста или из вопроса.
            3. Если в контексте есть однозначный ответ на вопрос: found = true. В answer запиши краткий \
            ответ на русском языке, строго по контексту. В quotes запиши от 1 до 3 цитат, которыми \
            подтверждается ответ: скопируй их из контекста дословно, целиком, без сокращений, \
            без многоточий и без изменения слов. Не ссылайся на номера фрагментов [1], [2]. \
            clarificationQuestion = "", clarificationOptions = [].
            4. Если ответа в контексте нет — в том числе когда вопрос не о процессах из регламентов, — \
            found = false, answer = "", quotes = [], clarificationQuestion = "", \
            clarificationOptions = []. Не отвечай по своим знаниям и не придумывай, куда обратиться.
            5. Отвечай кратко и по делу. Учитывай предыдущие сообщения диалога: местоимения \
            («он», «этот документ») относись к тому, о чём говорили раньше.
            6. Твой предыдущий вопрос пользователю мог остаться без ответа, а на отказ (заявку в службу \
            качества) пользователь может ответить уже после него. Поэтому решай, относится ли последнее \
            сообщение пользователя к тому вопросу, который вы обсуждали: это ответ на уточнение или \
            дополнение к нему — newTopic = false, и тогда отвечай на исходный вопрос с учётом всех \
            уточнений; это новый вопрос — newTopic = true, и тогда отвечай только на него. Если после \
            уточнения ответ всё ещё зависит от того, чего пользователь не назвал, и в контексте есть \
            ответ для разных условий, — задай следующий уточняющий вопрос (правило 2). Если в контексте \
            нет нужных сведений или они противоречат друг другу — found = false без уточняющего вопроса.
            7. Если вопрос о конкретном факте (число, имя, срок) — сначала найди в контексте точное \
            предложение с ответом и отвечай строго по нему, можно с короткой цитатой.
            """ + System.lineSeparator() + CONVERTER.getFormat();

    /** Замечание к ответу, не прошедшему проверку: дописывается к вопросу при повторной попытке. */
    private static final String REMARK = """
            Замечание к твоему предыдущему ответу: %s
            Верни ответ заново в том же JSON-формате. Если ответа в контексте нет — found = false.""";

    private static final String DEFAULT_SESSION = "default";

    /** Отказ модели без уточняющего вопроса — подставляется, когда лимит уточнений уже исчерпан. */
    private static final Grounded REFUSAL = new Grounded(false, "", List.of(), null, null, false);

    /** Пределы уточняющего вопроса модели: длина текста и число вариантов ответа. */
    private static final int MAX_CLARIFICATION_CHARS = 400;
    private static final int MAX_CLARIFICATION_OPTIONS = 4;

    /** Так уточняющий вопрос помечается в памяти диалога: модели видно, чем было её предыдущее сообщение. */
    private static final String CLARIFY_MARK = "Уточняющий вопрос пользователю: ";

    /** Дописывается к вопросу, когда лимит уточняющих вопросов исчерпан. */
    private static final String LAST_ROUND = """
            Уточняющие вопросы больше невозможны: дай однозначный ответ по контексту или found = false.""";

    /** Ответ пользователю, когда ответа в регламентах нет: вместо догадки — отсылка к службе качества. */
    private static final String NO_ANSWER_MESSAGE = """
            По регламентам компании ответ на этот вопрос не найден, поэтому я его не дам: \
            агент не отвечает наугад. Создана заявка №%d в службу качества%s. \
            Обратитесь в службу качества%s — ответ появится в базе знаний.""";

    /** Ответ пользователю, когда однозначный ответ не получился и после уточнения вопроса. */
    private static final String AMBIGUOUS_MESSAGE = """
            Однозначный ответ на этот вопрос дать не удалось даже после уточнения, поэтому я его не дам: \
            агент не отвечает наугад. Вопрос передан в службу качества — заявка №%d%s. \
            Обратитесь в службу качества%s — ответ появится в базе знаний.""";

    private static final Logger log = LoggerFactory.getLogger(QaService.class);

    private final KnowledgeBaseService kbService;
    private final QualityRequestService qualityRequests;
    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final int topK;
    private final double similarityThreshold;
    private final int maxAttempts;
    private final int maxClarificationRounds;
    private final String contact;

    /**
     * Незавершённые уточняющие диалоги по ключу памяти (база знаний:сессия).
     * Как и память диалога, живут только в памяти приложения.
     */
    private final Map<String, Clarifying> clarifying = new ConcurrentHashMap<>();

    public QaService(ChatModel chatModel,
                     KnowledgeBaseService kbService,
                     QualityRequestService qualityRequests,
                     ChatMemory chatMemory,
                     @Value("${app.rag.top-k:5}") int topK,
                     @Value("${app.rag.similarity-threshold:0.35}") double similarityThreshold,
                     @Value("${app.rag.answer.max-attempts:2}") int maxAttempts,
                     @Value("${app.rag.clarification.max-rounds:3}") int maxClarificationRounds,
                     @Value("${app.rag.quality.contact:}") String contact) {
        this.kbService = kbService;
        this.qualityRequests = qualityRequests;
        this.chatClient = ChatClient.builder(chatModel).build();
        this.chatMemory = chatMemory;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.maxClarificationRounds = Math.max(1, maxClarificationRounds);
        this.contact = contact == null ? "" : contact.strip();
    }

    /** Источник ответа: документ базы знаний и сходство его чанка с вопросом. */
    public record SourceInfo(String file, String title, String docType, Double similarity) {
    }

    /** Дословный фрагмент регламента, которым подтверждён ответ. */
    public record Quote(String file, String title, String text) {
    }

    /**
     * Ответ пользователю. found = false — это либо уточняющий вопрос (clarification, заявки нет:
     * агент ждёт ответа пользователя), либо заявка в службу качества: escalated = true,
     * requestId — номер заявки, вместо ответа — отсылка к службе.
     */
    public record Answer(String answer, List<SourceInfo> sources, boolean found, boolean escalated,
                         Long requestId, List<Quote> quotes, Clarification clarification) {
    }

    /** Уточняющий вопрос к пользователю: сам вопрос и варианты ответа, если их можно назвать по регламентам. */
    public record Clarification(String question, List<String> options) {
    }

    /**
     * Ответ модели: найден ли однозначный ответ в контексте, текст ответа и цитаты, которыми он
     * подтверждается; либо уточняющий вопрос (clarificationQuestion), когда однозначный ответ
     * дать нельзя, пока пользователь не уточнит свой вопрос. newTopic = true — последнее сообщение
     * пользователя не продолжает обсуждаемый вопрос, а задаёт новый.
     */
    public record Grounded(boolean found, String answer, List<String> quotes,
                           String clarificationQuestion, List<String> clarificationOptions, boolean newTopic) {
    }

    /**
     * Ответ модели, прошедший проверку: found = true — с цитатами; уточняющий вопрос —
     * с clarification; отказ — ни того, ни другого.
     */
    private record Reply(Grounded grounded, List<QuoteVerifier.Span> spans, Clarification clarification) {
    }

    /**
     * Уточняющий диалог по одному вопросу. Закончен, только когда вопрос решён, пользователь
     * сменил тему или диалог сброшен: исходный вопрос, история уточнений (уточнение агента →
     * ответ пользователя), последнее уточнение, число раундов и номер заявки в службу качества,
     * если агент уже отказался отвечать на этот вопрос.
     */
    private record Clarifying(String question, String dialogue, String lastClarification, int rounds,
                              Long requestId) {

        /** Следующий раунд того же диалога: агент задал новое уточнение, ответ на него ещё не получен. */
        Clarifying next(String clarification) {
            return new Clarifying(question, dialogue, clarification, rounds + 1, requestId);
        }

        /** То же после сообщения пользователя: пара «уточнение → ответ» уходит в историю. */
        Clarifying answered(String answer) {
            return new Clarifying(question, dialogue + "Уточнение агента: " + lastClarification
                    + "\nОтвет пользователя: " + answer + "\n", lastClarification, rounds, requestId);
        }
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

        // Незавершённый уточняющий диалог: сообщение пользователя (ответ на уточнение, дополнение после
        // отказа или новый вопрос) дополняет его историю
        Clarifying chain = clarifying.get(memoryKey);
        if (chain != null) {
            chain = chain.answered(question);
        }

        // <<Retrieve>>: топ-K ближайших по смыслу чанков из pgvector (HNSW, косинусная близость)
        List<Document> hits = retrieve(store, question, chain);

        // Ни один фрагмент регламентов не похож на вопрос — ответа нет заведомо, модель не зовём
        if (hits.isEmpty()) {
            return escalate(kbId, sessionId, memoryKey, question, Reason.NO_CONTEXT, chain);
        }

        // <<Augment>>: собираем найденные чанки в контекст; после уточнения контекст — по исходному вопросу
        String asked = chain == null ? question : chain.question() + " (уточнение пользователя: " + question + ")";
        String userMessage = "Контекст из регламентов компании:%n%s%nВопрос: %s"
                .formatted(context(hits), asked);
        boolean clarificationAllowed = chain == null || chain.rounds() < maxClarificationRounds;
        if (!clarificationAllowed) {
            userMessage += "%n%n%s".formatted(LAST_ROUND);
        }

        // <<Generate>>: инструкция + история диалога + контекст текущего вопроса
        Reply reply = ground(chatMemory.get(memoryKey), userMessage, hits, question, clarificationAllowed);

        // Ответ, не подтверждённый регламентом, недостоверен — не показываем
        if (reply == null) {
            return escalate(kbId, sessionId, memoryKey, question, Reason.UNVERIFIED, chain);
        }
        // Пользователь сменил тему: уточняющий диалог закончен, его сообщение — новый вопрос
        if (chain != null && reply.grounded().newTopic()) {
            clarifying.remove(memoryKey);
            chain = null;
        }
        // Уточняющий вопрос: ответа ещё нет, но и заявки нет — диалог продолжается
        if (reply.clarification() != null) {
            Clarifying next = chain == null
                    ? new Clarifying(question, "", reply.clarification().question(), 1, null)
                    : chain.next(reply.clarification().question());
            clarifying.put(memoryKey, next);
            remember(memoryKey, question, CLARIFY_MARK + reply.clarification().question());
            return new Answer(reply.clarification().question(), List.of(), false, false, null, List.of(),
                    reply.clarification());
        }
        // Отказ модели; после уточнений это значит, что однозначный ответ так и не получен
        if (!reply.grounded().found()) {
            return escalate(kbId, sessionId, memoryKey, question,
                    chain == null ? Reason.NOT_IN_DOCUMENTS : Reason.AMBIGUOUS, chain);
        }
        // Вопрос решён: диалог окончен, а заявка прежнего отказа по нему службе качества больше не нужна
        if (chain != null && chain.requestId() != null) {
            qualityRequests.cancelOpen(chain.requestId());
        }
        clarifying.remove(memoryKey);

        List<SourceInfo> sources = new ArrayList<>();
        for (Document hit : hits) {
            sources.add(new SourceInfo(meta(hit, "file"), meta(hit, "title"), meta(hit, "doc_type"),
                    similarity(hit.getMetadata())));
        }
        List<Quote> quotes = new ArrayList<>();
        for (QuoteVerifier.Span span : reply.spans()) {
            quotes.add(new Quote(meta(span.chunk(), "file"), meta(span.chunk(), "title"), span.text()));
        }

        remember(memoryKey, question, reply.grounded().answer());
        return new Answer(reply.grounded().answer(), sources, true, false, null, quotes, null);
    }

    /** Очистка диалога сессии в выбранной базе знаний (кнопка «Новый чат»): память и незавершённое уточнение. */
    public void clearSession(String sessionId, String kbId) {
        String memoryKey = memoryKey(kbId, sessionId);
        chatMemory.clear(memoryKey);
        clarifying.remove(memoryKey);
    }

    /**
     * Спрашивает модель и проверяет её ответ по регламентам. Ответ, не прошедший проверку,
     * переспрашивается с замечанием; null — ответ так и не подтверждён.
     *
     * @param clarificationAllowed можно ли ещё задать уточняющий вопрос (лимит раундов не исчерпан)
     */
    private Reply ground(List<Message> history, String userMessage, List<Document> hits, String question,
                         boolean clarificationAllowed) {
        String message = userMessage;
        String problem = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Grounded grounded = parse(chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .messages(history)
                    .user(message)
                    .call()
                    .content());

            if (grounded == null) {
                problem = "ответ не является JSON в запрошенном формате.";
            } else if (!grounded.found() && hasClarification(grounded)) {
                // Лимит уточнений исчерпан: уточнение не показываем, отказ станет заявкой
                if (!clarificationAllowed) {
                    return new Reply(REFUSAL, List.of(), null);
                }
                String text = grounded.clarificationQuestion().strip();
                List<String> options = clarificationOptions(grounded.clarificationOptions());
                if (text.isEmpty() || text.length() > MAX_CLARIFICATION_CHARS) {
                    problem = "уточняющий вопрос пуст или длиннее " + MAX_CLARIFICATION_CHARS + " символов.";
                } else if (!QuoteVerifier.numbersGrounded(text + " " + String.join(" ", options), question, hits)) {
                    problem = "в уточняющем вопросе или вариантах есть числа, которых нет ни в контексте, "
                            + "ни в вопросе. Убери их или откажись от уточнения.";
                } else {
                    return new Reply(grounded, List.of(), new Clarification(text, options));
                }
            } else if (!grounded.found()) {
                return new Reply(grounded, List.of(), null);
            } else {
                QuoteVerifier.Result result = QuoteVerifier.locate(grounded.quotes(), hits);
                if (result.missing() > 0 || result.spans().isEmpty()) {
                    problem = "цитаты не найдены в контексте дословно. Скопируй их из контекста точно, "
                            + "без сокращений и без изменения слов, или поставь found = false.";
                } else if (grounded.answer() == null || grounded.answer().isBlank()) {
                    problem = "поле answer пустое.";
                } else if (!QuoteVerifier.numbersGrounded(grounded.answer(), question, hits)) {
                    problem = "в answer есть числа, которых нет ни в контексте, ни в вопросе. "
                            + "Убери их: ответ не должен содержать ничего сверх контекста.";
                } else {
                    return new Reply(grounded, result.spans(), null);
                }
            }
            log.info("Попытка {}: ответ модели не принят — {}", attempt, problem);
            message = "%s%n%n%s".formatted(userMessage, REMARK.formatted(problem));
        }
        return null;
    }

    /** Ответ модели в виде структуры; null — если это не JSON в запрошенном формате. */
    private static Grounded parse(String text) {
        if (text == null) {
            return null;
        }
        // Модель может обернуть JSON в блок кода или сопроводить пояснением —
        // берём от первой «{» до последней «}»
        int from = text.indexOf('{');
        int to = text.lastIndexOf('}');
        if (from < 0 || to <= from) {
            return null;
        }
        try {
            return CONVERTER.convert(text.substring(from, to + 1));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Ответа по регламентам нет: вместо догадки — заявка в службу качества
     * и честный ответ пользователю, куда обратиться. Уточняющий диалог при этом не закрывается:
     * служба качества получает исходный вопрос и историю уточнений, а пользователь может ответить
     * и после отказа — тогда агент ответит сам, а его новое уточнение дополнит ту же заявку.
     */
    private Answer escalate(String kbId, String sessionId, String memoryKey, String question, Reason reason,
                            Clarifying chain) {
        long requestId;
        Clarifying open = null;
        if (chain == null) {
            requestId = qualityRequests.create(kbId, sessionId, question, reason, null);
        } else {
            // Агент уже отказывался по этому вопросу: новое уточнение дополняет ту же открытую заявку
            requestId = chain.requestId() != null && qualityRequests.addDialogue(chain.requestId(), chain.dialogue())
                    ? chain.requestId()
                    : qualityRequests.create(kbId, sessionId, chain.question(), reason, chain.dialogue().strip());
            open = new Clarifying(chain.question(), chain.dialogue(), chain.lastClarification(), chain.rounds(),
                    requestId);
        }
        if (open == null) {
            clarifying.remove(memoryKey);
        } else {
            clarifying.put(memoryKey, open);
        }
        String where = contact.isBlank() ? "" : " (" + contact + ")";
        String answer = (reason == Reason.AMBIGUOUS ? AMBIGUOUS_MESSAGE : NO_ANSWER_MESSAGE)
                .formatted(requestId, where, where);
        remember(memoryKey, question, answer);
        return new Answer(answer, List.of(), false, true, requestId, List.of(), null);
    }

    /** <<Augment>>: найденные чанки с номерами и источниками. */
    private static String context(List<Document> hits) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            Document hit = hits.get(i);
            context.append("[%d] %s (файл: %s)%n%s%n%n"
                    .formatted(i + 1, meta(hit, "title"), meta(hit, "file"), hit.getText()));
        }
        return context.toString();
    }

    /**
     * <<Retrieve>>: топ-K ближайших по смыслу чанков. Пока уточнение не завершено, ищем дважды —
     * по связке «исходный вопрос + уточнение + ответ» и по самому сообщению пользователя: так
     * находятся чанки и для ответа на уточнение, и для нового вопроса, заданного вместо ответа.
     */
    private List<Document> retrieve(VectorStore store, String question, Clarifying chain) {
        if (chain == null) {
            return search(store, question, topK);
        }
        List<Document> hits = new ArrayList<>(search(store,
                chain.question() + "\n" + chain.lastClarification() + "\n" + question, topK));
        Set<String> ids = new HashSet<>();
        for (Document hit : hits) {
            ids.add(hit.getId());
        }
        for (Document hit : search(store, question, Math.max(1, topK / 2))) {
            if (ids.add(hit.getId())) {
                hits.add(hit);
            }
        }
        return hits;
    }

    private List<Document> search(VectorStore store, String query, int k) {
        return store.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(k)
                .similarityThreshold(similarityThreshold)
                .build());
    }

    /** Просит ли модель уточнения: found = false и непустой уточняющий вопрос. */
    private static boolean hasClarification(Grounded grounded) {
        return grounded.clarificationQuestion() != null && !grounded.clarificationQuestion().isBlank();
    }

    /** Варианты ответа уточняющего вопроса: без пустых и повторов, не больше MAX_CLARIFICATION_OPTIONS. */
    private static List<String> clarificationOptions(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        Set<String> options = new LinkedHashSet<>();
        for (String option : raw) {
            if (option != null && !option.isBlank()) {
                options.add(option.strip());
            }
            if (options.size() >= MAX_CLARIFICATION_OPTIONS) {
                break;
            }
        }
        return List.copyOf(options);
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

    /** Значение из метаданных чанка; отсутствующее — пустая строка. */
    private static String meta(Document chunk, String key) {
        return String.valueOf(chunk.getMetadata().getOrDefault(key, ""));
    }

    /** pgvector возвращает косинусное расстояние в метаданных; сходство = 1 - расстояние. */
    private static Double similarity(Map<String, Object> meta) {
        Object distance = meta.get("distance");
        return (distance instanceof Number n) ? 1.0 - n.doubleValue() : null;
    }
}
