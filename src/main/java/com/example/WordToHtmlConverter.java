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

/**
 * 批量 Word 拆分导出工具。
 * <p>
 * 目标产物：
 * <ul>
 *   <li>header.html / content.html / footer.html</li>
 *   <li>word.css（已按容器类做样式作用域隔离）</li>
 *   <li>images/ 资源目录（imagesBase64=false 时）</li>
 *   <li>manifest.json 元数据</li>
 * </ul>
 */
public class WordToHtmlConverter {
    private static final String RESULT_DIR = "result";
    private static final String IMG_DIR = "images";
    private static final String ASPOSE_IMAGE_PREFIX = "Aspose.Words.";
    private static final String HEADER_FILE = "header.html";
    private static final String CONTENT_FILE = "content.html";
    private static final String FOOTER_FILE = "footer.html";
    private static final String CSS_FILE = "word.css";
    private static final String MANIFEST_FILE = "manifest.json";

    private static final Pattern STYLE_TAG = Pattern.compile("(?is)<style[^>]*>(.*?)</style>");
    private static final Pattern BODY_TAG = Pattern.compile("(?is)<body[^>]*>(.*?)</body>");
    private static final Pattern LINK_TAG = Pattern.compile("(?is)<link[^>]*>");
    private static final Pattern CSS_RULE_HEAD = Pattern.compile("(?m)^(\\s*)([^\\n\\{\\}]+?)\\s*\\{");

    /**
     * CLI 入口。
     * <p>
     * 负责：
     * <ul>
     *   <li>解析参数</li>
     *   <li>执行批量处理</li>
     *   <li>打印汇总并设置退出码</li>
     * </ul>
     */
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
            System.out.println("Total directories: " + summary.total);
            System.out.println("Success: " + summary.ok);
            System.out.println("Failed: " + summary.fail);
            System.out.println("Skipped (no document): " + summary.skipNoDoc);
            System.out.println("Skipped (overwrite=false): " + summary.skipOverwrite);
            System.out.println("Warnings: " + summary.warn);
            if (summary.fail > 0) {
                System.exit(1);
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid arguments: " + e.getMessage());
            usage();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Execution failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * 执行批量扫描与转换主流程。
     *
     * @param cfg 运行配置
     * @return 汇总统计
     */
    private static Summary run(Config cfg) throws Exception {
        log(cfg, "Root: " + cfg.root);
        List<Path> dirs = scanDirs(cfg);
        log(cfg, "Discovered directories: " + dirs.size());

        Summary s = new Summary();
        for (Path dir : dirs) {
            s.total++;
            String rel = rel(cfg.root, dir);
            log(cfg, "Processing directory: " + rel);
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

    /**
     * 根据配置扫描目标目录列表。
     * <p>
     * recursive=true 时递归；否则只处理 root 一级子目录。
     * 同时排除 result/images 目录，避免重复处理输出内容。
     *
     * @param cfg 运行配置
     * @return 待处理目录（已排序）
     */
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

    /**
     * 处理单个业务目录。
     * <p>
     * 步骤：
     * <ol>
     *   <li>发现并选择 Word 文件</li>
     *   <li>创建/清空 result 目录</li>
     *   <li>执行拆分导出</li>
 *   <li>写出 HTML/CSS/(可选 images)/manifest</li>
     * </ol>
     *
     * @param cfg 运行配置
     * @param dir 当前目录
     * @return 当前目录处理结果
     */
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
                warns.add("No matching Word file found.");
                return Result.of(Status.SKIP_NO_DOC, warns);
            }
            chosen = chooseDoc(docs, cfg.prefer);
            if (docs.size() > 1) {
                warns.add("Multiple Word files found, selected by rules: " + chosen.getFileName());
            }

            Path out = dir.resolve(RESULT_DIR);
            if (Files.exists(out) && !cfg.overwrite) {
                warns.add("result already exists and overwrite=false, skipped.");
                return Result.of(Status.SKIP_OVERWRITE, warns);
            }
            recreate(out);
            Path images = cfg.imagesBase64 ? null : out.resolve(IMG_DIR);
            if (images != null) {
                Files.createDirectories(images);
            }

            ManifestData manifestData = new ManifestData();
            Exported exported = convertOne(chosen, images, cfg, warns, manifestData);
            write(out.resolve(HEADER_FILE), exported.header);
            write(out.resolve(CONTENT_FILE), exported.content);
            write(out.resolve(FOOTER_FILE), exported.footer);
            write(out.resolve(CSS_FILE), exported.css);
            write(out.resolve(MANIFEST_FILE), manifest(chosen, warns, cfg, manifestData));
            return Result.of(Status.OK, warns);
        } catch (Exception e) {
            String source = chosen == null ? "" : " (source=" + chosen.getFileName() + ")";
            return Result.fail("Processing exception" + source + ": " + e.getMessage(), e, warns);
        }
    }

    /**
     * 将单个 Word 文档转换为三段 HTML + scoped CSS。
     * <p>
     * 关键规则：
     * <ul>
     *   <li>header/footer 来自 first section（First 非空优先，否则 Primary）</li>
     *   <li>content 拼接所有 section 的 body 块级节点（段落/表格）</li>
     *   <li>通过 HtmlBodyProcessor 处理 input 标签和特殊格式</li>
     * </ul>
     *
     * @param sourcePath Word 文件路径
     * @param imagesDir 图片输出目录（imagesBase64=true 时可为 null）
     * @param cfg 运行配置
     * @param warns 告警收集器
     * @param manifestData manifest 数据收集器
     * @return 三段 HTML + 汇总 CSS
     */
    private static Exported convertOne(Path sourcePath, Path imagesDir, Config cfg, List<String> warns, ManifestData manifestData) throws Exception {
        Document src = new Document(sourcePath.toString());
        if (cfg.updateFields) {
            try {
                src.updateFields();
            } catch (Exception e) {
                warns.add("updateFields failed: " + compact(e.getMessage()));
            }
        }
        if (cfg.acceptRevisions) {
            try {
                src.acceptAllRevisions();
            } catch (Exception e) {
                warns.add("acceptAllRevisions failed: " + compact(e.getMessage()));
            }
        }

        Document hDoc = blankDoc();
        Document cDoc = blankDoc();
        Document fDoc = blankDoc();

        Section first = src.getFirstSection();
        if (first == null) {
            throw new IllegalStateException("No section found in document.");
        }
        HeaderFooter h = pick(first, true);
        HeaderFooter f = pick(first, false);
        if (h == null) {
            warns.add("No usable header found; output will be an empty container.");
        } else {
            appendHeaderFooter(src, h, hDoc);
        }
        if (f == null) {
            warns.add("No usable footer found; output will be an empty container.");
        } else {
            appendHeaderFooter(src, f, fDoc);
        }
        appendContent(src, cDoc);

        HtmlFrag hFrag = saveFrag(hDoc, imagesDir, "wh-", cfg.imagesBase64);
        HtmlFrag cFrag = saveFrag(cDoc, imagesDir, "wc-", cfg.imagesBase64);
        HtmlFrag fFrag = saveFrag(fDoc, imagesDir, "wf-", cfg.imagesBase64);

        // 使用 HtmlBodyProcessor 处理 body 内容
        HtmlBodyProcessor processor = new HtmlBodyProcessor();

        HtmlProcessingResult hResult = processor.process(hFrag.body);
        HtmlProcessingResult cResult = processor.process(cFrag.body);
        // footer 使用特殊处理方法，处理 page 数字 of 数字
        HtmlProcessingResult fResult = processor.processFooter(fFrag.body);

        // 收集单体 |数字| 到 manifestData
        manifestData.addOrphanPipeNumbers(hResult.getOrphanPipeNumbers());
        manifestData.addOrphanPipeNumbers(cResult.getOrphanPipeNumbers());
        manifestData.addOrphanPipeNumbers(fResult.getOrphanPipeNumbers());

        String css = mergeCss(hFrag.css, cFrag.css, fFrag.css);
        return new Exported(
                wrap("word-header", hResult.getHtml()),
                wrap("word-content", cResult.getHtml()),
                wrap("word-footer", fResult.getHtml()),
                css
        );
    }

    /**
     * 创建仅包含一个空 body 的临时文档，用于承载某个片段。
     *
     * @return 可写入段落/表格节点的文档
     */
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

    /**
     * 选择 header 或 footer 节点。
     * <p>
     * 优先级：First（且有内容） > Primary（且有内容）> null。
     *
     * @param first 第一节
     * @param header true 表示选 header；false 表示选 footer
     * @return 选中的 HeaderFooter，可能为 null
     */
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

    /**
     * 判断 header/footer 是否可渲染（有文本或可见结构）。
     *
     * @param hf header/footer 节点
     * @return true 表示存在可渲染内容
     */
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

    /**
     * 将 header/footer 中的块级节点复制到目标文档 body。
     * 仅复制段落与表格，避免引入无关容器节点。
     *
     * @param src 源文档
     * @param from 源 header/footer
     * @param to 目标片段文档
     */
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

    /**
     * 复制所有 section 的正文块级节点到 content 片段文档。
     *
     * @param src 源文档
     * @param to 目标 content 文档
     */
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

    /**
     * 导出片段 HTML，并提取 body 片段与 style 块。
     *
     * @param doc 片段文档
     * @param imagesDir 图片目录（imagesBase64=false 时使用）
     * @param cssPrefix 该片段的 Aspose CSS 类名前缀
     * @param imagesBase64 图片是否以内嵌 base64 输出
     * @return 提取后的 HTML/CSS
     */
    private static HtmlFrag saveFrag(Document doc, Path imagesDir, String cssPrefix, boolean imagesBase64) throws Exception {
        HtmlSaveOptions opt = new HtmlSaveOptions(SaveFormat.HTML);
        opt.setEncoding(StandardCharsets.UTF_8);
        opt.setCssStyleSheetType(CssStyleSheetType.EMBEDDED);
        opt.setCssClassNamePrefix(cssPrefix);
        opt.setExportImagesAsBase64(imagesBase64);
        opt.setExportFontResources(false);
        opt.setExportHeadersFootersMode(ExportHeadersFootersMode.NONE);
        opt.setExportPageMargins(false);
        opt.setExportPageSetup(false);
        opt.setExportRoundtripInformation(false);
        if (!imagesBase64 && imagesDir != null) {
            opt.setImagesFolder(imagesDir.toAbsolutePath().toString());
            opt.setImagesFolderAlias(IMG_DIR);
            opt.setImageSavingCallback(args -> {
                String current = args.getImageFileName();
                if (current == null || current.isEmpty()) {
                    return;
                }
                String renamed = stripAsposeImagePrefix(current);
                if (!current.equals(renamed)) {
                    args.setImageFileName(renamed);
                }
            });
        }
        opt.setPrettyFormat(true);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out, opt);
        String html = out.toString(StandardCharsets.UTF_8);
        String body = extractBody(html);
        List<String> cssBlocks = extractCss(html);
        return new HtmlFrag(body, cssBlocks);
    }

    private static String stripAsposeImagePrefix(String imageFileName) {
        if (imageFileName == null || imageFileName.isEmpty()) {
            return imageFileName;
        }
        int slash = Math.max(imageFileName.lastIndexOf('/'), imageFileName.lastIndexOf('\\'));
        if (slash < 0) {
            return imageFileName.startsWith(ASPOSE_IMAGE_PREFIX)
                    ? imageFileName.substring(ASPOSE_IMAGE_PREFIX.length())
                    : imageFileName;
        }
        String dir = imageFileName.substring(0, slash + 1);
        String name = imageFileName.substring(slash + 1);
        if (name.startsWith(ASPOSE_IMAGE_PREFIX)) {
            name = name.substring(ASPOSE_IMAGE_PREFIX.length());
        }
        return dir + name;
    }

    /**
     * 从完整 HTML 中抽取 body 内容，移除 style/link 标签。
     *
     * @param html 完整 HTML 文本
     * @return 可直接注入编辑器的片段 HTML
     */
    private static String extractBody(String html) {
        Matcher m = BODY_TAG.matcher(html);
        String body = m.find() ? m.group(1) : html;
        body = STYLE_TAG.matcher(body).replaceAll("");
        body = LINK_TAG.matcher(body).replaceAll("");
        return body.trim();
    }

    /**
     * 抽取 HTML 中所有 style 标签内容。
     *
     * @param html 完整 HTML 文本
     * @return CSS 块集合
     */
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

    /**
     * 合并三段 CSS，并将选择器限定到对应容器类下，避免样式污染。
     *
     * @param headerCss header CSS 块集合
     * @param contentCss content CSS 块集合
     * @param footerCss footer CSS 块集合
     * @return 合并后的 scoped CSS
     */
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

    /**
     * 将一组 CSS 块作用域化后追加到目标集合。
     *
     * @param allScoped 汇总去重集合
     * @param cssBlocks 原始 CSS 块
     * @param scopeClass 作用域类（如 .word-content）
     */
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

    /**
     * 对单个 CSS 块进行选择器级作用域改写。
     *
     * @param css 原始 CSS 块
     * @param scopeClass 作用域类
     * @return 作用域化后的 CSS 块
     */
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

    /**
     * 处理逗号分隔的多个选择器，并逐个做作用域改写。
     *
     * @param selectorList 原始选择器列表
     * @param scopeClass 作用域类
     * @return 改写后的选择器列表
     */
    private static String scopeSelectorList(String selectorList, String scopeClass) {
        return Arrays.stream(selectorList.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(selector -> scopeSingleSelector(selector, scopeClass))
                .collect(Collectors.joining(", "));
    }

    /**
     * 处理单个选择器的作用域改写。
     * <p>
     * 规则：
     * <ul>
     *   <li>已带作用域前缀则保持不变</li>
     *   <li>html/body 起始选择器替换为作用域类</li>
     *   <li>其他选择器前置作用域类</li>
     * </ul>
     *
     * @param selector 单个选择器
     * @param scopeClass 作用域类
     * @return 改写后的选择器
     */
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

    /**
     * 为 HTML 片段包裹外层容器类，便于前端按区块注入与样式隔离。
     *
     * @param cls 容器类名
     * @param body body 片段
     * @return 包裹后的 HTML
     */
    private static String wrap(String cls, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"").append(cls).append("\">\n");
        if (body != null && !body.isBlank()) {
            sb.append(body).append('\n');
        }
        sb.append("</div>\n");
        return sb.toString();
    }

    /**
     * 构造 manifest.json 内容。
     *
     * @param srcDoc 源文档
     * @param warnings 告警列表
     * @param cfg 运行配置
     * @param manifestData manifest 数据收集器
     * @return JSON 文本
     */
    private static String manifest(Path srcDoc, List<String> warnings, Config cfg, ManifestData manifestData) {
        List<String> notes = Arrays.asList(
                "updateFields=" + cfg.updateFields,
                "acceptRevisions=" + cfg.acceptRevisions,
                "prefer=" + cfg.prefer,
                "imagesBase64=" + cfg.imagesBase64
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
        sb.append("    \"imagesBase64\": ").append(cfg.imagesBase64).append(",\n");
        if (cfg.imagesBase64) {
            sb.append("    \"imagesDir\": null\n");
        } else {
            sb.append("    \"imagesDir\": ").append(q(IMG_DIR + "/")).append("\n");
        }
        sb.append("  },\n");
        sb.append("  \"warnings\": ").append(toArr(warnings)).append(",\n");
        sb.append("  \"notes\": ").append(toArr(notes)).append(",\n");
        sb.append("  \"orphanPipeNumbers\": ").append(toArr(manifestData.getOrphanPipeNumbers())).append("\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * 将字符串列表序列化为 JSON 数组。
     *
     * @param values 文本列表
     * @return JSON 数组字符串
     */
    private static String toArr(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream().map(WordToHtmlConverter::q).collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * JSON 字符串加引号并转义。
     */
    private static String q(String s) {
        return "\"" + esc(s) + "\"";
    }

    /**
     * JSON 字符串转义。
     *
     * @param s 原始文本
     * @return 转义后文本
     */
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

    /**
     * 按固定规则从候选 Word 中选定一个：
     * <ol>
     *   <li>优先扩展名（prefer）</li>
     *   <li>文件名长度更短优先</li>
     *   <li>文件名字典序</li>
     * </ol>
     *
     * @param docs 候选文件
     * @param prefer 优先扩展名（doc/docx）
     * @return 选中的文件
     */
    private static Path chooseDoc(List<Path> docs, String prefer) {
        return docs.stream().sorted(Comparator
                .comparingInt((Path p) -> rank(p, prefer))
                .thenComparingInt(p -> p.getFileName().toString().length())
                .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))
        ).findFirst().orElseThrow();
    }

    /**
     * 计算候选文件优先级分值（越小越优先）。
     */
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

    /**
     * 读取文件扩展名（小写，不含点）。
     */
    private static String ext(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1);
    }

    /**
     * 判断文件名是否匹配任一 glob 规则。
     * 同时尝试原始大小写与小写名，提升跨平台兼容性。
     *
     * @param name 文件名
     * @param matchers 规则集合
     * @return 是否匹配
     */
    private static boolean matchesAny(Path name, List<PathMatcher> matchers) {
        Path lowerName = Paths.get(name.toString().toLowerCase(Locale.ROOT));
        for (PathMatcher m : matchers) {
            if (m.matches(name) || m.matches(lowerName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 先递归删除目录，再重建目录。
     *
     * @param dir 目标目录
     */
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

    /**
     * 以 UTF-8 覆盖写出文本文件。
     *
     * @param path 文件路径
     * @param txt 文件内容
     */
    private static void write(Path path, String txt) throws IOException {
        Files.writeString(path, txt == null ? "" : txt, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * 判断路径相对 root 的任一层级是否为忽略目录名。
     */
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

    /**
     * 当前是否属于内置忽略目录（输出目录）。
     */
    private static boolean isIgnoredName(String n) {
        return RESULT_DIR.equalsIgnoreCase(n) || IMG_DIR.equalsIgnoreCase(n);
    }

    /**
     * 将异常信息压平为单行文本。
     */
    private static String compact(String m) {
        return m == null ? "" : m.replace("\r", " ").replace("\n", " ").trim();
    }

    /**
     * 获取相对路径展示文本；失败时回退绝对路径。
     */
    private static String rel(Path root, Path p) {
        try {
            return root.relativize(p).toString();
        } catch (Exception e) {
            return p.toString();
        }
    }

    /**
     * 打印 info 日志。
     */
    private static void log(Config cfg, String msg) {
        System.out.println("[INFO] " + msg);
    }

    /**
     * 打印命令行帮助。
     */
    private static void usage() {
        System.out.println("Usage:");
        System.out.println("  java -jar word-to-html.jar [--root=. ] [--recursive=false] [--docPattern=*.doc,*.docx]");
        System.out.println("                        [--prefer=docx] [--overwrite=true] [--acceptRevisions=false]");
        System.out.println("                        [--updateFields=true] [--imagesBase64=true] [--log=info|debug] [--failFast=false]");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar word-to-html.jar --root=. --recursive=false --prefer=docx");
        System.out.println("  java -jar word-to-html.jar --root=./convert --recursive=true --overwrite=true");
        System.out.println("  java -jar word-to-html.jar --root=./convert --imagesBase64=false");
    }

    /**
     * 单目录处理状态。
     */
    private enum Status { OK, SKIP_NO_DOC, SKIP_OVERWRITE, FAIL }

    /**
     * 单目录处理结果对象。
     */
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

        /**
         * 构造成功或跳过结果。
         */
        static Result of(Status s, List<String> warnings) {
            return new Result(s, "", null, warnings);
        }

        /**
         * 构造失败结果。
         */
        static Result fail(String err, Exception ex, List<String> warnings) {
            return new Result(Status.FAIL, err, ex, warnings);
        }
    }

    /**
     * 批处理汇总统计。
     */
    private static final class Summary {
        int total;
        int ok;
        int fail;
        int skipNoDoc;
        int skipOverwrite;
        int warn;
    }

    /**
     * 单文档导出产物（3 段 HTML + CSS）。
     */
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

    /**
     * 导出片段中间结果（body HTML + style 集合）。
     */
    private static final class HtmlFrag {
        final String body;
        final List<String> css;

        HtmlFrag(String body, List<String> css) {
            this.body = body;
            this.css = css;
        }
    }

    /**
     * CLI 参数配置对象。
     */
    private static final class Config {
        final Path root;
        final boolean recursive;
        final boolean overwrite;
        final boolean acceptRevisions;
        final boolean updateFields;
        final boolean imagesBase64;
        final String prefer;
        final List<PathMatcher> docMatchers;
        final boolean debug;
        final boolean failFast;
        final boolean help;

        Config(Path root, boolean recursive, boolean overwrite, boolean acceptRevisions,
               boolean updateFields, boolean imagesBase64, String prefer, List<PathMatcher> docMatchers,
               boolean debug, boolean failFast, boolean help) {
            this.root = root;
            this.recursive = recursive;
            this.overwrite = overwrite;
            this.acceptRevisions = acceptRevisions;
            this.updateFields = updateFields;
            this.imagesBase64 = imagesBase64;
            this.prefer = prefer;
            this.docMatchers = docMatchers;
            this.debug = debug;
            this.failFast = failFast;
            this.help = help;
        }

        /**
         * 解析命令行参数并做基础校验。
         *
         * @param args CLI 参数
         * @return 结构化配置
         */
        static Config parse(String[] args) {
            Path root = Paths.get(".").toAbsolutePath().normalize();
            boolean recursive = false;
            boolean overwrite = true;
            boolean acceptRevisions = false;
            boolean updateFields = true;
            boolean imagesBase64 = true;
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
                                throw new IllegalArgumentException("Missing value for argument: --" + key);
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
                        case "imagesBase64": imagesBase64 = parseBool(val, key); break;
                        case "prefer": prefer = parsePrefer(val); break;
                        case "docPattern": docPatternRaw = val; break;
                        case "log": debug = parseLog(val); break;
                        case "failFast": failFast = parseBool(val, key); break;
                        default: throw new IllegalArgumentException("Unsupported argument: --" + key);
                    }
                } else {
                    if (positionalRoot != null) {
                        throw new IllegalArgumentException("Unexpected positional argument: " + arg);
                    }
                    positionalRoot = arg;
                }
            }

            if (positionalRoot != null) {
                root = parseRoot(positionalRoot);
            }
            if (!Files.exists(root)) {
                throw new IllegalArgumentException("root path does not exist: " + root);
            }
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("root is not a directory: " + root);
            }

            List<String> patterns = Arrays.stream(docPatternRaw.split(","))
                    .map(String::trim)
                    .filter(v -> !v.isEmpty())
                    .collect(Collectors.toList());
            if (patterns.isEmpty()) {
                throw new IllegalArgumentException("docPattern cannot be empty");
            }
            final Path matcherRoot = root;
            List<PathMatcher> matchers = patterns.stream().map(p -> {
                try {
                    return matcherRoot.getFileSystem().getPathMatcher("glob:" + p);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid docPattern: " + p);
                }
            }).collect(Collectors.toList());

            return new Config(root, recursive, overwrite, acceptRevisions,
                    updateFields, imagesBase64, prefer, matchers, debug, failFast, help);
        }

        /**
         * 解析 root 目录。
         */
        private static Path parseRoot(String v) {
            try {
                return Paths.get(v).toAbsolutePath().normalize();
            } catch (InvalidPathException e) {
                throw new IllegalArgumentException("Invalid root path: " + v);
            }
        }

        /**
         * 解析 prefer 参数，只允许 doc/docx。
         */
        private static String parsePrefer(String v) {
            String p = v.trim().toLowerCase(Locale.ROOT);
            if (!"doc".equals(p) && !"docx".equals(p)) {
                throw new IllegalArgumentException("--prefer only supports doc or docx, got: " + v);
            }
            return p;
        }

        /**
         * 解析日志级别（info/debug）。
         */
        private static boolean parseLog(String v) {
            String t = v.trim().toLowerCase(Locale.ROOT);
            if ("debug".equals(t)) {
                return true;
            }
            if ("info".equals(t)) {
                return false;
            }
            throw new IllegalArgumentException("--log only supports info|debug, got: " + v);
        }

        /**
         * 解析布尔参数。
         */
        private static boolean parseBool(String v, String key) {
            if ("true".equalsIgnoreCase(v)) {
                return true;
            }
            if ("false".equalsIgnoreCase(v)) {
                return false;
            }
            throw new IllegalArgumentException("--" + key + " only supports true/false, got: " + v);
        }

        /**
         * 判断是否是支持“无值即 true”的布尔参数。
         */
        private static boolean isBool(String k) {
            return "recursive".equals(k)
                    || "overwrite".equals(k)
                    || "acceptRevisions".equals(k)
                    || "updateFields".equals(k)
                    || "imagesBase64".equals(k)
                    || "failFast".equals(k);
        }
    }
}
