package io.testseer.backend.query;

/**
 * Simple glob matcher for topic short ids ({@code *} and {@code ?}).
 */
final class TopicGlobMatcher {

    private TopicGlobMatcher() {}

    static boolean matches(String pattern, String topicShortId) {
        if (pattern == null || topicShortId == null) {
            return false;
        }
        if (!pattern.contains("*") && !pattern.contains("?")) {
            return pattern.equals(topicShortId);
        }
        return topicShortId.matches(globToRegex(pattern));
    }

    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append(".");
                case '.' -> regex.append("\\.");
                case '\\' -> regex.append("\\\\");
                default -> {
                    if ("[](){}+^$|".indexOf(c) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(c);
                }
            }
        }
        regex.append('$');
        return regex.toString();
    }
}
