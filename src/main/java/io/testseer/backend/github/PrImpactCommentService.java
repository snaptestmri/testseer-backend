package io.testseer.backend.github;

import io.testseer.backend.analysis.CommitIndexValidator;
import io.testseer.backend.analysis.GapDetectionService;
import io.testseer.backend.analysis.GapReport;
import io.testseer.backend.analysis.ImpactAnalysisService;
import io.testseer.backend.analysis.ImpactReport;
import io.testseer.backend.query.ConsistencyGapService;
import io.testseer.backend.registry.ServiceEntry;
import io.testseer.backend.registry.ServiceRegistryService;
import io.testseer.backend.webhook.IngestionJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class PrImpactCommentService {

    private static final Logger log = LoggerFactory.getLogger(PrImpactCommentService.class);

    private final ImpactAnalysisService impactService;
    private final GapDetectionService gapService;
    private final ConsistencyGapService consistencyGapService;
    private final CommitIndexValidator indexValidator;
    private final ServiceRegistryService registryService;
    private final PrCommentPublisher publisher;
    private final String traceBaseUrl;

    public PrImpactCommentService(
            ImpactAnalysisService impactService,
            GapDetectionService gapService,
            ConsistencyGapService consistencyGapService,
            CommitIndexValidator indexValidator,
            ServiceRegistryService registryService,
            PrCommentPublisher publisher,
            @Value("${testseer.github.comment-trace-base-url:}") String traceBaseUrl) {
        this.impactService = impactService;
        this.gapService = gapService;
        this.consistencyGapService = consistencyGapService;
        this.indexValidator = indexValidator;
        this.registryService = registryService;
        this.publisher = publisher;
        this.traceBaseUrl = traceBaseUrl;
    }

    public void onPrJobComplete(IngestionJob job) {
        if (!"PR".equals(job.jobType()) || job.prNumber() == null) {
            return;
        }
        if (!publisher.isEnabled()) {
            return;
        }

        try {
            List<PrCommentFormatter.ServiceAnalysis> analyses = buildIndexedAnalyses(
                    job.orgId(), job.repo(), job.commitSha());
            if (analyses.isEmpty()) {
                log.debug("Skipping PR comment for {}/{}#{} — no indexed services yet",
                        job.orgId(), job.repo(), job.prNumber());
                return;
            }

            String body = PrCommentFormatter.format(
                    job.prNumber(), job.commitSha(), analyses, traceBaseUrl);
            publisher.publishOrUpdate(job.orgId(), job.repo(), job.prNumber(), body);
        } catch (Exception e) {
            log.warn("Failed to publish PR comment for {}/{}#{}: {}",
                    job.orgId(), job.repo(), job.prNumber(), e.getMessage(), e);
        }
    }

    private List<PrCommentFormatter.ServiceAnalysis> buildIndexedAnalyses(
            String orgId, String repo, String commitSha) {
        List<ServiceEntry> services = registryService.listAll().stream()
                .filter(s -> s.enabled() && orgId.equals(s.orgId()) && repo.equals(s.repo()))
                .sorted(Comparator.comparing(ServiceEntry::serviceId))
                .toList();

        List<PrCommentFormatter.ServiceAnalysis> analyses = new ArrayList<>();
        for (ServiceEntry service : services) {
            if (!indexValidator.isIndexed(service.serviceId(), commitSha)) {
                continue;
            }
            ImpactReport impact = impactService.buildReport(service.serviceId(), commitSha);
            GapReport gaps = gapService.buildReport(service.serviceId());
            List<ConsistencyGapService.ConsistencyGapView> consistencyGaps =
                    consistencyGapService.computeGaps(orgId, service.serviceId());
            analyses.add(new PrCommentFormatter.ServiceAnalysis(
                    service.serviceId(), impact, gaps, consistencyGaps));
        }
        return analyses;
    }
}
