package com.example;

import com.aspose.words.*;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import com.aspose.words.Body;
import com.aspose.words.CssStyleSheetType;
import com.aspose.words.Document;
import com.aspose.words.ExportHeadersFootersMode;
import com.aspose.words.HeaderFooter;
import com.aspose.words.HeaderFooterCollection;
import com.aspose.words.HeaderFooterType;
import com.aspose.words.HtmlSaveOptions;
import com.aspose.words.ImportFormatMode;
import com.aspose.words.Node;
import com.aspose.words.NodeCollection;
import com.aspose.words.NodeImporter;
import com.aspose.words.NodeType;
import com.aspose.words.SaveFormat;
import com.aspose.words.Section;

import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class WordToHtmlConverter {
    private static final String RESULT_DIR = "result";
    private static final String IMG_DIR = "images";
    private static final String HEADER_FILE = "header.html";
    private static final String CONTENT_FILE = "content.html";
    private static final String FOOTER_FILE = "footer.html";
    private static final String CSS_FILE = "word.css";
    private static final String MANIFEST_FILE = "manifest.json";

    private static final Pattern STYLE_TAG = Pattern.compile("(?is)<style[^>]*>(.*?)</style>");
    private static final Pattern BODY_TAG = Pattern.compile("(?is)<body[^>]*>(.*?)</body>");
    private static final Pattern LINK_TAG = Pattern.compile("(?is)<link[^>]*>");
    private static final Pattern CSS_RULE_HEAD = Pattern.compile("(?m)^(\\s*)([^\\n\\{\\}]+?)\\s*\\{");

    public static void main(String[] args) {

        try {
            Config cfg = Config.parse(args);
            if (cfg.help) {
                usage();
                return;
            }
            Summary summary = run(cfg);
            System.out.println();
            System.out.println("==== Summary ====");
            System.out.println("总目录数: " + summary.total);
            System.out.println("成功: " + summary.ok);
            System.out.println("失败: " + summary.fail);
            System.out.println("跳过(无文档): " + summary.skipNoDoc);
            System.out.println("跳过(overwrite=false): " + summary.skipOverwrite);
            System.out.println("warnings: " + summary.warn);
            if (summary.fail > 0) {
                System.exit(1);
            }
        } catch (IllegalArgumentException e) {
            System.err.println("参数错误: " + e.getMessage());
            usage();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("执行失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static Summary run(Config cfg) throws Exception {
        log(cfg, "Root: " + cfg.root);
        List<Path> dirs = scanDirs(cfg);
        log(cfg, "发现子目录数量: " + dirs.size());

        Summary s = new Summary();
        for (Path dir : dirs) {
            s.total++;
            String rel = rel(cfg.root, dir);
            log(cfg, "处理目录: " + rel);
            Result r = processDir(cfg, dir);
            s.warn += r.warnings.size();
            for (String w : r.warnings) {
                System.out.println("[WARN] [" + rel + "] " + w);
            }
            if (r.status == Status.OK) {
                s.ok++;
            } else if (r.status == Status.SKIP_NO_DOC) {
                s.skipNoDoc++;
            } else if (r.status == Status.SKIP_OVERWRITE) {
                s.skipOverwrite++;
            } else {
                s.fail++;
                System.err.println("[ERROR] " + rel + " -> " + r.err);
                if (cfg.debug && r.ex != null) {
                    r.ex.printStackTrace(System.err);
                }
                if (cfg.failFast) {
                    break;
                }
            }
        }
        return s;
    }

    private static List<Path> scanDirs(Config cfg) throws IOException {
        Comparator<Path> sort = Comparator.comparing(p -> p.toString().toLowerCase(Locale.ROOT));
        if (cfg.recursive) {
            try (Stream<Path> st = Files.walk(cfg.root)) {
                return st.filter(Files::isDirectory)
                        .filter(p -> !p.equals(cfg.root))
                        .filter(p -> !containsIgnored(cfg.root, p))
                        .sorted(sort)
                        .collect(Collectors.toList());
            }
        }
        try (Stream<Path> st = Files.list(cfg.root)) {
            return st.filter(Files::isDirectory)
                    .filter(p -> !isIgnoredName(p.getFileName().toString()))
                    .sorted(sort)
                    .collect(Collectors.toList());
        }
    }

    private static Result processDir(Config cfg, Path dir) {
        List<String> warns = new ArrayList<>();
        Path chosen = null;
        try {
            List<Path> docs;
            try (Stream<Path> st = Files.list(dir)) {
                docs = st.filter(Files::isRegularFile)
                        .filter(p -> matchesAny(p.getFileName(), cfg.docMatchers))
                        .collect(Collectors.toList());
            }
            if (docs.isEmpty()) {
                warns.add("未找到匹配的 Word 文件。");
                return Result.of(Status.SKIP_NO_DOC, warns);
            }
            chosen = chooseDoc(docs, cfg.prefer);
            if (docs.size() > 1) {
                warns.add("发现多个 Word 文件，已按规则选中: " + chosen.getFileName());
            }

            Path out = dir.resolve(RESULT_DIR);
            if (Files.exists(out) && !cfg.overwrite) {
                warns.add("result 已存在且 overwrite=false，跳过。");
                return Result.of(Status.SKIP_OVERWRITE, warns);
            }
            recreate(out);
            Path images = out.resolve(IMG_DIR);
            Files.createDirectories(images);

            Exported exported = convertOne(chosen, images, cfg, warns);
            write(out.resolve(HEADER_FILE), exported.header);
            write(out.resolve(CONTENT_FILE), exported.content);
            write(out.resolve(FOOTER_FILE), exported.footer);
            write(out.resolve(CSS_FILE), exported.css);
            write(out.resolve(MANIFEST_FILE), manifest(chosen, warns, cfg));
            return Result.of(Status.OK, warns);
        } catch (Exception e) {
            String source = chosen == null ? "" : " (source=" + chosen.getFileName() + ")";
            return Result.fail("处理异常" + source + ": " + e.getMessage(), e, warns);
        }
    }

    private static Exported convertOne(Path sourcePath, Path imagesDir, Config cfg, List<String> warns) throws Exception {
        Document src = new Document(sourcePath.toString());
        if (cfg.updateFields) {
            try {
                src.updateFields();
            } catch (Exception e) {
                warns.add("updateFields 失败: " + compact(e.getMessage()));
            }
        }
        if (cfg.acceptRevisions) {
            try {
                src.acceptAllRevisions();
            } catch (Exception e) {
                warns.add("acceptAllRevisions 失败: " + compact(e.getMessage()));
            }
        }

        Document hDoc = blankDoc();
        Document cDoc = blankDoc();
        Document fDoc = blankDoc();

        Section first = src.getFirstSection();
        if (first == null) {
            throw new IllegalStateException("文档不存在 Section。");
        }
        HeaderFooter h = pick(first, true);
        HeaderFooter f = pick(first, false);
        if (h == null) {
            warns.add("未找到可用 header，输出为空容器。");
        } else {
            appendHeaderFooter(src, h, hDoc);
        }
        if (f == null) {
            warns.add("未找到可用 footer，输出为空容器。");
        } else {
            appendHeaderFooter(src, f, fDoc);
        }
        appendContent(src, cDoc);

        HtmlFrag hFrag = saveFrag(hDoc, imagesDir, "wh-");
        HtmlFrag cFrag = saveFrag(cDoc, imagesDir, "wc-");
        HtmlFrag fFrag = saveFrag(fDoc, imagesDir, "wf-");

        String css = mergeCss(hFrag.css, cFrag.css, fFrag.css);
        return new Exported(
                wrap("word-header", hFrag.body),
                wrap("word-content", cFrag.body),
                wrap("word-footer", fFrag.body),
                css
        );
    }

    private static Document blankDoc() throws Exception {
        Document d = new Document();
        Section s = d.getFirstSection();
        if (s == null) {
            s = new Section(d);
            d.appendChild(s);
            s.ensureMinimum();
        }
        s.getBody().removeAllChildren();
        return d;
    }

    private static HeaderFooter pick(Section first, boolean header) {
        HeaderFooterCollection c = first.getHeadersFooters();
        int firstType = header ? HeaderFooterType.HEADER_FIRST : HeaderFooterType.FOOTER_FIRST;
        int primaryType = header ? HeaderFooterType.HEADER_PRIMARY : HeaderFooterType.FOOTER_PRIMARY;
        HeaderFooter firstChoice = c.getByHeaderFooterType(firstType);
        if (hasContent(firstChoice)) {
            return firstChoice;
        }
        HeaderFooter primary = c.getByHeaderFooterType(primaryType);
        return hasContent(primary) ? primary : null;
    }

    private static boolean hasContent(HeaderFooter hf) {
        if (hf == null || !hf.hasChildNodes()) {
            return false;
        }
        String text = hf.getText();
        if (text != null) {
            String t = text.replace("\r", "").replace("\n", "").replace("\u0007", "").trim();
            if (!t.isEmpty()) {
                return true;
            }
        }
        NodeCollection nodes = hf.getChildNodes(NodeType.ANY, true);
        for (Object obj : nodes) {
            Node n = (Node) obj;
            int type = n.getNodeType();
            if (type == NodeType.TABLE || type == NodeType.SHAPE || type == NodeType.GROUP_SHAPE
                    || type == NodeType.STRUCTURED_DOCUMENT_TAG || type == NodeType.OFFICE_MATH) {
                return true;
            }
        }
        return false;
    }

    private static void appendHeaderFooter(Document src, HeaderFooter from, Document to) throws Exception {
        NodeImporter importer = new NodeImporter(src, to, ImportFormatMode.KEEP_SOURCE_FORMATTING);
        Body body = to.getFirstSection().getBody();
        for (Object obj : from.getChildNodes(NodeType.ANY, false)) {
            Node n = (Node) obj;
            int type = n.getNodeType();
            if (type == NodeType.PARAGRAPH || type == NodeType.TABLE) {
                body.appendChild(importer.importNode(n, true));
            }
        }
        if (!body.hasChildNodes()) {
            body.ensureMinimum();
        }
    }

    private static void appendContent(Document src, Document to) throws Exception {
        NodeImporter importer = new NodeImporter(src, to, ImportFormatMode.KEEP_SOURCE_FORMATTING);
        Body body = to.getFirstSection().getBody();
        for (Section sec : src.getSections()) {
            Body secBody = sec.getBody();
            if (secBody == null) {
                continue;
            }
            for (Object obj : secBody.getChildNodes(NodeType.ANY, false)) {
                Node n = (Node) obj;
                int type = n.getNodeType();
                if (type == NodeType.PARAGRAPH || type == NodeType.TABLE) {
                    body.appendChild(importer.importNode(n, true));
                }
            }
        }
        if (!body.hasChildNodes()) {
            body.ensureMinimum();
        }
    }

    private static HtmlFrag saveFrag(Document doc, Path imagesDir, String cssPrefix) throws Exception {
        HtmlSaveOptions opt = new HtmlSaveOptions(SaveFormat.HTML);
        opt.setEncoding(StandardCharsets.UTF_8);
        opt.setCssStyleSheetType(CssStyleSheetType.EMBEDDED);
        opt.setCssClassNamePrefix(cssPrefix);
        opt.setExportImagesAsBase64(false);
        opt.setExportFontResources(false);
        opt.setExportHeadersFootersMode(ExportHeadersFootersMode.NONE);
        opt.setExportPageMargins(false);
        opt.setExportPageSetup(false);
        opt.setExportRoundtripInformation(false);
        opt.setImagesFolder(imagesDir.toAbsolutePath().toString());
        opt.setImagesFolderAlias(IMG_DIR);
        opt.setPrettyFormat(true);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out, opt);
        String html = out.toString(StandardCharsets.UTF_8);
        String body = extractBody(html);
        List<String> cssBlocks = extractCss(html);
        return new HtmlFrag(body, cssBlocks);
    }

    private static String extractBody(String html) {
        Matcher m = BODY_TAG.matcher(html);
        String body = m.find() ? m.group(1) : html;
        body = STYLE_TAG.matcher(body).replaceAll("");
        body = LINK_TAG.matcher(body).replaceAll("");
        return body.trim();
    }

    private static List<String> extractCss(String html) {
        List<String> blocks = new ArrayList<>();
        Matcher m = STYLE_TAG.matcher(html);
        while (m.find()) {
            String css = m.group(1).trim();
            if (!css.isEmpty()) {
                blocks.add(css);
            }
        }
        return blocks;
    }

    private static String mergeCss(List<String> headerCss, List<String> contentCss, List<String> footerCss) {
        Set<String> scopedBlocks = new LinkedHashSet<>();
        appendScopedCss(scopedBlocks, headerCss, ".word-header");
        appendScopedCss(scopedBlocks, contentCss, ".word-content");
        appendScopedCss(scopedBlocks, footerCss, ".word-footer");

        StringBuilder sb = new StringBuilder("/* Generated by WordToHtmlConverter */\n");
        for (String block : scopedBlocks) {
            sb.append(block).append("\n\n");
        }
        return sb.toString();
    }

    private static void appendScopedCss(Set<String> allScoped, List<String> cssBlocks, String scopeClass) {
        if (cssBlocks == null) {
            return;
        }
        for (String css : cssBlocks) {
            String scoped = scopeCssBlock(css, scopeClass);
            if (!scoped.isBlank()) {
                allScoped.add(scoped);
            }
        }
    }

    private static String scopeCssBlock(String css, String scopeClass) {
        if (css == null || css.isBlank()) {
            return "";
        }

        Matcher matcher = CSS_RULE_HEAD.matcher(css);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String selectors = matcher.group(2).trim();
            if (selectors.startsWith("@")) {
                continue;
            }

            String scopedSelectors = scopeSelectorList(selectors, scopeClass);
            String replacement = matcher.group(1) + scopedSelectors + " {";
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString().trim();
    }

    private static String scopeSelectorList(String selectorList, String scopeClass) {
        return Arrays.stream(selectorList.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(selector -> scopeSingleSelector(selector, scopeClass))
                .collect(Collectors.joining(", "));
    }

    private static String scopeSingleSelector(String selector, String scopeClass) {
        String s = selector.trim();
        if (s.isEmpty() || s.startsWith("@")) {
            return s;
        }

        if (s.equals(scopeClass)
                || s.startsWith(scopeClass + " ")
                || s.startsWith(scopeClass + ":")
                || s.startsWith(scopeClass + ">")
                || s.startsWith(scopeClass + "+")
                || s.startsWith(scopeClass + "~")) {
            return s;
        }

        s = s.replaceFirst("^(html|body)\\b", Matcher.quoteReplacement(scopeClass));
        if (s.equals(scopeClass)
                || s.startsWith(scopeClass + " ")
                || s.startsWith(scopeClass + ":")
                || s.startsWith(scopeClass + ">")
                || s.startsWith(scopeClass + "+")
                || s.startsWith(scopeClass + "~")) {
            return s;
        }

        if (s.startsWith(":root")) {
            return scopeClass + s.substring(":root".length());
        }

        return scopeClass + " " + s;
    }

    private static String wrap(String cls, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"").append(cls).append("\">\n");
        if (body != null && !body.isBlank()) {
            sb.append(body).append('\n');
        }
        sb.append("</div>\n");
        return sb.toString();
    }

    private static String manifest(Path srcDoc, List<String> warnings, Config cfg) {
        List<String> notes = Arrays.asList(
                "updateFields=" + cfg.updateFields,
                "acceptRevisions=" + cfg.acceptRevisions,
                "prefer=" + cfg.prefer
        );
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"sourceDoc\": ").append(q(srcDoc.getFileName().toString())).append(",\n");
        sb.append("  \"generatedAt\": ").append(q(Instant.now().toString())).append(",\n");
        sb.append("  \"outputs\": {\n");
        sb.append("    \"header\": ").append(q(HEADER_FILE)).append(",\n");
        sb.append("    \"content\": ").append(q(CONTENT_FILE)).append(",\n");
        sb.append("    \"footer\": ").append(q(FOOTER_FILE)).append(",\n");
        sb.append("    \"css\": ").append(q(CSS_FILE)).append(",\n");
        sb.append("    \"imagesDir\": ").append(q(IMG_DIR + "/")).append("\n");
        sb.append("  },\n");
        sb.append("  \"warnings\": ").append(toArr(warnings)).append(",\n");
        sb.append("  \"notes\": ").append(toArr(notes)).append("\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String toArr(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream().map(WordToHtmlConverter::q).collect(Collectors.joining(", ", "[", "]"));
    }

    private static String q(String s) {
        return "\"" + esc(s) + "\"";
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"': out.append("\\\""); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    private static Path chooseDoc(List<Path> docs, String prefer) {
        return docs.stream().sorted(Comparator
                .comparingInt((Path p) -> rank(p, prefer))
                .thenComparingInt(p -> p.getFileName().toString().length())
                .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))
        ).findFirst().orElseThrow();
    }

    private static int rank(Path p, String prefer) {
        String ext = ext(p);
        if (prefer.equals(ext)) {
            return 0;
        }
        if ("doc".equals(ext) || "docx".equals(ext)) {
            return 1;
        }
        return 2;
    }

    private static String ext(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1);
    }

    private static boolean matchesAny(Path name, List<PathMatcher> matchers) {
        Path lowerName = Paths.get(name.toString().toLowerCase(Locale.ROOT));
        for (PathMatcher m : matchers) {
            if (m.matches(name) || m.matches(lowerName)) {
                return true;
            }
        }
        return false;
    }

    private static void recreate(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (Stream<Path> st = Files.walk(dir)) {
                st.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        }
        Files.createDirectories(dir);
    }

    private static void write(Path path, String txt) throws IOException {
        Files.writeString(path, txt == null ? "" : txt, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static boolean containsIgnored(Path root, Path path) {
        Path rel;
        try {
            rel = root.relativize(path);
        } catch (Exception e) {
            return false;
        }
        for (Path p : rel) {
            if (isIgnoredName(p.toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIgnoredName(String n) {
        return RESULT_DIR.equalsIgnoreCase(n) || IMG_DIR.equalsIgnoreCase(n);
    }

    private static String compact(String m) {
        return m == null ? "" : m.replace("\r", " ").replace("\n", " ").trim();
    }

    private static String rel(Path root, Path p) {
        try {
            return root.relativize(p).toString();
        } catch (Exception e) {
            return p.toString();
        }
    }

    private static void log(Config cfg, String msg) {
        System.out.println("[INFO] " + msg);
    }

    private static void usage() {
        System.out.println("用法:");
        System.out.println("  java -jar word-to-html.jar [--root=. ] [--recursive=false] [--docPattern=*.doc,*.docx]");
        System.out.println("                        [--prefer=docx] [--overwrite=true] [--acceptRevisions=false]");
        System.out.println("                        [--updateFields=true] [--log=info|debug] [--failFast=false]");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  java -jar word-to-html.jar --root=. --recursive=false --prefer=docx");
        System.out.println("  java -jar word-to-html.jar --root=./convert --recursive=true --overwrite=true");
    }

    private enum Status { OK, SKIP_NO_DOC, SKIP_OVERWRITE, FAIL }

    private static final class Result {
        final Status status;
        final String err;
        final Exception ex;
        final List<String> warnings;

        private Result(Status status, String err, Exception ex, List<String> warnings) {
            this.status = status;
            this.err = err;
            this.ex = ex;
            this.warnings = warnings;
        }

        static Result of(Status s, List<String> warnings) {
            return new Result(s, "", null, warnings);
        }

        static Result fail(String err, Exception ex, List<String> warnings) {
            return new Result(Status.FAIL, err, ex, warnings);
        }
    }

    private static final class Summary {
        int total;
        int ok;
        int fail;
        int skipNoDoc;
        int skipOverwrite;
        int warn;
    }

    private static final class Exported {
        final String header;
        final String content;
        final String footer;
        final String css;

        Exported(String header, String content, String footer, String css) {
            this.header = header;
            this.content = content;
            this.footer = footer;
            this.css = css;
        }
    }

    private static final class HtmlFrag {
        final String body;
        final List<String> css;

        HtmlFrag(String body, List<String> css) {
            this.body = body;
            this.css = css;
        }
    }

    private static final class Config {
        final Path root;
        final boolean recursive;
        final boolean overwrite;
        final boolean acceptRevisions;
        final boolean updateFields;
        final String prefer;
        final List<PathMatcher> docMatchers;
        final boolean debug;
        final boolean failFast;
        final boolean help;

        Config(Path root, boolean recursive, boolean overwrite, boolean acceptRevisions,
               boolean updateFields, String prefer, List<PathMatcher> docMatchers,
               boolean debug, boolean failFast, boolean help) {
            this.root = root;
            this.recursive = recursive;
            this.overwrite = overwrite;
            this.acceptRevisions = acceptRevisions;
            this.updateFields = updateFields;
            this.prefer = prefer;
            this.docMatchers = docMatchers;
            this.debug = debug;
            this.failFast = failFast;
            this.help = help;
        }

        static Config parse(String[] args) {
            Path root = Paths.get(".").toAbsolutePath().normalize();
            boolean recursive = false;
            boolean overwrite = true;
            boolean acceptRevisions = false;
            boolean updateFields = true;
            String prefer = "docx";
            String docPatternRaw = "*.doc,*.docx";
            boolean debug = false;
            boolean failFast = false;
            boolean help = false;
            String positionalRoot = null;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    help = true;
                    continue;
                }
                if (arg.startsWith("--")) {
                    String key;
                    String val;
                    int eq = arg.indexOf('=');
                    if (eq > 0) {
                        key = arg.substring(2, eq);
                        val = arg.substring(eq + 1);
                    } else {
                        key = arg.substring(2);
                        if (isBool(key)) {
                            val = "true";
                        } else {
                            if (i + 1 >= args.length) {
                                throw new IllegalArgumentException("缺少参数值: --" + key);
                            }
                            val = args[++i];
                        }
                    }
                    switch (key) {
                        case "root": root = parseRoot(val); break;
                        case "recursive": recursive = parseBool(val, key); break;
                        case "overwrite": overwrite = parseBool(val, key); break;
                        case "acceptRevisions": acceptRevisions = parseBool(val, key); break;
                        case "updateFields": updateFields = parseBool(val, key); break;
                        case "prefer": prefer = parsePrefer(val); break;
                        case "docPattern": docPatternRaw = val; break;
                        case "log": debug = parseLog(val); break;
                        case "failFast": failFast = parseBool(val, key); break;
                        default: throw new IllegalArgumentException("不支持的参数: --" + key);
                    }
                } else {
                    if (positionalRoot != null) {
                        throw new IllegalArgumentException("多余的位置参数: " + arg);
                    }
                    positionalRoot = arg;
                }
            }

            if (positionalRoot != null) {
                root = parseRoot(positionalRoot);
            }
            if (!Files.exists(root)) {
                throw new IllegalArgumentException("root 路径不存在: " + root);
            }
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("root 不是目录: " + root);
            }

            List<String> patterns = Arrays.stream(docPatternRaw.split(","))
                    .map(String::trim)
                    .filter(v -> !v.isEmpty())
                    .collect(Collectors.toList());
            if (patterns.isEmpty()) {
                throw new IllegalArgumentException("docPattern 不能为空");
            }
            final Path matcherRoot = root;
            List<PathMatcher> matchers = patterns.stream().map(p -> {
                try {
                    return matcherRoot.getFileSystem().getPathMatcher("glob:" + p);
                } catch (Exception e) {
                    throw new IllegalArgumentException("docPattern 非法: " + p);
                }
            }).collect(Collectors.toList());

            return new Config(root, recursive, overwrite, acceptRevisions,
                    updateFields, prefer, matchers, debug, failFast, help);
        }

        private static Path parseRoot(String v) {
            try {
                return Paths.get(v).toAbsolutePath().normalize();
            } catch (InvalidPathException e) {
                throw new IllegalArgumentException("root 路径非法: " + v);
            }
        }

        private static String parsePrefer(String v) {
            String p = v.trim().toLowerCase(Locale.ROOT);
            if (!"doc".equals(p) && !"docx".equals(p)) {
                throw new IllegalArgumentException("--prefer 仅支持 doc 或 docx，当前: " + v);
            }
            return p;
        }

        private static boolean parseLog(String v) {
            String t = v.trim().toLowerCase(Locale.ROOT);
            if ("debug".equals(t)) {
                return true;
            }
            if ("info".equals(t)) {
                return false;
            }
            throw new IllegalArgumentException("--log 仅支持 info|debug，当前: " + v);
        }

        private static boolean parseBool(String v, String key) {
            if ("true".equalsIgnoreCase(v)) {
                return true;
            }
            if ("false".equalsIgnoreCase(v)) {
                return false;
            }
            throw new IllegalArgumentException("--" + key + " 仅支持 true/false，当前: " + v);
        }

        private static boolean isBool(String k) {
            return "recursive".equals(k)
                    || "overwrite".equals(k)
                    || "acceptRevisions".equals(k)
                    || "updateFields".equals(k)
                    || "failFast".equals(k);
        }
    }
}
