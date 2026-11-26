package ru.demo.ragagent.security;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * «Сменить пользователя». У Basic-аутентификации нет выхода: браузер держит
 * сохранённые учётные данные до закрытия. Ответ 401 с тем же realm заставляет
 * браузер забыть их и спросить заново — так в одном браузере меняют роль
 * (пользователь <-> служба качества).
 */
@RestController
public class LoginController {

    /** Адрес, на который ведёт ссылка «Сменить пользователя». */
    public static final String LOGOUT_PATH = "/logout";

    /** Всегда отвечает 401 с запросом Basic-учётных данных. */
    @GetMapping(LOGOUT_PATH)
    public ResponseEntity<Void> logout() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"" + SecurityConfig.REALM + "\"")
                .build();
    }
}
