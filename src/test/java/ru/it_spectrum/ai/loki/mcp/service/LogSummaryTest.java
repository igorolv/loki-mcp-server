package ru.it_spectrum.ai.loki.mcp.service;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LogSummaryTest {
    private static final List<String> SERVICE_LABELS = List.of("applicationName");
    private static final List<String> PACKAGES = List.of("ru.it_spectrum.asv", "ru.it_spectrum.core");
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private final EventNormalizer normalizer = new EventNormalizer();

    private static String render(List<LogSummary.Group> groups) {
        var lines = new ArrayList<String>();
        for (var group : groups) lines.addAll(LogSummary.render(group, ZONE));
        return String.join("\n", lines);
    }

    @Test
    void realErrorLinesCollapseByRootCauseIntoASmallPage() {
        var events = Fixtures.events("asva2-dev-errors.jsonl");
        long raw = events.stream().mapToLong(e -> LogText.bytes(e.line())).sum();
        var groups = LogSummary.group(events, normalizer, SERVICE_LABELS, PACKAGES);
        String text = render(groups);
        assertTrue(groups.size() <= 35, groups.size() + " groups");
        // Russian text is two bytes a character in UTF-8.
        assertTrue(LogText.bytes(text) < 13 * 1024, LogText.bytes(text) + " bytes");
        assertTrue(raw > 30 * LogText.bytes(text), "raw " + raw + " vs " + LogText.bytes(text));
        // Both ssj-backend lines of one task failure (execute + execution wrapper) are one group.
        var task = groups.stream().filter(g -> g.lastSignature != null && g.lastSignature.rootMessage().startsWith("Не найдена доступная задача")).toList();
        assertEquals(1, task.size(), text);
        assertEquals(2, task.getFirst().count);
        // The same for the duplicate key failure and the nsi-backend task NPE.
        assertEquals(2, groups.stream().filter(g -> g.lastSignature != null && g.lastSignature.rootMessage().contains("pk_doc_type")).findFirst().orElseThrow().count);
        assertEquals(2, groups.stream().filter(g -> g.lastSignature != null && g.lastSignature.rootMessage().contains("\"filePath\" is null")).findFirst().orElseThrow().count);
        // Broken pipes of two services and three paths: one root cause per service.
        assertTrue(text.contains("         IOException: Обрыв канала  ← wrapped in HttpMessageNotWritableException, …, AsyncRequestNotUsableException, ClientAbortException\n"), text);
        // The application frame is under the root cause, never a servlet filter.
        assertFalse(text.contains("doFilter"), text);
        assertTrue(text.contains("\n         at ru.it_spectrum.asv.nsi.dto.hooks.LiqBankDirGenDTOHooksBean.updateRecFields(LiqBankDirGenDTOHooksBean.java:"), text);
        assertTrue(text.contains("         WebServiceTransportException: Service Temporarily Unavailable [503]  ← wrapped in SpectrumException, SmevCallException\n"
                + "         at ru.it_spectrum.asv.bc.smev.common.SmevCallServiceBaseImpl.sendRequestToSmev("), text);
        // Lines without a stack trace keep their message template; space-grouped numbers are one number.
        assertEquals("Ошибка при отправке запроса: Карточка запроса ФОИВ *: Не удалось отправить", LogSummary.normalize("Ошибка при отправке запроса: Карточка запроса ФОИВ 6 029: Не удалось отправить"));
        assertEquals("took * ms, * of *", LogSummary.normalize("took 1 500 ms, 3 of 12"));
        assertEquals("A, B, C", LogSummary.wrappers(List.of("A", "B", "B", "C")));
        assertEquals("A, …, D, E", LogSummary.wrappers(List.of("A", "B", "C", "D", "E")));
    }

    @Test
    void rootMessageIsNotRepeatedWhenItIsTheLoggedMessage() {
        var events = Fixtures.events("asva2-dev-errors.jsonl");
        var npe = events.stream().filter(e -> e.line().contains("inStream parameter is null")).toList();
        var groups = LogSummary.group(npe, normalizer, SERVICE_LABELS, PACKAGES);
        assertEquals(1, groups.size());
        var lines = LogSummary.render(groups.getFirst(), ZONE);
        assertTrue(lines.getFirst().endsWith("ERROR ssj-ek-export-service  java.lang.NullPointerException: inStream parameter is null"), lines.getFirst());
        assertEquals("         NullPointerException", lines.get(1));
        assertTrue(lines.get(2).startsWith("         at ru.it_spectrum.core.version.VersionService.initVersionInfo(VersionService.java:"), lines.get(2));
        // Without application packages nothing is recognised as own code.
        var bare = LogSummary.render(LogSummary.group(npe, normalizer, SERVICE_LABELS, List.of()).getFirst(), ZONE);
        assertEquals(2, bare.size(), bare.toString());
    }
}
