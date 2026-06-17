package io.testseer.backend.ingestion.testcalls;

import io.testseer.backend.ingestion.contract.OpenApiSpecParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts HTTP method + path pairs from riq-qa-REST-Assured Java sources.
 */
public final class RestAssuredHttpCallExtractor {

    private static final Pattern REST_VERB_LITERAL = Pattern.compile(
            "(?i)\\.(get|post|put|patch|delete|head|options)\\(\\s*\"([^\"]+)\"");

    private static final Pattern DIAGNOSTICS_VERB_LITERAL = Pattern.compile(
            "(?i)RestAssuredDiagnostics\\.(get|post|put|patch|delete)\\([^,]+,\\s*(?:APIConstant\\.)?(\\w+|\"[^\"]+\")");

    private static final Pattern EXECUTE_LENIENT = Pattern.compile(
            "(?i)executeLenient\\([^,]+,\\s*\"(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\"\\s*,\\s*([^,)]+)");

    private static final Pattern API_CONSTANT = Pattern.compile(
            "public\\s+static\\s+final\\s+String\\s+(\\w+)\\s*=\\s*\"([^\"]+)\";");

    private static final Pattern REST_API_REQUEST = Pattern.compile(
            "(?i)restAPIRequest(?:Generic)?\\([^,]+,\\s*[^,]+,\\s*(?:APIConstant\\.)?(\\w+|\"[^\"]+\")\\s*,[^;]*?\"(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\"");

    private RestAssuredHttpCallExtractor() {}

    public record ExtractedCall(
            String httpMethod,
            String path,
            String pathNormalized,
            String pathConstantRef,
            String evidenceSource
    ) {}

    public static Map<String, String> extractPathConstants(List<String> fileContents) {
        Map<String, String> constants = new LinkedHashMap<>();
        for (String content : fileContents) {
            if (content == null) continue;
            Matcher matcher = API_CONSTANT.matcher(content);
            while (matcher.find()) {
                String path = matcher.group(2);
                if (looksLikePath(path)) {
                    constants.putIfAbsent(matcher.group(1), path);
                }
            }
        }
        return constants;
    }

    public static List<ExtractedCall> extractFromFile(String content, Map<String, String> pathConstants) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<ExtractedCall> calls = new ArrayList<>();

        addMatches(content, REST_VERB_LITERAL, 1, 2, "REST_ASSURED_VERB", pathConstants, seen, calls);
        addDiagnosticsMatches(content, pathConstants, seen, calls);
        addExecuteLenientMatches(content, pathConstants, seen, calls);
        addRestApiRequestMatches(content, pathConstants, seen, calls);

        return List.copyOf(calls);
    }

    private static void addDiagnosticsMatches(
            String content,
            Map<String, String> pathConstants,
            Set<String> seen,
            List<ExtractedCall> calls) {

        Matcher matcher = DIAGNOSTICS_VERB_LITERAL.matcher(content);
        while (matcher.find()) {
            String method = matcher.group(1).toUpperCase(Locale.ROOT);
            String pathToken = matcher.group(2).trim();
            resolveAndAdd(method, pathToken, "REST_ASSURED_DIAGNOSTICS", pathConstants, seen, calls);
        }
    }

    private static void addExecuteLenientMatches(
            String content,
            Map<String, String> pathConstants,
            Set<String> seen,
            List<ExtractedCall> calls) {

        Matcher matcher = EXECUTE_LENIENT.matcher(content);
        while (matcher.find()) {
            String method = matcher.group(1).toUpperCase(Locale.ROOT);
            String pathToken = matcher.group(2).trim();
            resolveAndAdd(method, pathToken, "REST_ASSURED_EXECUTE_LENIENT", pathConstants, seen, calls);
        }
    }

    private static void addRestApiRequestMatches(
            String content,
            Map<String, String> pathConstants,
            Set<String> seen,
            List<ExtractedCall> calls) {

        Matcher matcher = REST_API_REQUEST.matcher(content);
        while (matcher.find()) {
            String method = matcher.group(2).toUpperCase(Locale.ROOT);
            String pathToken = matcher.group(1).trim();
            resolveAndAdd(method, pathToken, "REST_ASSURED_HELPER", pathConstants, seen, calls);
        }
    }

    private static void addMatches(
            String content,
            Pattern pattern,
            int methodGroup,
            int pathGroup,
            String evidence,
            Map<String, String> pathConstants,
            Set<String> seen,
            List<ExtractedCall> calls) {

        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String method = matcher.group(methodGroup).toUpperCase(Locale.ROOT);
            String path = matcher.group(pathGroup);
            addCall(method, path, null, evidence, seen, calls);
        }
    }

    private static void resolveAndAdd(
            String method,
            String pathToken,
            String evidence,
            Map<String, String> pathConstants,
            Set<String> seen,
            List<ExtractedCall> calls) {

        if (pathToken.startsWith("\"") && pathToken.endsWith("\"")) {
            addCall(method, pathToken.substring(1, pathToken.length() - 1), null, evidence, seen, calls);
            return;
        }
        String constantName = pathToken.startsWith("APIConstant.")
                ? pathToken.substring("APIConstant.".length())
                : pathToken;
        String path = pathConstants.get(constantName);
        if (path != null) {
            addCall(method, path, constantName, evidence, seen, calls);
        }
    }

    private static void addCall(
            String method,
            String path,
            String constantRef,
            String evidence,
            Set<String> seen,
            List<ExtractedCall> calls) {

        if (!looksLikePath(path)) {
            return;
        }
        String normalized = normalizeTestPath(path);
        String key = method + "|" + normalized + "|" + evidence;
        if (!seen.add(key)) {
            return;
        }
        calls.add(new ExtractedCall(method, path, normalized, constantRef, evidence));
    }

    static String normalizeTestPath(String path) {
        if (path == null || path.isBlank()) {
            return OpenApiSpecParser.normalizePath("/");
        }
        String withSlash = path.startsWith("/") ? path : "/" + path;
        return OpenApiSpecParser.normalizePath(withSlash);
    }

    private static boolean looksLikePath(String path) {
        return path != null
                && !path.isBlank()
                && (path.startsWith("/") || path.contains("/") || path.contains("{"));
    }
}
