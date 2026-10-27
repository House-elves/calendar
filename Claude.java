import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Claude for an elf, locked down: what it reads includes other people's words (a feed, an email, a chat), so it gets
 * no tools (built in or MCP), none of your settings, and an empty folder of its own. Its answer is only text, for
 * the elf to check and save; it can't do anything.
 */
public class Claude {

    /** Claude's answer to the prompt, or null (and why, on stderr) if it failed. */
    static String ask(String prompt, int minutes) {
        try {
            Process p = new ProcessBuilder(List.of("claude", "-p", "--model", "sonnet", "--tools", "", "--strict-mcp-config",
                    "--setting-sources", "")).directory(Files.createTempDirectory("elf-claude-").toFile()).start();
            CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> read(p.getInputStream()));
            CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> read(p.getErrorStream()));
            try (var os = p.getOutputStream()) {
                os.write(prompt.getBytes(StandardCharsets.UTF_8));
            }
            if (!p.waitFor(minutes, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                System.err.println("Claude took too long");
                return null;
            }
            if (p.exitValue() != 0) {
                System.err.println("Claude failed: " + err.get(10, TimeUnit.SECONDS).strip());
                return null;
            }
            return out.get(10, TimeUnit.SECONDS).strip();
        } catch (Exception e) {
            System.err.println("Claude error: " + e.getMessage());
            return null;
        }
    }

    static String read(InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
