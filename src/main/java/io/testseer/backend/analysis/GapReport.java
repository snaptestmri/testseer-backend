package io.testseer.backend.analysis;

import java.util.List;

public record GapReport(
        String serviceId,
        String commitSha,
        int productionClassCount,
        int testedClassCount,
        int untestedClassCount,
        List<ClassGap> gaps
) {
    public record ClassGap(
            String classFqn,
            String filePath,
            String kind
    ) {}
}
