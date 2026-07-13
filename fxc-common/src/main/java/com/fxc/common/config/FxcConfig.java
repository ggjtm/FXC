package com.fxc.common.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * Minimal layered configuration for the FXC components. Each component reads a flat key/value file
 * ({@code conf/<component>.conf}) with localhost defaults so {@code ./gradlew :X:run} works out of
 * the box (see {@code docs/DESIGN.md} §5). Resolution order, highest precedence first:
 *
 * <ol>
 *   <li>JVM system properties ({@code -Dkey=value})</li>
 *   <li>the loaded config file</li>
 *   <li>the caller-supplied default</li>
 * </ol>
 *
 * <p>Kept deliberately dependency-free (no HOCON library) — the config files are simple
 * {@link Properties}-parseable {@code key=value} text. A richer format can be swapped in later
 * without changing call sites.
 */
public final class FxcConfig {

    private final Properties props;

    private FxcConfig(Properties props) {
        this.props = props;
    }

    /** Load from a file on disk. */
    public static FxcConfig load(Path file) {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read config file: " + file, e);
        }
        return new FxcConfig(p);
    }

    /** Load from a classpath resource (e.g. a bundled default config). */
    public static FxcConfig loadResource(String resourcePath) {
        Properties p = new Properties();
        try (InputStream in = FxcConfig.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("Config resource not found on classpath: " + resourcePath);
            }
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read config resource: " + resourcePath, e);
        }
        return new FxcConfig(p);
    }

    /** An empty configuration (every lookup falls through to its default). */
    public static FxcConfig empty() {
        return new FxcConfig(new Properties());
    }

    public Optional<String> find(String key) {
        String sys = System.getProperty(key);
        if (sys != null) {
            return Optional.of(sys);
        }
        return Optional.ofNullable(props.getProperty(key));
    }

    public String getString(String key, String defaultValue) {
        return find(key).orElse(defaultValue);
    }

    public String requireString(String key) {
        return find(key).orElseThrow(() -> new IllegalStateException("Missing required config key: " + key));
    }

    public int getInt(String key, int defaultValue) {
        return find(key).map(Integer::parseInt).orElse(defaultValue);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return find(key).map(Boolean::parseBoolean).orElse(defaultValue);
    }
}
