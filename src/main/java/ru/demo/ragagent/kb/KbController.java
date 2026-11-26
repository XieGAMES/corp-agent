package ru.demo.ragagent.kb;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/kb")
public class KbController {

    private final KnowledgeBaseService kbService;

    public KbController(KnowledgeBaseService kbService) {
        this.kbService = kbService;
    }

    public record CreateKbRequest(String title) {
    }

    /** Список баз знаний. */
    @GetMapping
    public List<KnowledgeBaseService.KbInfo> list() {
        return kbService.list();
    }

    /** Создать новую базу знаний (отдельную схему PostgreSQL). */
    @PostMapping
    public KnowledgeBaseService.KbInfo create(@RequestBody CreateKbRequest request) {
        return kbService.create(request.title());
    }

    /** Статистика базы знаний: документов и чанков. */
    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(value = "kbId", required = false) String kbId) {
        return kbService.stats(kbId);
    }

    /** Удалить базу знаний вместе со всеми её документами. */
    @DeleteMapping
    public ResponseEntity<Void> delete(@RequestParam("kbId") String kbId) {
        kbService.delete(kbId);
        return ResponseEntity.noContent().build();
    }
}
