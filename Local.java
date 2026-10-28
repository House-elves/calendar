import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Calendars another app on this machine already keeps a copy of: Evolution (and GNOME Calendar, which uses it),
 * Thunderbird and Apple Calendar. They signed in to your account, with your admin's blessing; this only reads the copy
 * they saved, so it's as fresh as their last sync. It never changes their files: a database is copied to a folder of
 * its own and read there, so the app that owns it is never locked out.
 *
 * A calendar's "address" in the config names it: evolution:<source>, thunderbird:<profile>/<calendar> or
 * apple:<folder under ~/Library/Calendars>. Only what find() finds can be added, so it can't be pointed elsewhere.
 */
public class Local {

    /** A calendar another app keeps here: its address in the config, the app, and its name there. */
    record Found(String source, String app, String name) {
    }

    static final Pattern ID = Pattern.compile("[\\w.@+-]{1,200}");
    static final Path HOME = Path.of(System.getProperty("user.home"));

    static boolean is(String scheme) {
        return scheme != null && List.of("evolution", "thunderbird", "apple").contains(scheme.toLowerCase(java.util.Locale.ROOT));
    }

    static String app(String scheme) {
        return switch (scheme) {
            case "evolution" -> "Evolution";
            case "thunderbird" -> "Thunderbird";
            default -> "Apple Calendar";
        };
    }

    /** Every calendar it can read from these apps, by name. */
    static List<Found> find() {
        List<Found> all = new ArrayList<>();
        all.addAll(evolution().values());
        all.addAll(thunderbird().values());
        all.addAll(apple().values());
        all.sort(Comparator.comparing(Found::app).thenComparing(Found::name));
        return all;
    }

    /** Folders where a saved .ics file usually is: Find calendars looks only here, a few folders deep. */
    static final List<String> FILE_FOLDERS = List.of("Documents", "Downloads", "Desktop");

    /** The .ics files in those folders, by their path from your home folder, like ~/Documents/work.ics. Up to 50. */
    static List<Found> files() {
        List<Found> all = new ArrayList<>();
        for (String folder : FILE_FOLDERS) {
            Path root = HOME.resolve(folder);
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root, 3)) {
                walk.filter(f -> !HOME.relativize(f).toString().matches("(.*/)?\\..*"))
                        .filter(f -> f.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".ics") && Files.isRegularFile(f))
                        .limit(50 - all.size())
                        .forEach(f -> all.add(new Found("~/" + HOME.relativize(f), "File", f.getFileName().toString().replaceFirst("(?i)\\.ics$", ""))));
            } catch (IOException | java.io.UncheckedIOException e) {
                // A folder it may not read is one it skips.
            }
            if (all.size() >= 50) break;
        }
        all.sort(Comparator.comparing(Found::source));
        return all;
    }

    /** The calendar as iCalendar text, for Ics, or an IOException saying what to do. */
    static String ics(String source) throws IOException {
        String scheme = source.substring(0, source.indexOf(':'));
        try {
            return switch (scheme) {
                case "evolution" -> evolutionIcs(source);
                case "thunderbird" -> thunderbirdIcs(source);
                default -> appleIcs(source);
            };
        } catch (IllegalStateException e) {
            throw new IOException("the calendar is too big to read");
        } catch (SQLException e) {
            throw new IOException(app(scheme) + "'s copy couldn't be read (" + Calendar.clip(String.valueOf(e.getMessage()), 200) + "). Try again after "
                    + app(scheme) + " has synced.");
        }
    }

    // ---- Evolution: ~/.config/evolution/sources/<id>.source names it, ~/.cache/evolution/calendar/<id>/cache.db holds it ----

    record EvoRoot(Path config, Path cache, Path data) {
    }

    static List<EvoRoot> evoRoots() {
        Path flat = HOME.resolve(".var/app/org.gnome.Evolution");
        return List.of(new EvoRoot(HOME.resolve(".config/evolution"), HOME.resolve(".cache/evolution"), HOME.resolve(".local/share/evolution")),
                new EvoRoot(flat.resolve("config/evolution"), flat.resolve("cache/evolution"), flat.resolve("data/evolution")));
    }

    /** Each calendar Evolution has a copy of: from an account (cache.db) or kept only here (calendar.ics). */
    static Map<String, Found> evolution() {
        Map<String, Found> found = new LinkedHashMap<>();
        for (EvoRoot root : evoRoots()) {
            Path sources = root.config().resolve("sources");
            if (!Files.isDirectory(sources)) continue;
            Map<String, Map<String, String>> all = new HashMap<>();
            try (Stream<Path> files = Files.list(sources)) {
                files.filter(f -> f.getFileName().toString().endsWith(".source")).forEach(f -> all.put(
                        f.getFileName().toString().replaceFirst("\\.source$", ""), keyFile(f)));
            } catch (IOException e) {
                continue;
            }
            all.forEach((id, keys) -> {
                if (!keys.containsKey("[Calendar]") || !ID.matcher(id).matches() || keys.getOrDefault("Enabled", "true").equals("false")) return;
                if (evoFile(root, id) == null) return;
                String name = keys.getOrDefault("DisplayName", id);
                String account = all.getOrDefault(keys.getOrDefault("Parent", ""), Map.of()).getOrDefault("DisplayName", "");
                if (!account.isEmpty() && !account.equals(name)) name = name + " (" + account + ")";
                found.putIfAbsent("evolution:" + id, new Found("evolution:" + id, "Evolution", name));
            });
        }
        return found;
    }

    /** An app an Evolution account signs in to Microsoft with, and the account. */
    record EvoApp(String app, String tenant, String redirect, String account) {
    }

    /**
     * The organisation's own app each Evolution account on this machine signs in to Microsoft with: one its account
     * settings override Microsoft's sign-in with (OverrideOauth2), as an organisation that allows only its own app has
     * you set up. Its return address is Microsoft's nativeclient page when Evolution leaves it empty, as Evolution does.
     */
    static List<EvoApp> evolutionMicrosoftApps() {
        Map<String, EvoApp> found = new LinkedHashMap<>();
        for (EvoRoot root : evoRoots()) {
            Path sources = root.config().resolve("sources");
            if (!Files.isDirectory(sources)) continue;
            try (Stream<Path> files = Files.list(sources)) {
                files.filter(f -> f.getFileName().toString().endsWith(".source")).map(Local::keyFile).forEach(keys -> {
                    if (!"true".equals(keys.get("OverrideOauth2"))) return;
                    String app = keys.getOrDefault("Oauth2ClientId", ""), tenant = keys.getOrDefault("Oauth2Tenant", "");
                    String redirect = keys.getOrDefault("Oauth2RedirectUri", "");
                    if (redirect.isEmpty()) redirect = "https://login.microsoftonline.com/common/oauth2/nativeclient";
                    if (!Microsoft.APP_ID.matcher(app).matches() || !(tenant.isEmpty() || Microsoft.TENANT.matcher(tenant).matches())
                            || !Microsoft.REDIRECT.matcher(redirect).matches()) return;
                    String account = keys.getOrDefault("DisplayName", "");
                    found.putIfAbsent(app + " " + tenant + " " + redirect, new EvoApp(app, tenant, redirect, Calendar.clip(account, 100)));
                });
            } catch (IOException ignored) {
            }
        }
        return List.copyOf(found.values());
    }

    static Path evoFile(EvoRoot root, String id) {
        for (Path p : List.of(root.cache().resolve("calendar").resolve(id).resolve("cache.db"),
                root.data().resolve("calendar").resolve(id).resolve("calendar.ics"))) {
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    static String evolutionIcs(String source) throws IOException, SQLException {
        String id = source.substring("evolution:".length());
        for (EvoRoot root : evoRoots()) {
            Path file = ID.matcher(id).matches() ? evoFile(root, id) : null;
            if (file == null) continue;
            if (file.getFileName().toString().endsWith(".ics")) return Calendar.readFile(file);
            StringBuilder out = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n");
            sqlite(file, db -> {
                try (Statement s = db.createStatement()) {
                    if (hasTable(db, "timezones")) {
                        try (ResultSet r = s.executeQuery("SELECT zone FROM timezones")) {
                            while (r.next()) append(out, r.getString(1));
                        }
                    }
                    // State 3 is one you deleted in Evolution that it hasn't told the server about yet.
                    try (ResultSet r = s.executeQuery("SELECT ECacheOBJ FROM ECacheObjects WHERE ECacheState IS NULL OR ECacheState != 3")) {
                        while (r.next()) append(out, r.getString(1));
                    }
                }
            });
            return out.append("END:VCALENDAR\r\n").toString();
        }
        throw new IOException("Evolution doesn't have that calendar any more. Remove it in Settings and add it again.");
    }

    /** Evolution's .source files: [Section] headers and key=value lines. Sections are kept as keys too. */
    static Map<String, String> keyFile(Path f) {
        Map<String, String> keys = new HashMap<>();
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                line = line.strip();
                if (line.startsWith("[")) keys.put(line, "");
                int eq = line.indexOf('=');
                if (eq > 0 && !line.startsWith("#")) keys.putIfAbsent(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        } catch (IOException ignored) {
        }
        return keys;
    }

    // ---- Thunderbird: prefs.js names its calendars, calendar-data/{cache,local}.sqlite holds them ----

    static List<Path> thunderbirdProfiles() {
        List<Path> profiles = new ArrayList<>();
        for (Path root : List.of(HOME.resolve(".thunderbird"), HOME.resolve(".var/app/org.mozilla.Thunderbird/.thunderbird"),
                HOME.resolve("snap/thunderbird/common/.thunderbird"), HOME.resolve("Library/Thunderbird/Profiles"))) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> dirs = Files.list(root)) {
                dirs.filter(d -> Files.isRegularFile(d.resolve("prefs.js")) && ID.matcher(d.getFileName().toString()).matches()).forEach(profiles::add);
            } catch (IOException ignored) {
            }
        }
        return profiles;
    }

    static final Pattern TB_NAME = Pattern.compile("user_pref\\(\"calendar\\.registry\\.([\\w-]{1,100})\\.name\", \"((?:[^\"\\\\]|\\\\.){0,200})\"\\);");

    static Map<String, Found> thunderbird() {
        Map<String, Found> found = new LinkedHashMap<>();
        for (Path profile : thunderbirdProfiles()) {
            if (!Files.isDirectory(profile.resolve("calendar-data"))) continue;
            String prefs;
            try {
                prefs = Files.readString(profile.resolve("prefs.js"), StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }
            Matcher m = TB_NAME.matcher(prefs);
            while (m.find()) {
                if (prefs.contains("user_pref(\"calendar.registry." + m.group(1) + ".disabled\", true);")) continue;
                String source = "thunderbird:" + profile.getFileName() + "/" + m.group(1);
                found.putIfAbsent(source, new Found(source, "Thunderbird", m.group(2).replace("\\\"", "\"").replace("\\\\", "\\")));
            }
        }
        return found;
    }

    static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    static final int ALL_DAY = 8;

    /** Thunderbird keeps each event in rows: its times, its properties, its people and its repeats. Put back together here. */
    static String thunderbirdIcs(String source) throws IOException, SQLException {
        String rest = source.substring("thunderbird:".length());
        int slash = rest.indexOf('/');
        String profileName = slash > 0 ? rest.substring(0, slash) : "", calId = slash > 0 ? rest.substring(slash + 1) : "";
        Path profile = thunderbirdProfiles().stream().filter(p -> p.getFileName().toString().equals(profileName)).findFirst().orElse(null);
        if (profile == null || !thunderbird().containsKey(source)) {
            throw new IOException("Thunderbird doesn't have that calendar any more. Remove it in Settings and add it again.");
        }
        StringBuilder out = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n");
        boolean any = false;
        for (String db : List.of("cache.sqlite", "local.sqlite")) {
            Path file = profile.resolve("calendar-data").resolve(db);
            if (!Files.isRegularFile(file)) continue;
            any = true;
            sqlite(file, c -> thunderbirdEvents(c, calId, out));
        }
        if (!any) throw new IOException("Thunderbird has no copy of its calendars yet. Open Thunderbird and let it sync.");
        return out.append("END:VCALENDAR\r\n").toString();
    }

    static void thunderbirdEvents(Connection c, String calId, StringBuilder out) throws SQLException {
        if (!hasTable(c, "cal_events")) return;
        Map<String, List<String>> lines = new HashMap<>();
        // Properties, people and repeats, by event and by which repeat they belong to ("" for the event itself).
        query(c, "SELECT item_id, COALESCE(recurrence_id, ''), key, value FROM cal_properties WHERE cal_id = ?", calId, r -> {
            String key = r.getString(3);
            if (key.matches("[A-Z][A-Z0-9-]{0,60}") && !key.equals("DTSTART") && !key.equals("DTEND")) {
                lines.computeIfAbsent(r.getString(1) + "|" + r.getString(2), k -> new ArrayList<>()).add(key + ":" + escape(r.getString(4)));
            }
        });
        if (hasTable(c, "cal_attendees")) {
            query(c, "SELECT item_id, COALESCE(recurrence_id, ''), icalString FROM cal_attendees WHERE cal_id = ?", calId,
                    r -> lines.computeIfAbsent(r.getString(1) + "|" + r.getString(2), k -> new ArrayList<>()).add(r.getString(3)));
        }
        if (hasTable(c, "cal_recurrence")) {
            query(c, "SELECT item_id, icalString FROM cal_recurrence WHERE cal_id = ?", calId,
                    r -> lines.computeIfAbsent(r.getString(1) + "|", k -> new ArrayList<>()).add(r.getString(2)));
        }
        // offline_journal 3 is one deleted in Thunderbird that the server hasn't heard about yet.
        query(c, "SELECT id, COALESCE(recurrence_id, 0), recurrence_id_tz, title, flags, event_start, event_start_tz, event_end, event_end_tz, ical_status"
                + " FROM cal_events WHERE cal_id = ? AND (offline_journal IS NULL OR offline_journal != 3)", calId, r -> {
            String id = r.getString(1);
            long rid = r.getLong(2);
            boolean allDay = (r.getInt(5) & ALL_DAY) != 0;
            out.append("BEGIN:VEVENT\r\nUID:").append(escape(id)).append("\r\n");
            if (r.getString(4) != null) out.append("SUMMARY:").append(escape(r.getString(4))).append("\r\n");
            if (r.getString(10) != null) out.append("STATUS:").append(r.getString(10).replaceAll("[^A-Z-]", "")).append("\r\n");
            out.append(time("DTSTART", r.getLong(6), r.getString(7), allDay));
            out.append(time("DTEND", r.getLong(8), r.getString(9), allDay));
            if (rid != 0) out.append(time("RECURRENCE-ID", rid, r.getString(3), allDay));
            for (String line : lines.getOrDefault(id + "|" + (rid == 0 ? "" : String.valueOf(rid)), List.of())) out.append(line.strip()).append("\r\n");
            out.append("END:VEVENT\r\n");
        });
    }

    /** Thunderbird's times are microseconds since 1970, in UTC, with the zone they were made in next to them. */
    static String time(String prop, long micros, String tz, boolean allDay) {
        Instant at = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L));
        if (allDay) return prop + ";VALUE=DATE:" + DAY.format(LocalDateTime.ofInstant(at, ZoneOffset.UTC)) + "\r\n";
        if (tz == null || tz.equals("UTC") || tz.isEmpty()) return prop + ":" + LOCAL.format(LocalDateTime.ofInstant(at, ZoneOffset.UTC)) + "Z\r\n";
        // A floating time is kept as if it were UTC: it's that clock time wherever you are.
        if (tz.equals("floating")) return prop + ":" + LOCAL.format(LocalDateTime.ofInstant(at, ZoneOffset.UTC)) + "\r\n";
        ZoneId zone;
        try {
            zone = ZoneId.of(tz);
        } catch (Exception e) {
            return prop + ":" + LOCAL.format(LocalDateTime.ofInstant(at, ZoneOffset.UTC)) + "Z\r\n";
        }
        // In its own zone, so a weekly meeting keeps its hour across daylight saving.
        return prop + ";TZID=" + zone.getId() + ":" + LOCAL.format(LocalDateTime.ofInstant(at, zone)) + "\r\n";
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\r\n", "\\n").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;");
    }

    // ---- Apple Calendar: ~/Library/Calendars/<account>/<calendar>.calendar/Events/*.ics, named in its Info.plist ----

    static final Path APPLE = HOME.resolve("Library/Calendars");
    static final Pattern TITLE = Pattern.compile("<key>Title</key>\\s*<string>([^<]{1,200})</string>");

    static Map<String, Found> apple() {
        Map<String, Found> found = new LinkedHashMap<>();
        if (!Files.isDirectory(APPLE)) return found;
        try (Stream<Path> dirs = Files.find(APPLE, 3, (p, a) -> a.isDirectory() && p.getFileName().toString().endsWith(".calendar")
                && Files.isDirectory(p.resolve("Events")))) {
            dirs.forEach(d -> {
                String rel = APPLE.relativize(d).toString();
                if (!rel.matches("[\\w.@+-]{1,200}(/[\\w.@+-]{1,200}){0,2}")) return;
                String name = d.getFileName().toString().replaceFirst("\\.calendar$", "");
                try {
                    Matcher m = TITLE.matcher(Files.readString(d.resolve("Info.plist"), StandardCharsets.UTF_8));
                    if (m.find()) name = m.group(1).replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
                } catch (IOException ignored) {
                }
                found.put("apple:" + rel, new Found("apple:" + rel, "Apple Calendar", name));
            });
        } catch (IOException | java.io.UncheckedIOException e) {
            // macOS asks before letting a program into ~/Library/Calendars: without that, there's nothing to list.
        }
        return found;
    }

    static String appleIcs(String source) throws IOException {
        if (!apple().containsKey(source)) {
            throw new IOException("Apple Calendar's copy isn't there, or macOS won't let it be read. Give the program that runs Elf Desk"
                    + " Full Disk Access in System Settings, Privacy & Security, or remove it in Settings.");
        }
        Path events = APPLE.resolve(source.substring("apple:".length())).resolve("Events");
        StringBuilder out = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n");
        long total = 0;
        try (Stream<Path> files = Files.list(events)) {
            for (Path f : (Iterable<Path>) files.filter(f -> f.getFileName().toString().endsWith(".ics"))::iterator) {
                total += Files.size(f);
                if (total > Calendar.MAX_ICS_BYTES) throw new IOException("the calendar is too big to read");
                String text = Files.readString(f, StandardCharsets.UTF_8);
                // Just what's between its BEGIN:VCALENDAR and END:VCALENDAR: its events and their zones.
                int from = text.indexOf("BEGIN:VCALENDAR"), to = text.lastIndexOf("END:VCALENDAR");
                if (from < 0 || to < from) continue;
                String inner = text.substring(text.indexOf('\n', from) + 1, to);
                append(out, inner.replaceAll("(?m)^(VERSION|PRODID|CALSCALE|METHOD):.*\\r?\\n", ""));
            }
        }
        return out.append("END:VCALENDAR\r\n").toString();
    }

    // ---- reading a copy of a database ----

    interface Use {
        void with(Connection db) throws SQLException;
    }

    interface Row {
        void with(ResultSet r) throws SQLException;
    }

    /** Copies the database (with its journal, so the newest changes come too) to a folder of its own, and reads that. */
    static void sqlite(Path file, Use use) throws IOException, SQLException {
        if (Files.size(file) > 500_000_000L) throw new IOException("its copy is too big to read");
        Path dir = Files.createTempDirectory("calendar-");
        try {
            Path copy = dir.resolve("copy.db");
            Files.copy(file, copy);
            for (String side : List.of("-wal", "-shm")) {
                Path p = file.resolveSibling(file.getFileName() + side);
                if (Files.isRegularFile(p)) Files.copy(p, dir.resolve("copy.db" + side));
            }
            try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + copy)) {
                use.with(db);
            }
        } finally {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path p : (Iterable<Path>) files::iterator) Files.deleteIfExists(p);
            }
            Files.deleteIfExists(dir);
        }
    }

    static boolean hasTable(Connection c, String table) throws SQLException {
        try (var s = c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            s.setString(1, table);
            try (ResultSet r = s.executeQuery()) {
                return r.next();
            }
        }
    }

    static void query(Connection c, String sql, String calId, Row row) throws SQLException {
        try (var s = c.prepareStatement(sql)) {
            s.setString(1, calId);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) row.with(r);
            }
        }
    }

    static void append(StringBuilder out, String block) {
        if (block == null || block.isBlank()) return;
        if (out.length() + block.length() > Calendar.MAX_ICS_BYTES) throw new IllegalStateException("too big");
        out.append(block.strip()).append("\r\n");
    }
}
