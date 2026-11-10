package com.buzzer.booking;

import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.common.SettingsService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Provider calendars. A provider is free for [start, end) when the interval lies inside one of their weekly working
 * windows (local time), is not on a day off, and does not overlap a job they already hold (accepted quote on a live
 * request, keeping a travel buffer between jobs). Providers who never set hours get Monday-Saturday 09:00-19:00.
 */
@Service
public class AvailabilityService {
    public record Window(int weekday, LocalTime start, LocalTime end) {}

    public record TimeOff(UUID id, LocalDate startsOn, LocalDate endsOn, String reason) {}

    public record Busy(UUID partnerId, OffsetDateTime startsAt, int minutes) {}

    /** Everything needed to answer "is this provider free?" for many providers at once. */
    public record Calendars(Map<UUID, List<Window>> windows, Map<UUID, List<TimeOff>> timeOff, Map<UUID, List<Busy>> busy, ZoneId zone, int bufferMinutes) {
        public boolean isFree(UUID partnerId, OffsetDateTime start, int minutes) {
            ZonedDateTime s = start.atZoneSameInstant(zone);
            ZonedDateTime e = s.plusMinutes(minutes);
            if (!s.toLocalDate().equals(e.minusNanos(1).toLocalDate())) return false; // jobs don't run past midnight
            LocalDate day = s.toLocalDate();
            for (TimeOff t : timeOff.getOrDefault(partnerId, List.of())) {
                if (!day.isBefore(t.startsOn()) && !day.isAfter(t.endsOn())) return false;
            }
            int wd = s.getDayOfWeek().getValue();
            boolean inWindow = windows.getOrDefault(partnerId, DEFAULT).stream().anyMatch(w -> w.weekday() == wd
                    && !s.toLocalTime().isBefore(w.start()) && !e.toLocalTime().isAfter(w.end()) && e.toLocalDate().equals(day));
            if (!inWindow) return false;
            OffsetDateTime from = start.minusMinutes(bufferMinutes);
            OffsetDateTime to = start.plusMinutes(minutes + bufferMinutes);
            for (Busy b : busy.getOrDefault(partnerId, List.of())) {
                OffsetDateTime bs = b.startsAt();
                OffsetDateTime be = bs.plusMinutes(b.minutes());
                if (bs.isBefore(to) && be.isAfter(from)) return false;
            }
            return true;
        }
    }

    /** Assumed when a provider has not set working hours yet. */
    static final List<Window> DEFAULT = defaults();

    private static List<Window> defaults() {
        List<Window> out = new ArrayList<>();
        for (int d = 1; d <= 6; d++) out.add(new Window(d, LocalTime.of(9, 0), LocalTime.of(19, 0)));
        return List.copyOf(out);
    }

    private static final String LIVE_STATUSES = "('PAYMENT_PENDING','PAYMENT_CONFIRMED','PARTNER_ASSIGNED','PARTNER_CONFIRMED','RESCHEDULING_REQUESTED',"
            + "'PARTNER_ARRIVED','SERVICE_STARTED','IN_PROGRESS','COMPLETION_PENDING')";

    private final JdbcClient jdbc;
    private final SettingsService settings;
    private final AuditService audit;

    public AvailabilityService(JdbcClient jdbc, SettingsService settings, AuditService audit) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.audit = audit;
    }

    public ZoneId zone() {
        try {
            return ZoneId.of(settings.get("platform.timezone", "Asia/Kolkata"));
        } catch (RuntimeException e) {
            return ZoneId.of("Asia/Kolkata");
        }
    }

    /** Loads calendars for the given providers covering [from, to). */
    public Calendars load(Collection<UUID> partnerIds, OffsetDateTime from, OffsetDateTime to) {
        Map<UUID, List<Window>> windows = new HashMap<>();
        Map<UUID, List<TimeOff>> off = new HashMap<>();
        Map<UUID, List<Busy>> busy = new HashMap<>();
        if (!partnerIds.isEmpty()) {
            List<UUID> ids = List.copyOf(partnerIds);
            record W(UUID partnerId, int weekday, LocalTime startTime, LocalTime endTime) {}
            jdbc.sql("SELECT partner_id, weekday, start_time, end_time FROM partner_availability WHERE partner_id IN (:ids)").param("ids", ids)
                    .query(W.class).list().forEach(w -> windows.computeIfAbsent(w.partnerId(), k -> new ArrayList<>())
                            .add(new Window(w.weekday(), w.startTime(), w.endTime())));
            record T(UUID partnerId, UUID id, LocalDate startsOn, LocalDate endsOn, String reason) {}
            jdbc.sql("SELECT partner_id, id, starts_on, ends_on, reason FROM partner_time_off WHERE partner_id IN (:ids) AND ends_on >= :d")
                    .param("ids", ids).param("d", from.atZoneSameInstant(zone()).toLocalDate().minusDays(1)).query(T.class).list()
                    .forEach(t -> off.computeIfAbsent(t.partnerId(), k -> new ArrayList<>()).add(new TimeOff(t.id(), t.startsOn(), t.endsOn(), t.reason())));
            busy(ids, from.minusDays(1), to).forEach(b -> busy.computeIfAbsent(b.partnerId(), k -> new ArrayList<>()).add(b));
        }
        return new Calendars(windows, off, busy, zone(), Math.max(0, settings.getInt("booking.travel_buffer_minutes", 30)));
    }

    /** Jobs the providers already hold: an accepted quotation on a live request with a scheduled time. */
    public List<Busy> busy(List<UUID> partnerIds, OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                SELECT q.partner_id,
                       COALESCE((SELECT b.scheduled_at FROM bookings b WHERE b.request_id = r.id AND b.status NOT IN ('CANCELLED','NO_SHOW')
                                 ORDER BY b.created_at DESC LIMIT 1), r.preferred_start) AS starts_at,
                       COALESCE(CAST(EXTRACT(EPOCH FROM (r.preferred_end - r.preferred_start)) / 60 AS int), 60) AS minutes
                FROM quotations q JOIN service_requests r ON r.id = q.request_id
                WHERE q.status = 'ACCEPTED' AND q.partner_id IN (:ids) AND r.lifecycle = 'REQUEST_V1' AND r.preferred_start IS NOT NULL
                  AND r.status IN """ + " " + LIVE_STATUSES + """

                  AND r.preferred_start >= :from AND r.preferred_start < :to""")
                .param("ids", partnerIds).param("from", from).param("to", to).query(Busy.class).list();
    }

    // ----- Partner calendar management -------------------------------------------------------------------

    public Map<String, Object> view(UUID partnerId) {
        List<Map<String, Object>> weekly = jdbc.sql("""
                SELECT weekday, to_char(start_time, 'HH24:MI') AS start_time, to_char(end_time, 'HH24:MI') AS end_time
                FROM partner_availability WHERE partner_id = :p ORDER BY weekday, start_time""").param("p", partnerId).query().listOfRows();
        boolean defaults = weekly.isEmpty();
        if (defaults) {
            weekly = DEFAULT.stream().map(w -> Map.<String, Object>of("weekday", w.weekday(), "start_time", w.start().toString(), "end_time", w.end().toString())).toList();
        }
        LocalDate today = LocalDate.now(zone());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("weekly", weekly);
        out.put("using_defaults", defaults);
        out.put("time_zone", zone().getId());
        out.put("time_off", jdbc.sql("""
                SELECT id, starts_on, ends_on, reason FROM partner_time_off WHERE partner_id = :p AND ends_on >= :d ORDER BY starts_on""")
                .param("p", partnerId).param("d", today).query().listOfRows());
        out.put("upcoming_jobs", jdbc.sql("""
                SELECT r.id AS request_id, r.request_number, r.status, i.name AS service_name, l.name AS locality_name, a.id AS assignment_id,
                       a.status AS assignment_status, COALESCE(b.scheduled_at, r.preferred_start) AS starts_at, r.preferred_end AS ends_at
                FROM assignments a JOIN service_requests r ON r.id = a.request_id LEFT JOIN service_items i ON i.id = r.service_item_id
                LEFT JOIN localities l ON l.id = r.locality_id LEFT JOIN bookings b ON b.assignment_id = a.id
                WHERE a.partner_id = :p AND a.status IN ('ACTIVE','CONFIRMED','IN_PROGRESS')
                  AND COALESCE(b.scheduled_at, r.preferred_start) >= now() - interval '12 hours'
                ORDER BY COALESCE(b.scheduled_at, r.preferred_start) NULLS LAST LIMIT 50""").param("p", partnerId).query().listOfRows());
        return out;
    }

    public record WindowInput(Integer weekday, String startTime, String endTime) {}

    /** Replaces the provider's weekly hours. To stop taking jobs altogether the provider uses the availability switch. */
    @Transactional
    public Map<String, Object> saveWeekly(UUID partnerId, List<WindowInput> in, UUID actor) {
        if (in == null || in.isEmpty()) {
            throw BusinessException.invalid("Add at least one working window - to stop taking jobs, switch 'Available for new leads' off");
        }
        if (in.size() > 21) throw BusinessException.invalid("At most 3 time windows per day");
        List<Window> windows = new ArrayList<>();
        for (WindowInput w : in) {
            if (w == null || w.weekday() == null || w.weekday() < 1 || w.weekday() > 7) throw BusinessException.invalid("Weekday must be 1 (Monday) to 7 (Sunday)");
            LocalTime s = time(w.startTime());
            LocalTime e = time(w.endTime());
            if (!e.isAfter(s)) throw BusinessException.invalid("End time must be after start time on " + DayOfWeek.of(w.weekday()));
            windows.add(new Window(w.weekday(), s, e));
        }
        windows.sort(Comparator.comparingInt(Window::weekday).thenComparing(Window::start));
        for (int i = 1; i < windows.size(); i++) {
            Window a = windows.get(i - 1), b = windows.get(i);
            if (a.weekday() == b.weekday() && b.start().isBefore(a.end())) throw BusinessException.invalid("Time windows overlap on " + DayOfWeek.of(a.weekday()));
        }
        jdbc.sql("DELETE FROM partner_availability WHERE partner_id = :p").param("p", partnerId).update();
        for (Window w : windows) {
            jdbc.sql("INSERT INTO partner_availability(partner_id, weekday, start_time, end_time) VALUES (:p, :d, :s, :e)")
                    .param("p", partnerId).param("d", w.weekday()).param("s", w.start()).param("e", w.end()).update();
        }
        audit.record(actor, "PARTNER", "PARTNER_HOURS_UPDATED", "PARTNER", partnerId, Map.of("windows", windows.size()));
        return view(partnerId);
    }

    public record TimeOffInput(LocalDate startsOn, LocalDate endsOn, String reason) {}

    @Transactional
    public Map<String, Object> addTimeOff(UUID partnerId, TimeOffInput in, UUID actor) {
        if (in == null || in.startsOn() == null) throw BusinessException.invalid("Choose the first day off");
        LocalDate end = in.endsOn() == null ? in.startsOn() : in.endsOn();
        LocalDate today = LocalDate.now(zone());
        if (in.startsOn().isBefore(today)) throw BusinessException.invalid("Time off can't start in the past");
        if (end.isBefore(in.startsOn())) throw BusinessException.invalid("The last day can't be before the first day");
        if (end.isAfter(today.plusYears(1))) throw BusinessException.invalid("Time off can be planned up to a year ahead");
        if (in.reason() != null && in.reason().length() > 200) throw BusinessException.invalid("Reason must be 200 characters or less");
        jdbc.sql("INSERT INTO partner_time_off(partner_id, starts_on, ends_on, reason) VALUES (:p, :s, :e, :r)")
                .param("p", partnerId).param("s", in.startsOn()).param("e", end).param("r", in.reason() == null || in.reason().isBlank() ? null : in.reason().trim())
                .update();
        audit.record(actor, "PARTNER", "PARTNER_TIME_OFF_ADDED", "PARTNER", partnerId, Map.of("from", in.startsOn().toString(), "to", end.toString()));
        Map<String, Object> out = new LinkedHashMap<>(view(partnerId));
        // Jobs already booked on those days stay booked: tell the provider so they can reschedule or cancel them.
        ZoneId z = zone();
        out.put("conflicts", jdbc.sql("""
                SELECT r.request_number, COALESCE(b.scheduled_at, r.preferred_start) AS starts_at FROM assignments a
                JOIN service_requests r ON r.id = a.request_id LEFT JOIN bookings b ON b.assignment_id = a.id
                WHERE a.partner_id = :p AND a.status IN ('ACTIVE','CONFIRMED')
                  AND COALESCE(b.scheduled_at, r.preferred_start) >= :from AND COALESCE(b.scheduled_at, r.preferred_start) < :to""")
                .param("p", partnerId).param("from", in.startsOn().atStartOfDay(z).toOffsetDateTime())
                .param("to", end.plusDays(1).atStartOfDay(z).toOffsetDateTime()).query().listOfRows());
        return out;
    }

    @Transactional
    public Map<String, Object> removeTimeOff(UUID partnerId, UUID id, UUID actor) {
        int n = jdbc.sql("DELETE FROM partner_time_off WHERE id = :id AND partner_id = :p").param("id", id).param("p", partnerId).update();
        if (n == 0) throw BusinessException.notFound("Time off");
        audit.record(actor, "PARTNER", "PARTNER_TIME_OFF_REMOVED", "PARTNER", partnerId, Map.of("id", id.toString()));
        return view(partnerId);
    }

    private static LocalTime time(String v) {
        try {
            return LocalTime.parse(v == null ? "" : v.trim());
        } catch (DateTimeParseException e) {
            throw BusinessException.invalid("Times must look like 09:00 or 18:30");
        }
    }
}
