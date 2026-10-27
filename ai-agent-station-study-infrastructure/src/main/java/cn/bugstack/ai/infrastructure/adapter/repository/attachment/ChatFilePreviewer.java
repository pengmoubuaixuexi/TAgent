package cn.bugstack.ai.infrastructure.adapter.repository.attachment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** Safe previews of stored files (tables and extracted text), never the raw document. */
@Component
public class ChatFilePreviewer {

    public record Sheet(String name, List<String> columns, List<List<String>> rows, int totalRows) { }

    /** kind: table, text or none. */
    public record Preview(String kind, String summary, List<Sheet> sheets, String text, boolean truncated) { }

    private static final int TABLE_ROWS = 50;
    private static final int TABLE_SHEETS = 3;
    private static final int TEXT_CHARS = 20_000;

    private final ChatFileParser parser;
    private final Path tmpRoot;

    public ChatFilePreviewer(ChatFileParser parser,
                             @Value("${agent.attachments.cache-dir:./data/chat-files-cache}") String cacheDir) {
        this.parser = parser;
        this.tmpRoot = Path.of(cacheDir).toAbsolutePath().normalize().resolve("tmp");
    }

    public Preview preview(String fileName, Path file) {
        ChatFileType type = ChatFileType.of(fileName);
        if (type == null) return new Preview("none", "", List.of(), "", false);
        Path dir = null;
        try {
            Files.createDirectories(tmpRoot);
            dir = tmpRoot.resolve("preview-" + UUID.randomUUID());
            Files.createDirectories(dir);
            ParsedFileWriter.Result result = parser.parse(file, type, dir);
            ParsedUnits units = ParsedUnits.open(dir);
            if ("ROW".equals(units.unit())) {
                List<Sheet> sheets = new ArrayList<>();
                for (ParsedUnits.Section section : units.sections()) {
                    if (sheets.size() >= TABLE_SHEETS) break;
                    List<List<String>> rows = new ArrayList<>();
                    for (Object unit : units.read(section.first() + 1, Math.min(TABLE_ROWS, Math.max(0, section.count() - 1)))) {
                        List<String> cells = new ArrayList<>();
                        if (unit instanceof List<?> list) list.forEach(cell -> cells.add(String.valueOf(cell)));
                        rows.add(cells);
                    }
                    sheets.add(new Sheet(section.name(), section.columns() == null ? List.of() : section.columns(), rows,
                            Math.max(0, section.count() - 1)));
                }
                boolean cut = units.sections().size() > TABLE_SHEETS
                        || units.sections().stream().anyMatch(s -> s.count() - 1 > TABLE_ROWS);
                return new Preview("table", result.summary(), sheets, "", cut || result.truncated());
            }
            StringBuilder text = new StringBuilder();
            boolean lines = "LINE".equals(units.unit());
            int number = 1;
            boolean cut = false;
            for (Object unit : units.read(1, Math.min(units.total(), 2000))) {
                String piece = lines ? unit + "\n"
                        : "=== 第 " + number + ("SLIDE".equals(units.unit()) ? " 张幻灯片" : " 页") + " ===\n" + unit + "\n\n";
                number++;
                if (text.length() + piece.length() > TEXT_CHARS) { cut = true; break; }
                text.append(piece);
            }
            return new Preview("text", result.summary(), List.of(), text.toString(),
                    cut || units.total() > 2000 || result.truncated());
        } catch (ChatFileParser.ParseFailure failure) {
            return new Preview("none", failure.getMessage(), List.of(), "", false);
        } catch (IOException | RuntimeException failure) {
            return new Preview("none", "无法生成预览", List.of(), "", false);
        } finally {
            if (dir != null) deleteTree(dir);
        }
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
