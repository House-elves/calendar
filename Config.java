import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * ~/.config/calendar/config: KEY=value lines. Elf Desk writes its settings from elf.yaml; the calendars are
 * CAL{n}_NAME and CAL{n}_ICS_SECRET lines, which its Settings list adds and removes here. Secrets live only here.
 */
public class Config {

    static final Path DIR = Path.of(System.getProperty("user.home"), ".config", "calendar");
    static final Path FILE = DIR.resolve("config");
    /** How many calendars it reads at most: each one is a read every hour. */
    static final int MAX = 20;
    static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N} ._'&()+-]{1,40}");

    /** A calendar's slot (1 to MAX, its colour on the desk), its name, and its private address (never printed). */
    record Cal(int slot, String name, URI address) {
    }

    List<Cal> calendars = new ArrayList<>();
    /** The slots with no address: their saved events are removed. */
    List<Integer> unused = new ArrayList<>();
    int weeksAhead;
    boolean hideDeclined;

    static Map<String, String> raw() throws IOException {
        Map<String, String> raw = new HashMap<>();
        if (Files.exists(FILE)) {
            for (String line : Files.readAllLines(FILE)) {
                line = line.strip();
                int eq = line.indexOf('=');
                if (!line.startsWith("#") && eq > 0) raw.putIfAbsent(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        }
        return raw;
    }

    static Config load() throws IOException {
        Map<String, String> raw = raw();
        Config c = new Config();
        for (int i = 1; i <= MAX; i++) {
            String name = name(raw, i);
            String address = raw.getOrDefault("CAL" + i + "_ICS_SECRET", "").strip();
            if (address.isEmpty()) {
                c.unused.add(i);
                continue;
            }
            URI uri = address(address);
            if (uri == null) {
                throw new IllegalStateException(name + "'s address isn't a calendar's https:// or webcal:// address, or an .ics file."
                        + " Remove it in Settings and add it again with its iCal address.");
            }
            c.calendars.add(new Cal(i, name, uri));
        }
        try {
            c.weeksAhead = Math.max(1, Math.min(26, Integer.parseInt(raw.getOrDefault("WEEKS_AHEAD", "6"))));
        } catch (NumberFormatException e) {
            c.weeksAhead = 6;
        }
        c.hideDeclined = Boolean.parseBoolean(raw.getOrDefault("HIDE_DECLINED", "true"));
        return c;
    }

    static String name(Map<String, String> raw, int slot) {
        String name = raw.getOrDefault("CAL" + slot + "_NAME", "").strip();
        return name.isEmpty() ? "Calendar " + slot : name;
    }

    /**
     * An iCal address as it's read: https only (webcal:// is the same address), or an .ics file on this machine, for a
     * calendar whose account won't publish one. Null if it's neither.
     */
    static URI address(String address) {
        address = address.strip();
        if (address.regionMatches(true, 0, "microsoft:", 0, 10)) {
            String account = address.substring(10).toLowerCase(Locale.ROOT);
            return Microsoft.ACCOUNT.matcher(account).matches() ? URI.create("microsoft:" + account) : null;
        }
        if (address.regionMatches(true, 0, "google:", 0, 7)) {
            String account = address.substring(7).toLowerCase(Locale.ROOT);
            return Microsoft.ACCOUNT.matcher(account).matches() ? URI.create("google:" + account) : null;
        }
        int colon = address.indexOf(':');
        if (colon > 0 && Local.is(address.substring(0, colon))) {
            String rest = address.substring(colon + 1);
            return rest.matches("[\\w.@+-]{1,200}(/[\\w.@+-]{1,200}){0,2}") ? URI.create(address.substring(0, colon).toLowerCase(Locale.ROOT) + ":" + rest) : null;
        }
        if (address.startsWith("/") || address.startsWith("~/") || address.regionMatches(true, 0, "file:", 0, 5)) return file(address);
        // Outlook and iCloud hand out webcal:// for the same https address.
        if (address.regionMatches(true, 0, "webcal://", 0, 9)) address = "https://" + address.substring(9);
        try {
            URI uri = URI.create(address);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null) return null;
            return uri;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** An .ics file's path as a file: address, or null if it isn't a whole path to an .ics file. */
    static URI file(String path) {
        try {
            Path p = path.regionMatches(true, 0, "file:", 0, 5) ? Path.of(URI.create(path))
                    : path.startsWith("~/") ? Path.of(System.getProperty("user.home"), path.substring(2)) : Path.of(path);
            p = p.normalize();
            if (!p.isAbsolute() || p.getFileName() == null || !p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ics")) return null;
            return p.toUri();
        } catch (IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
            return null;
        }
    }

    /** A calendar read through a Microsoft sign-in, not an address: its "address" only names the account. */
    static boolean isMicrosoft(URI address) {
        return "microsoft".equalsIgnoreCase(address.getScheme());
    }

    /** A calendar read through a Google sign-in, not an address: its "address" only names the account. */
    static boolean isGoogle(URI address) {
        return "google".equalsIgnoreCase(address.getScheme());
    }

    /** A calendar another app on this machine keeps a copy of: Evolution, Thunderbird or Apple Calendar (see Local). */
    static boolean isLocal(URI address) {
        return Local.is(address.getScheme());
    }

    static boolean isFile(URI address) {
        return "file".equalsIgnoreCase(address.getScheme());
    }

    /** Where a calendar lives, to show next to its name: the server's name, never the private part of the address. */
    static String where(URI address) {
        if (isFile(address)) return "File on this machine";
        if (isMicrosoft(address)) return "Outlook, signed in";
        if (isGoogle(address)) return "Google Calendar, signed in";
        if (isLocal(address)) return Local.app(address.getScheme()) + "'s copy on this machine";
        String host = address.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("calendar.google.com")) return "Google Calendar";
        if (host.startsWith("outlook.") || host.endsWith(".outlook.com")) return "Outlook";
        if (host.endsWith("icloud.com")) return "iCloud";
        return host;
    }

    // ---- the list in its Settings: written here, never through the desk ----

    /** Adds a calendar in the first free slot. Answers its slot, or throws with what to do. */
    static int add(String name, String address) throws IOException {
        name = name.strip();
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("Give it a short name: letters, digits and spaces, up to 40.");
        URI uri = address(address);
        if (uri == null) {
            throw new IllegalArgumentException("That isn't an iCal address or .ics file. An address starts with https:// or webcal://;"
                    + " a file is its whole path, like ~/Documents/work.ics.");
        }
        if (isMicrosoft(uri)) throw new IllegalArgumentException("Use Sign in to Microsoft for that.");
        if (isGoogle(uri)) throw new IllegalArgumentException("Use Sign in to Google for that.");
        if (isLocal(uri)) throw new IllegalArgumentException("Pick it under On this machine, Find calendars, instead.");
        if (isFile(uri) && !Files.isRegularFile(Path.of(uri))) throw new IllegalArgumentException("There's no file at " + address.strip() + ".");
        return addAt(name, uri);
    }

    /** Adds the calendar of a Microsoft account you just signed in to. Signing in again to the same one keeps its slot. */
    static int addMicrosoft(String name, String account) throws IOException {
        URI uri = URI.create("microsoft:" + account);
        Map<String, String> raw = raw();
        for (int i = 1; i <= MAX; i++) {
            if (uri.equals(address(raw.getOrDefault("CAL" + i + "_ICS_SECRET", "")))) return i;
        }
        return addAt(name.strip(), uri);
    }

    /** Adds the calendar of a Google account you just signed in to. Signing in again to the same one keeps its slot. */
    static int addGoogle(String name, String account) throws IOException {
        URI uri = URI.create("google:" + account);
        Map<String, String> raw = raw();
        for (int i = 1; i <= MAX; i++) {
            if (uri.equals(address(raw.getOrDefault("CAL" + i + "_ICS_SECRET", "")))) return i;
        }
        return addAt(name.strip(), uri);
    }

    /** Adds a calendar another app on this machine keeps, if it's one Local found. */
    static int addLocal(String name, String source) throws IOException {
        name = name.strip();
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("Give it a short name: letters, digits and spaces, up to 40.");
        URI uri = address(source);
        if (uri == null || !isLocal(uri) || Local.find().stream().noneMatch(f -> f.source().equals(uri.toString()))) {
            throw new IllegalArgumentException("That calendar isn't on this machine any more. Open its app and let it sync, then try again.");
        }
        return addAt(name, uri);
    }

    static int addAt(String name, URI uri) throws IOException {
        Map<String, String> raw = raw();
        int free = 0;
        for (int i = MAX; i >= 1; i--) {
            String old = raw.getOrDefault("CAL" + i + "_ICS_SECRET", "").strip();
            if (old.isEmpty()) {
                free = i;
            } else if (uri.equals(address(old))) {
                throw new IllegalArgumentException("That calendar is already here, as " + name(raw, i) + ".");
            }
        }
        if (free == 0) throw new IllegalArgumentException("It reads up to " + MAX + " calendars. Remove one first.");
        rewrite(free, Map.of("CAL" + free + "_NAME", name, "CAL" + free + "_ICS_SECRET", uri.toString()));
        return free;
    }

    /** Removes a calendar: its name and its address go from the config file. */
    static void remove(int slot) throws IOException {
        if (slot < 1 || slot > MAX) throw new IllegalArgumentException("There's no such calendar.");
        rewrite(slot, Map.of());
    }

    /** The config file with this slot's lines replaced, written whole and owner-only, as the desk writes it. */
    static void rewrite(int slot, Map<String, String> lines) throws IOException {
        rewrite(Set.of("CAL" + slot + "_NAME", "CAL" + slot + "_ICS_SECRET"), lines);
    }

    /** Settings Calendar's own Settings set, like the Microsoft app: an empty value takes its line out. */
    static void set(Map<String, String> values) throws IOException {
        Map<String, String> lines = new TreeMap<>(values);
        lines.values().removeIf(String::isEmpty);
        rewrite(values.keySet(), lines);
    }

    /** The config file without these keys' lines, these lines added, written whole and owner-only. */
    static void rewrite(Set<String> drop, Map<String, String> lines) throws IOException {
        List<String> out = new ArrayList<>();
        if (Files.exists(FILE)) {
            for (String line : Files.readAllLines(FILE)) {
                String key = line.strip();
                int eq = key.indexOf('=');
                key = eq > 0 ? key.substring(0, eq).strip() : "";
                if (!drop.contains(key)) out.add(line);
            }
        }
        // Name first, then address, so the file reads in pairs.
        lines.entrySet().stream().sorted(Map.Entry.comparingByKey((a, b) -> a.endsWith("_NAME") ? -1 : b.endsWith("_NAME") ? 1 : 0))
                .forEach(e -> out.add(e.getKey() + "=" + e.getValue()));
        Files.createDirectories(DIR);
        Path tmp = FILE.resolveSibling("config.new");
        Files.deleteIfExists(tmp);
        Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(tmp, out);
        Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
