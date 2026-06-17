package io.testseer.backend.ingestion.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import io.testseer.backend.config.MessagingRulePack;
import io.testseer.backend.ingestion.FactBatch;
import io.testseer.backend.ingestion.ParsedModel;
import io.testseer.backend.ingestion.messaging.MessagingFactOrchestrator.SourceFile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class HttpPubSubPublishLinker {

    private static final String TRANSPORT = "HTTP_PUBSUB";
    private static final String EVIDENCE = "HTTP_PUBSUB_LINKER";

    private final ObjectMapper mapper = new ObjectMapper();
    private final JavaParser parser = new JavaParser();

    public List<FactBatch.PubSubResourceFact> link(
            List<SourceFile> javaFiles,
            List<ParsedModel> models,
            List<YamlPubSubExtractor.ConfigFile> configFiles,
            List<FactBatch.ExternalEndpointFact> endpoints,
            List<FactBatch.ExternalCallSiteFact> callSites,
            MessagingRulePack rulePack) {

        List<MessagingRulePack.HttpPubSubPublishLinkRule> rules = rulePack.httpPubSubPublishLinks();
        if (rules.isEmpty()) {
            return List.of();
        }

        Map<String, String> clientFqnBySimple = new LinkedHashMap<>();
        for (ParsedModel model : models) {
            String fqn = model.classFqn();
            if (fqn == null) continue;
            clientFqnBySimple.put(simpleName(fqn), fqn);
        }

        List<CallerSite> callers = findCallers(javaFiles, rules);
        Set<String> seen = new LinkedHashSet<>();
        List<FactBatch.PubSubResourceFact> facts = new ArrayList<>();

        for (YamlPubSubExtractor.ConfigFile file : configFiles) {
            if (!isYaml(file.path())) continue;
            EnvLaneResolver.EnvProfile env = EnvLaneResolver.resolve(file.path());
            String module = EnvLaneResolver.resolveModuleName(file.path());

            for (YamlConfigUtils.FlatMapSource source : YamlConfigUtils.expandAndFlatten(file.path(), file.content())) {
                for (MessagingRulePack.HttpPubSubPublishLinkRule rule : rules) {
                    String topicRaw = source.flat().get(rule.configTopicKey());
                    if (topicRaw == null || topicRaw.isBlank()) continue;

                    String topicShortId = YamlConfigUtils.resolveProperty(topicRaw, source.flat());
                    String uriRaw = source.flat().get(rule.configUriKey());
                    String publishUri = uriRaw != null
                            ? YamlConfigUtils.resolveProperty(uriRaw, source.flat()) : null;
                    String httpMethod = firstNonBlank(
                            source.flat().get(prefixOf(rule.configUriKey()) + ".method"),
                            "POST");
                    String clientFqn = clientFqnBySimple.get(rule.clientClass());

                    List<CallerSite> matchedCallers = callers.stream()
                            .filter(c -> c.callerPattern().equals(rule.callerPattern()))
                            .toList();

                    if (matchedCallers.isEmpty() && clientFqn != null) {
                        matchedCallers = List.of(new CallerSite(clientFqn, "callNotificationAPI", rule.callerPattern()));
                    }

                    for (CallerSite caller : matchedCallers) {
                        String dedupe = "PUBLISH|" + topicShortId + "|" + env.envLane() + "|"
                                + source.sourcePath() + "|" + caller.classFqn();
                        if (!seen.add(dedupe)) continue;

                        double confidence = caller.classFqn() != null && publishUri != null ? 0.94 : 0.80;
                        facts.add(new FactBatch.PubSubResourceFact(
                                "TOPIC",
                                topicShortId,
                                env.envLane(),
                                env.envProfile(),
                                null,
                                publishUri,
                                "PUBLISH",
                                rule.configTopicKey(),
                                source.sourcePath(),
                                module,
                                caller.classFqn(),
                                caller.methodName(),
                                EnvLaneResolver.resolveWorkloadName(module),
                                EVIDENCE,
                                confidence,
                                httpPubSubAttributes(
                                        rule, topicShortId, publishUri, httpMethod, clientFqn, caller.classFqn())
                        ));
                    }
                }
            }
        }
        return facts;
    }

    private List<CallerSite> findCallers(
            List<SourceFile> javaFiles,
            List<MessagingRulePack.HttpPubSubPublishLinkRule> rules) {
        Set<String> patterns = new LinkedHashSet<>();
        for (MessagingRulePack.HttpPubSubPublishLinkRule rule : rules) {
            if (rule.callerPattern() != null) {
                patterns.add(rule.callerPattern());
            }
        }
        if (patterns.isEmpty()) {
            return List.of();
        }

        List<CallerSite> callers = new ArrayList<>();
        for (SourceFile file : javaFiles) {
            if (file.path() == null || file.content() == null) continue;
            if (file.path().endsWith(".kt")) continue;
            ParseResult<CompilationUnit> parsed = parser.parse(file.content());
            if (parsed.getResult().isEmpty()) continue;

            for (ClassOrInterfaceDeclaration cls : parsed.getResult().get().findAll(ClassOrInterfaceDeclaration.class)) {
                if (!cls.isTopLevelType()) continue;
                String classFqn = file.parsedModel().classFqn();
                if (classFqn == null) {
                    classFqn = cls.getFullyQualifiedName().orElse(cls.getNameAsString());
                }
                for (MethodDeclaration method : cls.getMethods()) {
                    for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                        String name = call.getNameAsString();
                        if (!patterns.contains(name)) continue;
                        callers.add(new CallerSite(classFqn, method.getNameAsString(), name));
                    }
                }
            }
        }
        return callers;
    }

    private String httpPubSubAttributes(
            MessagingRulePack.HttpPubSubPublishLinkRule rule,
            String topicShortId,
            String publishUri,
            String httpMethod,
            String clientFqn,
            String callerFqn) {
        try {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("transport", TRANSPORT);
            attrs.put("topicName", topicShortId);
            if (publishUri != null) {
                attrs.put("publishUri", publishUri);
            }
            if (httpMethod != null) {
                attrs.put("httpMethod", httpMethod);
            }
            attrs.put("configKey", rule.configUriKey());
            if (clientFqn != null) {
                attrs.put("clientClass", clientFqn);
            }
            if (callerFqn != null) {
                attrs.put("callerClass", callerFqn);
            }
            if (rule.flowStep() != null) {
                attrs.put("flowStep", rule.flowStep());
            }
            if (rule.topicAliases() != null && !rule.topicAliases().isEmpty()) {
                attrs.put("topicAliases", rule.topicAliases());
            }
            return mapper.writeValueAsString(attrs);
        } catch (JsonProcessingException ex) {
            return "{\"transport\":\"HTTP_PUBSUB\"}";
        }
    }

    private static String prefixOf(String dottedKey) {
        int dot = dottedKey.lastIndexOf('.');
        return dot > 0 ? dottedKey.substring(0, dot) : dottedKey;
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static boolean isYaml(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yaml") || lower.endsWith(".yml");
    }

    private record CallerSite(String classFqn, String methodName, String callerPattern) {}
}
