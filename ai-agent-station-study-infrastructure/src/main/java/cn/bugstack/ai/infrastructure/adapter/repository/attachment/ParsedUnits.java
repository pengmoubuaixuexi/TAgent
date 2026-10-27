package cn.bugstack.ai.infrastructure.adapter.repository.attachment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Random access to the units of a parsed file: chunk files on disk, or an in-memory list for artifacts. */
final class ParsedUnits {

    record Section(String name, int first, int count, List<String> columns) {
        int last() { return first + count - 1; }
    }

    record Match(int unit, String snippet) { }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String unit;
    private final int total;
    private final List<Section> sections;
    private final Path dir;
    private final List<ParsedFileWriter.Chunk> chunks;
    private final List<Object> memory;

    private ParsedUnits(String unit, int total, List<Section> sections, Path dir,
                        List<ParsedFileWriter.Chunk> chunks, List<Object> memory) {
        this.unit = unit;
        this.total = total;
        this.sections = sections;
        this.dir = dir;
        this.chunks = chunks;
        this.memory = memory;
    }

    /** @throws NoSuchFileException when the cache was removed and the file must be parsed again */
    static ParsedUnits open(Path dir) throws IOException {
        JsonNode index = JSON.readTree(Files.readString(dir.resolve(ParsedFileWriter.INDEX), StandardCharsets.UTF_8));
        List<Section> sections = new ArrayList<>();
        for (JsonNode s : index.path("sections")) {
            List<String> columns = null;
            if (s.path("columns").isArray()) {
                columns = new ArrayList<>();
                for (JsonNode c : s.path("columns")) columns.add(c.asText());
            }
            sections.add(new Section(s.path("name").isNull() ? null : s.path("name").asText(null),
                    s.path("first").asInt(), s.path("count").asInt(), columns));
        }
        List<ParsedFileWriter.Chunk> chunks = new ArrayList<>();
        for (JsonNode c : index.path("chunks")) {
            String file = c.path("file").asText();
            if (!file.matches("\\d{5}\\.jsonl")) throw new IOException("Invalid chunk name");
            chunks.add(new ParsedFileWriter.Chunk(file, c.path("first").asInt(), c.path("count").asInt()));
        }
        return new ParsedUnits(index.path("unit").asText("LINE"), index.path("units").asInt(), sections, dir, chunks, null);
    }

    static ParsedUnits memory(String unit, List<ParsedFileWriter.Section> sections, List<Object> units) {
        List<Section> mapped = sections.stream().map(s -> new Section(s.name(), s.first(), s.count(), s.columns())).toList();
        return new ParsedUnits(unit, units.size(), mapped, null, List.of(), units);
    }

    String unit() { return unit; }

    int total() { return total; }

    List<Section> sections() { return sections; }

    /** Units {@code first..first+count-1} (1-based, global across sheets). */
    List<Object> read(int first, int count) throws IOException {
        int from = Math.max(1, first), to = Math.min(total, first + count - 1);
        List<Object> out = new ArrayList<>(Math.max(0, to - from + 1));
        if (from > to) return out;
        if (memory != null) {
            out.addAll(memory.subList(from - 1, to));
            return out;
        }
        for (ParsedFileWriter.Chunk chunk : chunks) {
            int chunkLast = chunk.first() + chunk.count() - 1;
            if (chunkLast < from || chunk.first() > to) continue;
            try (BufferedReader reader = Files.newBufferedReader(dir.resolve(chunk.file()), StandardCharsets.UTF_8)) {
                int number = chunk.first();
                for (String line; (line = reader.readLine()) != null && number <= to; number++) {
                    if (number >= from) out.add(decode(line));
                }
            }
        }
        return out;
    }

    /** Case-insensitive substring search inside units {@code first..last}. */
    List<Match> search(int first, int last, String query, int limit) throws IOException {
        List<Match> matches = new ArrayList<>();
        String needle = query.toLowerCase(Locale.ROOT);
        if (memory != null) {
            for (int i = Math.max(1, first); i <= Math.min(last, total) && matches.size() < limit; i++) {
                String text = text(memory.get(i - 1));
                if (text.toLowerCase(Locale.ROOT).contains(needle)) matches.add(new Match(i, snippet(text, needle)));
            }
            return matches;
        }
        for (ParsedFileWriter.Chunk chunk : chunks) {
            int chunkLast = chunk.first() + chunk.count() - 1;
            if (chunkLast < first || chunk.first() > last) continue;
            try (BufferedReader reader = Files.newBufferedReader(dir.resolve(chunk.file()), StandardCharsets.UTF_8)) {
                int number = chunk.first();
                for (String line; (line = reader.readLine()) != null && number <= last; number++) {
                    // The JSON form keeps CJK and most text as-is: a cheap filter before decoding.
                    if (number < first || !line.toLowerCase(Locale.ROOT).contains(needle)) continue;
                    String text = text(decode(line));
                    if (text.toLowerCase(Locale.ROOT).contains(needle)) {
                        matches.add(new Match(number, snippet(text, needle)));
                        if (matches.size() >= limit) return matches;
                    }
                }
            }
        }
        return matches;
    }

    static String text(Object unit) {
        if (unit instanceof List<?> cells) {
            StringBuilder out = new StringBuilder();
            for (Object cell : cells) {
                if (out.length() > 0) out.append('\t');
                out.append(cell);
            }
            return out.toString();
        }
        return String.valueOf(unit);
    }

    private static Object decode(String line) throws IOException {
        JsonNode node = JSON.readTree(line);
        if (node.isArray()) {
            List<String> cells = new ArrayList<>(node.size());
            for (JsonNode cell : node) cells.add(cell.asText());
            return cells;
        }
        return node.asText();
    }

    private static String snippet(String text, String needle) {
        String flat = text.replace('\n', ' ');
        int at = flat.toLowerCase(Locale.ROOT).indexOf(needle);
        int start = Math.max(0, at - 60), end = Math.min(flat.length(), Math.max(at, 0) + needle.length() + 100);
        return (start > 0 ? "…" : "") + flat.substring(start, end) + (end < flat.length() ? "…" : "");
    }
}
