package ru.it_spectrum.ai.loki.mcp.service;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.loki.mcp.model.LogEvent;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class StackTraceTest {
    private static final List<String> PACKAGES = List.of("ru.it_spectrum.asv", "ru.it_spectrum.core");
    private static final List<Pattern> IGNORED = List.of(Pattern.compile("\\.doFilter(Internal)?$"));
    private final List<LogEvent> events = Fixtures.events("asva2-dev-errors.jsonl");
    private final EventNormalizer normalizer = new EventNormalizer();

    private String stack(String text) {
        return normalizer.view(Fixtures.containing(events, text), List.of("applicationName")).stackTrace();
    }

    private ErrorSignature signature(String text) {
        return ErrorSignature.of(stack(text), PACKAGES, IGNORED);
    }

    @Test
    void rootCauseKeepsMultiLineMessageAndNearestApplicationFrame() {
        var trace = StackTrace.parse(stack("pk_doc_type"));
        assertEquals(2, trace.sections().size());
        var root = trace.root();
        assertEquals(StackTrace.Kind.CAUSED_BY, root.kind());
        assertEquals("org.postgresql.util.PSQLException", root.type());
        assertEquals("PSQLException", root.simpleType());
        assertEquals("ОШИБКА: повторяющееся значение ключа нарушает ограничение уникальности \"pk_doc_type\"", root.firstMessageLine());
        assertTrue(root.message().contains("\n  Подробности: Ключ \"(doc_type_code)=(999)\" уже существует."), root.message());
        assertFalse(root.frames().isEmpty());
        assertTrue(root.omitted() > 0);
        var signature = ErrorSignature.of(stack("pk_doc_type"), PACKAGES);
        assertEquals("PSQLException", signature.rootType());
        assertEquals("ru.it_spectrum.asv.bc.userquery.ExecutePreparedStatementCallback.doInPreparedStatement(ExecutePreparedStatementCallback.java:39)",
                signature.appFrame().text().replaceAll(":\\d+\\)$", ":39)"));
        assertEquals(List.of("DuplicateKeyException"), signature.wrappers());
        // A frame of the root section wins even when the outer sections hold application frames too.
        assertTrue(trace.wrappers().getFirst().frames().stream().anyMatch(f -> f.className().startsWith("ru.it_spectrum.asv")));
    }

    @Test
    void constraintViolationListStaysInTheMessageAndFrameFallsBackToTheWrapper() {
        var trace = StackTrace.parse(stack("Could not commit JPA transaction"));
        assertEquals(3, trace.sections().size());
        assertEquals("jakarta.validation.ConstraintViolationException", trace.root().type());
        assertTrue(trace.root().firstMessageLine().startsWith("Validation failed for classes [ru.it_spectrum.asv.ssj.domain.PayoffApplication]"));
        assertTrue(trace.root().message().contains("\n\tConstraintViolationImpl{"), trace.root().message());
        var signature = ErrorSignature.of(stack("Could not commit JPA transaction"), PACKAGES);
        // Hibernate throws it; the nearest application frame is in a wrapper section.
        assertEquals("ru.it_spectrum.asv.ssj.bc.actions.payoffapps.PayoffApplicationEditAction", signature.appFrame().plainClassName());
        assertEquals("savePayoffApplication", signature.appFrame().plainMethod());
        assertEquals(List.of("TransactionSystemException", "RollbackException"), signature.wrappers());
    }

    @Test
    void chainOfCausesNamesTheDependencyAndTheCallSite() {
        var signature = signature("check_dul.CheckDulAction action failed");
        assertEquals("WebServiceTransportException", signature.rootType());
        assertEquals("Service Temporarily Unavailable [503]", signature.rootMessage());
        assertEquals("ru.it_spectrum.asv.bc.smev.common.SmevCallServiceBaseImpl.sendRequestToSmev", signature.appFrame().plainClassName() + "." + signature.appFrame().plainMethod());
        assertEquals(List.of("SpectrumException", "SmevCallException"), signature.wrappers());
    }

    @Test
    void servletFiltersAreNeverTheApplicationFrame() {
        var brokenPipe = signature("Обрыв канала");
        assertEquals("IOException", brokenPipe.rootType());
        assertEquals("Обрыв канала", brokenPipe.rootMessage());
        assertNull(brokenPipe.appFrame(), "the only application frames of a client abort are the filter chain");
        assertEquals("HttpMessageNotWritableException", brokenPipe.wrappers().getFirst());
        assertTrue(brokenPipe.wrappers().contains("ClientAbortException"), brokenPipe.wrappers().toString());
        var notFound = signature("No static resource api/selectedDateTime");
        assertEquals("NoResourceFoundException", notFound.rootType());
        assertNull(notFound.appFrame());
        assertTrue(notFound.wrappers().isEmpty());
    }

    @Test
    void lambdaAndProxyDecorationsAreStrippedAndLinesOfOneFailureShareAKey() {
        var executeError = signature("[TASK_EXECUTE_ERROR] Ошибка при выполнении задачи: taskExecutionId=500001");
        var executionError = signature("[TASK_EXECUTION_ERROR] Ошибка при выполнении задачи: taskExecutionId=500001");
        assertEquals("ru.it_spectrum.asv.bc.task.runtime.TaskServiceImpl.findDelegate(TaskServiceImpl.java:321)", executeError.appFrame().text());
        assertEquals("lambda$findDelegate$1", executeError.appFrame().method());
        assertEquals(executeError.key(), executionError.key());
        assertTrue(executeError.wrappers().isEmpty());
        assertEquals(List.of("ExecutionException", "SpectrumException"), executionError.wrappers());
        var proxy = signature("TransactionSynchronization.afterCompletion threw exception");
        assertEquals("ru.it_spectrum.asv.bc.service.S3DeletionService.onS3DeletionEvent(<generated>)", proxy.appFrame().text());
        assertEquals("ru.it_spectrum.asv.bc.service.S3DeletionService$$SpringCGLIB$$1", proxy.appFrame().className());
        var rollback = signature("Transaction silently rolled back");
        assertEquals("ru.it_spectrum.asv.ssj.bc.actions.payoffapps.PayoffApplicationCreateAction.savePayoffApplication", rollback.key().substring(rollback.key().indexOf(" at ") + 4));
    }

    @Test
    void everyFixtureTraceParsesWithATypedRootAndNoFrameIsAFilter() {
        int traces = 0, filters = 0;
        for (var event : events) {
            var view = normalizer.view(event, List.of("applicationName"));
            if (view.stackTrace() == null) continue;
            traces++;
            var signature = ErrorSignature.of(view.stackTrace(), PACKAGES, IGNORED);
            assertNotNull(signature, view.message());
            // The request filter of the stand's own code is only skipped because the profile says so.
            var unfiltered = ErrorSignature.of(view.stackTrace(), PACKAGES).appFrame();
            if (unfiltered != null && unfiltered.plainMethod().startsWith("doFilter")) filters++;
            assertNotNull(signature.rootType(), view.message());
            if (signature.appFrame() != null)
                assertFalse(signature.appFrame().plainMethod().startsWith("doFilter"), signature.appFrame().text());
        }
        assertEquals(23, traces);
        assertTrue(filters > 0);
    }

    @Test
    void plainTextTracesWithJarSuffixesOmissionsSuppressedAndRootFirstForms() {
        String logbackText = """
                org.springframework.web.client.ResourceAccessException: I/O error on GET request for "http://nsi:8611/x": Connection refused
                    at org.springframework.web.client.RestTemplate.doExecute(RestTemplate.java:915) ~[spring-web-6.2.0.jar:6.2.0]
                    at ru.it_spectrum.asv.ssj.client.NsiClient.load(NsiClient.java:40) ~[classes/:?]
                    Suppressed: java.lang.IllegalStateException: closed
                        at java.base/java.io.Reader.close(Reader.java:1)
                Caused by: java.net.ConnectException: Connection refused
                    at java.base/sun.nio.ch.Net.pollConnect(Native Method)
                    ... 12 common frames omitted
                """;
        var logback = StackTrace.parse(logbackText);
        assertEquals(List.of(StackTrace.Kind.OUTER, StackTrace.Kind.SUPPRESSED, StackTrace.Kind.CAUSED_BY),
                logback.sections().stream().map(StackTrace.Section::kind).toList());
        assertEquals("java.net.ConnectException", logback.root().type());
        assertEquals(12, logback.root().omitted());
        assertEquals("sun.nio.ch.Net", logback.root().frames().getFirst().className());
        assertEquals("Native Method", logback.root().frames().getFirst().location());
        assertEquals(List.of("ResourceAccessException"), logback.wrappers().stream().map(StackTrace.Section::simpleType).toList());
        var signature = ErrorSignature.of(logbackText, PACKAGES);
        assertEquals("ru.it_spectrum.asv.ssj.client.NsiClient.load(NsiClient.java:40)", signature.appFrame().text());
        assertEquals("ConnectException: Connection refused\n at ru.it_spectrum.asv.ssj.client.NsiClient.load", signature.key());

        String rootFirstText = """
                java.net.ConnectException: Connection refused
                	at java.base/sun.nio.ch.Net.pollConnect(Native Method)
                Wrapped by: org.springframework.web.client.ResourceAccessException: I/O error
                	at ru.it_spectrum.asv.ssj.client.NsiClient.load(NsiClient.java:40)
                Wrapped by: ru.it_spectrum.core.text.SpectrumException: NSI unavailable
                	at ru.it_spectrum.asv.ssj.bc.Service.run(Service.java:7)
                """;
        var rootFirst = StackTrace.parse(rootFirstText);
        assertEquals("java.net.ConnectException", rootFirst.root().type());
        assertEquals(List.of("SpectrumException", "ResourceAccessException"), rootFirst.wrappers().stream().map(StackTrace.Section::simpleType).toList());
        assertEquals("ru.it_spectrum.asv.ssj.client.NsiClient", ErrorSignature.of(rootFirstText, PACKAGES).appFrame().plainClassName());

        assertNull(StackTrace.parse("just a message\nwith two lines").root().type());
        assertNull(ErrorSignature.of("just a message\nwith two lines", PACKAGES));
        assertNull(ErrorSignature.of("", PACKAGES));
        var bare = StackTrace.parse("java.lang.NullPointerException\n\tat a.B.c(B.java:1)");
        assertEquals("java.lang.NullPointerException", bare.root().type());
        assertEquals("", bare.root().message());
        assertEquals("NullPointerException: ", ErrorSignature.of("java.lang.NullPointerException\n\tat a.B.c(B.java:1)", List.of()).key());
    }
}
