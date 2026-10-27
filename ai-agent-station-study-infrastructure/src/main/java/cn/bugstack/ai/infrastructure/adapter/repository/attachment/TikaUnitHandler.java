package cn.bugstack.ai.infrastructure.adapter.repository.attachment;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns Tika's XHTML into units: PDF pages ({@code div.page}), slides ({@code div.slide-content},
 * notes appended), spreadsheet rows ({@code table/tr/td}, sheet name from {@code h1}) or document lines.
 */
final class TikaUnitHandler extends DefaultHandler {

    enum Mode { LINES, PAGES, SLIDES, TABLE }

    /** Thrown to stop Tika once the writer reports a limit. */
    static final class Stop extends SAXException {
        Stop() { super("parse limit reached"); }
    }

    private static final Set<String> BLOCKS = Set.of("p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "div", "pre",
            "blockquote", "dt", "dd", "caption", "title", "section", "article");

    private final Mode mode;
    private final ParsedFileWriter out;
    private boolean inBody;
    private int skipDepth;
    private int depth;
    private boolean stopped;

    // LINES
    private final StringBuilder block = new StringBuilder();
    private boolean blankEmitted;
    private int tableRowDepth = -1;
    private final StringBuilder rowLine = new StringBuilder();

    // PAGES / SLIDES
    private int unitDepth = -1;
    private int notesDepth = -1;
    private final StringBuilder unit = new StringBuilder();
    private final StringBuilder notes = new StringBuilder();
    private String pendingSlide;

    // TABLE
    private boolean inHeading;
    private final StringBuilder heading = new StringBuilder();
    private String sheetName;
    private List<String> row;
    private StringBuilder cell;

    TikaUnitHandler(Mode mode, ParsedFileWriter out) {
        this.mode = mode;
        this.out = out;
    }

    boolean stopped() {
        return stopped;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
        String name = name(localName, qName);
        depth++;
        if ("body".equals(name)) { inBody = true; return; }
        if (!inBody) return;
        if (skipDepth > 0) { skipDepth++; return; }
        if ("script".equals(name) || "style".equals(name)) { skipDepth = 1; return; }
        String css = attributes == null ? null : attributes.getValue("class");
        switch (mode) {
            case PAGES -> {
                if ("div".equals(name) && "page".equals(css)) { unitDepth = depth; unit.setLength(0); }
                else if (unitDepth > 0 && BLOCKS.contains(name)) newline(unit);
            }
            case SLIDES -> {
                if ("div".equals(name) && ("slide-content".equals(css) || "page".equals(css))) {
                    emitPendingSlide();
                    unitDepth = depth;
                    unit.setLength(0);
                } else if ("div".equals(name) && "slide-notes".equals(css)) {
                    notesDepth = depth;
                    notes.setLength(0);
                } else if ("div".equals(name) && css != null && css.startsWith("slide-master")) {
                    skipDepth = 1;
                } else if (BLOCKS.contains(name)) {
                    newline(notesDepth > 0 ? notes : unit);
                }
            }
            case TABLE -> {
                if ("h1".equals(name)) { inHeading = true; heading.setLength(0); }
                else if ("table".equals(name)) { section(sheetName); sheetName = null; }
                else if ("tr".equals(name)) row = new ArrayList<>();
                else if (("td".equals(name) || "th".equals(name)) && row != null) cell = new StringBuilder();
            }
            case LINES -> {
                if ("tr".equals(name)) { flushBlock(); tableRowDepth = depth; rowLine.setLength(0); }
                else if (("td".equals(name) || "th".equals(name)) && tableRowDepth > 0 && rowLine.length() > 0) rowLine.append('\t');
                else if ("br".equals(name)) append("\n");
                else if (BLOCKS.contains(name) && tableRowDepth < 0) flushBlock();
                else if (BLOCKS.contains(name) && rowLine.length() > 0 && rowLine.charAt(rowLine.length() - 1) != '\t') rowLine.append(' ');
            }
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        String name = name(localName, qName);
        try {
            if (!inBody || "body".equals(name)) return;
            if (skipDepth > 0) { skipDepth--; return; }
            switch (mode) {
                case PAGES -> {
                    if (depth == unitDepth) { emitPage(unit.toString()); unitDepth = -1; }
                }
                case SLIDES -> {
                    if (depth == notesDepth) {
                        notesDepth = -1;
                        String text = notes.toString().strip();
                        if (text.isEmpty()) break;
                        if (unitDepth > 0) unit.append("\n备注：").append(text);
                        else if (pendingSlide != null) pendingSlide = pendingSlide + "\n备注：" + text;
                    } else if (depth == unitDepth) {
                        pendingSlide = unit.toString().strip();
                        unitDepth = -1;
                    }
                }
                case TABLE -> {
                    if ("h1".equals(name)) { inHeading = false; sheetName = heading.toString().strip(); }
                    else if (("td".equals(name) || "th".equals(name)) && row != null && cell != null) {
                        row.add(cell.toString().strip());
                        cell = null;
                    } else if ("tr".equals(name) && row != null) {
                        emitRow(row);
                        row = null;
                    }
                }
                case LINES -> {
                    if (depth == tableRowDepth) {
                        tableRowDepth = -1;
                        String line = rowLine.toString().replace('\n', ' ').strip();
                        if (!line.replace("\t", "").isBlank()) emitLine(line);
                        rowLine.setLength(0);
                    } else if (BLOCKS.contains(name) && tableRowDepth < 0) {
                        flushBlock();
                    }
                }
            }
        } finally {
            depth--;
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        if (!inBody || skipDepth > 0) return;
        String text = new String(ch, start, length);
        switch (mode) {
            case PAGES -> { if (unitDepth > 0) unit.append(text); }
            case SLIDES -> {
                if (notesDepth > 0) notes.append(text);
                else if (unitDepth > 0) unit.append(text);
            }
            case TABLE -> {
                if (inHeading) heading.append(text);
                else if (cell != null) cell.append(text);
            }
            case LINES -> append(text);
        }
    }

    @Override
    public void endDocument() throws SAXException {
        if (mode == Mode.SLIDES) emitPendingSlide();
        if (mode == Mode.LINES) flushBlock();
    }

    private void append(String text) {
        if (tableRowDepth > 0) rowLine.append(text);
        else block.append(text);
    }

    private void flushBlock() throws SAXException {
        if (block.length() == 0) return;
        String[] lines = block.toString().split("\\r?\\n", -1);
        block.setLength(0);
        for (String line : lines) {
            String value = line.stripTrailing();
            if (value.isBlank()) {
                // Keep paragraph breaks readable without long runs of blank lines.
                if (!blankEmitted) { emitLine(""); blankEmitted = true; }
            } else {
                emitLine(value);
                blankEmitted = false;
            }
        }
    }

    private void emitPendingSlide() throws SAXException {
        if (pendingSlide == null) return;
        String slide = pendingSlide;
        pendingSlide = null;
        guard(() -> out.page(slide));
    }

    private void emitPage(String text) throws SAXException {
        guard(() -> out.page(text.replaceAll("\\n{3,}", "\n\n")));
    }

    private void emitLine(String line) throws SAXException {
        guard(() -> out.line(line));
    }

    private void emitRow(List<String> cells) throws SAXException {
        guard(() -> out.row(cells));
    }

    private void section(String name) throws SAXException {
        guard(() -> out.section(name == null ? "" : name));
    }

    private void guard(Runnable action) throws SAXException {
        if (stopped) throw new Stop();
        try {
            action.run();
        } catch (ParsedFileWriter.LimitReached limit) {
            stopped = true;
            throw new Stop();
        }
    }

    private static void newline(StringBuilder target) {
        if (target.length() > 0 && target.charAt(target.length() - 1) != '\n') target.append('\n');
    }

    private static String name(String localName, String qName) {
        String name = localName == null || localName.isEmpty() ? qName : localName;
        return name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
    }
}
