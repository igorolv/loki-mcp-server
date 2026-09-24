package ru.it_spectrum.ai.loki.mcp.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What a stack trace says once the wrappers are peeled off: the root exception, its message, the nearest frame of
 * the application's own code and the chain of wrappers. Two lines with the same signature are one failure.
 *
 * @param rootType        simple class name of the root cause
 * @param rootMessage     first line of the root cause message, as logged
 * @param appFrame        the application frame closest to the throw site, or null when the trace has none
 * @param wrappers        simple class names of the exceptions that wrapped the root, outermost first
 * @param wrapperMessages first message lines of those wrappers, in the same order (a URL or a recipient is often there)
 */
public record ErrorSignature(String rootType, String rootMessage, StackTrace.Frame appFrame, List<String> wrappers,
                             List<String> wrapperMessages) {
    public ErrorSignature {
        wrappers = List.copyOf(wrappers);
        wrapperMessages = List.copyOf(wrapperMessages);
    }

    public static ErrorSignature of(String stackTrace, List<String> applicationPackages) {
        return of(stackTrace, applicationPackages, List.of());
    }

    /**
     * Null when the text has no exception header. {@code ignoredFrames} are matched against {@code Class.method} of an
     * application frame; a match is never the application frame.
     */
    public static ErrorSignature of(String stackTrace, List<String> applicationPackages, List<Pattern> ignoredFrames) {
        var trace = StackTrace.parse(stackTrace);
        var root = trace.root();
        if (root == null || root.type() == null) return null;
        var frame = applicationFrame(root, applicationPackages, ignoredFrames);
        // No application frame under the root: take the wrapper nearest to it (innermost first).
        var outward = trace.wrappers().reversed();
        if (frame == null)
            for (var wrapper : outward) if ((frame = applicationFrame(wrapper, applicationPackages, ignoredFrames)) != null) break;
        var wrappers = new ArrayList<String>();
        var messages = new ArrayList<String>();
        for (var wrapper : trace.wrappers()) {
            if (wrapper.simpleType() == null) continue;
            wrappers.add(wrapper.simpleType());
            messages.add(wrapper.firstMessageLine());
        }
        return new ErrorSignature(root.simpleType(), root.firstMessageLine(), frame, wrappers, messages);
    }

    /**
     * The first frame (nearest to the throw site) whose class is in one of the application packages and which no ignored
     * pattern matches.
     */
    static StackTrace.Frame applicationFrame(StackTrace.Section section, List<String> packages, List<Pattern> ignoredFrames) {
        for (var frame : section.frames()) {
            if (ignored(frame, ignoredFrames)) continue;
            for (String prefix : packages)
                if (frame.className().equals(prefix) || frame.className().startsWith(prefix + ".")) return frame;
        }
        return null;
    }

    private static boolean ignored(StackTrace.Frame frame, List<Pattern> ignoredFrames) {
        String name = frame.plainClassName() + "." + frame.plainMethod();
        for (var pattern : ignoredFrames) if (pattern.matcher(name).find()) return true;
        return false;
    }

    /**
     * Grouping key: root type, root message with identifiers normalized, application class and method (no line number,
     * which changes between builds of the same bug).
     */
    public String key() {
        return rootType + ": " + LogSummary.normalize(rootMessage)
                + (appFrame == null ? "" : "\n at " + appFrame.plainClassName() + "." + appFrame.plainMethod());
    }
}
