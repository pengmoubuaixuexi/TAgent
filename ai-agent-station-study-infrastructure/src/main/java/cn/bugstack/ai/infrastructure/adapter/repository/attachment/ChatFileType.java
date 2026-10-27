package cn.bugstack.ai.infrastructure.adapter.repository.attachment;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Allowed attachment types, decided by extension. Images go through the multimodal image path instead. */
public record ChatFileType(String extension, String fileKind, Route route, String unit, String mimeType) {

    public enum Route { PLAIN, DELIMITED, XLSX, TIKA_TABLE, TIKA_PAGES, TIKA_SLIDES, TIKA_LINES }

    private static final Set<String> CODE = Set.of("py", "java", "js", "mjs", "cjs", "ts", "jsx", "tsx", "css", "scss",
            "sass", "less", "sql", "sh", "bash", "zsh", "ps1", "bat", "cmd", "c", "h", "cc", "cpp", "cxx", "hpp", "cs", "go",
            "rs", "rb", "php", "kt", "kts", "swift", "scala", "r", "jl", "lua", "pl", "dart", "vue", "svelte", "gradle",
            "groovy", "proto", "graphql", "gql", "tf", "hcl", "ex", "exs", "erl", "hs", "fs", "clj", "zig", "asm", "m", "ipynb");
    private static final Set<String> TEXT = Set.of("txt", "log", "text", "json", "jsonl", "yaml", "yml", "xml", "html",
            "htm", "toml", "ini", "conf", "cfg", "properties", "env", "tex", "rst", "srt", "vtt");
    private static final Set<String> IMAGES = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "tif", "tiff");
    private static final Map<String, String> NAMED = Map.of("dockerfile", "CODE", "makefile", "CODE",
            "readme", "TEXT", "license", "TEXT");

    public static ChatFileType of(String fileName) {
        String ext = extension(fileName);
        String lowerName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (ext.isEmpty() && NAMED.containsKey(lowerName)) {
            return new ChatFileType("", NAMED.get(lowerName), Route.PLAIN, "LINE", "text/plain");
        }
        return switch (ext) {
            case "md", "markdown" -> new ChatFileType(ext, "MARKDOWN", Route.PLAIN, "LINE", "text/markdown");
            case "csv" -> new ChatFileType(ext, "CSV", Route.DELIMITED, "ROW", "text/csv");
            case "tsv" -> new ChatFileType(ext, "CSV", Route.DELIMITED, "ROW", "text/tab-separated-values");
            case "xlsx", "xlsm" -> new ChatFileType(ext, "SHEET", Route.XLSX, "ROW",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            case "xls" -> new ChatFileType(ext, "SHEET", Route.TIKA_TABLE, "ROW", "application/vnd.ms-excel");
            case "ods" -> new ChatFileType(ext, "SHEET", Route.TIKA_TABLE, "ROW", "application/vnd.oasis.opendocument.spreadsheet");
            case "pdf" -> new ChatFileType(ext, "PDF", Route.TIKA_PAGES, "PAGE", "application/pdf");
            case "docx" -> new ChatFileType(ext, "DOCUMENT", Route.TIKA_LINES, "LINE",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case "doc" -> new ChatFileType(ext, "DOCUMENT", Route.TIKA_LINES, "LINE", "application/msword");
            case "rtf" -> new ChatFileType(ext, "DOCUMENT", Route.TIKA_LINES, "LINE", "application/rtf");
            case "odt" -> new ChatFileType(ext, "DOCUMENT", Route.TIKA_LINES, "LINE", "application/vnd.oasis.opendocument.text");
            case "pptx" -> new ChatFileType(ext, "SLIDES", Route.TIKA_SLIDES, "SLIDE",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation");
            case "ppt" -> new ChatFileType(ext, "SLIDES", Route.TIKA_SLIDES, "SLIDE", "application/vnd.ms-powerpoint");
            case "odp" -> new ChatFileType(ext, "SLIDES", Route.TIKA_SLIDES, "SLIDE", "application/vnd.oasis.opendocument.presentation");
            default -> {
                if (CODE.contains(ext)) yield new ChatFileType(ext, "CODE", Route.PLAIN, "LINE", "text/plain");
                if (TEXT.contains(ext)) yield new ChatFileType(ext, "TEXT", Route.PLAIN, "LINE", "text/plain");
                yield null;
            }
        };
    }

    public static boolean isImage(String fileName) {
        return IMAGES.contains(extension(fileName));
    }

    static String extension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
