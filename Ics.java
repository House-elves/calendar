import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * An iCalendar (.ics) file, as Google Calendar and Outlook publish it: its events, with repeating ones expanded into
 * the days asked for. The file is someone else's text, so every number in it is bounded and anything it doesn't
 * understand is left out rather than guessed.
 */
public class Ics {

    record Prop(String name, Map<String, String> params, String value) {
        String param(String key) {
            return params.getOrDefault(key, "");
        }
    }

    static final class Component {
        final String type;
        final List<Prop> props = new ArrayList<>();
        final List<Component> children = new ArrayList<>();

        Component(String type) {
            this.type = type;
        }

        Prop prop(String name) {
            for (Prop p : props) if (p.name.equals(name)) return p;
            return null;
        }

        String text(String name) {
            Prop p = prop(name);
            return p == null ? "" : unescape(p.value);
        }

        List<Prop> all(String name) {
            return props.stream().filter(p -> p.name.equals(name)).toList();
        }
    }

    /** A time in the file: a day (all-day events) or a moment, with the zone it repeats in. */
    record When(ZonedDateTime at, boolean date) {
        String key() {
            return date ? at.toLocalDate().toString() : String.valueOf(at.toEpochSecond());
        }
    }

    /** One occurrence of an event, in the window asked for. */
    record Occurrence(Component event, When start, When end) {
    }

    final Component calendar;
    final Map<String, ZoneId> zones = new HashMap<>();

    Ics(String text) {
        calendar = parse(text);
        for (Component c : calendar.children) {
            if (c.type.equals("VTIMEZONE")) {
                String id = c.text("TZID");
                ZoneId z = fallbackZone(c);
                if (!id.isEmpty() && z != null) zones.put(id, z);
            }
        }
    }

    /** The calendar's own name: Google puts the owner's address here for their main calendar. */
    String name() {
        return calendar.text("X-WR-CALNAME");
    }

    // ---- reading the text ----

    static Component parse(String text) {
        // Long lines are folded: a line break followed by a space or tab continues the line.
        String unfolded = text.replace("\r\n", "\n").replace("\r", "\n").replaceAll("\n[ \t]", "");
        Component root = new Component("ROOT");
        List<Component> stack = new ArrayList<>(List.of(root));
        for (String line : unfolded.split("\n")) {
            if (line.isBlank()) continue;
            Prop p = prop(line);
            if (p == null) continue;
            Component top = stack.get(stack.size() - 1);
            if (p.name.equals("BEGIN")) {
                if (stack.size() > 8) continue;
                Component c = new Component(p.value.strip().toUpperCase());
                top.children.add(c);
                stack.add(c);
            } else if (p.name.equals("END")) {
                if (stack.size() > 1) stack.remove(stack.size() - 1);
            } else if (top.props.size() < 2000) {
                top.props.add(p);
            }
        }
        // The VCALENDAR, or what was there if the file had none.
        return root.children.stream().filter(c -> c.type.equals("VCALENDAR")).findFirst().orElse(root);
    }

    /** NAME;PARAM=value;PARAM="quoted:value":the value */
    static Prop prop(String line) {
        int i = 0;
        boolean quoted = false;
        int colon = -1;
        for (; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') quoted = !quoted;
            else if (ch == ':' && !quoted) {
                colon = i;
                break;
            }
        }
        if (colon < 0) return null;
        String head = line.substring(0, colon);
        String value = line.substring(colon + 1);
        List<String> parts = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        quoted = false;
        for (char ch : head.toCharArray()) {
            if (ch == '"') quoted = !quoted;
            else if (ch == ';' && !quoted) {
                parts.add(b.toString());
                b.setLength(0);
                continue;
            }
            b.append(ch);
        }
        parts.add(b.toString());
        Map<String, String> params = new LinkedHashMap<>();
        for (String part : parts.subList(1, parts.size())) {
            int eq = part.indexOf('=');
            if (eq > 0) params.put(part.substring(0, eq).toUpperCase(), part.substring(eq + 1).replace("\"", ""));
        }
        return new Prop(parts.get(0).toUpperCase(), params, value);
    }

    static String unescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(n == 'n' || n == 'N' ? '\n' : n);
            } else {
                b.append(ch);
            }
        }
        return b.toString();
    }

    // ---- times and zones ----

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    /** Outlook names zones the Windows way; these are the common ones. */
    static final Map<String, String> WINDOWS = Map.ofEntries(
            Map.entry("UTC", "UTC"), Map.entry("Coordinated Universal Time", "UTC"),
            Map.entry("GMT Standard Time", "Europe/London"), Map.entry("Greenwich Standard Time", "Atlantic/Reykjavik"),
            Map.entry("W. Europe Standard Time", "Europe/Berlin"), Map.entry("Central Europe Standard Time", "Europe/Budapest"),
            Map.entry("Romance Standard Time", "Europe/Paris"), Map.entry("Central European Standard Time", "Europe/Warsaw"),
            Map.entry("E. Europe Standard Time", "Europe/Chisinau"), Map.entry("FLE Standard Time", "Europe/Kiev"),
            Map.entry("GTB Standard Time", "Europe/Bucharest"), Map.entry("Israel Standard Time", "Asia/Jerusalem"),
            Map.entry("South Africa Standard Time", "Africa/Johannesburg"), Map.entry("Russian Standard Time", "Europe/Moscow"),
            Map.entry("Arabian Standard Time", "Asia/Dubai"), Map.entry("India Standard Time", "Asia/Kolkata"),
            Map.entry("China Standard Time", "Asia/Shanghai"), Map.entry("Singapore Standard Time", "Asia/Singapore"),
            Map.entry("Tokyo Standard Time", "Asia/Tokyo"), Map.entry("Korea Standard Time", "Asia/Seoul"),
            Map.entry("AUS Eastern Standard Time", "Australia/Sydney"), Map.entry("E. Australia Standard Time", "Australia/Brisbane"),
            Map.entry("New Zealand Standard Time", "Pacific/Auckland"), Map.entry("Eastern Standard Time", "America/New_York"),
            Map.entry("Central Standard Time", "America/Chicago"), Map.entry("Mountain Standard Time", "America/Denver"),
            Map.entry("US Mountain Standard Time", "America/Phoenix"), Map.entry("Pacific Standard Time", "America/Los_Angeles"),
            Map.entry("Alaskan Standard Time", "America/Anchorage"), Map.entry("Hawaiian Standard Time", "Pacific/Honolulu"),
            Map.entry("Atlantic Standard Time", "America/Halifax"), Map.entry("E. South America Standard Time", "America/Sao_Paulo"),
            Map.entry("Argentina Standard Time", "America/Buenos_Aires"), Map.entry("Mexico Standard Time", "America/Mexico_City"),
            Map.entry("Central America Standard Time", "America/Guatemala"), Map.entry("SA Pacific Standard Time", "America/Bogota"),
            Map.entry("Egypt Standard Time", "Africa/Cairo"), Map.entry("W. Central Africa Standard Time", "Africa/Lagos"),
            Map.entry("E. Africa Standard Time", "Africa/Nairobi"), Map.entry("Turkey Standard Time", "Europe/Istanbul"),
            Map.entry("Pakistan Standard Time", "Asia/Karachi"), Map.entry("SE Asia Standard Time", "Asia/Bangkok"),
            Map.entry("Taipei Standard Time", "Asia/Taipei"), Map.entry("W. Australia Standard Time", "Australia/Perth"),
            Map.entry("Cen. Australia Standard Time", "Australia/Adelaide"), Map.entry("Ireland Standard Time", "Europe/Dublin"));

    ZoneId zone(String tzid) {
        if (tzid == null || tzid.isEmpty()) return ZoneId.systemDefault();
        String id = tzid.strip();
        try {
            return ZoneId.of(id);
        } catch (Exception ignored) {
            // not an IANA name: try Windows names, then the file's own definition
        }
        if (WINDOWS.containsKey(id)) return ZoneId.of(WINDOWS.get(id));
        int slash = id.indexOf('/', 1);
        if (id.startsWith("/") && slash > 0) {
            // "/mozilla.org/20050126_1/Europe/Berlin" and the like
            String[] parts = id.split("/");
            for (int i = 1; i < parts.length - 1; i++) {
                try {
                    return ZoneId.of(String.join("/", java.util.Arrays.copyOfRange(parts, i, parts.length)));
                } catch (Exception ignored) {
                    // try a shorter tail
                }
            }
        }
        return zones.getOrDefault(id, ZoneId.systemDefault());
    }

    /** A zone the file defines but names oddly: its standard offset. Off by an hour in summer, better than nothing. */
    static ZoneId fallbackZone(Component tz) {
        for (Component c : tz.children) {
            if (!c.type.equals("STANDARD")) continue;
            try {
                String o = c.text("TZOFFSETTO").strip();
                if (o.matches("[+-]\\d{4}(\\d{2})?")) return ZoneOffset.of(o.substring(0, 3) + ":" + o.substring(3, 5));
            } catch (Exception ignored) {
                // not an offset
            }
        }
        return null;
    }

    When when(Prop p) {
        if (p == null) return null;
        return when(p.value.strip(), p.param("VALUE").equalsIgnoreCase("DATE"), p.param("TZID"));
    }

    When when(String v, boolean dateOnly, String tzid) {
        try {
            if (dateOnly || v.length() == 8) return new When(LocalDate.parse(v.substring(0, 8), DATE).atStartOfDay(ZoneId.systemDefault()), true);
            if (v.endsWith("Z")) return new When(LocalDateTime.parse(v.substring(0, 15), DATE_TIME).atZone(ZoneOffset.UTC), false);
            return new When(LocalDateTime.parse(v.substring(0, 15), DATE_TIME).atZone(zone(tzid)), false);
        } catch (Exception e) {
            return null;
        }
    }

    /** EXDATE and RDATE hold comma-separated times. */
    List<When> whens(List<Prop> props) {
        List<When> out = new ArrayList<>();
        for (Prop p : props) {
            for (String v : p.value.split(",")) {
                if (out.size() > 5000) return out;
                When w = when(v.strip(), p.param("VALUE").equalsIgnoreCase("DATE"), p.param("TZID"));
                if (w != null) out.add(w);
            }
        }
        return out;
    }

    // ---- the events in a window ----

    /** Every occurrence that overlaps [from, to), repeating events expanded, moved and cancelled ones applied. */
    List<Occurrence> occurrences(Instant from, Instant to) {
        Map<String, List<Component>> byUid = new LinkedHashMap<>();
        for (Component c : calendar.children) {
            if (!c.type.equals("VEVENT")) continue;
            String uid = c.text("UID");
            if (uid.isEmpty()) uid = "no-uid-" + byUid.size();
            byUid.computeIfAbsent(uid, k -> new ArrayList<>()).add(c);
        }
        List<Occurrence> out = new ArrayList<>();
        for (List<Component> group : byUid.values()) {
            Map<String, Component> moved = new HashMap<>();
            Component master = null;
            for (Component c : group) {
                When rid = when(c.prop("RECURRENCE-ID"));
                if (rid != null) moved.put(rid.key(), c);
                else if (master == null) master = c;
            }
            Set<String> used = new HashSet<>();
            if (master != null) {
                When start = when(master.prop("DTSTART"));
                if (start == null) continue;
                java.time.Duration length = length(master, start);
                List<When> starts = new ArrayList<>();
                Prop rrule = master.prop("RRULE");
                if (rrule != null) {
                    for (ZonedDateTime z : expand(start.at(), rule(rrule.value), to)) starts.add(new When(z, start.date()));
                } else {
                    starts.add(start);
                }
                starts.addAll(whens(master.all("RDATE")));
                Set<String> skip = new HashSet<>();
                whens(master.all("EXDATE")).forEach(w -> skip.add(w.key()));
                for (When s : starts) {
                    if (skip.contains(s.key())) continue;
                    Component m = moved.get(s.key());
                    if (m != null) {
                        used.add(s.key());
                        add(out, m, from, to);
                        continue;
                    }
                    add(out, new Occurrence(master, s, new When(s.at().plus(length), s.date())), from, to);
                }
            }
            // Moved occurrences whose original time wasn't in what was expanded (or with no master in this file).
            moved.forEach((key, m) -> {
                if (!used.contains(key)) add(out, m, from, to);
            });
        }
        return out;
    }

    void add(List<Occurrence> out, Component e, Instant from, Instant to) {
        When s = when(e.prop("DTSTART"));
        if (s == null) return;
        add(out, new Occurrence(e, s, new When(s.at().plus(length(e, s)), s.date())), from, to);
    }

    static void add(List<Occurrence> out, Occurrence o, Instant from, Instant to) {
        if (o.event().text("STATUS").equalsIgnoreCase("CANCELLED")) return;
        Instant s = o.start().at().toInstant(), e = o.end().at().toInstant();
        if (s.isBefore(to) && (e.isAfter(from) || (e.equals(s) && !s.isBefore(from)))) out.add(o);
    }

    java.time.Duration length(Component e, When start) {
        When end = when(e.prop("DTEND"));
        if (end != null && !end.at().isBefore(start.at())) {
            // A day event's end is a day; keep it a whole number of days whatever the zones did.
            if (start.date()) return java.time.Duration.ofDays(Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(
                    start.at().toLocalDate(), end.at().toLocalDate())));
            return java.time.Duration.between(start.at(), end.at());
        }
        String d = e.text("DURATION").strip();
        if (!d.isEmpty()) {
            try {
                return java.time.Duration.parse(d.replace("P0D", "PT0S").replaceFirst("^P(\\d+)W$", "P$1D").replaceFirst("^P(\\d+)W", "P$1D"));
            } catch (Exception ignored) {
                // fall through
            }
        }
        return start.date() ? java.time.Duration.ofDays(1) : java.time.Duration.ZERO;
    }

    // ---- repeating events (RRULE) ----

    static Map<String, String> rule(String value) {
        Map<String, String> r = new HashMap<>();
        for (String part : value.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) r.put(part.substring(0, eq).strip().toUpperCase(), part.substring(eq + 1).strip().toUpperCase());
        }
        return r;
    }

    record ByDay(int ordinal, DayOfWeek day) {
    }

    static final Map<String, DayOfWeek> DAYS = Map.of("MO", DayOfWeek.MONDAY, "TU", DayOfWeek.TUESDAY, "WE", DayOfWeek.WEDNESDAY,
            "TH", DayOfWeek.THURSDAY, "FR", DayOfWeek.FRIDAY, "SA", DayOfWeek.SATURDAY, "SU", DayOfWeek.SUNDAY);
    /** Enough for a daily meeting that began 50 years ago; past that the window has long been passed. */
    static final int MAX_PERIODS = 20_000;
    static final int MAX_OCCURRENCES = 5_000;

    static List<Integer> ints(String s) {
        List<Integer> out = new ArrayList<>();
        if (s == null) return out;
        for (String p : s.split(",")) {
            try {
                int n = Integer.parseInt(p.strip());
                if (Math.abs(n) <= 400) out.add(n);
            } catch (NumberFormatException ignored) {
                // left out
            }
        }
        return out;
    }

    static List<ByDay> byDay(String s) {
        List<ByDay> out = new ArrayList<>();
        if (s == null) return out;
        for (String p : s.split(",")) {
            p = p.strip();
            if (p.length() < 2) continue;
            DayOfWeek d = DAYS.get(p.substring(p.length() - 2));
            if (d == null) continue;
            String n = p.substring(0, p.length() - 2);
            try {
                out.add(new ByDay(n.isEmpty() ? 0 : Integer.parseInt(n.replace("+", "")), d));
            } catch (NumberFormatException ignored) {
                // left out
            }
        }
        return out;
    }

    /** The starts of a repeating event, from its first up to the end of the window. */
    static List<ZonedDateTime> expand(ZonedDateTime first, Map<String, String> r, Instant to) {
        String freq = r.getOrDefault("FREQ", "");
        int interval = Math.max(1, Math.min(1000, ints(r.get("INTERVAL")).stream().findFirst().orElse(1)));
        int count = ints(r.getOrDefault("COUNT", "")).stream().findFirst().orElse(0);
        if (r.containsKey("COUNT") && count <= 0) {
            try {
                count = Math.max(1, Math.min(MAX_OCCURRENCES, Integer.parseInt(r.get("COUNT"))));
            } catch (NumberFormatException e) {
                count = 1;
            }
        }
        Instant until = null;
        String u = r.get("UNTIL");
        if (u != null) {
            try {
                if (u.length() == 8) until = LocalDate.parse(u, DATE).plusDays(1).atStartOfDay(first.getZone()).toInstant().minusSeconds(1);
                else if (u.endsWith("Z")) until = LocalDateTime.parse(u.substring(0, 15), DATE_TIME).toInstant(ZoneOffset.UTC);
                else until = LocalDateTime.parse(u.substring(0, 15), DATE_TIME).atZone(first.getZone()).toInstant();
            } catch (Exception ignored) {
                // an UNTIL it can't read: the window still ends it
            }
        }
        List<ByDay> byDay = byDay(r.get("BYDAY"));
        List<Integer> byMonthDay = ints(r.get("BYMONTHDAY"));
        List<Integer> byMonth = ints(r.get("BYMONTH")).stream().filter(m -> m >= 1 && m <= 12).toList();
        List<Integer> bySetPos = ints(r.get("BYSETPOS"));
        DayOfWeek wkst = DAYS.getOrDefault(r.getOrDefault("WKST", "MO"), DayOfWeek.MONDAY);

        LocalDate d0 = first.toLocalDate();
        LocalTime time = first.toLocalTime();
        LocalDate last = LocalDate.ofInstant(to, first.getZone()).plusDays(1);
        List<ZonedDateTime> out = new ArrayList<>();
        int n = 0;
        for (int k = 0; k < MAX_PERIODS; k++) {
            TreeSet<LocalDate> days = new TreeSet<>();
            LocalDate periodStart;
            switch (freq) {
                case "DAILY" -> {
                    LocalDate d = d0.plusDays((long) k * interval);
                    periodStart = d;
                    boolean ok = (byMonth.isEmpty() || byMonth.contains(d.getMonthValue()))
                            && (byMonthDay.isEmpty() || monthDays(YearMonth.from(d), byMonthDay, List.of(), 0).contains(d))
                            && (byDay.isEmpty() || byDay.stream().anyMatch(b -> b.day() == d.getDayOfWeek()));
                    if (ok) days.add(d);
                }
                case "WEEKLY" -> {
                    LocalDate week = d0.with(TemporalAdjusters.previousOrSame(wkst)).plusWeeks((long) k * interval);
                    periodStart = week;
                    List<DayOfWeek> on = byDay.isEmpty() ? List.of(d0.getDayOfWeek()) : byDay.stream().map(ByDay::day).toList();
                    for (DayOfWeek d : on) {
                        LocalDate day = week.with(TemporalAdjusters.nextOrSame(d));
                        if (byMonth.isEmpty() || byMonth.contains(day.getMonthValue())) days.add(day);
                    }
                }
                case "MONTHLY" -> {
                    YearMonth m = YearMonth.from(d0).plusMonths((long) k * interval);
                    periodStart = m.atDay(1);
                    if (byMonth.isEmpty() || byMonth.contains(m.getMonthValue())) days.addAll(monthDays(m, byMonthDay, byDay, d0.getDayOfMonth()));
                }
                case "YEARLY" -> {
                    int year = d0.getYear() + k * interval;
                    periodStart = LocalDate.of(year, 1, 1);
                    List<Integer> months = byMonth.isEmpty() ? List.of(d0.getMonthValue()) : byMonth;
                    for (int month : months) {
                        days.addAll(monthDays(YearMonth.of(year, month), byMonthDay, byDay, d0.getDayOfMonth()));
                    }
                }
                default -> {
                    // A frequency it doesn't know (HOURLY and finer, rare in calendars): just the first.
                    return List.of(first);
                }
            }
            if (periodStart.isAfter(last)) break;
            List<LocalDate> picked = new ArrayList<>(days);
            if (!bySetPos.isEmpty() && !picked.isEmpty()) {
                List<LocalDate> set = new ArrayList<>();
                for (int p : bySetPos) {
                    int i = p > 0 ? p - 1 : picked.size() + p;
                    if (i >= 0 && i < picked.size()) set.add(picked.get(i));
                }
                picked = new ArrayList<>(new TreeSet<>(set));
            }
            for (LocalDate d : picked) {
                if (d.isBefore(d0)) continue;
                ZonedDateTime z = ZonedDateTime.of(d, time, first.getZone());
                if (until != null && z.toInstant().isAfter(until)) return out;
                if (d.isAfter(last)) return out;
                out.add(z);
                n++;
                if ((count > 0 && n >= count) || out.size() >= MAX_OCCURRENCES) return out;
            }
        }
        return out;
    }

    /** The days of a month that BYMONTHDAY and BYDAY pick, or the first event's day of the month if neither is given. */
    static Set<LocalDate> monthDays(YearMonth m, List<Integer> byMonthDay, List<ByDay> byDay, int defaultDay) {
        int len = m.lengthOfMonth();
        Set<LocalDate> fromMonthDay = new TreeSet<>();
        for (int d : byMonthDay) {
            int day = d > 0 ? d : len + d + 1;
            if (day >= 1 && day <= len) fromMonthDay.add(m.atDay(day));
        }
        Set<LocalDate> fromDay = new TreeSet<>();
        for (ByDay b : byDay) {
            List<LocalDate> all = new ArrayList<>();
            for (LocalDate d = m.atDay(1).with(TemporalAdjusters.nextOrSame(b.day())); !d.isAfter(m.atEndOfMonth()); d = d.plusWeeks(1)) all.add(d);
            if (b.ordinal() == 0) fromDay.addAll(all);
            else {
                int i = b.ordinal() > 0 ? b.ordinal() - 1 : all.size() + b.ordinal();
                if (i >= 0 && i < all.size()) fromDay.add(all.get(i));
            }
        }
        if (!byMonthDay.isEmpty() && !byDay.isEmpty()) {
            fromMonthDay.retainAll(fromDay);
            return fromMonthDay;
        }
        if (!byMonthDay.isEmpty()) return fromMonthDay;
        if (!byDay.isEmpty()) return fromDay;
        return defaultDay <= len ? Set.of(m.atDay(defaultDay)) : Set.of();
    }
}
