package cn.bugstack.ai.infrastructure.adapter.repository.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Receives the units of one parsed file (lines, pages, slides or table rows) and streams them
 * to chunk files, so a 50MB spreadsheet never sits in memory. Collects the preview, the inline
 * text of small files and the structure summary on the way.
 */
final class ParsedFileWriter implements AutoCloseable {

    static final String INDEX = "index.json";
    static final int CHUNK_BYTES = 256 * 1024;
    static final int CHUNK_UNITS = 5000;
    static final int MAX_LINE_CHARS = 2000;
    static final int MAX_CELL_CHARS = 2000;
    static final int MAX_COLUMNS = 500;
    private static final int PREVIEW_CHARS = 1500;
    private static final int PREVIEW_TABLE_CHARS = 2000;
    private static final int PREVIEW_ROWS = 5;
    private static final int PREVIEW_SECTIONS = 3;

    record Section(String name, int first, int count, List<String> columns) { }

    record Chunk(String file, int first, int count) { }

    record Result(String unit, int units, long chars, boolean truncated, List<Section> sections,
                  String preview, String inline, String summary) { }

    /** Thrown to stop a parser once a limit is reached; {@link #finish()} still produces a result. */
    static final class LimitReached extends RuntimeException {
        LimitReached() { super("parse limit reached", null, false, false); }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String unit;
    private final Path dir;
    private final long maxUnits;
    private final long maxChars;
    private final int inlineLimit;
    private final List<Object> memory;
    private final List<Section> sections = new ArrayList<>();
    private final List<Chunk> chunks = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final StringBuilder preview = new StringBuilder();

    private BufferedWriter chunkOut;
    private int chunkFirst;
    private int chunkUnits;
    private long chunkBytes;
    private int units;
    private long chars;
    private boolean truncated;
    private boolean wrapped;
    private StringBuilder inline = new StringBuilder();

    private boolean sectionOpen;
    private String sectionName;
    private int sectionFirst;
    private int sectionCount;
    private List<String> sectionColumns;
    private int previewRows;

    /** @param dir chunk directory, or null to keep the units in memory (artifact references) */
    ParsedFileWriter(String unit, Path dir, long maxUnits, long maxChars, int inlineLimit) {
        this.unit = unit;
        this.dir = dir;
        this.maxUnits = maxUnits;
        this.maxChars = maxChars;
        this.inlineLimit = inlineLimit;
        this.memory = dir == null ? new ArrayList<>() : null;
    }

    /** Starts a new sheet (ROW files); a CSV and other unit kinds use one unnamed section. */
    void section(String name) {
        closeSection();
        sectionOpen = true;
        sectionName = name == null ? null : name.isBlank() ? "工作表 " + (sections.size() + 1) : name.strip();
        sectionFirst = units + 1;
        sectionCount = 0;
        sectionColumns = null;
        previewRows = 0;
    }

    void line(String text) {
        String value = text == null ? "" : text;
        if (value.length() <= MAX_LINE_CHARS) {
            write(value);
            return;
        }
        wrapped = true;
        for (int at = 0; at < value.length(); at += MAX_LINE_CHARS) {
            write(value.substring(at, Math.min(value.length(), at + MAX_LINE_CHARS)));
        }
    }

    /** One page of a PDF or one slide. */
    void page(String text) {
        write(text == null ? "" : text.strip());
    }

    void row(List<String> cells) {
        if (!sectionOpen) section(null);
        List<String> row = new ArrayList<>(Math.min(cells.size(), MAX_COLUMNS));
        for (int i = 0; i < cells.size() && i < MAX_COLUMNS; i++) {
            String cell = cells.get(i) == null ? "" : cells.get(i);
            row.add(cell.length() <= MAX_CELL_CHARS ? cell : cell.substring(0, MAX_CELL_CHARS) + "…");
        }
        if (cells.size() > MAX_COLUMNS) truncated = true;
        write(row);
    }

    boolean truncated() {
        return truncated;
    }

    /** A caveat about the extracted content, appended to the summary. */
    void note(String text) {
        if (!notes.contains(text)) notes.add(text);
    }

    private void write(Object value) {
        if (units >= maxUnits || chars >= maxChars) {
            truncated = true;
            throw new LimitReached();
        }
        if (!sectionOpen) {
            sectionOpen = true;
            sectionName = null;
            sectionFirst = 1;
            sectionCount = 0;
        }
        units++;
        sectionCount++;
        long length = length(value);
        chars += length;
        collectPreview(value);
        collectInline(value);
        if (memory != null) {
            memory.add(value);
            return;
        }
        try {
            if (chunkOut == null) {
                chunkFirst = units;
                chunkUnits = 0;
                chunkBytes = 0;
                chunkOut = Files.newBufferedWriter(dir.resolve(chunkName(chunks.size())), StandardCharsets.UTF_8);
            }
            String encoded = JSON.writeValueAsString(value);
            chunkOut.write(encoded);
            chunkOut.write('\n');
            chunkUnits++;
            chunkBytes += encoded.length() + 1L;
            if (chunkUnits >= CHUNK_UNITS || chunkBytes >= CHUNK_BYTES) closeChunk();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void collectPreview(Object value) {
        if (value instanceof List<?> cells) {
            if (sections.size() >= PREVIEW_SECTIONS || preview.length() >= PREVIEW_TABLE_CHARS) return;
            if (sectionColumns == null) {
                sectionColumns = cells.stream().map(String::valueOf).toList();
                if (preview.length() > 0) preview.append('\n');
                preview.append('[').append(sectionName == null ? "表格" : sectionName).append("] 列：")
                        .append(clip(String.join(" | ", sectionColumns), 400)).append('\n');
                return;
            }
            if (previewRows >= PREVIEW_ROWS) return;
            previewRows++;
            StringBuilder line = new StringBuilder();
            for (Object cell : cells) {
                if (line.length() > 0) line.append('\t');
                line.append(clip(String.valueOf(cell), 40));
            }
            preview.append(clip(line.toString(), 300)).append('\n');
            return;
        }
        String text = String.valueOf(value);
        if ("LINE".equals(unit)) {
            if (units > 20 || preview.length() >= PREVIEW_CHARS) return;
            preview.append(clip(text, 200)).append('\n');
        } else if (preview.length() < 1000) {
            if (preview.length() > 0) preview.append('\n');
            preview.append("=== 第 ").append(units).append(" ").append("SLIDE".equals(unit) ? "张幻灯片" : "页").append(" ===\n")
                    .append(clip(text, 1000 - Math.min(preview.length(), 900)));
        }
    }

    private void collectInline(Object value) {
        if (inline == null) return;
        if (value instanceof List<?> cells) {
            if (sectionCount == 1 && sectionName != null) inline.append("[工作表 ").append(sectionName).append("]\n");
            for (int i = 0; i < cells.size(); i++) {
                if (i > 0) inline.append('\t');
                inline.append(cells.get(i));
            }
            inline.append('\n');
        } else if ("LINE".equals(unit)) {
            inline.append(value).append('\n');
        } else {
            inline.append("=== 第 ").append(units).append(" ").append("SLIDE".equals(unit) ? "张幻灯片" : "页")
                    .append(" ===\n").append(value).append('\n');
        }
        if (inline.length() > inlineLimit) inline = null;
    }

    Result finish() {
        closeSection();
        closeChunk();
        if (dir != null) writeIndex();
        String summary = summary();
        String inlineText = inline == null || inline.isEmpty() || chars == 0 ? null : inline.toString();
        return new Result(unit, units, chars, truncated, List.copyOf(sections), preview.toString().strip(), inlineText, summary);
    }

    /** Units kept in memory when constructed without a directory. */
    List<Object> memoryUnits() {
        return memory == null ? List.of() : memory;
    }

    List<Chunk> chunks() {
        return List.copyOf(chunks);
    }

    private String summary() {
        if (units == 0 || chars == 0) return "未提取到文字内容（可能是扫描件、图片或空文件）";
        String summary = switch (unit) {
            case "PAGE" -> "共 " + units + " 页";
            case "SLIDE" -> "共 " + units + " 张幻灯片";
            case "ROW" -> tableSummary();
            default -> "共 " + units + " 行" + (wrapped ? "（超长的行已按 " + MAX_LINE_CHARS + " 字符折行）" : "");
        };
        return notes.isEmpty() ? summary : summary + "；" + String.join("；", notes);
    }

    private String tableSummary() {
        if (sections.size() == 1 && sections.get(0).name() == null) {
            Section only = sections.get(0);
            return "共 " + only.count() + " 行，" + (only.columns() == null ? 0 : only.columns().size()) + " 列，第 1 行是列名";
        }
        StringBuilder out = new StringBuilder().append(sections.size()).append(" 个工作表：");
        for (int i = 0; i < sections.size(); i++) {
            if (i == 8) { out.append(" 等"); break; }
            Section s = sections.get(i);
            if (i > 0) out.append('、');
            out.append(s.name()).append('（').append(s.count()).append(" 行，")
                    .append(s.columns() == null ? 0 : s.columns().size()).append(" 列）");
        }
        return out.append("；每个工作表第 1 行通常是列名").toString();
    }

    private void closeSection() {
        if (!sectionOpen) return;
        sections.add(new Section(sectionName, sectionFirst, sectionCount,
                sectionColumns == null ? null : List.copyOf(sectionColumns)));
        sectionOpen = false;
    }

    private void closeChunk() {
        if (chunkOut == null) return;
        try {
            chunkOut.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        chunks.add(new Chunk(chunkName(chunks.size()), chunkFirst, chunkUnits));
        chunkOut = null;
    }

    private void writeIndex() {
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("v", 1);
        index.put("unit", unit);
        index.put("units", units);
        index.put("sections", sections.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            m.put("first", s.first());
            m.put("count", s.count());
            m.put("columns", s.columns());
            return m;
        }).toList());
        index.put("chunks", chunks.stream().map(c -> Map.of("file", c.file(), "first", c.first(), "count", c.count())).toList());
        try {
            Files.writeString(dir.resolve(INDEX), JSON.writeValueAsString(index), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        if (chunkOut != null) {
            try { chunkOut.close(); } catch (IOException ignored) { }
            chunkOut = null;
        }
    }

    static String chunkName(int index) {
        return String.format("%05d.jsonl", index);
    }

    private static long length(Object value) {
        if (value instanceof List<?> cells) {
            long total = 0;
            for (Object cell : cells) total += String.valueOf(cell).length() + 1L;
            return total;
        }
        return String.valueOf(value).length() + 1L;
    }

    private static String clip(String value, int max) {
        if (value == null) return "";
        String flat = value.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= max ? flat : flat.substring(0, Math.max(1, max - 1)) + "…";
    }
}
