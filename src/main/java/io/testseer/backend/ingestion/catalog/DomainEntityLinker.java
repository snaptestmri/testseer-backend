package io.testseer.backend.ingestion.catalog;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses domain ↔ entity mapper methods from DAO impl bodies. */
final class DomainEntityLinker {

    private static final Pattern MAP_DOMAIN_TO_ENTITY =
            Pattern.compile("(\\w+Entity)\\s+mapDomainToEntity\\s*\\(\\s*(\\w+)\\s+\\w+\\s*\\)");
    private static final Pattern MAP_ENTITY_TO_DOMAIN =
            Pattern.compile("(\\w+)\\s+mapEntityToDomain\\s*\\(\\s*(\\w+Entity)\\s+\\w+\\s*\\)");

    private DomainEntityLinker() {}

    static Map<String, String> parseDomainToEntity(String content, ImportIndex imports) {
        Map<String, String> domainToEntity = new LinkedHashMap<>();
        Matcher m = MAP_DOMAIN_TO_ENTITY.matcher(content);
        while (m.find()) {
            String entityFqn = imports.resolve(m.group(1));
            String domainFqn = imports.resolve(m.group(2));
            if (domainFqn != null && entityFqn != null) {
                domainToEntity.put(domainFqn, entityFqn);
            }
        }
        Matcher em = MAP_ENTITY_TO_DOMAIN.matcher(content);
        while (em.find()) {
            String domainFqn = imports.resolve(em.group(1));
            String entityFqn = imports.resolve(em.group(2));
            if (domainFqn != null && entityFqn != null) {
                domainToEntity.put(domainFqn, entityFqn);
            }
        }
        return domainToEntity;
    }
}
