///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS info.picocli:picocli:4.7.6
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS org.xerial:sqlite-jdbc:3.46.1.3
//DEPS org.slf4j:slf4j-nop:1.7.36
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//SOURCES Config.java
//SOURCES Ics.java
//SOURCES Microsoft.java
//SOURCES Local.java

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * calendar: reads your calendars through their private iCal addresses (Google Calendar, Outlook, iCloud or any other),
 * from .ics files on this machine, from the copies Evolution, Thunderbird or Apple Calendar keep here (see Local.java), or
 * from Outlook through your Microsoft sign-in (see Microsoft.java), and saves their events, from the start of last week to some weeks ahead, for its
 * tab on Elf Desk. An event that's on more than one calendar is saved once, with each. Without --save it prints what it read (the desk's dry run).
 * With --action it answers its Settings list: the calendars, adding one, signing in to Microsoft, removing one. It only reads: it never
 * changes a calendar, and sends nothing anywhere.
 */
@Command(name = "calendar", mixinStandardHelpOptions = true, version = "0.5.0", description = "Reads your calendars for Elf Desk")
public class Calendar implements Callable<Integer> {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path EVENTS = Config.DIR.resolve("events");
    static final int MAX_ICS_BYTES = 30_000_000;
    static final int MAX_ATTENDEES = 100;

    @Option(names = "--save", description = "Save what it read under ~/.config/calendar/events for Elf Desk")
    boolean save;

    @Option(names = "--action", description = "For its Settings list: calendars, add or remove, with the values as JSON on stdin")
    String action;

    @Override
    public Integer call() throws Exception {
        if (action != null) return action();
        Config config;
        try {
            config = Config.load();
        } catch (IllegalStateException e) {
            // The end of a failed run's log is what its tab shows: say what to do, not a stack trace.
            System.err.println(e.getMessage());
            return 1;
        }
        if (config.calendars.isEmpty()) {
            // Nothing set up yet, as on the desk's first run right after install: that's not a failure, just nothing to read.
            System.out.println("No calendar set yet. Add one with its private iCal address in Settings, then Refresh calendars.");
            if (save) {
                // All addresses removed: drop what was saved, so the tab shows how to start again.
                for (int slot : config.unused) Files.deleteIfExists(EVENTS.resolve("cal-" + slot + ".json"));
                Files.deleteIfExists(EVENTS.resolve("events.json"));
            }
            return 0;
        }
        ZoneId here = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(here);
        LocalDate fromDay = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
        LocalDate toDay = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).plusWeeks(config.weeksAhead).plusDays(1);
        Instant from = fromDay.atStartOfDay(here).toInstant(), to = toDay.atStartOfDay(here).toInstant();
        System.out.println("Reading " + config.calendars.size() + (config.calendars.size() == 1 ? " calendar" : " calendars")
                + ", " + fromDay + " to " + toDay.minusDays(1) + "...");

        HttpClient http = http();
        ArrayNode calendars = JSON.createArrayNode();
        Map<Integer, ArrayNode> bySlot = new LinkedHashMap<>();
        int failed = 0;
        for (Config.Cal cal : config.calendars) {
            ObjectNode status = calendars.addObject().put("slot", cal.slot()).put("name", cal.name());
            try {
                ArrayNode events = Config.isMicrosoft(cal.address())
                        ? Microsoft.events(http, cal.address().getSchemeSpecificPart(), cal.slot(), from, to, config.hideDeclined, save)
                        : Config.isLocal(cal.address())
                        ? events(new Ics(Local.ics(cal.address().toString())), cal.slot(), from, to, config.hideDeclined)
                        : events(new Ics(read(http, cal.address())), cal.slot(), from, to, config.hideDeclined);
                bySlot.put(cal.slot(), events);
                status.put("readAt", Instant.now().toString()).put("count", events.size());
                System.out.println(cal.name() + ": " + events.size() + " events");
                if (save) {
                    write(EVENTS.resolve("cal-" + cal.slot() + ".json"), JSON.createObjectNode().put("slot", cal.slot())
                            .put("readAt", Instant.now().toString()).set("events", events));
                }
            } catch (Exception e) {
                failed++;
                String why = why(e, cal.address());
                System.err.println("Couldn't read " + cal.name() + ": " + why);
                status.put("error", why);
                // Keep showing what it read last time, so one calendar being away doesn't empty the tab.
                JsonNode old = readJson(EVENTS.resolve("cal-" + cal.slot() + ".json"));
                if (old != null && old.path("events").isArray()) {
                    bySlot.put(cal.slot(), (ArrayNode) old.get("events"));
                    status.put("readAt", old.path("readAt").asText(null));
                }
            }
        }
        ArrayNode merged = merge(bySlot);
        System.out.println(merged.size() + " events in all");

        if (!save) {
            Instant now = Instant.now();
            int shown = 0;
            for (JsonNode e : merged) {
                if (shown >= 15) break;
                if (e.path("allDay").asBoolean() || Instant.parse(e.path("end").asText()).isBefore(now)) continue;
                System.out.println("- " + e.path("start").asText() + "  " + e.path("title").asText()
                        + (e.has("join") ? " (" + e.path("join").path("kind").asText() + ")" : ""));
                shown++;
            }
            return failed == config.calendars.size() ? 1 : 0;
        }
        for (int slot : config.unused) Files.deleteIfExists(EVENTS.resolve("cal-" + slot + ".json"));
        ObjectNode out = JSON.createObjectNode().put("generatedAt", Instant.now().toString())
                .put("from", fromDay.toString()).put("to", toDay.toString());
        out.set("calendars", calendars);
        out.set("events", merged);
        write(EVENTS.resolve("events.json"), out);
        System.out.println("Saved " + merged.size() + " events");
        // Fails only when nothing could be read: then the tab shows the log's end, which says what to do.
        return failed == config.calendars.size() ? 1 : 0;
    }

    // ---- its Settings list ----

    /**
     * One of its Settings list's actions. The last line printed is the answer: the calendars, by slot, name and where
     * they live, never their addresses, or {"error": what to do}.
     */
    int action() throws IOException {
        JsonNode in = JSON.readTree(System.in.readAllBytes());
        ObjectNode out = JSON.createObjectNode();
        try {
            switch (action) {
                case "calendars" -> {
                }
                case "add" -> out.put("added", Config.add(in.path("name").asText(""), in.path("address").asText("")));
                case "microsoft-sign-in" -> out.setAll(Microsoft.start(http(), in.path("name").asText("")));
                case "microsoft-check" -> out.setAll(Microsoft.check(http()));
                case "local" -> {
                    // What Evolution, Thunderbird or Apple Calendar keep here, and whether it's in the list already.
                    Map<String, String> had = Config.raw();
                    ArrayNode found = out.putArray("found");
                    for (Local.Found f : Local.find()) {
                        boolean added = false;
                        for (int i = 1; i <= Config.MAX; i++) added |= f.source().equals(had.getOrDefault("CAL" + i + "_ICS_SECRET", "").strip());
                        found.addObject().put("source", f.source()).put("app", f.app()).put("name", clip(f.name(), 200)).put("added", added);
                    }
                }
                case "add-local" -> out.put("added", Config.addLocal(in.path("name").asText(""), in.path("source").asText("")));
                case "remove" -> {
                    int slot = in.path("slot").asInt(0);
                    URI was = slot >= 1 && slot <= Config.MAX ? Config.address(Config.raw().getOrDefault("CAL" + slot + "_ICS_SECRET", "")) : null;
                    Config.remove(slot);
                    // A Microsoft sign-in goes with its calendar: nothing else uses it.
                    if (was != null && Config.isMicrosoft(was)) Microsoft.forget(was.getSchemeSpecificPart());
                    // Its events go now, not at the next refresh.
                    Files.deleteIfExists(EVENTS.resolve("cal-" + slot + ".json"));
                    out.put("removed", slot);
                }
                default -> throw new IllegalArgumentException("Not something it does: " + action);
            }
        } catch (IllegalArgumentException e) {
            out.put("error", e.getMessage());
        } catch (IOException e) {
            out.put("error", "Couldn't reach Microsoft. Are you online?");
        } catch (InterruptedException e) {
            out.put("error", "Stopped before it was done.");
        }
        ArrayNode list = out.putArray("calendars");
        Map<String, String> raw = Config.raw();
        for (int i = 1; i <= Config.MAX; i++) {
            String set = raw.getOrDefault("CAL" + i + "_ICS_SECRET", "").strip();
            if (set.isEmpty()) continue;
            URI address = Config.address(set);
            // One with a bad address is listed too, so it can be removed.
            list.addObject().put("slot", i).put("name", Config.name(raw, i))
                    .put("where", address == null ? "Not an iCal address" : Config.where(address)
                            + (address != null && Config.isMicrosoft(address) ? " as " + address.getSchemeSpecificPart() : ""));
        }
        out.put("max", Config.MAX);
        System.out.println(JSON.writeValueAsString(out));
        return 0;
    }

    // ---- reading a calendar ----

    static HttpClient http() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    static String read(HttpClient http, URI address) throws Exception {
        if (Config.isFile(address)) return readFile(Path.of(address));
        HttpRequest req = HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(90))
                .header("User-Agent", "calendar (House Elves)").GET().build();
        HttpResponse<java.io.InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (var in = res.body()) {
            int code = res.statusCode();
            if (code == 401 || code == 403 || code == 404) {
                throw new IOException("the address doesn't work any more (" + code + "). If it was reset, copy the new one into Settings.");
            }
            if (code != 200) throw new IOException("it answered " + code + ". Try again later.");
            // Read no more than the cap, whatever it says it sends.
            byte[] body = in.readNBytes(MAX_ICS_BYTES + 1);
            if (body.length > MAX_ICS_BYTES) throw new IOException("the calendar is too big to read");
            String text = new String(body, StandardCharsets.UTF_8);
            if (!text.stripLeading().startsWith("BEGIN:VCALENDAR")) {
                throw new IOException("that address didn't give a calendar. Copy its iCal (.ics) address into Settings.");
            }
            return text;
        }
    }

    /** An .ics file on this machine, read afresh each time, so whatever keeps it up to date shows here. */
    static String readFile(Path file) throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException("its .ics file isn't there any more. Save it again, or remove it in Settings and add where it is now.");
        if (Files.size(file) > MAX_ICS_BYTES) throw new IOException("the calendar is too big to read");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (!text.stripLeading().startsWith("BEGIN:VCALENDAR")) throw new IOException("its file isn't a calendar. Save the calendar as an iCalendar (.ics) file.");
        return text;
    }

    /** What went wrong, without the address: it's a secret. */
    static String why(Exception e, URI address) {
        if (e instanceof HttpTimeoutException) return "it took too long. Try again later.";
        if (e instanceof ConnectException || e instanceof java.nio.channels.UnresolvedAddressException
                || e.getCause() instanceof java.nio.channels.UnresolvedAddressException) {
            return "couldn't reach it. Are you online?";
        }
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return m.replace(address.toString(), "its address").replace(address.getRawPath() == null ? "\0" : address.getRawPath(), "");
    }

    // ---- its events ----

    static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'()\\[\\]{}|\\\\^`]+", Pattern.CASE_INSENSITIVE);

    static ArrayNode events(Ics ics, int slot, Instant from, Instant to, boolean hideDeclined) {
        // Google names your main calendar after your address: that's whose answers say "declined".
        String me = ics.name().contains("@") ? ics.name().strip().toLowerCase(Locale.ROOT) : "";
        List<ObjectNode> list = new ArrayList<>();
        for (Ics.Occurrence o : ics.occurrences(from, to)) {
            Ics.Component e = o.event();
            ObjectNode n = JSON.createObjectNode();
            n.put("uid", clip(e.text("UID"), 500));
            if (o.start().date()) {
                n.put("start", o.start().at().toLocalDate().toString()).put("end", o.end().at().toLocalDate().toString()).put("allDay", true);
            } else {
                n.put("start", o.start().at().toInstant().toString()).put("end", o.end().at().toInstant().toString()).put("allDay", false);
            }
            String title = e.text("SUMMARY").strip();
            n.put("title", clip(title.isEmpty() ? "(No title)" : title, 300));
            String location = clip(e.text("LOCATION").strip(), 1000);
            if (!location.isEmpty()) n.put("location", location);
            String status = e.text("STATUS").strip().toLowerCase(Locale.ROOT);
            if (status.equals("tentative")) n.put("tentative", true);
            Ics.Prop org = e.prop("ORGANIZER");
            if (org != null) n.set("organizer", person(org));
            boolean declined = false;
            ArrayNode people = JSON.createArrayNode();
            List<Ics.Prop> attendees = e.all("ATTENDEE");
            for (Ics.Prop a : attendees) {
                ObjectNode p = person(a);
                String answer = a.param("PARTSTAT").toLowerCase(Locale.ROOT);
                if (!answer.isEmpty()) p.put("answer", answer);
                if (!me.isEmpty() && me.equals(p.path("email").asText()) && answer.equals("declined")) declined = true;
                if (people.size() < MAX_ATTENDEES) people.add(p);
            }
            if (declined && hideDeclined) continue;
            if (declined) n.put("declined", true);
            if (!people.isEmpty()) n.set("attendees", people);
            if (attendees.size() > MAX_ATTENDEES) n.put("moreAttendees", attendees.size() - MAX_ATTENDEES);
            String description = clip(plain(e.text("DESCRIPTION")), 20_000);
            if (!description.isBlank()) n.put("description", description);
            Set<String> links = new LinkedHashSet<>();
            for (String text : List.of(e.text("X-GOOGLE-CONFERENCE"), e.text("X-MICROSOFT-SKYPETEAMSMEETINGURL"), location,
                    e.text("URL"), description)) {
                Matcher m = URL.matcher(text);
                while (m.find() && links.size() < 40) {
                    String url = checked(m.group());
                    if (url != null) links.add(url);
                }
            }
            if (!links.isEmpty()) n.set("links", JSON.valueToTree(links));
            ObjectNode join = join(links);
            if (join != null) n.set("join", join);
            n.putArray("calendars").add(slot);
            list.add(n);
        }
        list.sort(Comparator.comparing((ObjectNode n) -> n.path("start").asText()));
        ArrayNode out = JSON.createArrayNode();
        list.forEach(out::add);
        return out;
    }

    static ObjectNode person(Ics.Prop p) {
        ObjectNode n = JSON.createObjectNode();
        String name = clip(p.param("CN").strip(), 200);
        String email = p.value().strip().replaceFirst("(?i)^mailto:", "").toLowerCase(Locale.ROOT);
        if (!name.isEmpty() && !name.equalsIgnoreCase(email)) n.put("name", name);
        if (email.contains("@") && email.length() <= 320) n.put("email", email);
        return n;
    }

    /** Only http(s) links that parse, without trailing punctuation from the sentence they were in. */
    static String checked(String url) {
        url = url.replaceAll("[.,;:!?*]+$", "");
        if (url.length() > 2000) return null;
        try {
            URI u = URI.create(url);
            if (u.getHost() == null || !(u.getScheme().equalsIgnoreCase("https") || u.getScheme().equalsIgnoreCase("http"))) return null;
            return u.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** The meeting's own link: Google Meet, Teams, Zoom or Webex. */
    static ObjectNode join(Set<String> links) {
        String[][] kinds = {
                {"Google Meet", "^https://meet\\.google\\.com/[a-z]{3,4}-[a-z]{4}-[a-z]{3,4}.*"},
                {"Teams", "^https://teams\\.(microsoft|live)\\.com/(l/meetup-join|meet)/.*"},
                {"Zoom", "^https://([a-z0-9-]+\\.)*zoom(gov)?\\.us/(j|my|w|s)/.*"},
                {"Webex", "^https://([a-z0-9-]+\\.)*webex\\.com/.*(/j\\.php|/meet/|MTID=|/wbxmjs/joinservice/).*"},
        };
        for (String[] k : kinds) {
            for (String l : links) {
                if (l.toLowerCase(Locale.ROOT).matches(k[1].toLowerCase(Locale.ROOT)) || l.matches(k[1])) {
                    return JSON.createObjectNode().put("kind", k[0]).put("url", l);
                }
            }
        }
        return null;
    }

    /** A description as plain text: Google's can be HTML. Clipped first, so the patterns stay quick. */
    static String plain(String text) {
        text = clip(text, 60_000);
        if (!Pattern.compile("<(br|p|div|a|b|i|u|ul|ol|li|span|html)\\b", Pattern.CASE_INSENSITIVE).matcher(text).find()) return text.strip();
        text = Pattern.compile("<a\\s[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(text)
                .replaceAll(m -> {
                    String href = m.group(1), label = m.group(2).replaceAll("<[^>]*>", "").strip();
                    return Matcher.quoteReplacement(label.isEmpty() || label.equals(href) ? href : label + " (" + href + ")");
                });
        text = text.replaceAll("(?i)<br\\s*/?>|</p>|</div>|</li>", "\n").replaceAll("(?i)<li[^>]*>", "• ").replaceAll("<[^>]*>", "")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&apos;", "'").replace("&amp;", "&");
        return text.replaceAll("[ \t]+\n", "\n").replaceAll("\n{3,}", "\n\n").strip();
    }

    static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /**
     * One list from all the calendars, by start. The same meeting on two calendars (same id and start, or same title,
     * start and end) is kept once, with both calendars; it counts as declined only if it was declined on all of them.
     */
    static ArrayNode merge(Map<Integer, ArrayNode> bySlot) {
        Map<String, ObjectNode> seen = new LinkedHashMap<>();
        List<ObjectNode> all = new ArrayList<>();
        for (ArrayNode events : bySlot.values()) {
            for (JsonNode j : events) {
                ObjectNode e = j.deepCopy();
                String byId = "id|" + e.path("uid").asText() + "|" + e.path("start").asText();
                String byTitle = "title|" + e.path("title").asText().strip().toLowerCase(Locale.ROOT) + "|" + e.path("start").asText()
                        + "|" + e.path("end").asText();
                ObjectNode same = seen.getOrDefault(byId, seen.get(byTitle));
                if (same == null) {
                    seen.put(byId, e);
                    seen.put(byTitle, e);
                    all.add(e);
                    continue;
                }
                ArrayNode cals = (ArrayNode) same.get("calendars");
                e.path("calendars").forEach(c -> {
                    boolean has = false;
                    for (JsonNode x : cals) has |= x.asInt() == c.asInt();
                    if (!has) cals.add(c);
                });
                if (!e.path("declined").asBoolean()) same.remove("declined");
                // Keep the fuller copy's details: Outlook's may lack the join link Google's has, or the other way round.
                for (String field : List.of("join", "location", "description", "organizer", "attendees", "links")) {
                    if (!same.has(field) && e.has(field)) same.set(field, e.get(field));
                }
            }
        }
        all.sort(Comparator.comparing((ObjectNode n) -> n.path("start").asText()));
        ArrayNode out = JSON.createArrayNode();
        all.forEach(e -> {
            e.put("id", Integer.toHexString((e.path("uid").asText() + "|" + e.path("start").asText()).hashCode()));
            out.add(e);
        });
        return out;
    }

    // ---- files ----

    static JsonNode readJson(Path file) {
        try {
            return Files.exists(file) ? JSON.readTree(file.toFile()) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Written whole or not at all: the tab may read it at any moment. */
    static void write(Path file, JsonNode n) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".new");
        Files.writeString(tmp, JSON.writeValueAsString(n));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Calendar()).execute(args));
    }
}
