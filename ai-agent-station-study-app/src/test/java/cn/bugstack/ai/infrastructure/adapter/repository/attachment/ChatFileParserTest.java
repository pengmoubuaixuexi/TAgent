package cn.bugstack.ai.infrastructure.adapter.repository.attachment;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

public class ChatFileParserTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final ChatFileParser parser = new ChatFileParser();

    private record Parsed(ParsedFileWriter.Result result, ParsedUnits units) { }

    private Parsed parse(Path file) throws Exception {
        Path dir = temp.newFolder().toPath();
        ParsedFileWriter.Result result = parser.parse(file, ChatFileType.of(file.getFileName().toString()), dir);
        return new Parsed(result, ParsedUnits.open(dir));
    }

    @Test
    public void legacyEncodedCsvWithQuotedCommasIsReadAsRows() throws Exception {
        Path csv = temp.getRoot().toPath().resolve("销售.csv");
        Files.writeString(csv, "日期,地区,备注\n2024-01-01,华东,\"含逗号, 和\"\"引号\"\"\"\n2024-01-02,华北,\n",
                Charset.forName("GB18030"));
        Parsed p = parse(csv);
        assertEquals("ROW", p.result().unit());
        assertEquals(3, p.result().units());
        assertEquals(List.of("日期", "地区", "备注"), p.units().sections().get(0).columns());
        assertEquals("含逗号, 和\"引号\"", ((List<?>) p.units().read(2, 1).get(0)).get(2));
        assertTrue(p.result().summary(), p.result().summary().contains("共 3 行，3 列"));
        assertNotNull("a small table is placed in context", p.result().inline());
    }

    @Test
    public void xlsxKeepsSheetNamesRowNumbersAndEmptyCells() throws Exception {
        Path xlsx = temp.getRoot().toPath().resolve("report.xlsx");
        try (XSSFWorkbook book = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(xlsx)) {
            Sheet sales = book.createSheet("销售");
            row(sales, 0, "月份", "金额", "备注");
            Row sparse = sales.createRow(1);
            sparse.createCell(0).setCellValue("1月");
            sparse.createCell(2).setCellValue("只有备注");
            // Row index 2 is missing entirely in the file.
            Row numbers = sales.createRow(3);
            numbers.createCell(0).setCellValue("3月");
            numbers.createCell(1).setCellValue(300);
            row(book.createSheet("汇总"), 0, "总计");
            book.write(out);
        }
        Parsed p = parse(xlsx);
        ParsedUnits.Section first = p.units().sections().get(0);
        assertEquals("销售", first.name());
        assertEquals(4, first.count());
        assertEquals(List.of("1月", "", "只有备注"), p.units().read(first.first() + 1, 1).get(0));
        assertEquals("the missing row keeps later row numbers aligned", List.of(), p.units().read(first.first() + 2, 1).get(0));
        assertEquals(List.of("3月", "300"), p.units().read(first.first() + 3, 1).get(0));
        assertEquals("汇总", p.units().sections().get(1).name());
        assertTrue(p.result().summary().contains("2 个工作表"));
        assertTrue(p.result().preview().contains("[销售] 列：月份 | 金额 | 备注"));
    }

    @Test
    public void formulasWithoutStoredResultsAreCalculated() throws Exception {
        Path xlsx = temp.getRoot().toPath().resolve("summary.xlsx");
        try (XSSFWorkbook book = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(xlsx)) {
            Sheet sheet = book.createSheet("月度汇总");
            row(sheet, 0, "月份", "销售额");
            Row jan = sheet.createRow(1);
            jan.createCell(0).setCellValue("1月");
            jan.createCell(1).setCellValue(100);
            Row feb = sheet.createRow(2);
            feb.createCell(0).setCellValue("2月");
            feb.createCell(1).setCellValue(250.5);
            Row total = sheet.createRow(3);
            total.createCell(0).setCellValue("合计");
            total.createCell(1).setCellFormula("SUM(B2:B3)");
            // Like openpyxl and XlsxWriter: no stored result, Excel recalculates when it opens the file.
            book.setForceFormulaRecalculation(true);
            book.write(out);
        }
        Parsed p = parse(xlsx);
        assertEquals(List.of("合计", "350.5"), p.units().read(4, 1).get(0));
        assertFalse(p.result().summary(), p.result().summary().contains("没有保存公式的计算结果"));

        ReflectionTestUtils.setField(parser, "maxRecalculateBytes", 0L);
        Parsed big = parse(xlsx);
        assertTrue("too big to recalculate: stored values stay and the summary says so",
                big.result().summary().contains("没有保存公式的计算结果"));
    }

    @Test
    public void legacyXlsIsReadThroughTika() throws Exception {
        Path xls = temp.getRoot().toPath().resolve("old.xls");
        try (HSSFWorkbook book = new HSSFWorkbook(); OutputStream out = Files.newOutputStream(xls)) {
            Sheet sheet = book.createSheet("数据");
            row(sheet, 0, "名称", "数量");
            row(sheet, 1, "苹果", "3");
            book.write(out);
        }
        Parsed p = parse(xls);
        assertEquals("ROW", p.result().unit());
        assertEquals("数据", p.units().sections().get(0).name());
        assertEquals(List.of("名称", "数量"), p.units().sections().get(0).columns());
        assertTrue(ParsedUnits.text(p.units().read(2, 1).get(0)).contains("苹果"));
    }

    @Test
    public void pdfPagesBecomeUnits() throws Exception {
        Path pdf = temp.getRoot().toPath().resolve("two-pages.pdf");
        try (PDDocument document = new PDDocument()) {
            for (String text : new String[]{"Page one alpha", "Page two beta"}) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    stream.newLineAtOffset(72, 700);
                    stream.showText(text);
                    stream.endText();
                }
            }
            document.save(pdf.toFile());
        }
        Parsed p = parse(pdf);
        assertEquals("PAGE", p.result().unit());
        assertEquals(2, p.result().units());
        assertTrue(String.valueOf(p.units().read(2, 1).get(0)).contains("beta"));
        assertEquals("共 2 页", p.result().summary());
        assertEquals(1, p.units().search(1, 2, "ALPHA", 5).get(0).unit());
    }

    @Test
    public void wordParagraphsAndTablesBecomeLines() throws Exception {
        Path docx = temp.getRoot().toPath().resolve("notes.docx");
        try (XWPFDocument doc = new XWPFDocument(); OutputStream out = Files.newOutputStream(docx)) {
            doc.createParagraph().createRun().setText("第一段");
            XWPFTable table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("A1");
            table.getRow(0).getCell(1).setText("B1");
            table.getRow(1).getCell(0).setText("A2");
            table.getRow(1).getCell(1).setText("B2");
            doc.createParagraph().createRun().setText("最后一段");
            doc.write(out);
        }
        Parsed p = parse(docx);
        assertEquals("LINE", p.result().unit());
        List<String> lines = p.units().read(1, p.result().units()).stream().map(String::valueOf).toList();
        assertTrue(lines.toString(), lines.contains("第一段"));
        assertTrue(lines.toString(), lines.contains("A1\tB1"));
        assertTrue(lines.toString(), lines.contains("最后一段"));
    }

    @Test
    public void slidesBecomeUnits() throws Exception {
        Path pptx = temp.getRoot().toPath().resolve("deck.pptx");
        try (XMLSlideShow show = new XMLSlideShow(); OutputStream out = Files.newOutputStream(pptx)) {
            for (String text : new String[]{"开场：目标", "结论：下一步"}) {
                XSLFSlide slide = show.createSlide();
                XSLFTextBox box = slide.createTextBox();
                box.setText(text);
            }
            show.write(out);
        }
        Parsed p = parse(pptx);
        assertEquals("SLIDE", p.result().unit());
        assertEquals(2, p.result().units());
        assertTrue(String.valueOf(p.units().read(2, 1).get(0)).contains("结论"));
    }

    @Test
    public void largeTextIsStreamedWrappedAndLimited() throws Exception {
        Path log = temp.getRoot().toPath().resolve("big.log");
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 12_000; i++) text.append("line ").append(i).append('\n');
        text.append("x".repeat(4500)).append('\n');
        Files.writeString(log, text, StandardCharsets.UTF_8);
        Parsed p = parse(log);
        assertEquals(12_003, p.result().units());
        assertNull("large text is read on demand", p.result().inline());
        assertTrue(p.result().summary().contains("折行"));
        assertEquals("line 7000", p.units().read(7000, 1).get(0));
        assertEquals("reads across chunk files", List.of("line 4999", "line 5000", "line 5001"),
                p.units().read(4999, 3).stream().map(String::valueOf).toList());

        ReflectionTestUtils.setField(parser, "maxUnits", 100L);
        Parsed limited = parse(log);
        assertTrue(limited.result().truncated());
        assertEquals(100, limited.result().units());
    }

    @Test
    public void corruptSpreadsheetIsAReadableFailure() throws Exception {
        Path broken = temp.getRoot().toPath().resolve("broken.xlsx");
        Files.write(broken, new byte[]{1, 2, 3, 4, 5});
        try {
            parse(broken);
            fail("expected a parse failure");
        } catch (ChatFileParser.ParseFailure failure) {
            assertTrue(failure.getMessage().contains("Excel"));
        }
    }

    @Test
    public void typesAreWhitelistedAndImagesGoElsewhere() {
        assertEquals("SHEET", ChatFileType.of("a.XLSX").fileKind());
        assertEquals("CODE", ChatFileType.of("Dockerfile").fileKind());
        assertNull(ChatFileType.of("setup.exe"));
        assertNull(ChatFileType.of("archive.zip"));
        assertTrue(ChatFileType.isImage("photo.JPG"));
    }

    private static void row(Sheet sheet, int index, String... values) {
        Row row = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
    }
}
