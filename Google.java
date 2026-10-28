import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Google calendar read the way Thunderbird reads it, for a Google Workspace account whose admin turned off the
 * secret iCal address: you sign in at Google's own page once, and it reads your calendar through Google's Calendar API,
 * with permission to read events and nothing else.
 *
 * Google doesn't take a code typed elsewhere for calendars, so Calendar listens on this machine (127.0.0.1, for 5
 * minutes) for your browser to come back from Google's page. The action that starts it can't wait that long, so it
 * starts Calendar again to listen (--google-listen), and Settings asks every few seconds whether you're back.
 *
 * It needs a Google OAuth client of yours (a Desktop app's client ID and secret, set by Add calendar), and a Workspace
 * admin may have to allow it. Its sign-in is kept in ~/.config/calendar/google.json, owner-only, never in a folder the
 * tab reads. The calendar's slot in the config has the address google:your@address, which says which sign-in to use.
 */
public class Google {

    static final Path FILE = Config.DIR.resolve("google.json");
    static final Path LOCK = Config.DIR.resolve("google.lock");
    static final String AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
    /** Not final: a test answers in Google's place. */
    static String token = "https://oauth2.googleapis.com/token";
    /** Not final: a test answers in Google's place. */
    static String API = "https://www.googleapis.com/calendar/v3/";
    /** Read events, and know whose they are: nothing that can change or send anything. */
    static final String EVENTS_SCOPE = "https://www.googleapis.com/auth/calendar.events.readonly";
    static final String SCOPES = "openid email " + EVENTS_SCOPE;
    static final Duration WAIT = Duration.ofMinutes(5);
    static final Pattern CLIENT_ID = Pattern.compile("[0-9]{4,30}-[0-9a-z]{10,64}\\.apps\\.googleusercontent\\.com");
    static final Pattern SECRET = Pattern.compile("[\\x21-\\x7E]{8,100}");

    // ---- its app: a Google OAuth client of yours ----

    static String clientId() throws IOException {
        String id = Config.raw().getOrDefault("GOOGLE_CLIENT_ID", "").strip();
        if (!CLIENT_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Signing in to Google needs a client ID: Add calendar, Google, Sign in asks for it, and Calendar's README says how to make one.");
        }
        return id;
    }

    static String clientSecret() throws IOException {
        String s = Config.raw().getOrDefault("GOOGLE_CLIENT_SECRET", "").strip();
        if (s.isEmpty()) throw new IllegalArgumentException("Signing in to Google needs the client secret that goes with its client ID: Add calendar, Google, Sign in, Change app.");
        return s;
    }

    /** The client it signs in as, for Settings: {app, secret}, the ID and whether a secret is set. Never the secret. */
    static ObjectNode app() throws IOException {
        Map<String, String> raw = Config.raw();
        return Calendar.JSON.createObjectNode().put("app", raw.getOrDefault("GOOGLE_CLIENT_ID", "").strip())
                .put("secret", !raw.getOrDefault("GOOGLE_CLIENT_SECRET", "").isBlank());
    }

    /** Sets the client it signs in as, from Add calendar. An empty secret keeps the one set, for the same client. */
    static void setApp(String id, String secret) throws IOException {
        id = id.strip();
        secret = secret.strip();
        if (!CLIENT_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("The client ID is the one Google Cloud shows for a Desktop app, ending .apps.googleusercontent.com.");
        }
        Map<String, String> raw = Config.raw();
        if (secret.isEmpty()) {
            if (!id.equals(raw.getOrDefault("GOOGLE_CLIENT_ID", "").strip()) || raw.getOrDefault("GOOGLE_CLIENT_SECRET", "").isBlank()) {
                throw new IllegalArgumentException("Give the client secret Google Cloud shows with that client ID too.");
            }
            Config.set(Map.of("GOOGLE_CLIENT_ID", id));
            return;
        }
        if (!SECRET.matcher(secret).matches()) throw new IllegalArgumentException("That isn't a client secret: copy it from Google Cloud's Credentials page.");
        Config.set(Map.of("GOOGLE_CLIENT_ID", id, "GOOGLE_CLIENT_SECRET", secret));
    }

    // ---- signing in: back to this machine from Google's page ----

    /**
     * Starts signing in: Calendar listens on 127.0.0.1 in the background, and this answers {url}, Google's page to
     * open. Google sends your browser back to Calendar there once you've signed in.
     */
    static ObjectNode start(String name) throws IOException, InterruptedException {
        String calName = name.strip();
        if (!Config.NAME.matcher(calName).matches()) throw new IllegalArgumentException("Give it a short name: letters, digits and spaces, up to 40.");
        String clientId = clientId();
        clientSecret();
        String verifier = Microsoft.random(48), state = Microsoft.random(24);
        String until = Instant.now().plus(WAIT).toString();
        update(s -> s.putObject("pending").put("state", state).put("verifier", verifier).put("until", until).put("name", calName));
        // Calendar again, as the runner started it, but to listen.
        ProcessHandle.Info me = ProcessHandle.current().info();
        String[] args = me.arguments().orElseThrow(() -> new IOException("Couldn't start listening for Google's answer"));
        List<String> cmd = new ArrayList<>(List.of(me.command().orElseThrow(() -> new IOException("Couldn't start listening for Google's answer"))));
        for (String a : args) cmd.add(a.equals("--action=google-sign-in") ? "--google-listen" : a);
        if (!cmd.contains("--google-listen")) throw new IOException("Couldn't start listening for Google's answer");
        Files.createDirectories(Config.DIR);
        new ProcessBuilder(cmd).redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(Config.DIR.resolve("google-sign-in.log").toFile()))
                .start();
        int port = 0;
        for (int i = 0; i < 100 && port == 0; i++) {
            TimeUnit.MILLISECONDS.sleep(200);
            JsonNode p = read().path("pending");
            if (!p.path("state").asText().equals(state)) throw new IOException("Another sign-in to Google started meanwhile");
            port = p.path("port").asInt(0);
        }
        if (port == 0) throw new IOException("Calendar couldn't listen for Google's answer: see ~/.config/calendar/google-sign-in.log");
        Map<String, String> q = new LinkedHashMap<>();
        q.put("client_id", clientId);
        q.put("redirect_uri", "http://127.0.0.1:" + port);
        q.put("response_type", "code");
        q.put("scope", SCOPES);
        q.put("code_challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(Microsoft.sha256(verifier)));
        q.put("code_challenge_method", "S256");
        q.put("state", state);
        q.put("access_type", "offline");
        q.put("prompt", "select_account consent"); // a pass that lasts, every time, for the account you pick
        return Calendar.JSON.createObjectNode().put("url", AUTH + "?" + form(q)).put("interval", 3);
    }

    /** Answers {"waiting": true} until you're back from Google's page; then {added: slot}, or throws with what went wrong. */
    static ObjectNode check() throws IOException {
        JsonNode p = read().path("pending");
        if (!p.hasNonNull("state")) throw new IllegalArgumentException("Click Sign in to Google first.");
        JsonNode result = p.path("result");
        if (result.hasNonNull("added")) {
            update(s -> s.remove("pending"));
            return Calendar.JSON.createObjectNode().put("added", result.get("added").asInt());
        }
        if (result.hasNonNull("error")) {
            update(s -> s.remove("pending"));
            throw new IllegalArgumentException(result.get("error").asText());
        }
        if (Instant.parse(p.path("until").asText(Instant.EPOCH.toString())).isBefore(Instant.now())) {
            update(s -> s.remove("pending"));
            throw new IllegalArgumentException("Calendar stopped waiting for Google after 5 minutes. If Google said access is blocked, "
                    + "your organisation hasn't allowed your Google client: ask IT to allow its client ID. Otherwise click Sign in to Google again.");
        }
        return Calendar.JSON.createObjectNode().put("waiting", true);
    }

    /**
     * --google-listen: waits on 127.0.0.1 for your browser to come back from Google, for the sign-in {@link #start}
     * began, at most 5 minutes. Takes one answer with that sign-in's state, then stops.
     */
    static int listen(HttpClient http) throws Exception {
        JsonNode p = read().path("pending");
        String state = p.path("state").asText(""), verifier = p.path("verifier").asText(""), name = p.path("name").asText("Google");
        if (state.isEmpty()) return 1;
        Instant until = Instant.parse(p.path("until").asText(Instant.EPOCH.toString()));
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        int port = server.getAddress().getPort();
        boolean[] ours = { false };
        update(s -> {
            if (s.path("pending").path("state").asText().equals(state)) {
                ((ObjectNode) s.get("pending")).put("port", port);
                ours[0] = true;
            }
        });
        if (!ours[0]) return 1;
        CountDownLatch over = new CountDownLatch(1);
        server.createContext("/", ex -> {
            Map<String, String> q = Microsoft.query(ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery());
            if (!state.equals(q.get("state"))) {
                page(ex, 400, "This isn't the sign-in Calendar is waiting for. Go back to Elf Desk and click Sign in to Google again.");
                return;
            }
            String said;
            ObjectNode result = Calendar.JSON.createObjectNode();
            try {
                if (q.containsKey("error")) {
                    throw new IllegalArgumentException(q.get("error").equals("access_denied")
                            ? "You said no to Google, or your organisation doesn't allow this app, so Calendar isn't signed in."
                            : "Google said no: " + Calendar.clip(q.get("error"), 100));
                }
                String email = exchange(http, q.getOrDefault("code", ""), verifier, port);
                result.put("added", Config.addGoogle(name, email));
                said = "Calendar is signed in to " + email + ". You can close this tab and go back to Elf Desk.";
            } catch (Exception e) {
                result.put("error", e.getMessage());
                said = e.getMessage();
            }
            update(s -> {
                if (s.path("pending").path("state").asText().equals(state)) ((ObjectNode) s.get("pending")).set("result", result);
            });
            page(ex, 200, said);
            over.countDown();
        });
        server.start();
        try {
            over.await(Math.max(0, Duration.between(Instant.now(), until).toMillis()), TimeUnit.MILLISECONDS);
        } finally {
            server.stop(1);
        }
        return 0;
    }

    /** The code Google sent back, for a pass that lasts: keeps the sign-in and answers its address. */
    static String exchange(HttpClient http, String code, String verifier, int port) throws IOException, InterruptedException {
        if (code.isBlank()) throw new IllegalArgumentException("Google didn't send a sign-in back. Click Sign in to Google again.");
        JsonNode r = post(http, token, Map.of("client_id", clientId(), "client_secret", clientSecret(), "code", code,
                "code_verifier", verifier, "grant_type", "authorization_code", "redirect_uri", "http://127.0.0.1:" + port));
        if (!r.hasNonNull("access_token")) throw new IllegalArgumentException("Google wouldn't sign Calendar in: " + error(r));
        if (!r.hasNonNull("refresh_token")) throw new IllegalArgumentException("Google didn't give Calendar a sign-in that lasts. Click Sign in to Google again.");
        if (!(" " + r.path("scope").asText("") + " ").contains(" " + EVENTS_SCOPE + " ")) {
            throw new IllegalArgumentException("Google signed in, but without access to your calendar: tick it on Google's page when you sign in again.");
        }
        String email = claims(r.path("id_token").asText("")).path("email").asText("").strip().toLowerCase(Locale.ROOT);
        if (!Microsoft.ACCOUNT.matcher(email).matches()) throw new IllegalArgumentException("Signed in, but Google didn't say which account it is. Try again.");
        String refresh = r.get("refresh_token").asText();
        update(s -> s.withObject("/accounts").putObject(email).put("refreshToken", refresh).put("at", Instant.now().toString()));
        return email;
    }

    /** What the id_token says about who signed in. It came straight from Google's token endpoint, so it isn't checked again. */
    static JsonNode claims(String idToken) {
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) return Calendar.JSON.createObjectNode();
        try {
            return Calendar.JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
        } catch (Exception e) {
            return Calendar.JSON.createObjectNode();
        }
    }

    private static void page(HttpExchange ex, int status, String said) throws IOException {
        String html = "<!doctype html><meta charset=utf-8><title>Calendar</title><body style=\"font:16px system-ui;margin:3rem\"><p>"
                + said.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</p>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (var out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    /** Forgets a sign-in, when its calendar is removed. */
    static void forget(String account) throws IOException {
        update(s -> {
            if (s.path("accounts").has(account)) ((ObjectNode) s.get("accounts")).remove(account);
        });
    }

    /** A fresh pass to read the calendar with, from the kept sign-in. */
    static String accessToken(HttpClient http, String account) throws IOException, InterruptedException {
        String refresh = read().path("accounts").path(account).path("refreshToken").asText("");
        if (refresh.isEmpty()) throw new IOException("it isn't signed in. Remove it in Settings and sign in to Google again.");
        JsonNode r;
        try {
            r = post(http, token, Map.of("client_id", clientId(), "client_secret", clientSecret(), "grant_type", "refresh_token",
                    "refresh_token", refresh));
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage());
        }
        if (!r.hasNonNull("access_token")) {
            throw new IOException(r.path("error").asText("").equals("invalid_grant")
                    ? "its Google sign-in ran out or was taken back. Remove it in Settings and sign in to Google again."
                    : "Google wouldn't let it read: " + error(r));
        }
        return r.get("access_token").asText();
    }

    // ---- reading the calendar ----

    static final String FIELDS = "items(id,iCalUID,status,summary,start,end,location,description,organizer,attendees,hangoutLink,"
            + "conferenceData/entryPoints,transparency,eventType),nextPageToken";

    /** Its main calendar's events from..to, in the same shape as an iCal calendar's (see Calendar.events). */
    static ArrayNode events(HttpClient http, String account, int slot, Instant from, Instant to, boolean hideDeclined)
            throws IOException, InterruptedException {
        String pass = accessToken(http, account);
        List<ObjectNode> list = new ArrayList<>();
        String pageToken = "";
        for (int pages = 0; pages < 50; pages++) {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("timeMin", from.toString());
            q.put("timeMax", to.toString());
            q.put("singleEvents", "true");
            q.put("orderBy", "startTime");
            q.put("maxResults", "250");
            q.put("fields", FIELDS);
            if (!pageToken.isEmpty()) q.put("pageToken", pageToken);
            JsonNode page = get(http, URI.create(API + "calendars/primary/events?" + form(q)), pass);
            for (JsonNode e : page.path("items")) {
                if (e.path("status").asText("").equals("cancelled")) continue;
                // Where you work today, as Google Calendar shows above the day: not an event.
                if (e.path("eventType").asText("").equals("workingLocation")) continue;
                ObjectNode n = event(e, slot);
                if (n == null) continue;
                if (n.path("declined").asBoolean() && hideDeclined) continue;
                list.add(n);
            }
            pageToken = page.path("nextPageToken").asText("");
            if (pageToken.isEmpty()) break;
        }
        list.sort(Comparator.comparing((ObjectNode n) -> n.path("start").asText()));
        ArrayNode out = Calendar.JSON.createArrayNode();
        list.forEach(out::add);
        return out;
    }

    static ObjectNode event(JsonNode e, int slot) {
        ObjectNode n = Calendar.JSON.createObjectNode();
        n.put("uid", Calendar.clip(e.path("iCalUID").asText(e.path("id").asText("")), 500));
        try {
            if (e.path("start").hasNonNull("date")) {
                n.put("start", java.time.LocalDate.parse(e.path("start").path("date").asText()).toString())
                        .put("end", java.time.LocalDate.parse(e.path("end").path("date").asText()).toString()).put("allDay", true);
            } else {
                n.put("start", java.time.OffsetDateTime.parse(e.path("start").path("dateTime").asText()).toInstant().toString())
                        .put("end", java.time.OffsetDateTime.parse(e.path("end").path("dateTime").asText()).toInstant().toString()).put("allDay", false);
            }
        } catch (RuntimeException bad) {
            return null;
        }
        String title = e.path("summary").asText("").strip();
        n.put("title", Calendar.clip(title.isEmpty() ? "(No title)" : title, 300));
        String location = Calendar.clip(e.path("location").asText("").strip(), 1000);
        if (!location.isEmpty()) n.put("location", location);
        ObjectNode org = person(e.path("organizer"));
        if (!org.isEmpty()) n.set("organizer", org);
        ArrayNode people = Calendar.JSON.createArrayNode();
        int all = 0;
        for (JsonNode a : e.path("attendees")) {
            if (a.path("resource").asBoolean()) continue; // a room, not a person
            String answer = switch (a.path("responseStatus").asText("")) {
                case "accepted" -> "accepted";
                case "declined" -> "declined";
                case "tentative" -> "tentative";
                case "needsAction" -> "needs-action";
                default -> "";
            };
            if (a.path("self").asBoolean()) {
                if (answer.equals("declined")) n.put("declined", true);
                if (answer.equals("tentative")) n.put("tentative", true);
            }
            all++;
            if (people.size() >= Calendar.MAX_ATTENDEES) continue;
            ObjectNode p = person(a);
            if (!answer.isEmpty()) p.put("answer", answer);
            people.add(p);
        }
        if (!people.isEmpty()) n.set("attendees", people);
        if (all > Calendar.MAX_ATTENDEES) n.put("moreAttendees", all - Calendar.MAX_ATTENDEES);
        // Google keeps a description as HTML when it was written in Google Calendar.
        String text = e.path("description").asText("");
        String description = Calendar.clip(text.contains("<") ? Calendar.plain(text) : text.strip(), 20_000);
        if (!description.isBlank()) n.put("description", description);
        Set<String> links = new LinkedHashSet<>();
        List<String> sources = new ArrayList<>(List.of(e.path("hangoutLink").asText("")));
        for (JsonNode ep : e.path("conferenceData").path("entryPoints")) {
            if (ep.path("entryPointType").asText("").equals("video")) sources.add(ep.path("uri").asText(""));
        }
        sources.add(location);
        sources.add(description);
        for (String s : sources) {
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

    static ObjectNode person(JsonNode a) {
        ObjectNode p = Calendar.JSON.createObjectNode();
        String name = Calendar.clip(a.path("displayName").asText("").strip(), 200);
        String email = a.path("email").asText("").strip().toLowerCase(Locale.ROOT);
        if (!name.isEmpty() && !name.equalsIgnoreCase(email)) p.put("name", name);
        if (email.contains("@") && email.length() <= 320) p.put("email", email);
        return p;
    }

    // ---- talking to Google ----

    static JsonNode post(HttpClient http, String url, Map<String, String> values) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded").header("User-Agent", "calendar (House Elves)")
                .POST(HttpRequest.BodyPublishers.ofString(form(values))).build();
        return parse(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
    }

    static JsonNode get(HttpClient http, URI url, String pass) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + pass)
                .header("User-Agent", "calendar (House Elves)").GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 401 || res.statusCode() == 403) {
            JsonNode r = parseQuietly(res.body());
            String reason = r.path("error").path("errors").path(0).path("reason").asText("");
            throw new IOException(reason.equals("accessNotConfigured")
                    ? "Google Calendar API isn't turned on in your Google Cloud project: turn it on under APIs & Services, Library."
                    : "Google wouldn't let it read the calendar (" + res.statusCode() + "). Your organisation may have to allow your Google client.");
        }
        if (res.statusCode() != 200) throw new IOException("Google answered " + res.statusCode() + ". Try again later.");
        return parse(res.body());
    }

    static JsonNode parse(String body) throws IOException {
        if (body.length() > Calendar.MAX_ICS_BYTES) throw new IOException("Google's answer was too big to read");
        try {
            return Calendar.JSON.readTree(body);
        } catch (IOException e) {
            throw new IOException("Google's answer wasn't readable. Try again later.");
        }
    }

    static JsonNode parseQuietly(String body) {
        try {
            return parse(body);
        } catch (IOException e) {
            return Calendar.JSON.createObjectNode();
        }
    }

    /** What Google said went wrong, as what to do where it can. */
    static String error(JsonNode r) {
        String e = r.path("error").asText("no answer");
        if (e.equals("invalid_client")) return "Google doesn't know that client ID and secret. Check them: Add calendar, Google, Sign in, Change app.";
        if (e.equals("admin_policy_enforced") || e.equals("org_internal")) return "your organisation doesn't allow this Google client. Ask IT to allow its client ID.";
        return Calendar.clip(r.path("error_description").asText(e), 300);
    }

    static String form(Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        values.forEach((k, v) -> out.append(out.isEmpty() ? "" : "&").append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                .append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return out.toString();
    }

    // ---- its file: read by the sign-in's two processes, so changed under a lock ----

    static ObjectNode read() {
        JsonNode n = Calendar.readJson(FILE);
        return n instanceof ObjectNode o ? o : Calendar.JSON.createObjectNode();
    }

    /** Changes its file under a lock, written whole and owner-only: it holds your sign-in. */
    static void update(Consumer<ObjectNode> change) throws IOException {
        Files.createDirectories(Config.DIR);
        try (FileChannel lock = FileChannel.open(LOCK, StandardOpenOption.CREATE, StandardOpenOption.WRITE); var held = lock.lock()) {
            ObjectNode n = read();
            change.accept(n);
            Path tmp = FILE.resolveSibling("google.json.new");
            Files.deleteIfExists(tmp);
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(tmp, Calendar.JSON.writeValueAsString(n));
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }
}
