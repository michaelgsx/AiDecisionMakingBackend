package com.aidecision.backend.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads a local {@code .env} file into the Spring {@link org.springframework.core.env.Environment}
 * so {@code AZURE_OPENAI_*} and other keys work when running {@code ./mvnw spring-boot:run} from {@code backend/}.
 * <p>
 * OS / App Service environment variables still take precedence (same key wins first).
 * Skipped during Maven Surefire tests to avoid leaking developer secrets into unit tests.
 */
public class DotEnvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (runningUnderSurefire()) {
            return;
        }

        Path envFile = resolveEnvFile();
        if (envFile == null || !Files.isRegularFile(envFile)) {
            return;
        }

        Map<String, Object> map = parseDotEnv(envFile);
        if (map.isEmpty()) {
            return;
        }

        MutablePropertySources sources = environment.getPropertySources();
        sources.remove("dotenvFile");
        MapPropertySource dotenv = new MapPropertySource("dotenvFile", map);
        org.springframework.core.env.PropertySource<?> sysEnv =
                sources.get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        if (sysEnv != null) {
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, dotenv);
        } else {
            sources.addFirst(dotenv);
        }
    }

    private static boolean runningUnderSurefire() {
        return System.getProperty("surefire.test.class.path") != null
                || System.getProperty("surefire.real.class.path") != null;
    }

    /**
     * Prefer {@code backend/.env} when cwd is repo root, else {@code ./.env} when cwd is {@code backend/}.
     */
    private static Path resolveEnvFile() {
        Path userDir = Paths.get(System.getProperty("user.dir", ".")).normalize();
        List<Path> candidates = new ArrayList<>();
        candidates.add(userDir.resolve(".env"));
        candidates.add(userDir.resolve("backend").resolve(".env"));
        Path parent = userDir.getParent();
        if (parent != null) {
            candidates.add(parent.resolve(".env"));
        }
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    private static Map<String, Object> parseDotEnv(Path file) {
        Map<String, Object> map = new LinkedHashMap<>();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    continue;
                }
                String value = unquote(line.substring(eq + 1).trim());
                map.putIfAbsent(key, value);
            }
        } catch (IOException ignored) {
            return Map.of();
        }
        return map;
    }

    private static String unquote(String v) {
        if (v.length() >= 2) {
            if (v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
                return v.substring(1, v.length() - 1);
            }
            if (v.charAt(0) == '\'' && v.charAt(v.length() - 1) == '\'') {
                return v.substring(1, v.length() - 1);
            }
        }
        return v;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
