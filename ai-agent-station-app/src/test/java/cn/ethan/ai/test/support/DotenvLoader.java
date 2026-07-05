package cn.ethan.ai.test.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Loads a {@code .env} file from the module root into system properties.
 * <p>Only sets properties that are not already present, so command-line
 * {@code -D} flags always take precedence. Lines starting with {@code #}
 * and empty lines are ignored.</p>
 */
public final class DotenvLoader {

    private static final String DOTENV = ".env";
    private static volatile boolean loaded;

    private DotenvLoader() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        Path file = resolveModuleRoot().resolve(DOTENV);
        if (!Files.isRegularFile(file)) {
            loaded = true;
            return;
        }
        Properties properties = parse(file);
        properties.forEach((key, value) -> {
            String name = String.valueOf(key);
            if (System.getProperty(name) == null) {
                System.setProperty(name, String.valueOf(value));
            }
        });
        loaded = true;
    }

    private static Properties parse(Path file) {
        Properties properties = new Properties();
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    return;
                }
                int separator = trimmed.indexOf('=');
                if (separator <= 0) {
                    return;
                }
                String key = trimmed.substring(0, separator).trim();
                String value = trimmed.substring(separator + 1).trim();
                if (key.isEmpty()) {
                    return;
                }
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                properties.setProperty(key, value);
            });
        } catch (IOException ignored) {
        }
        return properties;
    }

    private static Path resolveModuleRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        if (cwd.getFileName() != null
                && "ai-agent-station-app".equals(cwd.getFileName().toString())) {
            return cwd;
        }
        Path module = cwd.resolve("ai-agent-station-app");
        if (Files.isDirectory(module)) {
            return module;
        }
        return cwd;
    }
}
