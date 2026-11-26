package ru.demo.ragagent.security;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * «Сменить пользователя». У Basic-аутентификации нет выхода: браузер держит
 * сохранённые учётные данные до закрытия. Ответ 401 с тем же realm заставляет
 * браузер забыть их и спросить заново — так в одном браузере меняют роль
 * (пользователь <-> служба качества).
 *
 * Страница шлёт запрос в фоне (fetch) и никуда не уходит: окно ввода появляется
 * поверх неё. После окна браузер повторяет тот же запрос уже с введёнными
 * учётными данными, поэтому «всегда 401» выглядело бы как неверный пароль.
 * Запрос несёт одноразовый ключ (k): первый запрос с ним получает 401, повтор
 * с верными учётными данными — 204 (фоновому запросу) или возврат на страницу,
 * где нажали ссылку (переходу по ней). Неверные учётные данные отбрасывает
 * Spring Security — снова 401.
 */
@RestController
public class LoginController {

    /** Адрес, на который ведёт ссылка «Сменить пользователя». */
    public static final String LOGOUT_PATH = "/logout";

    /** Сколько времени ссылка ждёт повтора после окна ввода. */
    private static final Duration PROMPT_TTL = Duration.ofMinutes(3);

    /** Ключи ссылок, на которые уже ответлено 401: запрос с таким ключом — повтор после окна ввода. */
    private final Map<String, Instant> prompted = new ConcurrentHashMap<>();

    /**
     * Ссылка без ключа и первый запрос по ссылке — 401: браузер забывает сохранённые
     * учётные данные и спрашивает заново. Повтор по той же ссылке с верными учётными
     * данными — возврат на страницу, где её нажали.
     */
    @GetMapping(LOGOUT_PATH)
    public ResponseEntity<Void> logout(@RequestParam(value = "k", required = false) String key,
                                       HttpServletRequest request) {
        if (key == null || key.isBlank() || firstTime(key) || request.getUserPrincipal() == null) {
            return challenge();
        }
        prompted.remove(key);
        // Фоновый запрос со страницы: браузер запомнит учётные данные, страница перезагрузится сама
        if (request.getHeader("X-Requested-With") != null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(backTo(request))).build();
    }

    /** Первый запрос по ссылке: запоминает ключ и отвечает true; повтор после окна ввода — false. */
    private boolean firstTime(String key) {
        Instant now = Instant.now();
        prompted.values().removeIf(until -> until.isBefore(now));
        return prompted.put(key, now.plus(PROMPT_TTL)) == null;
    }

    private static ResponseEntity<Void> challenge() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"" + SecurityConfig.REALM + "\"")
                .build();
    }

    /**
     * Страница, где нажали ссылку, — путь из Referer (у повтора он тот же, что у первого запроса).
     * Берётся только путь, поэтому возврат всегда на это же приложение; без Referer — чат.
     */
    private static String backTo(HttpServletRequest request) {
        String referer = request.getHeader(HttpHeaders.REFERER);
        if (referer != null) {
            try {
                URI uri = URI.create(referer);
                String path = uri.getPath();
                if (path != null && path.startsWith("/") && !path.startsWith("//") && !LOGOUT_PATH.equals(path)) {
                    return path + (uri.getQuery() == null ? "" : "?" + uri.getQuery());
                }
            } catch (IllegalArgumentException e) {
                // некорректный Referer — возвращаемся в чат
            }
        }
        return "/";
    }
}
