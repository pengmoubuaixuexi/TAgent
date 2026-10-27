package cn.bugstack.ai.infrastructure.adapter.repository.attachment;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLFilterImpl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts attachment text as units. Spreadsheets are streamed (POI event API for xlsx) and
 * written straight to disk; Office documents and PDFs go through Tika like the knowledge base.
 * Embedded documents are not parsed.
 */
@Slf4j
@Component
public class ChatFileParser {

    /** A failure the user can act on; the message is shown as-is. */
    public static final class ParseFailure extends Exception {
        public ParseFailure(String message) { super(message); }
        public ParseFailure(String message, Throwable cause) { super(message, cause); }
    }

    private final AutoDetectParser tika = new AutoDetectParser();

    @Value("${agent.attachments.parse.max-units:2000000}")
    private long maxUnits = 2_000_000;

    @Value("${agent.attachments.parse.max-chars:120000000}")
    private long maxChars = 120_000_000;

    /** Unpacked xl/sharedStrings.xml; POI keeps all shared strings in memory. */
    @Value("${agent.attachments.parse.max-shared-strings-bytes:67108864}")
    private long maxSharedStringsBytes = 64L * 1024 * 1024;

    /** Workbooks without stored formula results are recalculated in memory up to this much unpacked sheet XML. */
    @Value("${agent.attachments.parse.max-recalculate-bytes:2097152}")
    private long maxRecalculateBytes = 2L * 1024 * 1024;

    @Value("${agent.attachments.inline-chars:8192}")
    private int inlineChars = 8192;

    /** Parses {@code source} into {@code target} (an empty directory). */
    public ParsedFileWriter.Result parse(Path source, ChatFileType type, Path target) throws ParseFailure, IOException {
        try (ParsedFileWriter out = new ParsedFileWriter(type.unit(), target, maxUnits, maxChars, inlineChars)) {
            try {
                switch (type.route()) {
                    case PLAIN -> plain(source, out);
                    case DELIMITED -> delimited(source, "tsv".equals(type.extension()) ? '\t' : 0, out);
                    case XLSX -> xlsx(source, out);
                    case TIKA_TABLE -> tika(source, TikaUnitHandler.Mode.TABLE, out);
                    case TIKA_PAGES -> tika(source, TikaUnitHandler.Mode.PAGES, out);
                    case TIKA_SLIDES -> tika(source, TikaUnitHandler.Mode.SLIDES, out);
                    case TIKA_LINES -> tika(source, TikaUnitHandler.Mode.LINES, out);
                }
            } catch (ParsedFileWriter.LimitReached reached) {
                // Keep everything parsed so far; the result is marked truncated.
            }
            return out.finish();
        }
    }

    /** Units of an artifact revision, kept in memory (artifacts are at most 200000 characters). */
    public Rendered renderArtifact(String format, JsonNode content) {
        String unit = "TABLE".equals(format) ? "ROW" : "LINE";
        ParsedFileWriter out = new ParsedFileWriter(unit, null, maxUnits, maxChars, inlineChars);
        if ("TABLE".equals(format)) {
            List<String> columns = new ArrayList<>();
            content.path("columns").forEach(c -> columns.add(c.asText()));
            out.row(columns);
            for (JsonNode row : content.path("rows")) {
                List<String> cells = new ArrayList<>();
                row.forEach(c -> cells.add(c.asText()));
                out.row(cells);
            }
        } else {
            String text = "CODE".equals(format) ? content.path("code").asText("") : content.path("markdown").asText("");
            for (String line : text.split("\\r?\\n", -1)) out.line(line);
        }
        ParsedFileWriter.Result result = out.finish();
        return new Rendered(result, ParsedUnits.memory(unit, result.sections(), out.memoryUnits()));
    }

    public record Rendered(ParsedFileWriter.Result result, ParsedUnits units) { }

    private void plain(Path source, ParsedFileWriter out) throws IOException {
        try (BufferedReader reader = new BufferedReader(TextCharsets.reader(source))) {
            for (String line; (line = reader.readLine()) != null; ) out.line(line);
        }
    }

    private void delimited(Path source, char fixed, ParsedFileWriter out) throws IOException, ParseFailure {
        char delimiter = fixed != 0 ? fixed : sniffDelimiter(source);
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setIgnoreEmptyLines(false)
                .setAllowMissingColumnNames(true)
                .setLenientEof(true)
                .setTrailingData(true)
                .get();
        try (Reader reader = TextCharsets.reader(source); CSVParser parser = format.parse(reader)) {
            for (CSVRecord record : parser) {
                List<String> cells = new ArrayList<>(record.size());
                record.forEach(cells::add);
                out.row(cells);
            }
        } catch (java.io.UncheckedIOException | IllegalStateException malformed) {
            throw new ParseFailure("表格格式有误，无法按行读取（可能有未闭合的引号）", malformed);
        }
    }

    private char sniffDelimiter(Path source) throws IOException {
        try (BufferedReader reader = new BufferedReader(TextCharsets.reader(source))) {
            String first = reader.readLine();
            if (first == null) return ',';
            long commas = first.chars().filter(c -> c == ',').count();
            long semicolons = first.chars().filter(c -> c == ';').count();
            long tabs = first.chars().filter(c -> c == '\t').count();
            if (tabs > commas && tabs >= semicolons) return '\t';
            if (semicolons > commas) return ';';
            return ',';
        }
    }

    private void xlsx(Path source, ParsedFileWriter out) throws ParseFailure {
        WorkbookInfo workbook = inspect(source);
        try {
            if (workbook.formulasUncalculated() && workbook.sheetBytes() <= maxRecalculateBytes) {
                recalculated(source, out);
                return;
            }
            long formulas = 0;
            try (OPCPackage pkg = OPCPackage.open(source.toFile(), PackageAccess.READ)) {
                ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg, false);
                XSSFReader reader = new XSSFReader(pkg);
                StylesTable styles = reader.getStylesTable();
                DataFormatter formatter = new DataFormatter(Locale.CHINA);
                XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
                while (sheets.hasNext()) {
                    try (InputStream sheet = sheets.next()) {
                        out.section(sheets.getSheetName());
                        FormulaCounter xml = new FormulaCounter(XMLHelper.newXMLReader());
                        xml.setContentHandler(new XSSFSheetXMLHandler(styles, null, strings, new RowCollector(out), formatter, false));
                        try {
                            xml.parse(new InputSource(sheet));
                        } finally {
                            formulas += xml.formulas;
                        }
                    }
                }
            } finally {
                if (workbook.formulasUncalculated() && formulas > 0) {
                    out.note("这个表格由程序生成，文件里没有保存公式的计算结果，公式单元格可能显示为空或 0，以 Excel 打开后重新计算的结果为准");
                }
            }
        } catch (ParsedFileWriter.LimitReached reached) {
            throw reached;
        } catch (EncryptedDocumentException encrypted) {
            throw new ParseFailure("文件已加密，无法读取", encrypted);
        } catch (Exception failure) {
            if (failure.getCause() instanceof ParsedFileWriter.LimitReached reached) throw reached;
            log.warn("xlsx parse failed: {}", failure.toString());
            throw new ParseFailure("无法解析这个 Excel 文件，请确认文件完整、未加密，或另存为 CSV 后上传", failure);
        }
    }

    /**
     * @param formulasUncalculated the writer left formula results to the reader: openpyxl and XlsxWriter
     *                             store none (or 0) and set fullCalcOnLoad, Excel and WPS store real results
     * @param sheetBytes           unpacked size of all worksheet parts
     */
    private record WorkbookInfo(boolean formulasUncalculated, long sheetBytes) { }

    private static final Pattern FULL_CALC_ON_LOAD =
            Pattern.compile("<(?:\\w+:)?calcPr\\b[^>]*\\bfullCalcOnLoad=\"(?:1|true)\"");

    private WorkbookInfo inspect(Path source) throws ParseFailure {
        try (ZipFile zip = new ZipFile(source.toFile())) {
            ZipEntry entry = zip.getEntry("xl/sharedStrings.xml");
            if (entry != null && entry.getSize() > maxSharedStringsBytes) {
                throw new ParseFailure("表格里的文字太多（共享字符串超过 " + (maxSharedStringsBytes / 1024 / 1024)
                        + " MB），请另存为 CSV 后上传");
            }
            long sheetBytes = 0;
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                ZipEntry part = entries.nextElement();
                if (part.getName().startsWith("xl/worksheets/") && part.getName().endsWith(".xml")) {
                    sheetBytes += part.getSize() < 0 ? Integer.MAX_VALUE : part.getSize();
                }
            }
            boolean uncalculated = false;
            ZipEntry book = zip.getEntry("xl/workbook.xml");
            if (book != null) {
                try (InputStream in = zip.getInputStream(book)) {
                    uncalculated = FULL_CALC_ON_LOAD.matcher(new String(in.readNBytes(1 << 20), StandardCharsets.UTF_8)).find();
                }
            }
            return new WorkbookInfo(uncalculated, sheetBytes);
        } catch (IOException notZip) {
            throw new ParseFailure("无法解析这个 Excel 文件，请确认文件完整或另存为 CSV 后上传", notZip);
        }
    }

    /** Loads the whole (small) workbook and computes the formulas; one that POI cannot compute shows as its text. */
    private void recalculated(Path source, ParsedFileWriter out) throws Exception {
        OPCPackage pkg = OPCPackage.open(source.toFile(), PackageAccess.READ);
        XSSFWorkbook book;
        try {
            book = new XSSFWorkbook(pkg);
        } catch (Exception | Error failure) {
            pkg.revert();
            throw failure;
        }
        try (book) {
            FormulaEvaluator evaluator = book.getCreationHelper().createFormulaEvaluator();
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            for (Sheet sheet : book) {
                out.section(sheet.getSheetName());
                RowCollector rows = new RowCollector(out);
                for (Row row : sheet) {
                    rows.startRow(row.getRowNum());
                    for (Cell cell : row) rows.cell(cell.getAddress().formatAsString(), value(cell, formatter, evaluator), null);
                    rows.endRow(row.getRowNum());
                }
            }
        }
    }

    private static String value(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell.getCellType() != CellType.FORMULA) return formatter.formatCellValue(cell);
        try {
            return formatter.formatCellValue(cell, evaluator);
        } catch (RuntimeException unsupported) {
            return "=" + cell.getCellFormula();
        }
    }

    /** Counts formula cells on their way to the POI sheet handler. */
    private static final class FormulaCounter extends XMLFilterImpl {
        long formulas;

        FormulaCounter(XMLReader parent) {
            super(parent);
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            if ("f".equals(localName)) formulas++;
            super.startElement(uri, localName, qName, attributes);
        }
    }

    /** Pads missing cells and empty rows so row and column numbers match the spreadsheet. */
    private static final class RowCollector implements XSSFSheetXMLHandler.SheetContentsHandler {
        private static final int MAX_PADDED_ROWS = 10_000;
        private final ParsedFileWriter out;
        private List<String> row;
        private int column;
        private int lastRow = -1;
        private int padded;

        RowCollector(ParsedFileWriter out) {
            this.out = out;
        }

        @Override
        public void startRow(int rowNum) {
            while (lastRow + 1 < rowNum && padded < MAX_PADDED_ROWS) {
                out.row(List.of());
                lastRow++;
                padded++;
            }
            row = new ArrayList<>();
            column = -1;
        }

        @Override
        public void endRow(int rowNum) {
            out.row(row);
            lastRow = rowNum;
        }

        @Override
        public void cell(String reference, String value, XSSFComment comment) {
            int index = reference == null ? column + 1 : new CellReference(reference).getCol();
            while (row.size() < index) row.add("");
            row.add(value == null ? "" : value);
            column = index;
        }
    }

    private void tika(Path source, TikaUnitHandler.Mode mode, ParsedFileWriter out) throws ParseFailure {
        TikaUnitHandler handler = new TikaUnitHandler(mode, out);
        try (InputStream in = Files.newInputStream(source)) {
            // No Parser in the context: embedded files are not parsed.
            tika.parse(in, handler, new Metadata(), new ParseContext());
        } catch (TikaUnitHandler.Stop stop) {
            // limit reached; keep what was parsed
        } catch (Exception failure) {
            if (handler.stopped()) return;
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            if (root instanceof EncryptedDocumentException
                    || failure.getClass().getSimpleName().contains("Encrypted")
                    || root.getClass().getSimpleName().contains("Encrypted")) {
                throw new ParseFailure("文件已加密，无法读取", failure);
            }
            if (root instanceof ParsedFileWriter.LimitReached) return;
            log.warn("attachment parse failed mode={}: {}", mode, failure.toString());
            throw new ParseFailure("无法解析这个文件，请确认文件完整、未加密", failure);
        }
    }

    /** UTF-8 (with or without BOM), UTF-16 with BOM, otherwise GB18030, the usual legacy encoding here. */
    static final class TextCharsets {
        private TextCharsets() { }

        static Reader reader(Path file) throws IOException {
            byte[] head;
            try (InputStream in = Files.newInputStream(file)) {
                head = in.readNBytes(1 << 20);
            }
            Charset charset = detect(head);
            InputStream in = Files.newInputStream(file);
            int bom = bomLength(head);
            if (bom > 0) in.skipNBytes(bom);
            return new java.io.InputStreamReader(in, charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE));
        }

        static Charset detect(byte[] head) {
            if (head.length >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
                return StandardCharsets.UTF_8;
            }
            if (head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE) return StandardCharsets.UTF_16LE;
            if (head.length >= 2 && (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF) return StandardCharsets.UTF_16BE;
            int length = head.length;
            // A multi-byte character may be cut at the end of the sample.
            for (int i = Math.max(0, length - 3); i < length; i++) {
                if ((head[i] & 0xC0) == 0xC0) { length = i; break; }
            }
            try {
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(head, 0, length));
                return StandardCharsets.UTF_8;
            } catch (CharacterCodingException notUtf8) {
                return Charset.forName("GB18030");
            }
        }

        private static int bomLength(byte[] head) {
            if (head.length >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) return 3;
            if (head.length >= 2 && ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE
                    || (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF)) return 2;
            return 0;
        }
    }
}
