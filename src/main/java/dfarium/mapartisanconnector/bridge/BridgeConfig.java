package dfarium.mapartisanconnector.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** config/mapartisan_connector.json, read on every /mapartisan start so edits apply without restarting. */
public record BridgeConfig(int port, List<String> allowedOrigins) {
    public static final BridgeConfig DEFAULT = new BridgeConfig(25590, List.of(
            "http://localhost:5173", "http://127.0.0.1:5173",
            "http://localhost:4173", "http://127.0.0.1:4173"));

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Writes the defaults when the file is missing. Throws IllegalStateException on an unreadable file. */
    public static BridgeConfig load(Path file) {
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    GSON.toJson(DEFAULT, writer);
                }
                return DEFAULT;
            }
            BridgeConfig config;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                config = GSON.fromJson(reader, BridgeConfig.class);
            }
            if (config == null || config.port() < 1024 || config.port() > 65535
                    || config.allowedOrigins() == null || config.allowedOrigins().isEmpty()) {
                throw new IllegalStateException(file.getFileName() + ": port must be between 1024 and 65535 and allowedOrigins cannot be empty");
            }
            return config;
        } catch (IOException | JsonParseException e) {
            throw new IllegalStateException("Could not read " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }
}
