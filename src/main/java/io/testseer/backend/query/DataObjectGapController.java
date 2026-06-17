package io.testseer.backend.query;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@Tag(name = "Query — Data Object Gaps", description = "Catalog vs DDL drift report (WRK-23)")
@RestController
@RequestMapping("/v1/gaps")
public class DataObjectGapController {

    private final DataObjectGapService gapService;
    private final ConsistencyGapService consistencyGapService;

    public DataObjectGapController(
            DataObjectGapService gapService,
            ConsistencyGapService consistencyGapService) {
        this.gapService = gapService;
        this.consistencyGapService = consistencyGapService;
    }

    @Operation(summary = "Data object catalog drift vs DDL")
    @GetMapping("/data-objects")
    public ResponseEntity<ResponseEnvelope<List<DataObjectGapService.DataObjectGapView>>> getDataObjectGaps(
            @RequestParam String orgId) {
        return ResponseEntity.ok(ResponseEnvelope.of(
                Instant.now(), null, FreshnessStatus.CURRENT, gapService.computeGaps(orgId)));
    }

    @Operation(summary = "Consistency scenario gaps — undocumented dual-writes, orphan rules, unlinked mirrors")
    @GetMapping("/consistency")
    public ResponseEntity<ResponseEnvelope<List<ConsistencyGapService.ConsistencyGapView>>> getConsistencyGaps(
            @RequestParam String orgId,
            @RequestParam String serviceId) {
        return ResponseEntity.ok(ResponseEnvelope.of(
                Instant.now(), null, FreshnessStatus.CURRENT,
                consistencyGapService.computeGaps(orgId, serviceId)));
    }
}
