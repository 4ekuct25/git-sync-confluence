package com.gitlab.sync.confluence.security;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Entities;
import org.jsoup.safety.Safelist;

/**
 * HTML/Storage-Format sanitization for content rendered from GitLab markdown.
 *
 * <p>All content that reaches a Confluence page from an external GitLab
 * repository is untrusted. This utility guarantees that:</p>
 * <ul>
 *   <li>executable constructs ({@code <script>}, inline event handlers,
 *       {@code javascript:} URLs) are stripped;</li>
 *   <li>embedding/document vectors ({@code <iframe>}, {@code <object>},
 *       {@code <embed>}, {@code <form>}) are removed;</li>
 *   <li>documented markdown output (links, images, tables, headings, lists,
 *       inline code, etc.) is preserved.</li>
 * </ul>
 */
public final class SanitizeUtil {

    /**
     * Custom safelist built on top of {@link Safelist#basicWithImages()}
     * which already permits (verified against jsoup 1.17.2): a[href], b,
     * blockquote, br, code, em, i, img[src,alt,title,longdesc,width,height],
     * li, ol, p, pre, span, strong, sub, sup, u, ul.
     *
     * On top of that we explicitly add the table tags required for markdown
     * tables (and for raw-HTML tables, with colspan/rowspan) and the heading
     * tags required for markdown headings.
     *
     * <p>Tags that are NOT on the safelist (details, summary, kbd, mark,
     * div, ...) survive {@link #sanitizeHtml} only as their visible text:
     * the markup is dropped, the content is kept — the desired FR12
     * behaviour for neutral tags outside the supported subset.
     */
    private static final Safelist HTML_SAFELIST = buildHtmlSafelist();

    /** Tags that must never survive sanitization (defense in depth). */
    private static final String[] STRIPPED_TAGS = {
            "script", "iframe", "object", "embed", "form", "applet",
            "style", "noscript", "base", "meta", "link"
    };

    /**
     * Placeholder scheme that keeps scheme-less (relative) URLs alive through
     * {@link Jsoup#clean}. Jsoup drops every href/src that carries no
     * protocol (verified against jsoup 1.17.2: {@code ../../assets/x.svg}
     * and {@code #anchor} are stripped even without an explicit protocol
     * allowlist and even with {@code preserveRelativeLinks(true)}), but
     * exactly those values are what GitLabLinkResolver rewrites into
     * resolvable attachment-proxy / blob URLs AFTER sanitization. During
     * clean() the value is masked with a placeholder on the allowed https
     * protocol and restored (escaped) afterwards. Values that carry a
     * protocol — including protocol-relative {@code //host/x} — are left
     * to Jsoup.
     */
    private static final String REL_URL_MARKER = "https://gitsync-relative.invalid/rel";
    private static final Pattern URL_ATTR_PATTERN =
            Pattern.compile("(?i)\\b(href|src)\\s*=\\s*(\"[^\"]*\"|'[^']*')");
    private static final Pattern URL_SCHEME_PATTERN =
            Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");

    private SanitizeUtil() {}

    private static Safelist buildHtmlSafelist() {
        Safelist safelist = Safelist.basicWithImages();

        // Tables (markdown tables)
        safelist.addTags("table", "thead", "tbody", "tfoot", "tr", "td", "th");

        // Headings (markdown headings)
        safelist.addTags("h1", "h2", "h3", "h4", "h5", "h6");

        // Block-level misc
        safelist.addTags("hr", "figure", "figcaption");

        // Allow align/class on table cells for basic styling, but no events.
        safelist.addAttributes("table", "align", "border", "cellpadding", "cellspacing");
        safelist.addAttributes("td", "align", "colspan", "rowspan", "width");
        safelist.addAttributes("th", "align", "colspan", "rowspan", "width");
        safelist.addAttributes("a", "rel");

        // class on table cells: presentational theme hooks only
        // (confluenceTable/confluenceTh/confluenceTd, highlight-*) — a class
        // value cannot carry event handlers, so it is not an execution vector.
        // Without it, raw-HTML tables in docs lose the theme's border and
        // header shading that markdown tables (converted) keep.
        safelist.addAttributes("table", "class");
        safelist.addAttributes("td", "class");
        safelist.addAttributes("th", "class");

        // Explicitly ban javascript: (and friends) in href/src.
        // Jsoup 1.14+ blocks these by default in clean(); the safelist
        // additionally restricts attribute protocols via addProtocols.
        safelist.addProtocols("a", "href", "http", "https", "mailto");
        safelist.addProtocols("img", "src", "http", "https", "data");

        return safelist;
    }

    /**
     * Sanitize untrusted HTML (e.g. rendered markdown) for embedding into a
     * Confluence page.
     *
     * <p>Strips {@code <script>}, {@code on*} event attributes,
     * {@code javascript:} URLs, {@code <iframe>}, {@code <object>},
     * {@code <embed>}, {@code <form>} and similar, while preserving the
     * documented set of markup tags used by the markdown renderer.</p>
     *
     * @param html the untrusted HTML; may be {@code null} or empty
     * @return sanitized HTML, never {@code null}
     */
    public static String sanitizeHtml(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        // HTML comments carry no rendered content, and Jsoup's clean() does
        // NOT drop them: a comment becomes visible escaped text wrapped in
        // <p> ("&lt;!-- ... --&gt;"). JiHu docs start with a docs-meta
        // comment header — strip comments (well-formed and unterminated)
        // before sanitizing.
        String noComments = html.replaceAll("(?s)<!--.*?-->", "");
        noComments = noComments.replaceAll("(?s)<!--.*\\z", "");
        // Jsoup's clean() with a safelist removes disallowed tags/attributes,
        // rewrites URLs to only permitted protocols and drops on* handlers.
        // Scheme-less (relative) href/src would be dropped by that URL check
        // and never reach the link resolver — mask them for the duration of
        // clean() (see REL_URL_MARKER).
        List<String> relativeValues = new ArrayList<>();
        String masked = URL_ATTR_PATTERN.matcher(noComments).replaceAll(match -> {
            String quoted = match.group(2);
            String value = quoted.substring(1, quoted.length() - 1);
            // find(), NOT matches(): the pattern is ^-anchored, so find()
            // tests "value starts with a scheme" while matches() would
            // require the whole value to be just "scheme:" and would mask
            // payloads like javascript:alert(1) as if they were relative
            // URLs (XSS via mask/restore).
            if (URL_SCHEME_PATTERN.matcher(value).find() || value.startsWith("//")) {
                return Matcher.quoteReplacement(match.group());
            }
            String replacement = match.group(1) + "=\""
                    + REL_URL_MARKER + relativeValues.size() + "\"";
            relativeValues.add(Entities.unescape(value));
            return Matcher.quoteReplacement(replacement);
        });
        String cleaned = Jsoup.clean(masked, HTML_SAFELIST);
        if (!relativeValues.isEmpty()) {
            cleaned = Pattern.compile(Pattern.quote(REL_URL_MARKER) + "(\\d+)")
                    .matcher(cleaned)
                    .replaceAll(match -> {
                        int idx = Integer.parseInt(match.group(1));
                        return idx < relativeValues.size()
                                ? Matcher.quoteReplacement(
                                        escapeAttrValue(relativeValues.get(idx)))
                                : Matcher.quoteReplacement(match.group());
                    });
        }

        // Defense in depth: explicitly remove any residual dangerous tags
        // (clean() should already have, but a future safelist tweak must not
        // silently let these through).
        for (String tag : STRIPPED_TAGS) {
            cleaned = cleaned.replaceAll(
                    "(?is)<" + tag + "\\b[^>]*>.*?</" + tag + ">", "");
            cleaned = cleaned.replaceAll("(?is)<" + tag + "\\b[^>]*/?>", "");
        }

        return cleaned;
    }

    /**
     * Escape a restored URL value for attribute position. The value is
     * entity-decoded at capture time (see {@link Entities#unescape}), so one
     * escaping pass is correct; a quote character cannot occur (the capture
     * regex cannot match a value containing its own delimiter).
     *
     * <p>Note: a path with special characters (e.g. {@code &}) ends up
     * entity-encoded in the final HTML. GitLabLinkResolver is regex-based and
     * not entity-aware, so it may fail to resolve such a link — it then stays
     * relative (broken in the browser) rather than becoming an injection.
     */
    private static String escapeAttrValue(String value) {
        return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;");
    }

    /**
     * Sanitize Confluence Storage Format XML.
     *
     * <p>Storage Format is XHTML-flavoured XML, so Jsoup's HTML cleaner is not
     * directly applicable. This method:</p>
     * <ol>
     *   <li>removes any {@code <script>} element (with its body) entirely;</li>
     *   <li>removes Confluence macros that allow raw HTML execution, most
           notably the structured macro {@code ac:name="html"} (the raw
       "HTML" macro), and its macro-params/plain-body blocks in full;</li>
     *   <li>strips inline {@code onclick}-style event attributes and
       {@code javascript:} hrefs as an extra defense-in-depth layer.</li>
     * </ol>
     *
     * @param storageXml storage-format document or fragment; may be null
     * @return sanitized storage-format XML, never null
     */
    public static String sanitizeForStorage(String storageXml) {
        if (storageXml == null || storageXml.isEmpty()) {
            return "";
        }

        String out = storageXml;

        // 1. Remove any <script>...</script> element entirely.
        out = out.replaceAll("(?is)<script\\b[^>]*>.*?</script>", "");
        out = out.replaceAll("(?is)<script\\b[^>]*/?>", "");

        // 2. Remove structured-macro blocks whose ac:name is "html"
        //    (the raw-HTML macro). Pattern handles nested ac:parameters and
        //    ac:plain-body content, terminated by the matching close tag.
        out = out.replaceAll(
                "(?is)<ac:structured-macro\\b(?=[^>]*ac:name=\"html\")[^>]*>.*?</ac:structured-macro>",
                "");
        // Also match the self-closing form if any.
        out = out.replaceAll(
                "(?is)<ac:structured-macro\\b(?=[^>]*ac:name=\"html\")[^>]*/>",
                "");

        // 3. Remove any inline event-handler attributes (onclick, onerror, ...),
        //    both quote styles and optional whitespace around '='.
        out = out.replaceAll("(?is)\\son\\w+\\s*=\\s*\"[^\"]*\"", "");
        out = out.replaceAll("(?is)\\son\\w+\\s*=\\s*'[^']*'", "");

        // 4. Neutralize javascript: URLs inside href/src attributes.
        out = out.replaceAll("(?is)href\\s*=\\s*\"\\s*javascript:[^\"]*\"",
                "href=\"#\"");
        out = out.replaceAll("(?is)src\\s*=\\s*\"\\s*javascript:[^\"]*\"",
                "src=\"\"");
        out = out.replaceAll("(?is)href\\s*=\\s*'\\s*javascript:[^']*'",
                "href=\"#\"");
        out = out.replaceAll("(?is)src\\s*=\\s*'\\s*javascript:[^']*'",
                "src=\"\"");

        // 5. Strip iframe/object/embed/form elements in storage format too,
        //    should any have leaked in.
        for (String tag : new String[]{"iframe", "object", "embed", "form"}) {
            out = out.replaceAll("(?is)<" + tag + "\\b[^>]*>.*?</" + tag + ">", "");
            out = out.replaceAll("(?is)<" + tag + "\\b[^>]*/?>", "");
        }

        return out;
    }
}
