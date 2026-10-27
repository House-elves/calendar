import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** ~/.config/my-elf/config: KEY=value lines. Elf Desk writes it from the settings in elf.yaml; secrets live only here. */
public class Config {

    static final Path DIR = Path.of(System.getProperty("user.home"), ".config", "my-elf");
    static final Path FILE = DIR.resolve("config");

    String feedUrl;
    int maxItems;
    boolean sumUp;

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
        c.feedUrl = raw.getOrDefault("FEED_URL", "");
        c.maxItems = Integer.parseInt(raw.getOrDefault("MAX_ITEMS", "30"));
        c.sumUp = Boolean.parseBoolean(raw.getOrDefault("SUM_UP", "false"));
        if (c.feedUrl.isBlank()) throw new IllegalStateException("Set the feed in my-elf's settings on the desk");
        if (!c.feedUrl.startsWith("https://") && !c.feedUrl.startsWith("http://")) throw new IllegalStateException("The feed is a web address");
        return c;
    }
}
