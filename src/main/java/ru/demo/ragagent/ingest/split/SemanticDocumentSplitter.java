package ru.demo.ragagent.ingest.split;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

/**
 * Семантический чанкер: разбиение текста по смыслу — по абзацам и предложениям,
 * а не по фиксированному числу токенов. Реализует DocumentTransformer,
 * поэтому взаимозаменяем с TokenTextSplitter.
 *
 * Алгоритм:
 *  1) текст делится на абзацы (пустая строка) и предложения (. ! ? …);
 *  2) предложения накапливаются в чанк, пока не достигнут maxTokens (CL100K);
 *  3) граница абзаца закрывает чанк досрочно, если он уже >= minTokens —
 *     абзацы не перемешиваются между чанками;
 *  4) короткие огрызки (маркеры глав «III», пустые фрагменты) приклеиваются
 *     к следующему предложению, а не образуют отдельный чанк — именно склейка
 *     таких обрывков разрастала чанки «Тараса Бульбы» до 7 КБ;
 *  5) последнее предложение чанка повторяется в начале следующего (перекрытие) —
 *     смысл на стыках не теряется;
 *  6) аномально длинное предложение режется по словам;
 *  7) пустые/мусорные куски короче minChunkChars отбрасываются.
 */
public class SemanticDocumentSplitter implements DocumentTransformer {

    private final int maxTokens;
    private final int minTokens;
    private final int overlapSentences;
    private final int minChunkChars;
    private final Encoding encoding = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    public SemanticDocumentSplitter(int maxTokens, int minTokens, int overlapSentences, int minChunkChars) {
        this.maxTokens = maxTokens;
        this.minTokens = minTokens;
        this.overlapSentences = overlapSentences;
        this.minChunkChars = minChunkChars;
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        List<Document> result = new ArrayList<>();
        for (Document doc : documents) {
            Map<String, Object> metadata = doc.getMetadata();
            for (String chunk : splitText(doc.getText())) {
                result.add(new Document(chunk, metadata));
            }
        }
        return result;
    }

    /** Разбивает текст на чанки по предложениям/абзацам. */
    public List<String> splitText(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        List<String> current = new ArrayList<>();
        int currentTokens = 0;

        for (Sentence s : toSentences(text)) {
            int sTokens = encoding.countTokens(s.text());

            // Аномально длинное предложение: закрыть чанк, предложение разрезать по словам
            if (sTokens > maxTokens) {
                current = flushChunk(current, chunks);
                chunks.addAll(splitByWords(s.text()));
                continue;
            }

            int nextTokens = currentTokens + sTokens + (current.isEmpty() ? 0 : 1);
            if (nextTokens > maxTokens && !current.isEmpty()) {
                current = flushChunk(current, chunks);
                currentTokens = encoding.countTokens(String.join(" ", current));
                nextTokens = currentTokens + sTokens + 1;
            }
            current.add(s.text());
            currentTokens = nextTokens;

            // Граница абзаца: закрыть чанк, если он уже содержательный
            if (s.endsParagraph() && currentTokens >= minTokens) {
                current = flushChunk(current, chunks);
                currentTokens = current.isEmpty() ? 0
                        : encoding.countTokens(String.join(" ", current));
            }
        }
        flushChunk(current, chunks);

        return chunks.stream()
                .map(String::strip)
                .filter(c -> c.length() >= minChunkChars)
                .toList();
    }

    private record Sentence(String text, boolean endsParagraph) {
    }

    /** Абзацы -> предложения; короткие огрызки клеятся к следующему предложению. */
    private List<Sentence> toSentences(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("(?m)^[ \\t]+$", "")
                .replaceAll("\\n{3,}", "\n\n");

        List<Sentence> out = new ArrayList<>();
        Deque<String> pending = new ArrayDeque<>(); // короткий огрызок ждёт следующего предложения

        String[] paragraphs = normalized.split("\\n\\s*\\n");
        for (int p = 0; p < paragraphs.length; p++) {
            String paragraph = paragraphs[p].replace('\n', ' ').strip();
            if (paragraph.isEmpty()) {
                continue;
            }
            String[] parts = paragraph.split("(?<=[.!?…])\\s+");
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i].strip();
                if (part.isEmpty()) {
                    continue;
                }
                boolean lastInText = (p == paragraphs.length - 1) && (i == parts.length - 1);
                if (part.length() < 4 && !lastInText) {
                    pending.add(part);
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                while (!pending.isEmpty()) {
                    sb.append(pending.poll()).append(' ');
                }
                out.add(new Sentence(sb.append(part).toString().strip(), (i == parts.length - 1)));
            }
        }
        if (!pending.isEmpty()) {
            out.add(new Sentence(String.join(" ", pending), true));
        }
        return out;
    }

    /** Закрывает чанк; возвращает хвост предложений — перекрытие для следующего чанка. */
    private List<String> flushChunk(List<String> sentences, List<String> chunks) {
        String joined = String.join(" ", sentences).strip();
        if (!joined.isBlank()) {
            chunks.add(joined);
        }
        int overlap = Math.min(overlapSentences, Math.max(0, sentences.size() - 1));
        List<String> tail = new ArrayList<>(
                sentences.subList(sentences.size() - overlap, sentences.size()));
        sentences.clear();
        return tail;
    }

    /** Разрезает слишком длинное предложение по словам на куски <= maxTokens. */
    private List<String> splitByWords(String sentence) {
        List<String> parts = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (String word : sentence.split("\\s+")) {
            String candidate = sb.isEmpty() ? word : sb + " " + word;
            if (!sb.isEmpty() && encoding.countTokens(candidate) > maxTokens) {
                parts.add(sb.toString().strip());
                sb.setLength(0);
            }
            sb.append(sb.isEmpty() ? word : " " + word);
        }
        if (!sb.isEmpty()) {
            parts.add(sb.toString().strip());
        }
        return parts;
    }
}
