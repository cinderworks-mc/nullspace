package dev.nullspace;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

// the optional mods are compile only. their types may appear only in their own integration package, and that package
// may only be reached behind a ModList check for the mod, or the game fails to start when the mod is missing
class SoftDependencyTest {
    private static final Path SOURCES = Path.of("src/main/java");

    // foreign package -> the integration package allowed to import it
    private static final Map<String, String> FOREIGN = Map.of(
        "net.blay09.", "integration/waystones/",
        "tschipp.carryon.", "integration/carryon/",
        "xaero.pac.", "integration/opac/",
        "dev.ftb.mods.ftbchunks.", "integration/ftbchunks/",
        "dev.ftb.mods.ftbteams.", "integration/ftbteams/");

    // integration entry class -> mod id that has to be checked before it is touched
    private static final Map<String, String> ENTRY = Map.of(
        "WaystonesGuard.", "waystones",
        "CarryOnGuard.", "carryon",
        "OpacIntegration.", "openpartiesandclaims",
        "FtbChunksIntegration.", "ftbchunks",
        "FtbTeamsProvider.", "ftbteams");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> s = Files.walk(SOURCES)) { return s.filter(p -> p.toString().endsWith(".java")).sorted().toList(); }
    }

    @Test void foreignTypesStayInTheirIntegrationPackage() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : sources()) {
            String path = file.toString().replace('\\', '/');
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.startsWith("import ")) continue;
                FOREIGN.forEach((pkg, home) -> {
                    if (line.contains(" " + pkg) && !path.contains(home)) offenders.add(path + "  " + line.trim());
                });
            }
        }
        assertTrue(offenders.isEmpty(), "optional mod types outside their integration package:\n" + String.join("\n", offenders));
    }

    @Test void integrationsAreOnlyReachedBehindAModCheck() throws IOException {
        List<String> offenders = new ArrayList<>();
        int calls = 0;
        for (Path file : sources()) {
            String path = file.toString().replace('\\', '/');
            if (path.contains("/integration/")) continue;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.startsWith("import ")) continue;
                for (var e : ENTRY.entrySet()) {
                    if (!line.contains(e.getKey())) continue;
                    calls++;
                    String guard = "isLoaded(\"" + e.getValue() + "\")";
                    boolean checked = false;
                    for (int j = Math.max(0, i - 2); j <= i; j++) checked |= lines.get(j).contains(guard);
                    if (!checked) offenders.add(path + ":" + (i + 1) + "  " + line.trim());
                }
            }
        }
        assertEquals(ENTRY.size(), calls, "every integration has exactly one entry call");
        assertTrue(offenders.isEmpty(), "integration reached without its ModList check:\n" + String.join("\n", offenders));
    }

    @Test void waystonesIsDeclaredOptional() throws IOException {
        String toml = Files.readString(Path.of("src/main/resources/META-INF/neoforge.mods.toml"), StandardCharsets.UTF_8);
        int at = toml.indexOf("modId=\"waystones\"");
        assertTrue(at > 0, "waystones is declared");
        String block = toml.substring(at, toml.indexOf("side=", at));
        assertTrue(block.contains("type=\"optional\""), "waystones must stay optional:\n" + block);
    }
}
