package ru.demo.ragagent.qa;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.demo.ragagent.quality.QualityRequestService;

/**
 * Чат с агентом и заявки, созданные в нём. Правила доступа заданы в SecurityConfig:
 * заявки своей сессии видит любой пользователь, консоль службы качества — только её роль.
 */@RestController
@RequestMapping("/api")
public class AskController {

    private final QaService qaService;
    private final QualityRequestService qualityRequests;

    public AskController(QaService qaService, QualityRequestService qualityRequests) {
        this.qaService = qaService;
        this.qualityRequests = qualityRequests;
    }

    public record AskRequest(String question, String sessionId, String kbId) {
    }

    /** Кто вошёл и его роли («ROLE_…»). */
    public record Me(String username, List<String> roles) {
    }

    /** GET /api/me — учётная запись вошедшего: страница чата по ней решает, показывать ли консоль службы качества. */
    @GetMapping("/me")
    public Me me(@AuthenticationPrincipal UserDetails user) {
        return new Me(user.getUsername(),
                user.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
    }

    /**
     * POST /api/ask {"question": "...", "sessionId": "uuid", "kbId": "default"}.
     * sessionId и kbId не обязательны: дефолт — отдельная сессия в основной базе знаний.
     */
    @PostMapping("/ask")
    public QaService.Answer ask(@RequestBody AskRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("Вопрос не может быть пустым");
        }
        return qaService.ask(request.question().trim(), request.sessionId(), request.kbId());
    }

    /** DELETE /api/chat?sessionId=...&kbId=... — очистить диалог (кнопка «Новый чат»). */
    @DeleteMapping("/chat")
    public ResponseEntity<Void> clearChat(@RequestParam(value = "sessionId", required = false) String sessionId,
                                          @RequestParam(value = "kbId", required = false) String kbId) {
        qaService.clearSession(sessionId, kbId);
        return ResponseEntity.noContent().build();
    }

    /** GET /api/chat/requests?sessionId=... — заявки этой сессии: пользователь видит в них ответы службы качества. */
    @GetMapping("/chat/requests")
    public List<QualityRequestService.RequestInfo> requests(@RequestParam("sessionId") String sessionId) {
        return qualityRequests.listBySession(sessionId);
    }
}
