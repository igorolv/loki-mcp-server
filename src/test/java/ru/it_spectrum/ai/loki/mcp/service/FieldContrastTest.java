package ru.it_spectrum.ai.loki.mcp.service;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.loki.mcp.model.LogEvent;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fields that set a summary group apart, on the window and background of asva2-dev-contrast-*.
 */
class FieldContrastTest {
    private static final List<String> SERVICE_LABELS = List.of("applicationName", "instance", "container");
    private static final List<String> PACKAGES = List.of("ru.it_spectrum.asv", "ru.it_spectrum.core");
    private final EventNormalizer normalizer = new EventNormalizer();

    private List<FieldContrast.Line> background(List<LogEvent> events) {
        var lines = new ArrayList<FieldContrast.Line>();
        for (var event : events) {
            var view = normalizer.view(event, SERVICE_LABELS);
            if (view.service() != null && FieldContrast.background(view))
                lines.add(new FieldContrast.Line(view.service(), FieldContrast.fields(event, normalizer)));
        }
        return lines;
    }

    private Map<String, Map<String, Set<String>>> values(List<LogEvent> events) {
        var values = new HashMap<String, Map<String, Set<String>>>();
        for (var event : events)
            for (var field : FieldContrast.fields(event, normalizer).entrySet())
                values.computeIfAbsent(normalizer.view(event, SERVICE_LABELS).service(), k -> new HashMap<>())
                        .computeIfAbsent(field.getKey(), k -> new HashSet<>()).add(field.getValue());
        return values;
    }

    private Map<String, List<String>> findings(List<LogEvent> window, List<LogEvent> background) {
        var result = new LinkedHashMap<String, List<String>>();
        var lines = background(background);
        var values = values(window);
        for (var group : LogSummary.group(window, normalizer, SERVICE_LABELS, PACKAGES))
            result.put(group.template.split("\n")[0], FieldContrast.findings(group, normalizer, lines, values));
        return result;
    }

    private static List<String> of(Map<String, List<String>> findings, String part) {
        return findings.entrySet().stream().filter(e -> e.getKey().contains(part)).findFirst().orElseThrow().getValue();
    }

    @Test
    void theWindowOfTheFixtureNamesThePodOfTheNewBuildAndTheUser() {
        var findings = findings(Fixtures.events("asva2-dev-contrast-window.jsonl"), Fixtures.events("asva2-dev-contrast-background.jsonl"));
        // The task no worker was found for: every line on the one pod of build 2799, which holds 7% of the other lines.
        var task = of(findings, "DeleteDraftUploadsDelegate");
        assertEquals(1, task.size(), task.toString());
        assertTrue(task.getFirst().startsWith("         all 6 lines: pod ssj-main-asv-…p-connect-76566db777-gbwck (7% in other lines of ssj-main), "
                + "node_name k8s-node3.example.internal (15%), build.version 2799 (43%), "), task.getFirst());
        assertTrue(task.getFirst().endsWith(" (+2 more)"), task.getFirst());
        // One user in its own thread; the field is rare in other lines but varies there.
        var upload = of(findings, "Загрузка файлов невозможна");
        assertEquals("         all 5 lines: userId 1002 (rare in other lines of ssj-main), process.thread.name Информация по загрузке (0%), "
                + "service.name ssj-ui-backend (54%)", upload.getFirst());
        assertEquals("         3 of 5 lines: pod ssj-main-asv-…p-backend-8588ff8446-gg7fd (1% in other lines of ssj-main), "
                + "node_name k8s-node5.example.internal (2%)", upload.get(1));
        // The build of a PR stand does not vary in its lines, so its being rare in other lines says nothing.
        assertFalse(of(findings, "checkSeverity=CRITICAL_ERROR").toString().contains("rare"), of(findings, "checkSeverity=CRITICAL_ERROR").toString());
        // Groups of fewer than 3 lines have none.
        assertTrue(of(findings, "inStream parameter is null").isEmpty());
        assertTrue(findings.values().stream().allMatch(f -> f.size() <= FieldContrast.FINDINGS));
    }

    @Test
    void plainTextLinesAndTooFewOtherLinesTellNothing() {
        var window = new ArrayList<LogEvent>();
        for (int i = 0; i < 3; i++)
            window.add(new LogEvent(Integer.toString(i), Map.of("instance", "a", "pod", "a-1"),
                    "{\"log\":{\"level\":\"ERROR\"},\"build\":{\"version\":\"7\"},\"message\":\"Failed to store the document " + i + "\"}", Map.of()));
        var plain = new ArrayList<LogEvent>();
        for (int i = 0; i < 25; i++) plain.add(new LogEvent(Integer.toString(i), Map.of("instance", "a", "pod", i < 20 ? "a-2" : "a-1"), "INFO started " + i, Map.of()));
        var findings = findings(window, plain);
        // The pod is a label every line carries; the build is carried by no other line and does not vary.
        assertEquals(List.of("         all 3 lines: pod a-1 (20% in other lines of a)"), findings.values().iterator().next());
        assertTrue(findings(window, plain.subList(0, 19)).values().iterator().next().isEmpty());
    }

    @Test
    void longValuesKeepTheirEnd() {
        assertEquals("ssj-main-asv-…p-connect-76566db777-gbwck", FieldContrast.middle("ssj-main-asv-app-connect-76566db777-gbwck"));
        assertEquals("short", FieldContrast.middle("short"));
        var slices = FieldContrast.slices(new QueryTime.Range(java.time.Instant.EPOCH, java.time.Instant.EPOCH.plusSeconds(1200)));
        assertEquals(FieldContrast.SLICES, slices.size());
        assertEquals(java.time.Instant.EPOCH.plusSeconds(100), slices.getFirst().end());
        assertEquals(java.time.Instant.EPOCH.plusSeconds(1200), slices.getLast().end());
    }
}
