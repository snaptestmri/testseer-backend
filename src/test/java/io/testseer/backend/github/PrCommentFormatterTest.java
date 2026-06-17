package io.testseer.backend.github;

import io.testseer.backend.analysis.GapReport;
import io.testseer.backend.analysis.ImpactReport;
import io.testseer.backend.query.ConsistencyGapService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PrCommentFormatterTest {

    @Test
    void format_includesChangedAffectedTestsAndGaps() {
        ImpactReport impact = ImpactReport.withoutArtifactImpact(
                "billing-service",
                "abc123def456",
                List.of(new ImpactReport.ChangedSymbol(
                        "com.acme.OrderController", "CLASS",
                        "src/main/java/com/acme/OrderController.java",
                        "GET", "/orders/{id}")),
                List.of(new ImpactReport.AffectedConsumer(
                        "graph", "orders-service", "orders-service",
                        "com.acme.OrderClient", "HTTP_CLIENT",
                        "GET", "/orders/{id}")),
                List.of(),
                List.of(),
                List.of(
                        new ImpactReport.SuggestedTest(
                                "UNIT", "OrderControllerTest", "billing-service", true, "covers changed class"),
                        new ImpactReport.SuggestedTest(
                                "INTEGRATION", "PaymentClientTest", "billing-service", false,
                                "no test class")),
                List.of("com.acme.PaymentClient"));

        GapReport gaps = new GapReport(
                "billing-service", "abc123def456",
                10, 7, 3,
                List.of(
                        new GapReport.ClassGap(
                                "com.acme.PaymentController", "src/main/java/PaymentController.java",
                                "ENDPOINT_CONTROLLER"),
                        new GapReport.ClassGap(
                                "com.acme.InternalHelper", "src/main/java/InternalHelper.java",
                                "CLASS")));

        List<ConsistencyGapService.ConsistencyGapView> consistencyGaps = List.of(
                new ConsistencyGapService.ConsistencyGapView(
                        "DUAL_WRITE_SAME_HANDLER",
                        "PartnerOfferCallRecorder#save",
                        null,
                        "poll both saveToDb + markAllPendingAsProcessed"));

        String body = PrCommentFormatter.format(
                47,
                "abc123def456",
                List.of(new PrCommentFormatter.ServiceAnalysis(
                        "billing-service", impact, gaps, consistencyGaps)),
                "http://testseer/graph");

        assertThat(body).contains(PrCommentFormatter.MARKER);
        assertThat(body).contains("PR #47");
        assertThat(body).contains("**Commit:** `abc123d`");
        assertThat(body).contains("**Changed:** OrderController");
        assertThat(body).contains("orders-service (calls GET /orders/{id})");
        assertThat(body).contains("OrderControllerTest (existing, covers changed class)");
        assertThat(body).contains("⚠️ PaymentClientTest");
        assertThat(body).contains("PaymentClient has no test class");
        assertThat(body).contains("1 controller lacks test coverage");
        assertThat(body).contains("DUAL_WRITE_SAME_HANDLER — PartnerOfferCallRecorder#save");
        assertThat(body).contains("[View full trace](http://testseer/graph)");
    }

    @Test
    void format_handlesEmptyAnalyses() {
        String body = PrCommentFormatter.format(1, "sha", List.of(), null);

        assertThat(body).contains("Indexing in progress");
        assertThat(body).doesNotContain("[View full trace]");
    }
}
