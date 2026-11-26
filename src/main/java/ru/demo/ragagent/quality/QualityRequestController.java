package ru.demo.ragagent.quality;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API страницы заявок службы качества. Доступ — только роли QUALITY; само правило
 * задано в SecurityConfig, здесь только маршруты.
 */
@RestController
@RequestMapping("/api/quality-requests")
public class QualityRequestController {

    private final QualityRequestService service;

    public QualityRequestController(QualityRequestService service) {
        this.service = service;
    }

    public record AnswerRequest(String answer) {
    }

    /** Заявки службы качества; по умолчанию только открытые (openOnly=false — все). */
    @GetMapping
    public List<QualityRequestService.RequestInfo> list(
            @RequestParam(value = "openOnly", defaultValue = "true") boolean openOnly) {
        return service.list(openOnly);
    }

    /** Ответить на заявку: вопрос и ответ сохраняются в базе знаний документом. */
    @PostMapping("/{id}/answer")
    public QualityRequestService.AnswerResult answer(@PathVariable("id") long id,
                                                     @RequestBody AnswerRequest request,
                                                     @AuthenticationPrincipal UserDetails author) {
        return service.answer(id, request.answer(), author == null ? null : author.getUsername());
    }

    /** Удалить заявку. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
