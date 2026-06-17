package io.testseer.backend.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Admin — Indexing", description = "Clear or rebuild indexed facts")
@RestController
@RequestMapping("/admin/index")
public class IndexClearController {

    private final IndexClearService indexClearService;

    public IndexClearController(IndexClearService indexClearService) {
        this.indexClearService = indexClearService;
    }

    @Operation(summary = "Clear indexed facts",
               description = """
                   Removes indexed facts so you can re-index cleanly.
                   scope=SERVICE (default): all facts + graph for one serviceId.
                   scope=MESSAGING: Option C facts only (pubsub, schema, gates, etc.).
                   scope=ORG: all facts for orgId; set includeRegistry=true to also remove service_registry rows.
                   Does not delete shared TOPIC/SUBSCRIPTION graph nodes that other services may reference.""")
    @PostMapping("/clear")
    public IndexClearResponse clear(@Valid @RequestBody IndexClearRequest request) {
        return indexClearService.clear(request);
    }

    @Operation(summary = "Clear indexed facts for one service (shortcut)")
    @DeleteMapping("/{serviceId}")
    public IndexClearResponse clearServiceById(@PathVariable String serviceId) {
        return indexClearService.clear(new IndexClearRequest("SERVICE", null, serviceId, false));
    }
}
