import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** ~/.config/calendar/config: KEY=value lines. Elf Desk writes it from the settings in elf.yaml; secrets live only here. */
public class Config {

    static final Path DIR = Path.of(System.getProperty("user.home"), ".config", "calendar");
    static final Path FILE = DIR.resolve("config");
    static final String[] ORDINAL = {"First", "Second", "Third"};
    /** The only places it reads: what elf.yaml's reads says. */
    static final List<String> HOSTS = List.of("calendar.google.com", "outlook.office365.com");

    /** A calendar slot (1 to 3), its name on the desk, and its private address (never printed). */
    record Cal(int slot, String name, URI address) {
    }

    List<Cal> calendars = new ArrayList<>();
    /** The slots with no address: their saved events are removed. */
    List<Integer> unused = new ArrayList<>();
    int weeksAhead;
    boolean hideDeclined;

    static Config load() throws IOException {
        Map<String, String> raw = new HashMap<>();
        if (Files.exists(FILE)) {
            for (String line : Files.readAllLines(FILE)) {
                line = line.strip();
                int eq = line.indexOf('=');
                if (!line.startsWith("#") && eq > 0) raw.putIfAbsent(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        }
        Config c = new Config();
        String[] names = {"Gmail", "Red Hat", "IBM"};
        for (int i = 1; i <= 3; i++) {
            String name = raw.getOrDefault("CAL" + i + "_NAME", "").strip();
            if (name.isEmpty()) name = names[i - 1];
            String address = raw.getOrDefault("CAL" + i + "_ICS_SECRET", "").strip();
            if (address.isEmpty()) {
                c.unused.add(i);
                continue;
            }
            // Outlook sometimes hands out webcal:// for the same https address.
            if (address.regionMatches(true, 0, "webcal://", 0, 9)) address = "https://" + address.substring(9);
            URI uri;
            try {
                uri = URI.create(address);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(ORDINAL[i - 1] + " calendar's address isn't a web address. Copy it again in Settings.");
            }
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || !HOSTS.contains(uri.getHost().toLowerCase())) {
                throw new IllegalStateException(ORDINAL[i - 1] + " calendar's address should start with https://calendar.google.com/"
                        + " or https://outlook.office365.com/. Copy its iCal address again in Settings.");
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
}
