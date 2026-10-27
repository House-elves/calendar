import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An Outlook calendar read the way Thunderbird and Evolution read it: you sign in to Microsoft once, and it reads your
 * calendar through Microsoft Graph, with permission to read calendars and nothing else. For work accounts whose admin
 * turned off publishing an iCal address. Signing in uses Microsoft's device code: it shows a code, you type it at
 * microsoft.com/devicelogin in your browser, so your password never passes through here.
 *
 * Its sign-in is kept in ~/.config/calendar/microsoft.json, owner-only, never in a folder the tab reads. The calendar's
 * slot in the config has the address microsoft:your@address, which says which sign-in to use.
 */
public class Microsoft {

    static final Path FILE = Config.DIR.resolve("microsoft.json");
    static final String LOGIN = "https://login.microsoftonline.com/common/oauth2/v2.0/";
    static final String GRAPH = "https://graph.microsoft.com/v1.0/";
    /** Read your calendars, keep signed in, and know whose they are: nothing that can change or send anything. */
    static final String SCOPES = "offline_access User.Read Calendars.Read";
    static final Pattern APP_ID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    static final Pattern ACCOUNT = Pattern.compile("[\\w.+'-]{1,64}@[\\w.-]{1,250}");

    // ---- signing in ----

    /** The app Calendar signs in to Microsoft as: Microsoft only lets an app it knows read a calendar. */
    static String appId() throws IOException {
        String id = Config.raw().getOrDefault("MICROSOFT_APP_ID", "").strip();
        if (!APP_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Signing in to Microsoft needs an app ID. Set Microsoft app ID in Calendar's settings: see its README for how to get one.");
        }
        return id;
    }

    /** Starts signing in: answers the code to type and where. Remembers the sign-in's own code and the calendar's name. */
    static ObjectNode start(HttpClient http, String name) throws IOException, InterruptedException {
        name = name.strip();
        if (!Config.NAME.matcher(name).matches()) throw new IllegalArgumentException("Give it a short name: letters, digits and spaces, up to 40.");
        JsonNode r = post(http, LOGIN + "devicecode", Map.of("client_id", appId(), "scope", SCOPES));
        if (!r.hasNonNull("device_code") || !r.hasNonNull("user_code")) throw new IllegalArgumentException("Microsoft wouldn't start signing in: " + error(r));
        ObjectNode saved = saved();
        saved.putObject("pending").put("deviceCode", r.get("device_code").asText()).put("name", name)
                .put("until", Instant.now().plusSeconds(r.path("expires_in").asLong(900)).toString());
        save(saved);
        // Only Microsoft's own sign-in page is shown as a link.
        String url = r.path("verification_uri").asText("");
        if (!url.matches("https://(www\\.)?microsoft\\.com/[\\w/]{1,40}|https://login\\.microsoftonline\\.com/[\\w/]{1,60}")) url = "https://microsoft.com/devicelogin";
        return Calendar.JSON.createObjectNode().put("code", Calendar.clip(r.get("user_code").asText(), 20)).put("url", url)
                .put("interval", Math.max(3, Math.min(30, r.path("interval").asInt(5))));
    }

    /**
     * Whether you've finished signing in. Answers {"waiting": true} until you have; then keeps the sign-in, adds the
     * calendar to the list and answers its slot. Throws with what to do when it didn't work.
     */
    static ObjectNode check(HttpClient http) throws IOException, InterruptedException {
        ObjectNode saved = saved();
        JsonNode pending = saved.path("pending");
        if (!pending.hasNonNull("deviceCode")) throw new IllegalArgumentException("Click Sign in to Microsoft first.");
        if (Instant.parse(pending.path("until").asText(Instant.EPOCH.toString())).isBefore(Instant.now())) {
            saved.remove("pending");
            save(saved);
            throw new IllegalArgumentException("The code ran out before you signed in. Click Sign in to Microsoft again.");
        }
        JsonNode r = post(http, LOGIN + "token", Map.of("client_id", appId(), "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                "device_code", pending.get("deviceCode").asText()));
        String err = r.path("error").asText("");
        if (err.equals("authorization_pending") || err.equals("slow_down")) return Calendar.JSON.createObjectNode().put("waiting", true);
        String name = pending.path("name").asText("Outlook");
        saved.remove("pending");
        if (!r.hasNonNull("refresh_token") || !r.hasNonNull("access_token")) {
            save(saved);
            throw new IllegalArgumentException(err.equals("authorization_declined") ? "You said no in the browser, so it isn't signed in."
                    : err.equals("expired_token") ? "The code ran out before you signed in. Click Sign in to Microsoft again." : "Couldn't sign in: " + error(r));
        }
        JsonNode me = get(http, URI.create(GRAPH + "me?$select=mail,userPrincipalName"), r.get("access_token").asText());
        String account = me.path("mail").asText("").isBlank() ? me.path("userPrincipalName").asText("") : me.path("mail").asText("");
        account = account.strip().toLowerCase(Locale.ROOT);
        if (!ACCOUNT.matcher(account).matches()) {
            save(saved);
            throw new IllegalArgumentException("Signed in, but Microsoft didn't say which account it is. Try again.");
        }
        saved.withObject("/accounts").putObject(account).put("refreshToken", r.get("refresh_token").asText());
        save(saved);
        int slot = Config.addMicrosoft(name, account);
        return Calendar.JSON.createObjectNode().put("added", slot);
    }

    /** Forgets a sign-in, when its calendar is removed and no other calendar uses it. */
    static void forget(String account) throws IOException {
        ObjectNode saved = saved();
        if (saved.path("accounts").has(account)) {
            ((ObjectNode) saved.get("accounts")).remove(account);
            save(saved);
        }
    }

    /** A fresh pass to read the calendar with, from the kept sign-in. Keeps Microsoft's new sign-in when keep is set. */
    static String token(HttpClient http, String account, boolean keep) throws IOException, InterruptedException {
        ObjectNode saved = saved();
        String refresh = saved.path("accounts").path(account).path("refreshToken").asText("");
        if (refresh.isEmpty()) throw new IOException("it isn't signed in. Remove it in Settings and sign in to Microsoft again.");
        JsonNode r;
        try {
            r = post(http, LOGIN + "token", Map.of("client_id", appId(), "grant_type", "refresh_token", "refresh_token", refresh, "scope", SCOPES));
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage());
        }
        if (!r.hasNonNull("access_token")) {
            throw new IOException(r.path("error").asText("").equals("invalid_grant")
                    ? "its Microsoft sign-in ran out or was taken back. Remove it in Settings and sign in to Microsoft again."
                    : "Microsoft wouldn't let it read: " + error(r));
        }
        if (keep && r.hasNonNull("refresh_token") && !r.get("refresh_token").asText().equals(refresh)) {
            saved.withObject("/accounts").putObject(account).put("refreshToken", r.get("refresh_token").asText());
            save(saved);
        }
        return r.get("access_token").asText();
    }

    // ---- reading the calendar ----

    static final String FIELDS = "iCalUId,subject,start,end,isAllDay,isCancelled,location,showAs,responseStatus,organizer,attendees,"
            + "body,onlineMeeting,webLink";

    /** Its events from..to, in the same shape as an iCal calendar's (see Calendar.events). */
    static ArrayNode events(HttpClient http, String account, int slot, Instant from, Instant to, boolean hideDeclined, boolean keep)
            throws IOException, InterruptedException {
        String token = token(http, account, keep);
        URI next = URI.create(GRAPH + "me/calendarView?startDateTime=" + from + "&endDateTime=" + to + "&$top=200&$select=" + FIELDS);
        List<ObjectNode> list = new ArrayList<>();
        for (int pages = 0; next != null && pages < 50; pages++) {
            JsonNode page = get(http, next, token);
            for (JsonNode e : page.path("value")) {
                if (e.path("isCancelled").asBoolean()) continue;
                ObjectNode n = event(e, account, slot);
                if (n == null) continue;
                if (n.path("declined").asBoolean() && hideDeclined) continue;
                list.add(n);
            }
            String link = page.path("@odata.nextLink").asText("");
            // Follow only Microsoft Graph's own next page: the pass goes with it.
            next = link.startsWith(GRAPH) ? URI.create(link) : null;
        }
        list.sort(Comparator.comparing((ObjectNode n) -> n.path("start").asText()));
        ArrayNode out = Calendar.JSON.createArrayNode();
        list.forEach(out::add);
        return out;
    }

    static ObjectNode event(JsonNode e, String account, int slot) {
        ObjectNode n = Calendar.JSON.createObjectNode();
        n.put("uid", Calendar.clip(e.path("iCalUId").asText(""), 500));
        try {
            if (e.path("isAllDay").asBoolean()) {
                n.put("start", LocalDateTime.parse(e.path("start").path("dateTime").asText()).toLocalDate().toString())
                        .put("end", LocalDateTime.parse(e.path("end").path("dateTime").asText()).toLocalDate().toString()).put("allDay", true);
            } else {
                n.put("start", utc(e.path("start"))).put("end", utc(e.path("end"))).put("allDay", false);
            }
        } catch (RuntimeException bad) {
            return null;
        }
        String title = e.path("subject").asText("").strip();
        n.put("title", Calendar.clip(title.isEmpty() ? "(No title)" : title, 300));
        String location = Calendar.clip(e.path("location").path("displayName").asText("").strip(), 1000);
        if (!location.isEmpty()) n.put("location", location);
        if (e.path("showAs").asText("").equals("tentative") || e.path("responseStatus").path("response").asText("").equals("tentativelyAccepted")) {
            n.put("tentative", true);
        }
        ObjectNode org = person(e.path("organizer").path("emailAddress"));
        if (!org.isEmpty()) n.set("organizer", org);
        if (e.path("responseStatus").path("response").asText("").equals("declined")) n.put("declined", true);
        ArrayNode people = Calendar.JSON.createArrayNode();
        int all = 0;
        for (JsonNode a : e.path("attendees")) {
            all++;
            if (people.size() >= Calendar.MAX_ATTENDEES) continue;
            ObjectNode p = person(a.path("emailAddress"));
            String answer = switch (a.path("status").path("response").asText("")) {
                case "accepted" -> "accepted";
                case "declined" -> "declined";
                case "tentativelyAccepted" -> "tentative";
                case "notResponded" -> "needs-action";
                default -> "";
            };
            if (!answer.isEmpty()) p.put("answer", answer);
            people.add(p);
        }
        if (!people.isEmpty()) n.set("attendees", people);
        if (all > Calendar.MAX_ATTENDEES) n.put("moreAttendees", all - Calendar.MAX_ATTENDEES);
        JsonNode body = e.path("body");
        String text = body.path("content").asText("");
        String description = Calendar.clip(body.path("contentType").asText("").equalsIgnoreCase("html") ? Calendar.plain(text) : text.strip(), 20_000);
        if (!description.isBlank()) n.put("description", description);
        Set<String> links = new LinkedHashSet<>();
        for (String s : List.of(e.path("onlineMeeting").path("joinUrl").asText(""), location, description)) {
            Matcher m = Calendar.URL.matcher(s);
            while (m.find() && links.size() < 40) {
                String url = Calendar.checked(m.group());
                if (url != null) links.add(url);
            }
        }
        if (!links.isEmpty()) n.set("links", Calendar.JSON.valueToTree(links));
        ObjectNode join = Calendar.join(links);
        if (join != null) n.set("join", join);
        n.putArray("calendars").add(slot);
        return n;
    }

    /** Graph's times are in UTC unless asked otherwise, with no zone written: it says so in timeZone. */
    static String utc(JsonNode t) {
        String zone = t.path("timeZone").asText("UTC");
        LocalDateTime at = LocalDateTime.parse(t.path("dateTime").asText());
        if (!zone.equalsIgnoreCase("UTC")) throw new IllegalArgumentException("not in UTC");
        return at.toInstant(ZoneOffset.UTC).toString();
    }

    static ObjectNode person(JsonNode a) {
        ObjectNode p = Calendar.JSON.createObjectNode();
        String name = Calendar.clip(a.path("name").asText("").strip(), 200);
        String email = a.path("address").asText("").strip().toLowerCase(Locale.ROOT);
        if (!name.isEmpty() && !name.equalsIgnoreCase(email)) p.put("name", name);
        if (email.contains("@") && email.length() <= 320) p.put("email", email);
        return p;
    }

    // ---- talking to Microsoft ----

    static JsonNode post(HttpClient http, String url, Map<String, String> form) throws IOException, InterruptedException {
        StringJoiner body = new StringJoiner("&");
        form.forEach((k, v) -> body.add(k + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded").header("User-Agent", "calendar (House Elves)")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        return parse(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
    }

    static JsonNode get(HttpClient http, URI url, String token) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + token)
                .header("Prefer", "outlook.timezone=\"UTC\", outlook.body-content-type=\"text\"")
                .header("User-Agent", "calendar (House Elves)").GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 401 || res.statusCode() == 403) {
            throw new IOException("Microsoft wouldn't let it read the calendar (" + res.statusCode() + "). Your admin may have to allow Calendar's app.");
        }
        if (res.statusCode() != 200) throw new IOException("Microsoft answered " + res.statusCode() + ". Try again later.");
        return parse(res.body());
    }

    static JsonNode parse(String body) throws IOException {
        if (body.length() > Calendar.MAX_ICS_BYTES) throw new IOException("Microsoft's answer was too big to read");
        try {
            return Calendar.JSON.readTree(body);
        } catch (IOException e) {
            throw new IOException("Microsoft's answer wasn't readable. Try again later.");
        }
    }

    /**
     * What Microsoft said went wrong, as what to do where it can: most often the organisation hasn't allowed the app,
     * which only its admin can change. Its numbers are Microsoft's AADSTS codes.
     */
    static String error(JsonNode r) {
        String d = r.path("error_description").asText(r.path("error").asText("no answer"));
        if (d.contains("AADSTS65001") || d.contains("AADSTS90094") || d.contains("AADSTS90095") || d.contains("AADSTS50105")) {
            return "your organisation has to allow Calendar's app first. Ask your admin to approve the Microsoft app ID in Calendar's settings"
                    + " for reading calendars, as they did for Thunderbird or Evolution.";
        }
        if (d.contains("AADSTS53003") || d.contains("AADSTS50199")) return "your organisation's sign-in rules block it on this machine. Ask your admin.";
        if (d.contains("AADSTS700016") || d.contains("AADSTS7000218")) return "Microsoft doesn't know that app ID, or it isn't set up as a public client. Check Microsoft app ID in Settings.";
        // Microsoft's own words, first line only, with its trace numbers left out.
        d = d.lines().findFirst().orElse(d).replaceAll("Trace ID:.*|Correlation ID:.*|Timestamp:.*", "").strip();
        return Calendar.clip(d, 300);
    }

    // ---- its file ----

    static ObjectNode saved() {
        JsonNode n = Calendar.readJson(FILE);
        return n instanceof ObjectNode o ? o : Calendar.JSON.createObjectNode();
    }

    /** Written whole and owner-only: it holds your sign-in. */
    static void save(ObjectNode n) throws IOException {
        Files.createDirectories(Config.DIR);
        Path tmp = FILE.resolveSibling("microsoft.json.new");
        Files.deleteIfExists(tmp);
        Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(tmp, Calendar.JSON.writeValueAsString(n));
        Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
