package ru.it_spectrum.ai.loki.mcp.service;

import ru.it_spectrum.ai.loki.mcp.connection.LogRule;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies a connection's rules catalogue to a line: the first rule whose patterns all match wins. {@code message} is
 * searched in the logged message, then the root cause message, then the wrapper messages. The code knows only this
 * engine; what the lines of a stand mean lives in its rules file.
 */
public final class LogRules {
    /**
     * Patterns are searched in the head of the logged message only: a 30 KB SQL text is not worth a regex pass.
     */
    static final int MESSAGE_CHARS = 2000;

    private LogRules() {
    }

    /**
     * A rule that matched a line, with {@code ${name}} placeholders already filled from that line.
     */
    public record Match(LogRule rule, String subject, String advice) {
        public LogRule.Category category() {
            return rule.category();
        }

        /**
         * {@code [dependency: SMEV]}, {@code [startup]}.
         */
        public String tag() {
            return "[" + rule.category().text() + (subject == null ? "" : ": " + subject) + "]";
        }
    }

    public static Match match(List<LogRule> rules, EventNormalizer.View view, ErrorSignature signature) {
        if (rules.isEmpty()) return null;
        String message = view.message() == null ? "" : view.message();
        if (message.length() > MESSAGE_CHARS) message = message.substring(0, MESSAGE_CHARS);
        for (var rule : rules) {
            if (rule.exception() != null && !exceptionMatches(rule.exception(), signature)) continue;
            if (rule.logger() != null && (view.logger() == null || !rule.logger().matcher(view.logger()).find())) continue;
            Matcher matched = null;
            if (rule.message() != null) {
                matched = find(rule.message(), message);
                if (matched == null && signature != null) matched = find(rule.message(), signature.rootMessage());
                if (signature != null)
                    for (int i = 0; matched == null && i < signature.wrapperMessages().size(); i++)
                        matched = find(rule.message(), signature.wrapperMessages().get(i));
                if (matched == null) continue;
            }
            return new Match(rule, LogRule.expand(rule.subject(), matched), LogRule.expand(rule.advice(), matched));
        }
        return null;
    }

    private static boolean exceptionMatches(Pattern pattern, ErrorSignature signature) {
        if (signature == null) return false;
        if (pattern.matcher(signature.rootType()).find()) return true;
        for (String wrapper : signature.wrappers()) if (pattern.matcher(wrapper).find()) return true;
        return false;
    }

    private static Matcher find(Pattern pattern, String text) {
        if (text == null) return null;
        var matcher = pattern.matcher(text);
        return matcher.find() ? matcher : null;
    }
}
