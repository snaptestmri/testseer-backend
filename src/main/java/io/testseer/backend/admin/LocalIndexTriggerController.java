package io.testseer.backend.admin;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/index/local")
public class LocalIndexTriggerController {

    private final LocalIndexTriggerService triggerService;

    public LocalIndexTriggerController(LocalIndexTriggerService triggerService) {
        this.triggerService = triggerService;
    }

    @PostMapping
    public LocalIndexTriggerResponse trigger(
            @Valid @RequestBody LocalIndexTriggerRequest request) {
        return triggerService.trigger(request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadPath(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
