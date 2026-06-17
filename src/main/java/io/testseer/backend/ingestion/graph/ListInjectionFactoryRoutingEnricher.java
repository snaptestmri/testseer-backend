package io.testseer.backend.ingestion.graph;

import io.testseer.backend.ingestion.ParsedModel;

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
 * Detects Spring factories that register {@code List<Strategy>} beans into a {@code Map}
 * via {@code Collectors.toMap(...::getAdapterName)} (partner-adapter pattern).
 */
public final class ListInjectionFactoryRoutingEnricher {

    private static final Pattern LIST_PARAM = Pattern.compile("List<([^>]+)>");
    private static final Pattern COLLECT_TO_MAP = Pattern.compile("Collectors\\.toMap\\s*\\(");
    private static final Pattern ADAPTER_NAME_REF = Pattern.compile("::getAdapterName");
    private static final Pattern CONSTANT_ADAPTER_NAME =
            Pattern.compile("ADAPTER_NAME\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern RETURN_CONSTANT =
            Pattern.compile("return\\s+(\\w+)\\.ADAPTER_NAME");

    private ListInjectionFactoryRoutingEnricher() {}

    public static List<ParsedModel.FactoryRoutingDef> enrich(
            List<ParsedModel> models,
            Map<String, String> sourceByClassFqn) {
        List<ParsedModel.FactoryRoutingDef> all = new ArrayList<>();
        enrichByFactory(models, sourceByClassFqn).values().forEach(all::addAll);
        return all;
    }

    /** Routes keyed by factory FQN (for graph projection). */
    public static Map<String, List<ParsedModel.FactoryRoutingDef>> enrichByFactory(
            List<ParsedModel> models,
            Map<String, String> sourceByClassFqn) {

        Map<String, ParsedModel> byFqn = indexByFqn(models);
        Map<String, List<ParsedModel.FactoryRoutingDef>> byFactory = new LinkedHashMap<>();

        for (ParsedModel model : models) {
            if (model.classFqn() == null || model.parseError()) {
                continue;
            }
            String source = sourceByClassFqn != null ? sourceByClassFqn.get(model.classFqn()) : null;
            if (source == null || !COLLECT_TO_MAP.matcher(source).find()
                    || !ADAPTER_NAME_REF.matcher(source).find()) {
                continue;
            }
            String elementType = listElementType(model);
            if (elementType == null) {
                continue;
            }
            String selectorMethod = findSelectorMethod(model, source);
            List<ParsedModel.FactoryRoutingDef> routes = new ArrayList<>();
            for (String implementorFqn : findImplementors(elementType, byFqn, sourceByClassFqn)) {
                String implementorSource = sourceByClassFqn != null
                        ? sourceByClassFqn.get(implementorFqn) : null;
                routes.add(new ParsedModel.FactoryRoutingDef(
                        selectorMethod,
                        "String",
                        inferRoutingKey(implementorFqn, implementorSource),
                        null,
                        implementorFqn,
                        false));
            }
            if (!routes.isEmpty()) {
                byFactory.put(model.classFqn(), routes);
            }
        }
        return byFactory;
    }

    private static Map<String, ParsedModel> indexByFqn(List<ParsedModel> models) {
        Map<String, ParsedModel> byFqn = new LinkedHashMap<>();
        for (ParsedModel model : models) {
            if (model.classFqn() != null) {
                byFqn.put(model.classFqn(), model);
            }
        }
        return byFqn;
    }

    private static String listElementType(ParsedModel factory) {
        for (String param : factory.constructorParamTypes()) {
            Matcher matcher = LIST_PARAM.matcher(param);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }
        return null;
    }

    private static String findSelectorMethod(ParsedModel model, String source) {
        for (ParsedModel.MethodDef method : model.publicMethods()) {
            if (method.name() != null && source.contains(method.name() + "(")
                    && source.contains(".get(")) {
                return method.name();
            }
        }
        return "getProcessor";
    }

    private static Set<String> findImplementors(
            String elementType,
            Map<String, ParsedModel> byFqn,
            Map<String, String> sourceByClassFqn) {

        String elementSimple = simpleName(elementType);
        Set<String> implementors = new LinkedHashSet<>();

        for (ParsedModel model : byFqn.values()) {
            if (model.classFqn() == null) {
                continue;
            }
            String source = sourceByClassFqn != null ? sourceByClassFqn.get(model.classFqn()) : null;
            if (isAbstractAdapterBase(model.classFqn(), source)) {
                continue;
            }
            if (implementsType(model, elementType, elementSimple)) {
                implementors.add(model.classFqn());
                continue;
            }
            if (source != null && source.contains("implements " + elementSimple)) {
                implementors.add(model.classFqn());
                continue;
            }
            if (source != null && "BaseAdapter".equals(elementSimple)
                    && source.contains("extends OfferBaseAdapter")) {
                implementors.add(model.classFqn());
            }
        }
        return implementors;
    }

    private static boolean isAbstractAdapterBase(String classFqn, String source) {
        if (classFqn != null && classFqn.endsWith(".OfferBaseAdapter")) {
            return true;
        }
        return source != null && source.contains("abstract class");
    }

    private static boolean implementsType(ParsedModel model, String elementType, String elementSimple) {
        for (String iface : model.implementedInterfaces()) {
            if (iface.equals(elementType) || iface.endsWith("." + elementSimple)) {
                return true;
            }
        }
        return false;
    }

    static String inferRoutingKey(String classFqn, String source) {
        if (source != null) {
            Matcher retConst = RETURN_CONSTANT.matcher(source);
            if (retConst.find()) {
                Matcher constVal = CONSTANT_ADAPTER_NAME.matcher(source);
                if (constVal.find()) {
                    return constVal.group(1);
                }
            }
            if (source.contains("getClass().getSimpleName()") || source.contains("getClass().getSimpleName()")) {
                return simpleName(classFqn);
            }
        }
        return simpleName(classFqn);
    }

    private static String simpleName(String fqn) {
        if (fqn == null) {
            return null;
        }
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }
}
