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
                throw new IllegalStateException(name + "'s address isn't a calendar's https:// or webcal:// address."
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

    /** An iCal address as it's read: https only (webcal:// is the same address), or null if it isn't one. */
    static URI address(String address) {
        address = address.strip();
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

    /** Where a calendar lives, to show next to its name: the server's name, never the private part of the address. */
    static String where(URI address) {
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
        if (uri == null) throw new IllegalArgumentException("That isn't an iCal address. It starts with https:// or webcal://.");
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
        List<String> out = new ArrayList<>();
        if (Files.exists(FILE)) {
            for (String line : Files.readAllLines(FILE)) {
                String key = line.strip();
                int eq = key.indexOf('=');
                key = eq > 0 ? key.substring(0, eq).strip() : "";
                if (!key.equals("CAL" + slot + "_NAME") && !key.equals("CAL" + slot + "_ICS_SECRET")) out.add(line);
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
