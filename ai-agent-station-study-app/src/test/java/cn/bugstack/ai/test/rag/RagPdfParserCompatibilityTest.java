package cn.bugstack.ai.test.rag;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.Test;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayOutputStream;
import java.util.stream.Collectors;
import static org.junit.Assert.assertTrue;

/** Real PDF parsing on the application dependency graph; no model or external storage. */
public class RagPdfParserCompatibilityTest {
    @Test public void tikaCanDetectAndExtractPdfText() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("TAgent PDF ingestion compatibility fixture");
                content.endText();
            }
            pdf.save(output);
        }
        var documents = new TikaDocumentReader(new ByteArrayResource(output.toByteArray())).get();
        String text = documents.stream().map(d -> d.getText()).collect(Collectors.joining("\n"));
        assertTrue(text.contains("TAgent PDF ingestion compatibility fixture"));
    }
}
