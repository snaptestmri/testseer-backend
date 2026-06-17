package io.testseer.backend.ingestion.consistency;

import io.testseer.backend.ingestion.FactBatch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Classifies same-handler WRITE groups into S-01 / S-06 / S-07 patterns. */
final class HandlerWriteScenarioClassifier {

    static final String PATTERN_DUAL_WRITE = "DUAL_WRITE_SAME_HANDLER";
    static final String PATTERN_MULTI_TABLE = "MULTI_TABLE_DOMAIN";
    static final String PATTERN_CROSS_STORE = "CROSS_STORE_WRITE";

    record ClassifiedWriteGroup(
            String pattern,
            List<Participant> participants,
            List<String> pollStoreOrder,
            String primaryStore,
            String primaryPhysical
    ) {}

    record Participant(
            String storeType,
            String physicalName,
            String role,
            String accessorFqn,
            String daoMethod,
            String lagClass
    ) {}

    private HandlerWriteScenarioClassifier() {}

    static ClassifiedWriteGroup classify(List<FactBatch.DataAccessFact> writes) {
        List<FactBatch.DataAccessFact> sorted = new ArrayList<>(writes);
        sorted.sort(Comparator
                .comparing((FactBatch.DataAccessFact f) -> nullSafe(f.storeType()))
                .thenComparing(f -> nullSafe(f.tableOrEntity()))
                .thenComparing(f -> nullSafe(f.daoMethod())));

        Set<String> storeTypes = new LinkedHashSet<>();
        Set<String> physicalTables = new LinkedHashSet<>();
        for (FactBatch.DataAccessFact f : sorted) {
            if (f.storeType() != null) storeTypes.add(f.storeType().toUpperCase(Locale.ROOT));
            String physical = physicalName(f);
            if (physical != null) physicalTables.add(normalize(physical));
        }

        String pattern;
        if (storeTypes.size() > 1) {
            pattern = PATTERN_CROSS_STORE;
        } else if (physicalTables.size() > 1) {
            pattern = PATTERN_MULTI_TABLE;
        } else {
            pattern = PATTERN_DUAL_WRITE;
        }

        List<Participant> participants = new ArrayList<>();
        boolean primaryAssigned = false;
        for (FactBatch.DataAccessFact f : sorted) {
            String role = roleFor(pattern, primaryAssigned);
            if ("PRIMARY".equals(role)) {
                primaryAssigned = true;
            }
            participants.add(new Participant(
                    f.storeType(),
                    physicalName(f),
                    role,
                    f.accessorFqn(),
                    f.daoMethod(),
                    "SYNC"));
        }

        FactBatch.DataAccessFact first = sorted.get(0);
        List<String> pollOrder = sorted.stream()
                .map(FactBatch.DataAccessFact::storeType)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();

        return new ClassifiedWriteGroup(
                pattern,
                participants,
                pollOrder,
                first.storeType(),
                physicalName(first));
    }

    private static String roleFor(String pattern, boolean primaryAssigned) {
        return switch (pattern) {
            case PATTERN_CROSS_STORE -> primaryAssigned ? "SECONDARY" : "PRIMARY";
            case PATTERN_MULTI_TABLE -> primaryAssigned ? "REQUIRED_SIBLING" : "PRIMARY";
            default -> "PRIMARY";
        };
    }

    private static String physicalName(FactBatch.DataAccessFact f) {
        return f.tableOrEntity();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String normalize(String name) {
        return name.replace("_", "").toLowerCase(Locale.ROOT);
    }
}
