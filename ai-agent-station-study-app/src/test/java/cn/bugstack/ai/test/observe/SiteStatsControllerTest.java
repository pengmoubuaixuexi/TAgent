package cn.bugstack.ai.test.observe;

import cn.bugstack.ai.infrastructure.dao.IAdminUserDao;
import cn.bugstack.ai.infrastructure.dao.po.DailyRegistrationCount;
import cn.bugstack.ai.trigger.http.SiteStatsController;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class SiteStatsControllerTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private final IAdminUserDao users = mock(IAdminUserDao.class);
    // UTC still October 1; Shanghai already October 2.
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-01T16:05:00Z"), ZoneOffset.UTC);
    private Path path() { return folder.getRoot().toPath().resolve("report.json"); }
    private SiteStatsController controller() { return new SiteStatsController(users, path(), clock); }
    private void report(String generated, String rows) throws Exception {
        Files.writeString(path(), "{\"schemaVersion\":1,\"timezone\":\"Asia/Shanghai\",\"generatedAt\":\"" + generated
                + "\",\"days\":" + rows + "}");
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> today(Map<String, Object> data) { return (Map<String, Object>) data.get("today"); }

    @Test public void missingTrafficIsUnknownWhileRegistrationStillWorks() {
        DailyRegistrationCount row = new DailyRegistrationCount();
        row.setDay("2026-10-02"); row.setCount(2);
        when(users.countRegistrationsByDay(any(), any())).thenReturn(List.of(row));
        Map<String, Object> data = controller().stats(7).getData();
        assertEquals("MISSING", data.get("trafficStatus"));
        assertEquals("2026-10-02", today(data).get("date"));
        assertNull(today(data).get("pv"));
        assertEquals(2L, today(data).get("registrations"));
        verify(users).countRegistrationsByDay(LocalDateTime.parse("2026-09-26T00:00:00"), LocalDateTime.parse("2026-10-03T00:00:00"));
    }

    @Test public void missingHistoricalDaysAreNotInventedAsZero() throws Exception {
        report("2026-10-01T16:04:00Z", "[{\"date\":\"2026-10-02\",\"pv\":8,\"uv\":3}]");
        Map<String, Object> data = controller().stats(7).getData();
        assertEquals("AVAILABLE", data.get("trafficStatus"));
        assertEquals(8L, today(data).get("pv"));
        List<?> days = (List<?>) data.get("days");
        assertEquals(7, days.size());
        assertNull(((Map<?, ?>) days.get(0)).get("pv"));
        assertEquals(0L, today(data).get("registrations"));
    }

    @Test public void staleDataIsMarkedAndRetained() throws Exception {
        report("2026-10-01T15:40:00Z", "[{\"date\":\"2026-10-01\",\"pv\":9,\"uv\":4}]");
        Map<String, Object> data = controller().stats(30).getData();
        assertEquals("STALE", data.get("trafficStatus"));
        assertNull(today(data).get("uv"));
        assertEquals(30, ((List<?>) data.get("days")).size());
    }

    @Test public void malformedCountsCannotLookLikeSuccessfulZeroes() throws Exception {
        report("2026-10-01T16:04:00Z", "[{\"date\":\"2026-10-02\",\"pv\":1,\"uv\":2}]");
        Map<String, Object> data = controller().stats(7).getData();
        assertEquals("INVALID", data.get("trafficStatus"));
        assertNull(today(data).get("pv"));
    }

    @Test public void databaseFailureDoesNotEraseValidTraffic() throws Exception {
        report("2026-10-01T16:04:00Z", "[{\"date\":\"2026-10-02\",\"pv\":0,\"uv\":0}]");
        when(users.countRegistrationsByDay(any(), any())).thenThrow(new IllegalStateException("database offline"));
        Map<String, Object> data = controller().stats(7).getData();
        assertEquals(false, data.get("registrationAvailable"));
        assertNull(today(data).get("registrations"));
        assertEquals(0L, today(data).get("pv"));
    }

    @Test(expected = ResponseStatusException.class) public void unboundedTimeWindowIsRejected() {
        controller().stats(999999);
    }
}
