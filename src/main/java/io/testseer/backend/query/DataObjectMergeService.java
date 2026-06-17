package io.testseer.backend.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testseer.backend.config.MessagingRulePack;
import io.testseer.backend.config.MessagingRulePackLoader;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Phase 5: overlay rule pack hints and DDL validation on data access views at query time. */
@Service
public class DataObjectMergeService {

    private final MessagingRulePack rulePack;
    private final DataObjectValidationService validationService;
    private final ObjectMapper mapper = new ObjectMapper();

    public DataObjectMergeService(
            MessagingRulePackLoader rulePackLoader,
            DataObjectValidationService validationService) {
        this.rulePack = rulePackLoader.getRulePack();
        this.validationService = validationService;
    }

    public List<MessagingFlowService.DataAccessView> mergeAll(
            String orgId, List<MessagingFlowService.DataAccessView> views) {
        if (views == null || views.isEmpty()) return List.of();
        List<MessagingFlowService.DataAccessView> merged = new ArrayList<>(views.size());
        for (MessagingFlowService.DataAccessView view : views) {
            merged.add(merge(orgId, view));
        }
        return merged;
    }

    public MessagingFlowService.DataAccessView merge(String orgId, MessagingFlowService.DataAccessView view) {
        MessagingRulePack.DataObjectRule rule = findRule(view);
        String physicalName = firstNonBlank(
                rule != null ? rule.physicalName() : null, view.tableOrEntity());
        String storeType = firstNonBlank(
                rule != null ? rule.storeType() : null, view.storeType());
        String entityFqn = firstNonBlank(
                rule != null ? rule.entityFqn() : null, view.entityFqn());
        String domainFqn = firstNonBlank(
                rule != null ? rule.domainFqn() : null, view.domainFqn());
        String accessorFqn = firstNonBlank(
                rule != null ? rule.accessorFqn() : null, view.accessorFqn());
        String catalogRef = view.catalogRef();
        String correlationKeys = view.correlationKeys();
        if (rule != null && rule.correlationKeys() != null && !rule.correlationKeys().isEmpty()) {
            correlationKeys = toJson(rule.correlationKeys());
        }

        String validationKind = validationService.validate(orgId, storeType, physicalName, catalogRef);
        double confidence = view.confidence();
        if ("DDL_CONFIRMED".equals(validationKind)) {
            confidence = Math.min(1.0, confidence + 0.05);
        }

        String evidence = view.evidenceSource();
        if (rule != null) {
            evidence = evidence != null && !evidence.isBlank()
                    ? evidence + "+RULE_PACK"
                    : "RULE_PACK";
        }

        String pollHint = rule != null ? rule.pollHint() : null;
        String flowSteps = rule != null ? toJson(rule.flowSteps()) : null;

        return new MessagingFlowService.DataAccessView(
                view.handlerClassFqn(),
                view.handlerMethod(),
                view.operation(),
                physicalName != null ? physicalName : view.tableOrEntity(),
                view.daoMethod(),
                correlationKeys,
                evidence,
                confidence,
                storeType,
                entityFqn,
                domainFqn,
                accessorFqn,
                view.accessorKind(),
                catalogRef,
                view.secondaryStores(),
                physicalName,
                pollHint,
                validationKind,
                flowSteps,
                view.consistencyHints() != null ? view.consistencyHints() : List.of()
        );
    }

    private MessagingRulePack.DataObjectRule findRule(MessagingFlowService.DataAccessView view) {
        Map<String, MessagingRulePack.DataObjectRule> rules = rulePack.dataObjects();
        if (rules.isEmpty()) return null;

        if (view.entityFqn() != null) {
            for (MessagingRulePack.DataObjectRule rule : rules.values()) {
                if (view.entityFqn().equals(rule.entityFqn())) return rule;
            }
        }
        if (view.tableOrEntity() != null) {
            MessagingRulePack.DataObjectRule byTable = rules.get(view.tableOrEntity());
            if (byTable != null) return byTable;
            for (Map.Entry<String, MessagingRulePack.DataObjectRule> e : rules.entrySet()) {
                if (matchesPhysical(e.getKey(), view.tableOrEntity())
                        || matchesPhysical(e.getValue().physicalName(), view.tableOrEntity())) {
                    return e.getValue();
                }
            }
        }
        if (view.daoMethod() != null && view.accessorFqn() != null) {
            for (MessagingRulePack.DataObjectRule rule : rules.values()) {
                if (rule.methods() != null
                        && rule.methods().contains(view.daoMethod())
                        && rule.accessorFqn() != null
                        && view.accessorFqn().endsWith(simpleName(rule.accessorFqn()))) {
                    return rule;
                }
            }
        }
        return null;
    }

    private static boolean matchesPhysical(String candidate, String physicalName) {
        if (candidate == null || physicalName == null) return false;
        return candidate.equalsIgnoreCase(physicalName);
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) return preferred;
        return fallback;
    }

    private String toJson(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        try {
            return mapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }
}
