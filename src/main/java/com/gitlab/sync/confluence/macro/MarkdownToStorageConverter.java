package com.gitlab.sync.confluence.macro;

import com.vladsch.flexmark.ast.AutoLink;
import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.Code;
import com.gitlab.sync.confluence.security.SanitizeUtil;
import com.vladsch.flexmark.ast.Emphasis;
import com.vladsch.flexmark.ast.HtmlBlock;
import com.vladsch.flexmark.ast.HtmlCommentBlock;
import com.vladsch.flexmark.ast.HtmlInline;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.ListItem;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.StrongEmphasis;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.ext.emoji.EmojiExtension;
import com.vladsch.flexmark.ext.footnotes.FootnoteExtension;
import com.vladsch.flexmark.ext.toc.TocExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.Strikethrough;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts GitHub-flavoured Markdown into Confluence Storage Format (CSF) XML.
 *
 * <p>Parsing is delegated to {@link
 * <a href="https://flexmark.github.io/">Flexmark</a>} (via
 * {@code flexmark-all}), which produces a Markdown AST. This converter walks
 * that AST and emits Confluence storage — <em>not</em> HTML — so the result can
 * be dropped straight into a Confluence page body:</p>
 *
 * <ul>
 *   <li>Headings {@code h1..h6} become {@code <h1>..</h6>} with generated
 *       {@code id} attributes (so anchor links work).</li>
 *   <li>Paragraphs, bold/italic, inline and fenced code, links, images,
 *       blockquotes, horizontal rules and (ordered/unordered) lists map to
 *       their native storage equivalents.</li>
 *   <li>GFM tables become raw HTML {@code <table>} markup (macro output is
 *       not re-parsed, so nested Confluence macros would not render).</li>
 *   <li>GFM task lists ({@code - [ ]} / {@code - [x]}) become list items prefixed
 *       with a ☐ / ☑ glyph.</li>
 *   <li>GFM strikethrough becomes {@code <strike>}.</li>
 *   <li>Emoji shortcodes ({@code :rocket:}) become the Unicode emoji
 *       character, passed through as plain text.</li>
 *   <li>Raw HTML uses the safe FR12 subset: block-level
 *       {@code <details>}/{@code <summary>} becomes the expand macro
 *       ({@code ac:structured-macro ac:name="expand"}) with the inner content
 *       converted as regular markdown (hoisted from the source before
 *       parsing); {@code <br>}/{@code <sub>}/{@code <sup>} and HTML
 *       {@code <ul>}/{@code <ol>}/{@code <li>} and
 *       {@code <table>}/{@code <tr>}/{@code <td>}/{@code <th>} (with
 *       {@code colspan}/{@code rowspan}) pass the sanitizer untouched.
 *       Neutral tags outside the subset (e.g. {@code <kbd>}, {@code <mark>})
 *       lose their markup but keep their text; dangerous constructs
 *       ({@code <script>}, {@code on*} handlers, {@code javascript:} URLs)
 *       are removed by {@link SanitizeUtil}.</li>
 *   <li>Footnote definitions are collected and rendered at the end of the
 *       document as a styled list.</li>
 *   <li>A {@code [TOC]} marker (or the {@code toc} fenced block) becomes a
 *       self-rendered nested list of anchor links to the document headings.
 *       Confluence does not execute structured macros that arrive inside
 *       macro output, so delegating to the built-in {@code toc} macro would
 *       print its raw XML on the page.</li>
 *   <li>Fenced code blocks whose language is a diagram type (mermaid/plantuml)
 *       are handed to {@link KrokiRenderer}.</li>
 * </ul>
 *
 * <p>The output is a fragment (no root element) suitable for embedding inside a
 * page's {@code <div class="content">}. All text/attribute values are XML-escaped.</p>
 */
public final class MarkdownToStorageConverter {

    private static final Logger LOG = LoggerFactory.getLogger(MarkdownToStorageConverter.class);

    // ------------------------------------------------------------------
    // Line sink — source-line recording for comment anchoring
    // ------------------------------------------------------------------

    /**
     * A commentable block element as emitted by this converter, with its
     * 1-based source-line span in the ORIGINAL markdown and its plain text.
     *
     * <p>The list of spans (in emission order) is exactly the sequence of
     * {@code p, li, h1..h6, td} elements the frontend selects in the
     * rendered DOM (document order), so span index == the frontend's
     * 0-based {@code lineAnchor}.</p>
     */
    public static final class BlockSpan {
        /** 1-based first source line of the block. */
        public final int startLine;
        /** 1-based last source line of the block (inclusive). */
        public final int endLine;
        /** Normalized-ish plain text of the block (for similarity matching). */
        public final String text;

        public BlockSpan(int startLine, int endLine, String text) {
            this.startLine = startLine;
            this.endLine = endLine;
            this.text = text == null ? "" : text;
        }
    }

    /**
     * Active sink for the currently running {@link #convert} on this thread;
     * {@code null} outside a line-recording conversion. A nested conversion
     * (a hoisted {@code <details>} body) appends to the SAME list with an
     * added line offset ({@link #LINE_BASE}).
     */
    private static final ThreadLocal<List<BlockSpan>> LINE_SINK = new ThreadLocal<>();

    /**
     * Cumulative 0-based line offset for the current conversion level —
     * nonzero only inside a recursively converted {@code <details>} body,
     * whose inner AST lines are relative to the body, not to the document.
     */
    private static final ThreadLocal<Integer> LINE_BASE = ThreadLocal.withInitial(() -> 0);

    /**
     * Line map for the source currently being parsed: {@code [hoistedLine0] =
     * originalLine0}. Non-null only while parsing a details-hoisted source
     * (the hoisting collapses each {@code <details>} region into a
     * placeholder, shifting AST line numbers); identity otherwise.
     */
    private static final ThreadLocal<int[]> LINE_MAP = new ThreadLocal<>();

    private static int toOriginalLine0(int line0) {
        int[] map = LINE_MAP.get();
        if (map != null && line0 >= 0 && line0 < map.length) {
            int v = map[line0];
            return v >= 0 ? v : line0;
        }
        return line0;
    }

    private static void recordLine(int startLine, int endLine, String text) {
        List<BlockSpan> sink = LINE_SINK.get();
        if (sink != null) {
            int base = LINE_BASE.get();
            String t = text == null ? "" : text;
            t = t.replaceAll("\\s+", " ").trim();
            int s = toOriginalLine0(startLine - 1) + 1 + base;
            int e = toOriginalLine0(endLine - 1) + 1 + base;
            if (e < s) {
                e = s;
            }
            sink.add(new BlockSpan(s, e, t));
        }
    }

    private static void recordNode(Node node, String text) {
        // flexmark reports 0-based lines; the rest of the plugin uses 1-based.
        recordLine(node.getStartLineNumber() + 1, node.getEndLineNumber() + 1, text);
    }

    /**
     * Record the commentable elements ({@code p, li, h1..h6, td}) that
     * survive inside an FR12-sanitized raw-HTML block, in DOM order. Raw
     * HTML lists/tables keep their {@code <li>}/{@code <td>} through the
     * sanitizer and reach the rendered DOM, so they must appear in the span
     * list exactly like their markdown-rendered counterparts. Every such
     * element is attributed to the source block's first line (a raw-HTML
     * block has no per-element markdown line of its own).
     */
    private static void recordHtmlBlocks(String cleanHtml, Node block) {
        if (cleanHtml == null || cleanHtml.isEmpty()) {
            return;
        }
        if (LINE_SINK.get() == null) {
            return;
        }
        int line = block.getStartLineNumber() + 1;
        try {
            org.jsoup.nodes.Document doc = Jsoup.parseBodyFragment(cleanHtml);
            // document() order == the order these elements appear in the DOM.
            for (Element el : doc.select("p, li, h1, h2, h3, h4, h5, h6, td")) {
                recordLine(line, line, el.text());
            }
        } catch (Exception e) {
            LOG.debug("recordHtmlBlocks: could not parse sanitized HTML: {}", e.getMessage());
        }
    }

    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(
                    TablesExtension.create(),
                    FootnoteExtension.create(),
                    StrikethroughExtension.create(),
                    TaskListExtension.create(),
                    TocExtension.create(),
                    EmojiExtension.create()))
            .build();

    private MarkdownToStorageConverter() {
        // static utility — no instances
    }

    /**
     * Convert a Markdown document to Confluence Storage Format.
     *
     * @param markdown    the raw Markdown source (may be {@code null})
     * @param krokiBaseUrl the Kroki base URL for diagram blocks, or {@code null}
     *                     to use the default / system property value
     * @return storage-format XML fragment; never {@code null}
     */
    public static String convert(String markdown, String krokiBaseUrl) {
        return convert(markdown, krokiBaseUrl, null);
    }

    /**
     * Same as {@link #convert(String, String)} but additionally records, in
     * {@code lineSink}, one {@link BlockSpan} per commentable block element
     * ({@code p, li, h1..h6, td}) in the exact emission order — i.e. the
     * frontend's {@code lineAnchor} index space.
     *
     * @param lineSink target list, or {@code null} to disable recording
     */
    public static String convert(String markdown, String krokiBaseUrl, List<BlockSpan> lineSink) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        boolean sinkOwner = lineSink != null;
        if (sinkOwner) {
            LINE_SINK.set(lineSink);
        }
        int[] lineMap = null;
        int[] prevMap = LINE_MAP.get();
        try {
            // FR12: hoist <details>/<summary> blocks from the raw source
            // BEFORE parsing. A blank line inside the block terminates the
            // raw-HTML block per CommonMark, so the inner markdown cannot be
            // recovered from the flexmark AST — the block is replaced by a
            // placeholder and rendered as the expand macro instead.
            Map<String, DetailsEntry> details = new HashMap<>();
            DETAILS.get().push(details);
            try {
                String source;
                if (containsIgnoreCase(markdown, "<details")) {
                    DetailsResult dr = extractDetails(markdown, details, krokiBaseUrl);
                    source = dr.source;
                    lineMap = dr.lineMap;
                    if (lineMap != null) {
                        LINE_MAP.set(lineMap);
                    }
                } else {
                    source = markdown;
                }
                // Clear after the pre-pass: a nested (details) conversion may
                // have dirtied the per-document state while running.
                HEADING_COUNTS.get().clear(); // per-document slug dedup, not per-thread
                Node document = PARSER.parse(source);
                // Pre-compute heading slugs in document order: the TOC may sit
                // before later headings and must link to the ids those headings
                // will actually carry; rendering reuses the same slugs.
                java.util.Map<Node, String> preSlugs = PRE_SLUGS.get();
                preSlugs.clear();
                List<HeadingInfo> heads = DOC_HEADINGS.get();
                heads.clear();
                for (Node n : document.getDescendants()) {
                    if (n instanceof Heading) {
                        Heading h = (Heading) n;
                        preSlugs.put(h, slugId(h));
                        heads.add(new HeadingInfo(Math.max(1, Math.min(6, h.getLevel())),
                                preSlugs.get(h), plainTextOf(h)));
                    }
                }
                List<FooterEntry> footers = new ArrayList<>();
                StringBuilder out = new StringBuilder(markdown.length() * 2);
                renderBlocks(document, out, krokiBaseUrl, footers);
                if (!footers.isEmpty()) {
                    out.append(footerSection(footers));
                }
                return out.toString();
            } finally {
                DETAILS.get().pop();
                if (lineMap != null) {
                    if (prevMap != null) {
                        LINE_MAP.set(prevMap);
                    } else {
                        LINE_MAP.remove();
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Markdown → Confluence Storage conversion failed; returning raw-escaped fallback", e);
            // Safe-failure: never return empty / never let the page break.
            // Fall back to an escaped, readable paragraph so the user still
            // sees something rather than a blank page.
            return "<p style=\"color:#888\"><em>Не удалось отрендерить Markdown. "
                    + "Исходный текст:</em></p><p>"
                    + xmlEscape(markdown) + "</p>";
        }
    }

    // ------------------------------------------------------------------
    // Block-level rendering
    // ------------------------------------------------------------------

    private static void renderBlocks(Node node, StringBuilder out, String krokiUrl,
                                     List<FooterEntry> footers) {
        for (Node child : node.getChildren()) {
            renderBlock(child, out, krokiUrl, footers);
        }
    }

    private static void renderBlock(Node node, StringBuilder out, String krokiUrl,
                                    List<FooterEntry> footers) {
        if (node instanceof Heading) {
            Heading h = (Heading) node;
            int level = Math.max(1, Math.min(6, h.getLevel()));
            String id = slugId(h);
            recordNode(node, plainTextOf(h));
            out.append("<h").append(level).append(" id=\"").append(id).append("\">");
            renderInlines(h, out, krokiUrl, footers);
            out.append("</h").append(level).append('>');
        } else if (node instanceof Paragraph) {
            // A paragraph whose only content is the literal text "[TOC]" (or
            // "{TOC}" / "<div class="toc">") is a TOC directive. flexmark
            // parses the bare "[TOC]" as a LinkRef, but the Paragraph node
            // keeps the full source text in its own chars.
            String raw = node.getChars() == null ? "" : node.getChars().toString().trim();
            if (isTocMarker(raw)) {
                // The TOC renders one <li> per heading — record each so the
                // span list keeps parity with the frontend's block selector.
                renderToc(out, node.getStartLineNumber() + 1);
                return;
            }
            if (DETAILS_PLACEHOLDER.matcher(raw).matches()) {
                // FR12 placeholder left by the <details> pre-pass: convert
                // the hoisted body NOW (document order) and emit the expand
                // macro. The body's AST lines are body-relative, so the line
                // base is raised to the body's first content line in this
                // document for the duration of the recursive conversion.
                java.util.Deque<Map<String, DetailsEntry>> stack = DETAILS.get();
                Map<String, DetailsEntry> details = stack.peek();
                DetailsEntry entry = details == null ? null : details.get(raw);
                if (entry != null) {
                    int prevBase = LINE_BASE.get();
                    boolean sinkActive = LINE_SINK.get() != null;
                    if (sinkActive) {
                        LINE_BASE.set(prevBase + entry.bodyBase);
                    }
                    String rendered;
                    try {
                        rendered = convert(entry.body, krokiUrl);
                    } finally {
                        if (sinkActive) {
                            LINE_BASE.set(prevBase);
                        }
                    }
                    out.append(expandMacroXml(entry.title, rendered));
                    return;
                }
            }
            recordNode(node, plainTextOf(node));
            out.append("<p>");
            renderInlines(node, out, krokiUrl, footers);
            out.append("</p>");
        } else if (node instanceof FencedCodeBlock) {
            FencedCodeBlock fb = (FencedCodeBlock) node;
            String info = fb.getInfo() == null ? "" : fb.getInfo().toString().trim();
            String language = firstWord(info);
            String code = codeBlockText(fb);
            if (KrokiRenderer.isDiagramBlock(language)) {
                out.append(KrokiRenderer.renderDiagram(language, code, krokiUrl));
            } else {
                appendCodeBlock(out, code, language);
            }
        } else if (node instanceof IndentedCodeBlock) {
            IndentedCodeBlock icb = (IndentedCodeBlock) node;
            appendCodeBlock(out, codeBlockText(icb), "");
        } else if (node instanceof BulletList) {
            renderList(node, out, "ul", null, krokiUrl, footers);
        } else if (node instanceof OrderedList) {
            OrderedList ol = (OrderedList) node;
            renderList(node, out, "ol", ol.getStartNumber(), krokiUrl, footers);
        } else if (node instanceof BlockQuote) {
            out.append("<blockquote>");
            renderBlocks(node, out, krokiUrl, footers);
            out.append("</blockquote>");
        } else if (node instanceof ThematicBreak) {
            out.append("<hr/>");
        } else if (node instanceof com.vladsch.flexmark.ext.tables.TableBlock) {
            renderTable(node, out, krokiUrl, footers);
        } else if (node instanceof com.vladsch.flexmark.ext.footnotes.FootnoteBlock) {
            // Footnote *definitions* are deferred to the footer.
            com.vladsch.flexmark.ext.footnotes.FootnoteBlock fn =
                    (com.vladsch.flexmark.ext.footnotes.FootnoteBlock) node;
            // Bare marker ("1") via getText(); fall back to the raw sequence.
            String label = fn.getText() != null ? fn.getText().toString().trim()
                    : (fn.getFootnote() == null ? "" : fn.getFootnote().toString().trim());
            List<String> parts = new ArrayList<>();
            renderChildrenTo(node, parts, krokiUrl, footers);
            String text = String.join("", parts).trim();
            footers.add(new FooterEntry(label, text, node.getStartLineNumber() + 1));
        } else if (node instanceof com.vladsch.flexmark.ext.toc.TocBlock) {
            renderToc(out, node.getStartLineNumber() + 1);
        } else if (node instanceof HtmlCommentBlock) {
            // Block-level HTML comment (e.g. the docs-meta header that JiHu
            // docs carry). HtmlCommentBlock extends HtmlBlockBase, NOT
            // HtmlBlock, so it would otherwise fall into the unknown-leaf
            // branch below and be printed as escaped text. Comments carry
            // no rendered content — drop them.
        } else if (node instanceof HtmlBlock) {
            // Raw HTML from the repository — untrusted: sanitize, never pass
            // through. Block-level <details> were already hoisted to expand
            // macros by the pre-pass; what reaches here (an inline or
            // unterminated <details>, an HTML list/table, ...) is the FR12
            // safe subset, which the sanitizer preserves, or foreign markup,
            // which is reduced to its visible text / removed.
            String html = node.getChars() == null ? "" : node.getChars().toString();
            String clean = SanitizeUtil.sanitizeHtml(html);
            // The FR12 subset KEEPS <li>/<td> (and friends) from raw HTML:
            // those elements reach the rendered DOM, so record them for
            // line-anchor parity (all attributed to this block's first line).
            recordHtmlBlocks(clean, node);
            out.append(clean);
        } else if (node.getChars() != null && !node.getChars().isEmpty()
                && !node.hasChildren()) {
            // Unknown leaf block — emit escaped text defensively.
            recordNode(node, node.getChars().toString());
            out.append("<p>").append(xmlEscape(node.getChars().toString())).append("</p>");
        } else {
            // Container / unrecognised block: recurse so no content is dropped.
            renderBlocks(node, out, krokiUrl, footers);
        }
    }

    private static void renderList(Node listNode, StringBuilder out, String tag,
                                   Integer start, String krokiUrl, List<FooterEntry> footers) {
        if ("ol".equals(tag) && start != null && start != 1) {
            out.append("<ol start=\"").append(start).append("\">");
        } else {
            out.append('<').append(tag).append('>');
        }
        for (Node child : listNode.getChildren()) {
            if (child instanceof ListItem) {
                renderListItem(child, out, krokiUrl, footers);
            } else if (child instanceof BulletList || child instanceof OrderedList) {
                // Nested list directly (loose lists can nest).
                if (child instanceof BulletList) {
                    renderList(child, out, "ul", null, krokiUrl, footers);
                } else {
                    OrderedList nested = (OrderedList) child;
                    renderList(child, out, "ol", nested.getStartNumber(), krokiUrl, footers);
                }
            }
        }
        out.append("</").append(tag).append('>');
    }

    private static void renderListItem(Node item, StringBuilder out, String krokiUrl,
                                       List<FooterEntry> footers) {
        boolean checked = false;
        String taskMarker = null;
        if (item instanceof com.vladsch.flexmark.ext.gfm.tasklist.TaskListItem) {
            taskMarker = taskListMarker(item);
            if (taskMarker != null) {
                checked = "x".equalsIgnoreCase(taskMarker.trim());
            }
        }

        recordNode(item, plainTextOf(item));
        out.append("<li>");
        if (taskMarker != null) {
            out.append(checked ? "☑ " : "☐ ");
        }
        // A list item's content is one or more paragraphs / nested lists. The
        // first paragraph is rendered inline (no <p> wrapper) inside the <li>;
        // nested blocks keep their own tags.
        boolean firstPara = true;
        for (Node child : item.getChildren()) {
            if (child instanceof Paragraph) {
                if (firstPara) {
                    renderInlines(child, out, krokiUrl, footers);
                    firstPara = false;
                } else {
                    out.append("<br/>");
                    renderInlines(child, out, krokiUrl, footers);
                }
            } else if (child instanceof BulletList || child instanceof OrderedList) {
                if (child instanceof BulletList) {
                    renderList(child, out, "ul", null, krokiUrl, footers);
                } else {
                    OrderedList nested = (OrderedList) child;
                    renderList(child, out, "ol", nested.getStartNumber(), krokiUrl, footers);
                }
            } else if (child instanceof FencedCodeBlock) {
                FencedCodeBlock fb = (FencedCodeBlock) child;
                String info = fb.getInfo() == null ? "" : fb.getInfo().toString().trim();
                String language = firstWord(info);
                String code = codeBlockText(fb);
                if (KrokiRenderer.isDiagramBlock(language)) {
                    out.append(KrokiRenderer.renderDiagram(language, code, krokiUrl));
                } else {
                    appendCodeBlock(out, code, language);
                }
            } else {
                renderBlock(child, out, krokiUrl, footers);
            }
        }
        out.append("</li>");
    }

    /**
     * Inspect a task-list item's leading source text to recover the checkbox
     * state. The Flexmark tasklist extension keeps the marker at the start of
     * the item's source, so we scan the first {@code [ ]} / {@code [x]} token.
     *
     * @return the character inside the brackets ({@code " "} or {@code "x"}),
     *         or {@code null} if the item is not a task item
     */
    private static String taskListMarker(Node item) {
        BasedSequence chars = item.getChars();
        if (chars == null) {
            return null;
        }
        String src = chars.toString();
        int idx = src.indexOf('[');
        if (idx < 0 || idx + 2 > src.length()) {
            return null;
        }
        char c = src.charAt(idx + 1);
        if (c != ' ' && c != 'x' && c != 'X') {
            return null;
        }
        return String.valueOf(c);
    }

    private static void renderTable(Node table, StringBuilder out, String krokiUrl,
                                    List<FooterEntry> footers) {
        // Emit a raw HTML table: a macro's output is NOT re-parsed, so a
        // nested ac:structured-macro would show up as raw XML in the view.
        // The 8.5 theme styles tables only when the editor's classes are
        // present (border/header shading key on .confluenceTable/.confluenceTh/
        // .confluenceTd); a bare <table> renders borderless.
        out.append("<table class=\"confluenceTable\">");
        // Walk: TableBlock -> (TableHead | TableSeparator | TableBody) -> TableRow -> TableCell.
        boolean inHead = false;
        boolean headOpen = false;
        boolean bodyOpen = false;
        for (Node child : table.getChildren()) {
            String cn = child.getClass().getSimpleName();
            if (cn.equals("TableHead")) {
                if (bodyOpen) { out.append("</tbody>"); bodyOpen = false; }
                out.append("<thead>"); headOpen = true; inHead = true;
            } else if (cn.equals("TableBody")) {
                if (headOpen) { out.append("</thead>"); headOpen = false; }
                out.append("<tbody>"); bodyOpen = true; inHead = false;
            } else if (cn.equals("TableSeparator")) {
                continue; // separator row is not content
            } else if (isTableRow(child)) {
                // Fallback: a TableRow directly under the block.
                out.append("<tr>");
                for (Object cellObj : child.getChildren()) {
                    Node cell = (Node) cellObj;
                    if (isTableCell(cell)) {
                        appendTableCell(out, cell, krokiUrl, footers);
                    }
                }
                out.append("</tr>");
                continue;
            }
            for (Object rowObj : child.getChildren()) {
                Node row = (Node) rowObj;
                if (isTableRow(row)) {
                    out.append("<tr>");
                    for (Object cellObj : row.getChildren()) {
                        Node cell = (Node) cellObj;
                        if (isTableCell(cell)) {
                            appendTableCell(out, cell, krokiUrl, footers);
                        }
                    }
                    out.append("</tr>");
                }
            }
            if (headOpen) { out.append("</thead>"); headOpen = false; }
            if (bodyOpen) { out.append("</tbody>"); bodyOpen = false; }
        }
        if (headOpen) out.append("</thead>");
        if (bodyOpen) out.append("</tbody>");
        out.append("</table>");
    }

    private static void appendTableCell(StringBuilder out, Node cell, String krokiUrl,
                                        List<FooterEntry> footers) {
        boolean header = cell instanceof com.vladsch.flexmark.ext.tables.TableCell
                && ((com.vladsch.flexmark.ext.tables.TableCell) cell).isHeader();
        String tag = header ? "th" : "td";
        if (!header) {
            // The frontend selector includes <td> but not <th>.
            recordNode(cell, plainTextOf(cell));
        }
        // Theme classes — see renderTable; without them the header row loses
        // its shading and cells lose padding/borders.
        out.append('<').append(tag)
                .append(header ? " class=\"confluenceTh\"" : " class=\"confluenceTd\"")
                .append('>');
        renderInlines(cell, out, krokiUrl, footers);
        out.append("</").append(tag).append('>');
    }

    private static boolean isTableRow(Node n) {
        return n != null && n.getClass().getSimpleName().equals("TableRow");
    }

    private static boolean isTableCell(Node n) {
        return n != null && n.getClass().getSimpleName().equals("TableCell");
    }

    // ------------------------------------------------------------------
    // Inline-level rendering
    // ------------------------------------------------------------------

    /** Tags whose inline form must swallow its content until the matching close. */
    private static final Set<String> DANGEROUS_INLINE_TAGS = new HashSet<>(Arrays.asList(
            "script", "style", "iframe", "object", "embed", "form", "applet",
            "noscript", "base", "meta", "link"));

    private static void renderInlineChildren(Node node, StringBuilder out, String krokiUrl,
                                              List<FooterEntry> footers) {
        // A dangerous inline tag (e.g. <script> mid-paragraph) must swallow
        // its content: flexmark splits such markup into HtmlInline + Text
        // nodes, so the script body would otherwise survive as plain text
        // while only the tags themselves hit the sanitizer.
        String pendingClose = null;
        for (Node child : node.getChildren()) {
            if (pendingClose != null) {
                if (isClosingTag(child, pendingClose)) {
                    pendingClose = null;
                }
                continue;
            }
            String tag = dangerousInlineTag(child);
            if (tag != null) {
                pendingClose = tag;
                continue;
            }
            renderInline(child, out, krokiUrl, footers);
        }
    }

    /** @return the tag name if the node opens a dangerous inline tag, else null */
    private static String dangerousInlineTag(Node node) {
        if (!(node instanceof HtmlInline)) {
            return null;
        }
        String raw = node.getChars() == null ? ""
                : node.getChars().toString().trim().toLowerCase(Locale.ROOT);
        if (raw.length() < 2 || raw.charAt(0) != '<' || raw.charAt(1) == '/') {
            return null;
        }
        int end = 1;
        while (end < raw.length()
                && (Character.isLetterOrDigit(raw.charAt(end)) || raw.charAt(end) == '-')) {
            end++;
        }
        String name = raw.substring(1, end);
        return DANGEROUS_INLINE_TAGS.contains(name) ? name : null;
    }

    private static boolean isClosingTag(Node node, String name) {
        if (!(node instanceof HtmlInline)) {
            return false;
        }
        String raw = node.getChars() == null ? ""
                : node.getChars().toString().trim().toLowerCase(Locale.ROOT);
        int after = 2 + name.length();
        return raw.startsWith("</" + name) && raw.length() > after
                && (raw.charAt(after) == '>' || Character.isWhitespace(raw.charAt(after)));
    }

    private static void renderInline(Node node, StringBuilder out, String krokiUrl,
                                     List<FooterEntry> footers) {
        if (node instanceof Text) {
            // Preserve hard line breaks: Flexmark keeps "\n" in Text for
            // trailing "  " (two-space) line breaks; render them as <br/>.
            String t = node.getChars().toString();
            if (t.contains("\n")) {
                String[] lines = t.split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (i > 0) {
                        out.append("<br/>");
                    }
                    out.append(xmlEscape(lines[i]));
                }
            } else {
                out.append(xmlEscape(t));
            }
        } else if (node instanceof StrongEmphasis) {
            out.append("<strong>");
            renderInlineChildren(node, out, krokiUrl, footers);
            out.append("</strong>");
        } else if (node instanceof Emphasis) {
            out.append("<em>");
            renderInlineChildren(node, out, krokiUrl, footers);
            out.append("</em>");
        } else if (node instanceof Strikethrough) {
            out.append("<strike>");
            renderInlineChildren(node, out, krokiUrl, footers);
            out.append("</strike>");
        } else if (node instanceof com.vladsch.flexmark.ext.emoji.Emoji) {
            // Emoji shortcut (":rocket:"). In flexmark 0.64 the node keeps
            // only the SHORT NAME — the Unicode is resolved at render time —
            // so we run the same resolver the library's own HTML renderer
            // uses (internal API, pinned by the 0.64.8 dependency; the jar
            // ships inside the bundle). Storage Format carries the resulting
            // Unicode character as ordinary text (xmlEscape leaves non-ASCII
            // bytes untouched, the sanitizer only removes markup, never
            // text). Unknown shortcodes keep their original ":name:" form.
            com.vladsch.flexmark.ext.emoji.Emoji emoji =
                    (com.vladsch.flexmark.ext.emoji.Emoji) node;
            com.vladsch.flexmark.ext.emoji.internal.EmojiResolvedShortcut r =
                    com.vladsch.flexmark.ext.emoji.internal.EmojiResolvedShortcut
                            .getEmojiText(emoji,
                                    com.vladsch.flexmark.ext.emoji.EmojiShortcutType.GITHUB,
                                    com.vladsch.flexmark.ext.emoji.EmojiImageType.UNICODE_ONLY,
                                    "", false);
            out.append(xmlEscape(r.isUnicode && r.emojiText != null
                    ? r.emojiText : node.getChars().toString()));
        } else if (node instanceof Code) {
            // Code is a DelimitedNode: the backtick-free text lives in the
            // child Text node, so render the children (not getChars()).
            out.append("<code>");
            renderInlineChildren(node, out, krokiUrl, footers);
            out.append("</code>");
        } else if (node instanceof Link) {
            Link link = (Link) node;
            String url = link.getUrl() == null ? "" : link.getUrl().toString().trim();
            out.append("<a href=\"").append(xmlEscape(url)).append("\">");
            renderInlineChildren(node, out, krokiUrl, footers);
            out.append("</a>");
        } else if (node instanceof AutoLink) {
            AutoLink al = (AutoLink) node;
            out.append("<a href=\"").append(xmlEscape(al.getUrl().toString())).append("\">")
               .append(xmlEscape(node.getChars().toString())).append("</a>");
        } else if (node instanceof Image) {
            Image img = (Image) node;
            String url = img.getUrl() == null ? "" : img.getUrl().toString().trim();
            String alt = "";
            for (Node c : img.getChildren()) {
                if (c instanceof Text) {
                    alt = c.getChars().toString().trim();
                    break;
                }
            }
            // Bare <img> — macro output is not re-processed by Confluence,
            // so <ac:image>/<ri:attachment> would render raw/empty. Relative
            // srcs are rewritten to the attachment proxy by GitLabLinkResolver
            // (inline rendering, no GitLab login wall).
            if (url.isEmpty()) {
                out.append(alt.isEmpty() ? "" : xmlEscape(alt));
            } else {
                out.append("<img src=\"").append(xmlEscape(url))
                   .append("\" alt=\"")
                   .append(xmlEscape(alt))
                   .append("\" style=\"max-width:100%\">");
                if (!alt.isEmpty()) {
                    // The caption is a real <p> in the rendered DOM — record it.
                    recordNode(node, alt);
                    out.append(
                            "<p style=\"font-size:0.8em;color:#888;text-align:center\">"
                                    + xmlEscape(alt) + "</p>");
                }
            }
        } else if (node instanceof com.vladsch.flexmark.ext.footnotes.Footnote) {
            // Inline footnote reference → a superscript number.
            com.vladsch.flexmark.ext.footnotes.Footnote fn =
                    (com.vladsch.flexmark.ext.footnotes.Footnote) node;
            // getText() is the bare marker ("1"), not the raw source ("[^1]").
            String label = fn.getText() == null ? "" : fn.getText().toString().trim();
            if (label.isEmpty()) {
                label = fn.getChars() == null ? "" : fn.getChars().toString().trim();
            }
            out.append("<sup id=\"fn-").append(xmlEscape(label)).append("\">")
               .append(xmlEscape(label)).append("</sup>");
        } else if (node instanceof HtmlInline) {
            // Raw inline HTML from the repository — untrusted. The FR12 safe
            // subset (br/sub/sup without attributes) is passed through
            // verbatim: sanitizing the tags one by one would auto-close an
            // opening <sub> before its content ("H<sub></sub>2O"). Everything
            // else goes through the sanitizer: neutral tags (e.g. <mark>)
            // lose their markup but keep their text, dangerous tags are
            // removed.
            String raw = node.getChars() == null ? "" : node.getChars().toString().trim();
            String pass = normalizeSafeInlineTag(raw);
            out.append(pass != null ? pass : SanitizeUtil.sanitizeHtml(raw));
        } else if (node.getChars() != null && !node.getChars().isEmpty()
                && !node.hasChildren()) {
            // Unknown inline leaf — emit escaped text defensively.
            out.append(xmlEscape(node.getChars().toString()));
        } else {
            // Container inline — recurse.
            renderInlineChildren(node, out, krokiUrl, footers);
        }
    }

    // ------------------------------------------------------------------
    // FR12: raw-HTML subset (details/summary → expand macro, br/sub/sup)
    // ------------------------------------------------------------------

    /** A placeholder line left in the source for a hoisted <details> block. */
    private static final Pattern DETAILS_PLACEHOLDER =
            Pattern.compile("\\A@@gitsync-details-(\\d+)@@\\z");

    /**
     * A hoisted {@code <details>} region awaiting conversion. The body is
     * converted LAZILY, at the placeholder's position in the block walk, so
     * that line-sink spans are recorded in document order (an expand macro
     * emits its inner blocks where it sits, not before the whole document).
     *
     * @param bodyBase 0-based line of the body's first content character in
     *                 the document that contained the {@code <details>}
     */
    private static final class DetailsEntry {
        final String title;
        final String body;
        final int bodyBase;

        DetailsEntry(String title, String body, int bodyBase) {
            this.title = title;
            this.body = body;
            this.bodyBase = bodyBase;
        }
    }

    /**
     * Thread-local stack of per-document placeholder → entry maps.
     * {@link #convert} pushes a level before parsing and pops it afterwards;
     * a details body that is itself converted recursively gets its own level,
     * so nested blocks never clobber each other's placeholders.
     */
    private static final ThreadLocal<java.util.Deque<Map<String, DetailsEntry>>> DETAILS =
            ThreadLocal.withInitial(java.util.ArrayDeque::new);

    /**
     * Result of {@link #extractDetails}: the hoisted source plus the
     * line map (hoisted 0-based line → original 0-based line) needed to
     * translate flexmark AST line numbers back to the original markdown.
     */
    private static final class DetailsResult {
        final String source;
        /** null when nothing was hoisted (the source is unchanged). */
        final int[] lineMap;

        DetailsResult(String source, int[] lineMap) {
            this.source = source;
            this.lineMap = lineMap;
        }
    }

    /**
     * Replace every block-level {@code <details>} element in the raw source
     * with a placeholder line, remembering the region (converted lazily, at
     * the placeholder's position in the block walk).
     *
     * <p>This runs on the raw source (not the flexmark AST) because a blank
     * line inside {@code <details>} terminates the raw-HTML block per
     * CommonMark — the inner markdown would otherwise be unrecognizable.
     * Openers that are not at the start of a line (≤3 spaces of indent),
     * that sit inside a fenced code block, or that are never closed, are left
     * untouched and fall back to the sanitizer.</p>
     *
     * @return the transformed source and its line map; {@code details}
     *         receives placeholder → {@link DetailsEntry} entries
     */
    private static DetailsResult extractDetails(String markdown,
                                                Map<String, DetailsEntry> details,
                                                String krokiUrl) {
        StringBuilder out = new StringBuilder(markdown.length());
        int pos = 0;
        int n = 0;
        // Character spans of the hoisted regions, in order:
        // {openStart, closeEnd, chunkLen} — the placeholder chunk that
        // replaces original[openStart, closeEnd) is "\n\nPH\n\n"
        // (chunkLen = 4 + placeholder length).
        List<int[]> regions = new ArrayList<>();
        Matcher m = Pattern.compile("(?is)<details\\b[^>]*>").matcher(markdown);
        while (m.find()) {
            int openStart = m.start();
            int openEnd = m.end();
            if (!detailsAtLineStart(markdown, openStart) || inFencedCode(markdown, openStart)) {
                continue; // inline / code context: the sanitizer handles it
            }
            int[] close = findMatchingDetailsClose(markdown, openEnd);
            if (close == null) {
                continue; // unterminated: fall back to the sanitizer
            }
            int closeStart = close[0];
            int closeEnd = close[1];
            String title = "";
            int bodyStart = openEnd;
            String inner = markdown.substring(openEnd, closeStart);
            if (inner.strip().toLowerCase(Locale.ROOT).startsWith("<summary")) {
                Matcher sm = Pattern.compile("(?is)<summary\\b[^>]*>(.*?)</summary>")
                        .matcher(inner);
                if (sm.find()) {
                    title = collapseWhitespace(stripTagsHtml(unescapeBasicEntities(sm.group(1))));
                    bodyStart = openEnd + sm.end();
                }
            }
            String rawBody = markdown.substring(bodyStart, closeStart);
            String body = rawBody.strip();
            String ph = "@@gitsync-details-" + (n++) + "@@";
            // 0-based line of the body's FIRST CONTENT character in THIS
            // document — the base the lazy conversion adds to body-relative
            // AST lines. (Not bodyStart: the body may begin several lines
            // after the <summary> tag.)
            int bodyBase = countLinesBefore(markdown,
                    bodyStart + (rawBody.length() - rawBody.stripLeading().length()));
            details.put(ph, new DetailsEntry(title, body, bodyBase));
            out.append(markdown, pos, openStart);
            out.append("\n\n").append(ph).append("\n\n");
            regions.add(new int[]{openStart, closeEnd, 4 + ph.length()});
            pos = closeEnd;
            m.region(closeEnd, markdown.length());
        }
        out.append(markdown, pos, markdown.length());
        if (regions.isEmpty()) {
            return new DetailsResult(markdown, null);
        }
        return new DetailsResult(out.toString(),
                buildHoistLineMap(markdown, out.toString(), regions));
    }

    /** Number of {@code \n} in {@code s[0, idx)} — the 0-based line index of {@code idx}. */
    private static int countLinesBefore(String s, int idx) {
        int c = 0;
        for (int i = 0; i < idx; i++) {
            if (s.charAt(i) == '\n') {
                c++;
            }
        }
        return c;
    }

    /**
     * Build the hoisted→original 0-based line map EXACTLY, via the
     * character correspondence between the two strings: the hoisted source
     * is the original with each region {@code [openStart, closeEnd)}
     * replaced by its placeholder chunk, so every hoisted character has a
     * well-defined original character (chunk characters map to the region's
     * opener). Each hoisted line is mapped through the original character
     * at its start.
     *
     * @param regions {@code {openStart, closeEnd, chunkLen}} per hoisted
     *                region, in order
     */
    private static int[] buildHoistLineMap(String original, String hoisted,
                                           List<int[]> regions) {
        int hoistLines = countLinesBefore(hoisted, hoisted.length()) + 1;
        int[] origOf = new int[hoisted.length()];
        int h = 0;
        int o = 0;
        for (int[] r : regions) {
            while (h < origOf.length && o < r[0]) {
                origOf[h++] = o++;
            }
            for (int k = 0; k < r[2] && h < origOf.length; k++) {
                origOf[h++] = r[0]; // placeholder chunk — the opener's line
            }
            o = r[1];
        }
        while (h < origOf.length) {
            origOf[h++] = Math.min(o++, original.length());
        }
        int[] map = new int[hoistLines];
        int lineStart = 0;
        for (int L = 0; L < hoistLines; L++) {
            int pos = Math.min(lineStart, Math.max(0, hoisted.length() - 1));
            int origPos = pos >= 0 && pos < origOf.length ? origOf[pos] : 0;
            map[L] = countLinesBefore(original, origPos);
            int nl = hoisted.indexOf('\n', lineStart);
            if (nl < 0) {
                break; // last line mapped
            }
            lineStart = nl + 1;
        }
        return map;
    }

    /** The opener must begin a line with at most three spaces of indentation. */
    private static boolean detailsAtLineStart(String s, int idx) {
        int lineStart = s.lastIndexOf('\n', idx - 1) + 1;
        int i = lineStart;
        while (i < idx && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return i == idx && (idx - lineStart) <= 3;
    }

    /** Whether the line containing {@code idx} lies inside a fenced code block. */
    private static boolean inFencedCode(String s, int idx) {
        int targetLineStart = s.lastIndexOf('\n', idx - 1) + 1;
        char fenceChar = 0;
        int fenceLen = 0;
        boolean inFence = false;
        int ls = 0;
        while (ls < targetLineStart) {
            int le = s.indexOf('\n', ls);
            int lineEnd = le < 0 ? s.length() : le;
            int content = ls;
            while (content < ls + 3 && content < lineEnd && s.charAt(content) == ' ') {
                content++;
            }
            String t = s.substring(content, lineEnd);
            if (!t.isEmpty() && (t.charAt(0) == '`' || t.charAt(0) == '~')) {
                int run = 0;
                while (run < t.length() && t.charAt(run) == t.charAt(0)) {
                    run++;
                }
                boolean restWhitespace = t.substring(run).trim().isEmpty();
                if (!inFence) {
                    if (run >= 3 && restWhitespace) {
                        inFence = true;
                        fenceChar = t.charAt(0);
                        fenceLen = run;
                    }
                } else if (t.charAt(0) == fenceChar && run >= fenceLen && restWhitespace) {
                    inFence = false;
                }
            }
            if (le < 0) {
                break;
            }
            ls = le + 1;
        }
        return inFence;
    }

    /**
     * Depth-aware search for the {@code </details>} matching the opener that
     * ends at {@code from}. @return {@code [closeStart, closeEnd)} or null.
     */
    private static int[] findMatchingDetailsClose(String s, int from) {
        int depth = 1;
        int i = from;
        while (i < s.length()) {
            int nextOpen = indexOfTag(s, "<details", i);
            int nextClose = indexOfTag(s, "</details", i);
            if (nextClose < 0) {
                return null;
            }
            if (nextOpen >= 0 && nextOpen < nextClose) {
                depth++;
                i = nextOpen + "<details".length();
            } else {
                depth--;
                if (depth == 0) {
                    int gt = s.indexOf('>', nextClose + "</details".length());
                    if (gt < 0) {
                        return null;
                    }
                    return new int[]{nextClose, gt + 1};
                }
                i = nextClose + "</details".length();
            }
        }
        return null;
    }

    /** Case-insensitive {@code indexOf} of a tag prefix at a word boundary. */
    private static int indexOfTag(String s, String prefix, int from) {
        int i = from;
        while (i <= s.length() - prefix.length()) {
            int found = s.indexOf(prefix.charAt(0), i);
            if (found < 0 || found + prefix.length() > s.length()) {
                return -1;
            }
            boolean match = true;
            for (int k = 0; k < prefix.length(); k++) {
                if (Character.toLowerCase(s.charAt(found + k)) != prefix.charAt(k)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                int after = found + prefix.length();
                if (after >= s.length() || Character.isWhitespace(s.charAt(after))
                        || s.charAt(after) == '>') {
                    return found;
                }
            }
            i = found + 1;
        }
        return -1;
    }

    /** The Confluence expand macro wrapping the converted block content. */
    private static String expandMacroXml(String title, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("<ac:structured-macro ac:name=\"expand\" ac:schema-version=\"1\">");
        if (title != null && !title.isEmpty()) {
            sb.append("<ac:parameter ac:name=\"title\">")
              .append(xmlEscape(title))
              .append("</ac:parameter>");
        }
        sb.append("<ac:rich-text-body>")
          .append(body == null ? "" : body)
          .append("</ac:rich-text-body>");
        sb.append("</ac:structured-macro>");
        return sb.toString();
    }

    /** FR12 safe inline tags (no attributes) — passed through verbatim. */
    private static String normalizeSafeInlineTag(String raw) {
        String t = raw.toLowerCase(Locale.ROOT);
        switch (t) {
            case "<br>":
            case "<br/>":
            case "<br />":
                return "<br/>";
            case "<sub>":
                return "<sub>";
            case "</sub>":
                return "</sub>";
            case "<sup>":
                return "<sup>";
            case "</sup>":
                return "</sup>";
            default:
                return null;
        }
    }

    /**
     * Keep {@code <sub>}/{@code <sup>} well-formed within one inline context:
     * a stray closing tag is dropped, an unterminated opener is closed at the
     * end of the context. Storage is parsed as XHTML, so an unbalanced pair
     * could break the whole page body. Openers are matched with their
     * attribute form too (footnote references render as
     * {@code <sup id="fn-N">}), so those are counted, not orphaned.
     */
    private static void balanceInlineSubSup(StringBuilder sb, int start) {
        String s = sb.substring(start);
        String fixed = fixTagBalance(s, "<sup(?=[\\s>])", "</sup\\s*>", "</sup>");
        fixed = fixTagBalance(fixed, "<sub(?=[\\s>])", "</sub\\s*>", "</sub>");
        if (!fixed.equals(s)) {
            sb.replace(start, sb.length(), fixed);
        }
    }

    private static String fixTagBalance(String s, String openRegex, String closeRegex,
                                        String closeTag) {
        int opens = countMatches(s, openRegex);
        int closes = countMatches(s, closeRegex);
        if (opens == closes) {
            return s;
        }
        if (opens > closes) {
            StringBuilder sb = new StringBuilder(s);
            for (int i = closes; i < opens; i++) {
                sb.append(closeTag);
            }
            return sb.toString();
        }
        // More closers than openers: drop the leading stray closers.
        StringBuilder sb = new StringBuilder();
        Matcher cm = Pattern.compile(closeRegex).matcher(s);
        int toDrop = closes - opens;
        int idx = 0;
        while (toDrop > 0 && cm.find()) {
            sb.append(s, idx, cm.start());
            idx = cm.end();
            toDrop--;
        }
        sb.append(s, idx, s.length());
        return sb.toString();
    }

    private static int countMatches(String s, String regex) {
        int count = 0;
        Matcher m = Pattern.compile(regex).matcher(s);
        while (m.find()) {
            count++;
        }
        return count;
    }

    /** Case-insensitive "contains" without allocating a lowercased copy. */
    private static boolean containsIgnoreCase(String s, String needle) {
        int i = 0;
        while (i <= s.length() - needle.length()) {
            int found = s.indexOf(needle.charAt(0), i);
            if (found < 0 || found + needle.length() > s.length()) {
                return false;
            }
            boolean match = true;
            for (int k = 0; k < needle.length(); k++) {
                if (Character.toLowerCase(s.charAt(found + k)) != needle.charAt(k)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
            i = found + 1;
        }
        return false;
    }

    /** Remove all HTML tags, keeping the text. */
    private static String stripTagsHtml(String s) {
        return s == null ? "" : s.replaceAll("(?s)<[^>]+>", "");
    }

    /** Unescape the basic HTML entities (inverse of {@link #xmlEscape}). */
    private static String unescapeBasicEntities(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
    }

    /** Collapse any whitespace run into a single space and trim. */
    private static String collapseWhitespace(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Recurse into a node's children rendering them as <em>inline</em> markup
     * (used for headings and paragraphs, whose children are inline nodes).
     */
    private static void renderInlines(Node node, StringBuilder out, String krokiUrl,
                                      List<FooterEntry> footers) {
        // Children of paragraphs/headings/cells are inline nodes.
        int start = out.length();
        renderInlineChildren(node, out, krokiUrl, footers);
        // An inline <sub>/<sup> pair that the source left unbalanced would
        // corrupt the XHTML the page body is parsed as — rebalance it here.
        balanceInlineSubSup(out, start);
    }

    /** Collect inline text of a subtree as a list of fragments (for footers). */
    private static void renderChildrenTo(Node node, List<String> parts, String krokiUrl,
                                         List<FooterEntry> footers) {
        StringBuilder sb = new StringBuilder();
        for (Node child : node.getChildren()) {
            renderInline(child, sb, krokiUrl, footers);
        }
        parts.add(sb.toString());
    }

    private static void appendCodeBlock(StringBuilder out, String code, String language) {
        // Confluence storage: a code block is a <pre> containing a <code>, with
        // newlines as <br/> so the page preserves line structure.
        out.append("<pre><code");
        if (language != null && !language.isEmpty()) {
            out.append(" class=\"language-").append(xmlEscape(language)).append('"');
        }
        out.append('>');
        String[] lines = code.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append("<br/>");
            }
            out.append(xmlEscape(lines[i]));
        }
        out.append("</code></pre>");
    }

    private static String footerSection(List<FooterEntry> footers) {
        StringBuilder sb = new StringBuilder();
        sb.append("<hr/>");
        sb.append("<div style=\"font-size:0.85em;border-top:1px solid #ccc;margin-top:1em;padding-top:0.5em\">");
        for (FooterEntry f : footers) {
            // Each footnote is a <p> in the rendered DOM — keep the line
            // sink in parity (the DOM text is label + plain text).
            recordLine(f.line, f.line, f.label + " " + stripTagsHtml(f.text));
            sb.append("<p style=\"font-size:0.8em\"><sup>")
              .append(xmlEscape(f.label)).append("</sup> ")
              .append(f.text)
              .append("</p>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private static String firstWord(String s) {
        if (s == null) {
            return "";
        }
        int i = 0;
        while (i < s.length() && !Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return s.substring(0, i);
    }

    /** Extract the raw source text of a code block, stripping its fence lines. */
    private static boolean isFenceLine(String line) {
        String t = line.trim();
        return t.startsWith("```") || t.startsWith("~~~");
    }

    private static String codeBlockText(Node block) {
        BasedSequence chars = block.getChars();
        if (chars == null) {
            return "";
        }
        String src = chars.toString();
        // Split into lines and drop the leading/fencing lines. For fenced blocks
        // the first line is the opening fence (```lang) and the last non-blank
        // line is the closing fence. For indented blocks there is no fence.
        String[] lines = src.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        // Drop ONLY the outer fences: the first fence line (opening) and the
        // last fence line (closing). Fence-like lines in between are content
        // (e.g. a 4-backtick block documenting markdown with inner fences).
        int first = 0;
        while (first < lines.length && lines[first].trim().isEmpty()) {
            first++;
        }
        int last = lines.length - 1;
        while (last > first && lines[last].trim().isEmpty()) {
            last--;
        }
        int from = (first <= last && isFenceLine(lines[first])) ? first + 1 : first;
        int to = (last >= from && isFenceLine(lines[last])) ? last : last + 1;
        List<String> body = new ArrayList<>();
        for (int i = from; i < to; i++) {
            body.add(lines[i]);
        }
        // Strip a single leading blank line (from the opening fence's newline).
        int start = 0;
        int end = body.size();
        while (start < end && body.get(start).isEmpty()) {
            start++;
        }
        while (end > start && body.get(end - 1).isEmpty()) {
            end--;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (i > start) {
                sb.append('\n');
            }
            sb.append(body.get(i));
        }
        return sb.toString();
    }

    /** Concatenated plain text of the node's {@code Text} descendants. */
    private static String plainTextOf(Node node) {
        StringBuilder sb = new StringBuilder();
        for (Node c : node.getDescendants()) {
            if (c instanceof Text) {
                sb.append(c.getChars().toString());
            }
        }
        return sb.toString();
    }

    /**
     * Render a self-contained table of contents: a nested list of anchor
     * links to the document headings (level of the first heading = root).
     *
     * @param markerLine 1-based source line of the {@code [TOC]} marker (or
     *                   the TocBlock) — the TOC entries have no separate
     *                   source lines of their own, so they are all attributed
     *                   to it in the line sink.
     */
    private static void renderToc(StringBuilder out, int markerLine) {
        List<HeadingInfo> heads = DOC_HEADINGS.get();
        if (heads.isEmpty()) {
            return;
        }
        int base = heads.get(0).level;
        out.append("<div class=\"gs-toc\"><ol>");
        int depth = 0;
        boolean[] liOpen = new boolean[8];
        for (HeadingInfo h : heads) {
            int d = Math.max(0, h.level - base);
            if (d > depth + 1) {
                d = depth + 1; // never skip a list level
            }
            while (d < depth) {
                out.append("</li></ol>");
                liOpen[depth] = false;
                depth--;
            }
            while (d > depth) {
                out.append("<ol>");
                depth++;
            }
            if (liOpen[d]) {
                out.append("</li>");
            }
            liOpen[d] = true;
            recordLine(markerLine, markerLine, h.text);
            out.append("<li><a href=\"#").append(h.slug).append("\">")
               .append(xmlEscape(h.text)).append("</a>");
        }
        for (int i = depth; i >= 0; i--) {
            if (liOpen[i]) {
                out.append("</li>");
            }
            out.append("</ol>");
        }
        out.append("</div>");
    }

    /** Heading entry for the self-rendered TOC: level, anchor slug, plain text. */
    private static final class HeadingInfo {
        final int level;
        final String slug;
        final String text;

        HeadingInfo(int level, String slug, String text) {
            this.level = level;
            this.slug = slug;
            this.text = text;
        }
    }

    private static final ThreadLocal<java.util.Map<Node, String>> PRE_SLUGS =
            ThreadLocal.withInitial(java.util.HashMap::new);

    private static final ThreadLocal<List<HeadingInfo>> DOC_HEADINGS =
            ThreadLocal.withInitial(ArrayList::new);

    private static boolean isTocMarker(String raw) {
        if (raw == null) {
            return false;
        }
        String r = raw.trim();
        return r.equalsIgnoreCase("[TOC]")
                || r.equals("{TOC}")
                || r.equals("<div class=\"toc\"/>")
                || r.startsWith("<div class=\"toc\">");
    }

    /**
     * Build a stable, URL-safe anchor id from a heading. Repeated headings get
     * numeric suffixes ({@code -1}, {@code -2}, ...) to stay unique on the page.
     */
    private static final ThreadLocal<java.util.Map<String, Integer>> HEADING_COUNTS =
            ThreadLocal.withInitial(java.util.HashMap::new);

    private static String slugId(Heading heading) {
        java.util.Map<Node, String> pre = PRE_SLUGS.get();
        String known = pre.get(heading);
        if (known != null) {
            return known;
        }
        StringBuilder title = new StringBuilder();
        for (Node child : heading.getChildren()) {
            if (child instanceof Text) {
                title.append(child.getChars().toString());
            }
        }
        String slug = slugify(title.toString());
        java.util.Map<String, Integer> counts = HEADING_COUNTS.get();
        int n = counts.getOrDefault(slug, 0);
        if (n == 0) {
            counts.put(slug, 1);
            return slug;
        }
        counts.put(slug, n + 1);
        return slug + "-" + n;
    }

    private static String slugify(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (char c : lower.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            } else if (Character.isWhitespace(c)) {
                sb.append('-');
            }
        }
        while (sb.length() > 0 && sb.charAt(0) == '-') {
            sb.deleteCharAt(0);
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
            sb.setLength(sb.length() - 1);
        }
        if (sb.length() == 0) {
            return "section";
        }
        return sb.toString();
    }

    private static String xmlEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /** A deferred footnote definition collected during the walk. */
    private static final class FooterEntry {
        final String label;
        final String text;
        /** 1-based source line of the footnote definition block. */
        final int line;

        FooterEntry(String label, String text) {
            this(label, text, -1);
        }

        FooterEntry(String label, String text, int line) {
            this.label = label;
            this.text = text;
            this.line = line;
        }
    }
}
