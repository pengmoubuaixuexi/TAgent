package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.infrastructure.dao.IAdminUserDao;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

/** Admin-only via SiteSecurityConfig. Only aggregate counts leave this endpoint. */
@Slf4j
@RestController
public class SiteStatsController {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final IAdminUserDao users;
    private final Path reportPath;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired
    public SiteStatsController(IAdminUserDao users,
                              @Value("${observe.site-stats.report-path:./data/site-stats/report.json}") String reportPath) {
        this(users, Path.of(reportPath), Clock.systemUTC());
    }

    public SiteStatsController(IAdminUserDao users, Path reportPath, Clock clock) {
        this.users = users;
        this.reportPath = reportPath;
        this.clock = clock;
    }

    @GetMapping("/api/v1/observe/site-stats")
    public Response<Map<String, Object>> stats(@RequestParam(value = "days", defaultValue = "7") int days) {
        if (days != 7 && days != 30) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days must be 7 or 30");
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        LocalDate start = today.minusDays(days - 1L);
        Map<String, long[]> traffic = new HashMap<>();
        String trafficStatus = "MISSING";
        String generatedAt = null;
        try {
            if (Files.exists(reportPath)) {
                if (Files.size(reportPath) > 2_000_000) throw new IllegalArgumentException("report too large");
                JsonNode report = json.readTree(Files.readString(reportPath));
                if (report.path("schemaVersion").asInt() != 1 || !ZONE.getId().equals(report.path("timezone").asText())
                        || !report.path("days").isArray()) throw new IllegalArgumentException("invalid report schema");
                Instant generated = Instant.parse(report.path("generatedAt").asText());
                long age = Duration.between(generated, clock.instant()).getSeconds();
                if (age < -60) throw new IllegalArgumentException("future report");
                for (JsonNode day : report.path("days")) {
                    String date = LocalDate.parse(day.path("date").asText()).toString();
                    JsonNode pv = day.path("pv"), uv = day.path("uv");
                    if (!pv.isIntegralNumber() || !uv.isIntegralNumber() || !pv.canConvertToLong() || !uv.canConvertToLong()
                            || pv.longValue() < 0 || uv.longValue() < 0 || uv.longValue() > pv.longValue()
                            || traffic.put(date, new long[]{pv.longValue(), uv.longValue()}) != null)
                        throw new IllegalArgumentException("invalid daily counts");
                }
                generatedAt = generated.toString();
                trafficStatus = age > 900 ? "STALE" : "AVAILABLE";
            }
        } catch (Exception e) {
            traffic.clear();
            trafficStatus = "INVALID";
            log.warn("Site statistics report unavailable ({})", e.getClass().getSimpleName());
        }
        Map<String, Long> registrations = new HashMap<>();
        boolean registrationAvailable = true;
        try {
            users.countRegistrationsByDay(start.atStartOfDay(), today.plusDays(1).atStartOfDay())
                    .forEach(row -> registrations.put(row.getDay(), row.getCount()));
        } catch (Exception e) {
            registrationAvailable = false;
            log.warn("Site registration counts unavailable ({})", e.getClass().getSimpleName());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            String date = d.toString();
            long[] counts = traffic.get(date);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", date);
            row.put("pv", counts == null ? null : counts[0]);
            row.put("uv", counts == null ? null : counts[1]);
            row.put("registrations", registrationAvailable ? registrations.getOrDefault(date, 0L) : null);
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timezone", ZONE.getId());
        data.put("trafficStatus", trafficStatus);
        data.put("generatedAt", generatedAt);
        data.put("registrationAvailable", registrationAvailable);
        data.put("today", rows.get(rows.size() - 1));
        data.put("days", rows);
        return Response.<Map<String, Object>>builder().code("0000").info("success").data(data).build();
    }
}
