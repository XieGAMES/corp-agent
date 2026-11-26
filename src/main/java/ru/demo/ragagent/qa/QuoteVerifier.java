package ru.demo.ragagent.qa;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;

/**
 * Проверка того, что ответ модели опирается только на текст регламентов.
 *
 * Модель может добавить к найденным фрагментам что-то от себя, поэтому её ответ
 * принимается только при двух условиях:
 *  1) каждая цитата, которой модель подтверждает ответ, дословно есть в одном из
 *     фрагментов. Регистр, «ё», знаки препинания и переводы строк не важны,
 *     слова — важны. Пользователю показывается подстрока самого регламента,
 *     а не текст модели;
 *  2) все числа ответа взяты из фрагментов или из вопроса — так отсекаются
 *     выдуманные сроки, суммы и проценты.
 */
public final class QuoteVerifier {

    /** Цитата из регламента: дословный фрагмент и чанк, из которого он взят. */
    public record Span(String text, Document chunk) {
    }

    /**
     * Итог поиска цитат: spans — найденные фрагменты, missing — сколько кусков цитат
     * в регламентах не нашлось (больше нуля — ответ не подтверждён).
     */
    public record Result(List<Span> spans, int missing) {
    }

    /** Цитата проверяется по частям — по предложениям и между многоточиями. */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?…])\\s+|\\.{1,3}|…");

    /** Ссылка модели на номер фрагмента («[1]»): это разметка контекста, а не данные из регламента. */
    private static final Pattern FRAGMENT_REF = Pattern.compile("\\[\\d+]");

    /** Номер в начале строки списка («1. », «2) »): его ставит модель, а не регламент. */
    private static final Pattern LIST_MARKER = Pattern.compile("(?m)^\\s*\\d+[.)]\\s+");

    private static final Pattern NUMBER = Pattern.compile("\\d+");

    /** Короче четырёх символов кусок не проверяем: в нём нет утверждений. */
    private static final int MIN_PIECE_CHARS = 4;

    private QuoteVerifier() {
    }

    /** Находит цитаты модели в чанках регламентов. */
    public static Result locate(List<String> quotes, List<Document> chunks) {
        List<Normalized> texts = new ArrayList<>();
        for (Document chunk : chunks) {
            texts.add(normalize(chunk.getText()));
        }

        List<Span> spans = new ArrayList<>();
        Set<String> shown = new HashSet<>();
        int missing = 0;
        for (String quote : quotes == null ? List.<String>of() : quotes) {
            for (String piece : SENTENCE_END.split(quote == null ? "" : quote)) {
                Normalized needle = normalize(piece);
                if (needle.text().strip().length() < MIN_PIECE_CHARS) {
                    continue;
                }
                Span span = find(needle, texts, chunks);
                if (span == null) {
                    missing++;
                } else if (shown.add(span.chunk().getId() + '|' + span.text())) {
                    spans.add(span);
                }
            }
        }
        return new Result(spans, missing);
    }

    /** Все ли числа ответа взяты из чанков или из самого вопроса. */
    public static boolean numbersGrounded(String answer, String question, List<Document> chunks) {
        Set<String> known = new HashSet<>();
        collectNumbers(question, known);
        for (Document chunk : chunks) {
            collectNumbers(chunk.getText(), known);
        }
        String text = LIST_MARKER.matcher(FRAGMENT_REF.matcher(answer).replaceAll("")).replaceAll("");
        Set<String> used = new HashSet<>();
        collectNumbers(text, used);
        return known.containsAll(used);
    }

    private static void collectNumbers(String text, Set<String> numbers) {
        if (text == null) {
            return;
        }
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            numbers.add(matcher.group());
        }
    }

    /** Ищет кусок цитаты в чанках; возвращает подстроку исходного текста чанка. */
    private static Span find(Normalized needle, List<Normalized> texts, List<Document> chunks) {
        for (int i = 0; i < texts.size(); i++) {
            Normalized hay = texts.get(i);
            int at = hay.text().indexOf(needle.text());
            if (at < 0) {
                continue;
            }
            // Игла начинается пробелом-границей слова, и его начало указывает на первый разделитель
            // перед словом («. » после номера пункта, «: » и т. п.) — цитата начинается с первой буквы.
            int first = 0;
            while (needle.text().charAt(first) == ' ') {
                first++;
            }
            int from = hay.origin()[at + first];
            int to = hay.origin()[at + needle.text().length() - 1];
            return new Span(chunks.get(i).getText().substring(from, to).strip(), chunks.get(i));
        }
        return null;
    }

    /**
     * Нормализованный текст: только буквы и цифры в нижнем регистре («ё» → «е»),
     * разделители — по одному пробелу, и пробелы-ограничители по краям, чтобы слово
     * не находилось внутри другого слова. origin[i] — где был символ i в исходном тексте.
     */
    private record Normalized(String text, int[] origin) {
    }

    private static Normalized normalize(String source) {
        StringBuilder text = new StringBuilder(" ");
        int[] origin = new int[source.length() + 2];
        int n = 1;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                char low = Character.toLowerCase(c);
                origin[n] = i;
                text.append(low == 'ё' ? 'е' : low);
                n++;
            } else if (text.charAt(n - 1) != ' ') {
                origin[n] = i;
                text.append(' ');
                n++;
            }
        }
        if (text.charAt(n - 1) != ' ') {
            origin[n] = source.length();
            text.append(' ');
        }
        return new Normalized(text.toString(), origin);
    }
}
