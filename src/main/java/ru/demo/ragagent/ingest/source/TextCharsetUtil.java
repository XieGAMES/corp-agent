package ru.demo.ragagent.ingest.source;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Определение кодировки простого текстового файла без внешних библиотек.
 *
 * Нужен потому, что у Tika без ICU4J не работает автоопределение кодировки:
 * .txt в CP1251 (типичная кодировка русских текстов без BOM) извлекается
 * как пустая строка. Порядок проверки:
 * 1) BOM (UTF-8 / UTF-16LE / UTF-16BE);
 * 2) строго валидный UTF-8;
 * 3) иначе — windows-1251 (безопасный fallback для кириллицы).
 */
public final class TextCharsetUtil {

    private TextCharsetUtil() {
    }

    public static String readText(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);

        // BOM
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }

        // Строгая проверка UTF-8: если байты валидны — это точно UTF-8
        if (isValidUtf8(bytes)) {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        // Не UTF-8 и без BOM — для русскоязычных текстов это почти всегда CP1251
        return new String(bytes, java.nio.charset.Charset.forName("windows-1251"));
    }

    public static boolean isPlainText(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".log")
                || lower.endsWith(".csv") || lower.endsWith(".srt");
    }

    private static boolean isValidUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
