package ru.demo.ragagent.qa;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AskController {

    private final QaService qaService;

    public AskController(QaService qaService) {
        this.qaService = qaService;
    }

    public record AskRequest(String question, String sessionId, String kbId) {
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
}
