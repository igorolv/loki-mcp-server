package ru.it_spectrum.ai.loki.mcp.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A Java stack trace as {@code Throwable.printStackTrace} writes it: sections ({@code Type: message} header, frames,
 * {@code ... N more}) chained by {@code Caused by:} from the outermost exception to the root cause, {@code Suppressed:}
 * sections aside. The logstash root-cause-first form ({@code Wrapped by:}) is accepted too. Messages keep their
 * continuation lines; frames keep their class, method and location. Nothing is guessed: a text without a header is a
 * trace without sections.
 */
public record StackTrace(List<Section> sections) {
    public enum Kind {OUTER, CAUSED_BY, SUPPRESSED, WRAPPED_BY}

    public record Frame(String className, String method, String location) {
        /**
         * {@code Class.method(File.java:12)} with lambda and CGLIB decorations removed: {@code lambda$find$1} is
         * {@code find}, {@code Service$$SpringCGLIB$$0} is {@code Service}.
         */
        public String plainClassName() {
            int proxy = className.indexOf("$$");
            return proxy > 0 ? className.substring(0, proxy) : className;
        }

        public String plainMethod() {
            var matcher = LAMBDA.matcher(method);
            return matcher.matches() ? matcher.group(1) : method;
        }

        public String text() {
            return plainClassName() + "." + plainMethod() + "(" + location + ")";
        }
    }

    public static final class Section {
        final Kind kind;
        final String type;
        final StringBuilder message = new StringBuilder();
        final List<Frame> frames = new ArrayList<>();
        int omitted;

        Section(Kind kind, String type, String message) {
            this.kind = kind;
            this.type = type;
            this.message.append(message);
        }

        public Kind kind() {
            return kind;
        }

        /**
         * Fully qualified exception class, or null when the header had no class-like token.
         */
        public String type() {
            return type;
        }

        public String simpleType() {
            if (type == null) return null;
            return type.substring(type.lastIndexOf('.') + 1);
        }

        public String message() {
            return message.toString();
        }

        public String firstMessageLine() {
            String text = message();
            int newline = text.indexOf('\n');
            return (newline < 0 ? text : text.substring(0, newline)).strip();
        }

        public List<Frame> frames() {
            return frames;
        }

        public int omitted() {
            return omitted;
        }
    }

    public StackTrace {
        sections = List.copyOf(sections);
    }

    private static final Pattern FRAME = Pattern.compile("at (?:[\\w.\\-]+/)?([\\w$.]+)\\.([\\w$<>]+)\\(([^)]*)\\).*");
    private static final Pattern OMITTED = Pattern.compile("\\.\\.\\. (\\d+) (?:more|common frames omitted).*");
    private static final Pattern TYPE = Pattern.compile("([a-zA-Z_$][\\w$]*(?:\\.[a-zA-Z_$][\\w$]*)+)(?::\\s?(.*))?", Pattern.DOTALL);
    private static final Pattern LAMBDA = Pattern.compile("lambda\\$([\\w<>]+)\\$\\d+");

    public static StackTrace parse(String text) {
        var sections = new ArrayList<Section>();
        Section current = null;
        for (String raw : text.split("\r?\n")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            var frame = FRAME.matcher(line);
            if (frame.matches()) {
                if (current != null) current.frames.add(new Frame(frame.group(1), frame.group(2), frame.group(3)));
                continue;
            }
            var omitted = OMITTED.matcher(line);
            if (omitted.matches()) {
                if (current != null) current.omitted += Integer.parseInt(omitted.group(1));
                continue;
            }
            Kind kind = null;
            String header = line;
            if (line.startsWith("Caused by: ")) {
                kind = Kind.CAUSED_BY;
                header = line.substring("Caused by: ".length());
            } else if (line.startsWith("Suppressed: ")) {
                kind = Kind.SUPPRESSED;
                header = line.substring("Suppressed: ".length());
            } else if (line.startsWith("Wrapped by: ")) {
                kind = Kind.WRAPPED_BY;
                header = line.substring("Wrapped by: ".length());
            } else if (current == null || (current.frames.isEmpty() && current.omitted == 0)) {
                // The first line, or a continuation of a multi-line message before its frames.
                if (current == null) kind = Kind.OUTER;
            } else if (TYPE.matcher(line).matches() && !line.contains(" ")) {
                // A bare class after frames (some formatters omit the "Caused by:" prefix).
                kind = Kind.CAUSED_BY;
            }
            if (kind == null) {
                if (current != null) current.message.append('\n').append(raw.stripTrailing());
                continue;
            }
            var typed = TYPE.matcher(header);
            current = typed.matches() ? new Section(kind, typed.group(1), typed.group(2) == null ? "" : typed.group(2))
                    : new Section(kind, null, header);
            sections.add(current);
        }
        return new StackTrace(sections);
    }

    /**
     * The exception that started it: the last {@code Caused by:}, or the first section when the trace is written
     * root-first ({@code Wrapped by:}) or has no causes. Suppressed sections never count.
     */
    public Section root() {
        if (sections.isEmpty()) return null;
        if (sections.stream().anyMatch(s -> s.kind == Kind.WRAPPED_BY)) return sections.getFirst();
        Section root = sections.getFirst();
        for (Section section : sections) if (section.kind == Kind.CAUSED_BY) root = section;
        return root;
    }

    /**
     * Exceptions that wrapped the root, outermost first; empty when the root is the only one.
     */
    public List<Section> wrappers() {
        var result = new ArrayList<Section>();
        Section root = root();
        for (Section section : sections)
            if (section != root && section.kind != Kind.SUPPRESSED) result.add(section);
        // Root-first form lists the wrappers innermost first.
        if (root != null && root.kind == Kind.OUTER && !result.isEmpty() && result.getFirst().kind == Kind.WRAPPED_BY)
            java.util.Collections.reverse(result);
        return result;
    }
}
