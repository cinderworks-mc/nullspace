package dev.nullspace;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

// lint for player text: no english literals in server, client, integration or core code, every key used in code
// exists in the lang file, and every lang key is used
class MessagesTest {
    private static final Path SOURCES = Path.of("src/main/java");
    private static final Path LANG = Path.of("src/main/resources/assets/nullspace/lang/en_us.json");

    private static final Pattern LITERAL_WITH_WORDS = Pattern.compile("Component\\.literal\\(\\s*\"[^\"]*[A-Za-z]{2}");
    private static final Pattern PLAIN_THROW = Pattern.compile("new (IllegalArgument|IllegalState|Security)Exception\\(");
    private static final Pattern KEY = Pattern.compile("\"((?:message|error|gui|command|finish)\\.nullspace\\.[a-z0-9_.]*|nullspace\\.perm\\.[a-z0-9_.]*)\"");
    // keys minecraft looks up from the registry (items, blocks, tab, sounds) and the tempad access names are not
    // named in our code
    private static final Pattern REGISTRY_KEY = Pattern.compile("^(item|block|itemGroup|subtitles|tag\\.item)\\..*|^access\\.nullspace\\.guests$|^upgrade\\.nullspace\\.archive_link$|^(dimension|provider)\\.nullspace\\.[a-z_]+$");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> s = Files.walk(SOURCES)) { return s.filter(p -> p.toString().endsWith(".java")).sorted().toList(); }
    }

    private static Map<String, String> lang() throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        Matcher m = Pattern.compile("^\\s*\"([^\"]+)\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\",?\\s*$", Pattern.MULTILINE).matcher(Files.readString(LANG, StandardCharsets.UTF_8));
        while (m.find()) map.put(m.group(1), m.group(2));
        return map;
    }

    @Test void noHardCodedEnglishInPlayerFacingCode() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : sources()) {
            String path = file.toString().replace('\\', '/');
            boolean core = path.contains("/core/");
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.contains("// dev only")) continue;
                if (!line.contains("i18n-ok") && LITERAL_WITH_WORDS.matcher(line).find()) offenders.add(path + ":" + (i + 1) + "  " + line.trim());
                if (core && PLAIN_THROW.matcher(line).find()) offenders.add(path + ":" + (i + 1) + "  core must throw ArchiveException/ArchiveSecurityException: " + line.trim());
            }
        }
        assertTrue(offenders.isEmpty(), "English literals in player-facing code (move them to en_us.json, or mark a non-text literal with i18n-ok):\n" + String.join("\n", offenders));
    }

    @Test void everyKeyUsedInCodeExistsAndEveryLangKeyIsUsed() throws IOException {
        Map<String, String> lang = lang();
        assertTrue(lang.size() > 100, "lang file should parse (found " + lang.size() + " entries)");
        Set<String> used = new TreeSet<>(), prefixes = new TreeSet<>();
        for (Path file : sources()) {
            Matcher m = KEY.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (m.find()) (m.group(1).endsWith(".") ? prefixes : used).add(m.group(1));
        }
        List<String> missing = used.stream().filter(k -> !lang.containsKey(k)).toList();
        assertTrue(missing.isEmpty(), "keys referenced in code but missing from en_us.json: " + missing);
        for (String prefix : prefixes) assertTrue(lang.keySet().stream().anyMatch(k -> k.startsWith(prefix)), "no lang key matches the computed key prefix " + prefix);
        List<String> unused = lang.keySet().stream()
            .filter(k -> !REGISTRY_KEY.matcher(k).matches() && !used.contains(k) && prefixes.stream().noneMatch(k::startsWith)).toList();
        assertTrue(unused.isEmpty(), "keys in en_us.json that no code uses: " + unused);
    }

    // Tempad titles the sections of its teleport list with toLanguageKey("dimension") of the destination's dimension and toLanguageKey("provider")
    // of the provider, and shows the raw key when the lang file has none
    @Test void tempadSectionTitlesForTheArchiveAreNamed() throws IOException {
        Map<String, String> lang = lang();
        assertTrue(lang.containsKey("dimension.nullspace.archive"), "the Tempad list sorted by dimension shows dimension.nullspace.archive raw");
        assertTrue(lang.containsKey("provider.nullspace.archives"), "the Tempad list sorted by type shows provider.nullspace.archives raw");
    }

    @Test void langEntriesAreNeverRawKeysAndPlaceholdersAreWellFormed() throws IOException {
        Pattern placeholder = Pattern.compile("%(?:\\d+\\$)?[a-z]");
        for (var e : lang().entrySet()) {
            assertFalse(e.getValue().isBlank(), e.getKey() + " is empty");
            assertFalse(e.getValue().startsWith("message.nullspace.") || e.getValue().startsWith("error.nullspace."), e.getKey() + " reads like a key");
            Matcher m = placeholder.matcher(e.getValue());
            while (m.find()) assertTrue(m.group().matches("%\\d+\\$s"), e.getKey() + " should use positional %n$s placeholders, found " + m.group());
        }
    }

    // the item ids that get grey lines under the name (client.ItemTooltips): one to four short lines each
    private static final List<String> TOOLTIP_ITEMS = List.of("access_cartridge", "spatial_expander", "annex_module", "continuity_anchor", "recall_module",
        "archive_link_upgrade", "surface_applicator", "waypoint_module", "archive_terminal", "destination_pad");

    @Test void everyItemWithATooltipKeepsItShort() throws IOException {
        Map<String, String> lang = lang();
        for (String id : TOOLTIP_ITEMS) {
            String base = "item.nullspace." + id + ".tooltip.";
            assertTrue(lang.containsKey(base + "1"), id + " has no tooltip");
            assertFalse(lang.containsKey(base + "5"), id + " has more than four tooltip lines");
            boolean gap = false;
            for (int i = 1; i <= 4; i++) {
                String line = lang.get(base + i);
                if (line == null) { gap = true; continue; }
                assertFalse(gap, id + " tooltip lines must be numbered without gaps");
                assertTrue(line.split("\\s+").length <= 12 && line.length() <= 48, id + " tooltip line " + i + " is too long: " + line);
            }
        }
    }

    @Test void textFollowsTheHouseStyle() throws IOException {
        Pattern banned = Pattern.compile("\\w - \\w|\u2014|\u2013|[Pp]lease|successfully|for help|\\bON\\b|\\bOFF\\b|\\w\\(s\\)");
        Pattern spacedSize = Pattern.compile("[\\d$s]\\s+x\\s+[\\d%]");
        for (var e : lang().entrySet()) {
            String key = e.getKey(), value = e.getValue();
            assertFalse(banned.matcher(value).find(), key + " breaks the style: " + value);
            assertFalse(spacedSize.matcher(value).find(), key + " writes a size as 'a x b': " + value);
            // sizes are 13x13; the multiplication sign is only for item counts
            assertTrue(!value.contains("\\u00d7") || key.equals("message.nullspace.cost.item"), key + " uses the multiplication sign");
            if (key.startsWith("message.") || key.startsWith("error.") || key.startsWith("command."))
                assertTrue(value.split("\\s+").length <= 25, key + " is over 25 words: " + value);
        }
    }

    @Test void everyCoreExceptionKeyIsInTheLangFile() throws IOException {
        Map<String, String> lang = lang();
        Pattern core = Pattern.compile("Archive(?:Security)?Exception\\(\\s*\"([a-z0-9_.]+)\"");
        int found = 0;
        for (Path file : sources()) {
            Matcher m = core.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (m.find()) { found++; assertTrue(lang.containsKey(m.group(1)), file + " throws unknown key " + m.group(1)); }
        }
        assertTrue(found > 20, "expected many keyed exceptions, found " + found);
    }
}
