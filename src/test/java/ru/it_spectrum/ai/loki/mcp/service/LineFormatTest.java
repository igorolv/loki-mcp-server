package ru.it_spectrum.ai.loki.mcp.service;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.loki.mcp.connection.ConnectionsLoader;
import ru.it_spectrum.ai.loki.mcp.connection.LineFormat;
import ru.it_spectrum.ai.loki.mcp.connection.LogRule;
import ru.it_spectrum.ai.loki.mcp.model.LogEvent;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain Spring Boot console lines split by the format of examples/java-rules.json; the code knows no layout.
 */
class LineFormatTest {
    private static final ConnectionsLoader.Catalogue JAVA = ConnectionsLoader.loadCatalogue(Path.of("examples/java-rules.json"));
    private final EventNormalizer normalizer = new EventNormalizer(JAVA.formats());
    // Spring Boot 3 (no application name), 3.4+ (application name before the thread) and 2 (a space instead of T).
    private static final String BOOT3 = "2026-09-24T15:10:16.432+03:00  WARN 1 --- [           main] ConfigServletWebServerApplicationContext : "
            + "Exception encountered during context initialization - cancelling refresh attempt";
    private static final String BOOT34 = "2026-09-24T12:27:01.057Z  INFO 1 --- [config-server] [nio-8888-exec-6] o.s.c.c.s.e.NativeEnvironmentRepository  : "
            + "Adding property source: Config resource 'file [/tmp/config-repo-17/dev/application.yaml]'";
    private static final String BOOT2 = "2021-03-01 10:00:00.123 ERROR 12345 --- [scheduling-1] c.e.orders.OrderJob : Order 17 failed";

    private static LogEvent event(String line) {
        return new LogEvent("1", Map.of("instance", "app-main"), line, Map.of());
    }

    @Test
    void theFormatSplitsLevelLoggerThreadAndMessage() {
        var view = normalizer.view(event(BOOT3), List.of("instance"));
        assertEquals(EventNormalizer.Format.PLAIN, view.format());
        assertEquals("WARN", view.level());
        assertEquals("ConfigServletWebServerApplicationContext", view.logger());
        assertEquals("Exception encountered during context initialization - cancelling refresh attempt", view.message());
        assertEquals("app-main", view.service());
        assertTrue(view.jsonFields().isEmpty());
        var boot34 = normalizer.view(event(BOOT34), List.of("instance"));
        assertEquals("o.s.c.c.s.e.NativeEnvironmentRepository", boot34.logger());
        assertTrue(boot34.message().startsWith("Adding property source: "), boot34.message());
        var boot2 = normalizer.view(event(BOOT2), List.of("instance"));
        assertEquals("ERROR", boot2.level());
        assertEquals("Order 17 failed", boot2.message());
        // The application name serves as the service when no label names one.
        assertEquals("config-server", normalizer.view(new LogEvent("1", Map.of(), BOOT34, Map.of()), List.of("instance")).service());
        // Without formats the line is the message, as before.
        assertEquals(BOOT3, new EventNormalizer().view(event(BOOT3), List.of("instance")).message());
    }

    @Test
    void aStackTraceStaysAfterTheMessageAndOtherLinesKeepTheirRest() {
        var view = normalizer.view(event("2026-09-24T15:26:09.765+03:00 ERROR 1 --- [           main] o.s.boot.SpringApplication               : "
                + "Application run failed\norg.springframework.beans.factory.BeanCreationException: Error creating bean\n\tat o.s.A.b(A.java:1)"), List.of("instance"));
        assertEquals("Application run failed", view.message());
        assertTrue(view.stackTrace().startsWith("org.springframework.beans.factory.BeanCreationException"), view.stackTrace());
        var report = normalizer.view(event("2026-09-24T15:26:09.736+03:00  INFO 1 --- [           main] .s.b.a.l.ConditionEvaluationReportLogger : "
                + "\n\nError starting ApplicationContext."), List.of("instance"));
        // An empty message on the first line: the report below it is the message.
        assertEquals("Error starting ApplicationContext.", report.message());
        assertEquals("INFO", report.level());
        // A shipper that sends every line apart leaves nothing after the colon; the history counts the logger, which the
        // raw line holds.
        var empty = event("2026-09-24T15:26:11.242+03:00 ERROR 1 --- [           main] o.s.b.d.LoggingFailureAnalysisReporter   : ");
        assertEquals("(empty message, logger o.s.b.d.LoggingFailureAnalysisReporter)", normalizer.view(empty, List.of("instance")).message());
        var group = LogSummary.group(List.of(empty), normalizer, List.of("instance"), List.of()).getFirst();
        assertEquals("o.s.b.d.LoggingFailureAnalysisReporter", GroupHistory.fragment(group));
        assertTrue(empty.line().contains(GroupHistory.fragment(group)));
    }

    @Test
    void linesOfOneMessageGroupTogetherAcrossThreadsAndTimesAndRulesSeeTheLogger() {
        var events = List.of(event(BOOT2),
                event("2021-03-01 10:05:00.456 ERROR 12345 --- [scheduling-2] c.e.orders.OrderJob : Order 18 failed"),
                event("2021-03-01 10:06:00.789 ERROR 12345 --- [http-nio-8080-exec-3] c.e.orders.OrderJob : Order 19 failed"));
        var rules = List.of(new LogRule("order-job", LogRule.Category.CONFIGURATION, null, null, Pattern.compile("^c\\.e\\.orders\\."),
                "orders", "The order job of this stand is misconfigured.", null));
        var groups = LogSummary.group(events, normalizer, List.of("instance"), List.of(), rules);
        assertEquals(1, groups.size());
        assertEquals(3, groups.getFirst().count);
        assertEquals("[configuration: orders]", groups.getFirst().rule.tag());
        // The thread is a field for the contrast, its pool numbers folded; time and pid are not fields.
        var fields = FieldContrast.fields(events.getLast(), normalizer);
        assertEquals("http-nio-*-exec-*", fields.get("thread"));
        assertFalse(fields.containsKey("time") || fields.containsKey("pid") || fields.containsKey("message"), fields.toString());
    }

    @Test
    void startLinesInTheConsoleLayoutAreRecognised() {
        var mark = ServiceStarts.mark(event("2026-09-24T15:26:09.001+03:00  INFO 1 --- [           main] c.e.orders.Application                   : "
                + "Started Application in 12.5 seconds (process running for 13.1)"), normalizer, List.of("instance"));
        assertNotNull(mark);
        assertEquals(ServiceStarts.Kind.STARTED, mark.kind());
    }

    @Test
    void aFormatNeedsAMessageGroup() {
        assertThrows(LokiOperationException.class, () -> new LineFormat("x", Pattern.compile("^(?<level>\\w+)")));
        assertThrows(LokiOperationException.class, () -> new LineFormat("Bad id", Pattern.compile("(?<message>.*)")));
        assertEquals(List.of("spring-boot-console"), JAVA.formats().stream().map(LineFormat::id).toList());
    }
}
