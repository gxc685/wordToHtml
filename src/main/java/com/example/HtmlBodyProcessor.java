package com.example;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML Body 处理器
 * 负责处理 HTML body 内部的转换逻辑
 */
public class HtmlBodyProcessor {

    // 匹配完整的 [字符内容]（用于 input value）
    private static final Pattern BRACKET_VALUE_PATTERN = Pattern.compile("^\\s*\\[([^\\]]+)\\]\\s*$");
    // 匹配文本中的 [字符内容]
    private static final Pattern BRACKET_TEXT_PATTERN = Pattern.compile("\\[([^\\]]+)\\]");
    // 仅匹配 input value 完全是 |数字|
    private static final Pattern PIPE_VALUE_PATTERN = Pattern.compile("^\\s*\\|\\s*(\\d+)\\s*\\|\\s*$");
    // 匹配 page 数字 of 数字（支持各种大小写和空格）
    private static final Pattern PAGE_OF_PATTERN = Pattern.compile(
            "(?i)\\bpage\\b\\s*(\\d+)\\s*\\bof\\b\\s*(\\d+)");

    /**
     * 处理 HTML 内容
     *
     * @param html 原始 HTML
     * @return 处理结果，包含处理后的 HTML 和单体 |数字| 列表
     */
    public HtmlProcessingResult process(String html) {
        return processInternal(html, false);
    }

    /**
     * 处理 Footer HTML 内容（特殊处理 page 数字 of 数字）
     *
     * @param html 原始 HTML
     * @return 处理结果
     */
    public HtmlProcessingResult processFooter(String html) {
        return processInternal(html, true);
    }

    /**
     * 处理 HTML 内容。
     *
     * @param html 原始 HTML
     * @param footerMode 是否启用 footer 的 page/of 处理
     * @return 处理结果
     */
    private HtmlProcessingResult processInternal(String html, boolean footerMode) {
        List<String> orphanPipeNumbers = new ArrayList<>();
        Document doc = Jsoup.parse(html == null ? "" : html, "", Parser.xmlParser());

        if (footerMode) {
            processPageOfPattern(doc);
        }
        processTextAnchors(doc);
        processInputTags(doc, orphanPipeNumbers);
        processBracketTextInNodes(doc);

        Element body = doc.selectFirst("body");
        String processedHtml = body != null ? body.html() : doc.html();
        return new HtmlProcessingResult(processedHtml, orphanPipeNumbers);
    }

    /**
     * 处理 page 数字 of 数字 格式。
     *
     * @param doc HTML 文档
     */
    private void processPageOfPattern(Document doc) {
        List<TextNode> textNodes = new ArrayList<>();
        collectTextNodes(doc, textNodes);

        for (TextNode textNode : textNodes) {
            String text = textNode.getWholeText();
            String processedText = processPageOfText(text);
            if (!processedText.equals(text)) {
                textNode.text(processedText);
            }
        }
    }

    /**
     * 处理文本中的 page 数字 of 数字
     *
     * @param text 原始文本
     * @return 处理后的文本
     */
    private String processPageOfText(String text) {
        Matcher matcher = PAGE_OF_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            // 替换为 page {{_pageNo}} of {{_totalPageNo}}
            String replacement = "page {{_pageNo}} of {{_totalPageNo}}";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * 处理文本节点中的 [字符内容]，替换为 {{字符内容}}
     *
     * @param doc HTML 文档
     */
    private void processBracketTextInNodes(Document doc) {
        List<TextNode> textNodes = new ArrayList<>();
        collectTextNodes(doc, textNodes);

        for (TextNode textNode : textNodes) {
            String text = textNode.getWholeText();
            String processedText = processBracketText(text);
            if (!processedText.equals(text)) {
                textNode.text(processedText);
            }
        }
    }

    /**
     * 处理文本中的 [字符内容] 替换为 {{字符内容}}
     *
     * @param text 原始文本
     * @return 处理后的文本
     */
    private String processBracketText(String text) {
        Matcher matcher = BRACKET_TEXT_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String content = matcher.group(1).trim();
            String replacement = "{{" + content + "}}";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * 递归收集所有文本节点
     *
     * @param node 当前节点
     * @param textNodes 文本节点列表
     */
    private void collectTextNodes(Node node, List<TextNode> textNodes) {
        if (node instanceof TextNode) {
            textNodes.add((TextNode) node);
        }
        for (Node child : node.childNodes()) {
            collectTextNodes(child, textNodes);
        }
    }

    /**
     * 处理 TextField 导出的锚点，适配无 input 的场景：
     * 1. <a name="text"> ... </a> -> 去掉 a，保留内部节点
     * 2. <a name="text"></a> -> 删除空 a
     *
     * @param doc HTML 文档
     */
    private void processTextAnchors(Document doc) {
        List<Element> anchors = new ArrayList<>();
        for (Element anchor : doc.select("a")) {
            if (isTextAnchor(anchor)) {
                anchors.add(anchor);
            }
        }

        for (Element anchor : anchors) {
            if (anchor.parent() == null) {
                continue;
            }
            if (anchor.childNodeSize() > 0) {
                anchor.unwrap();
                continue;
            }
            anchor.remove();
        }
    }

    /**
     * 是否为 TextField 占位锚点。
     */
    private boolean isTextAnchor(Element anchor) {
        return anchor != null
                && "a".equalsIgnoreCase(anchor.tagName())
                && "text".equalsIgnoreCase(anchor.attr("name").trim());
    }

    /**
     * 处理 input 标签
     * 1. 仅当 value 完全匹配 [字符内容] 时，替换为 span（{{字符内容}}）
     * 2. 仅当 value 完全匹配 |数字| 时，参与成对处理
     * 3. |数字| 不成对时，移除孤立 input
     * 注意：普通文本节点中的 [字符内容] 由 processBracketTextInNodes 处理
     *
     * @param doc HTML 文档
     * @param orphanPipeNumbers 孤立数字收集器
     */
    private void processInputTags(Document doc, List<String> orphanPipeNumbers) {
        Elements inputs = doc.select("input");
        Map<String, List<Element>> numberToInputs = new LinkedHashMap<>();

        for (Element input : inputs) {
            String value = input.attr("value");

            Matcher bracketMatcher = BRACKET_VALUE_PATTERN.matcher(value);
            if (bracketMatcher.matches()) {
                // 替换为 span 标签
                Element span = new Element("span");
                copyInputPresentationAttributes(input, span);
                String content = bracketMatcher.group(1).trim();
                span.text("{{" + content + "}}");
                input.replaceWith(span);
                continue;
            }

            Matcher pipeMatcher = PIPE_VALUE_PATTERN.matcher(value);
            if (pipeMatcher.matches()) {
                String number = pipeMatcher.group(1).trim();
                numberToInputs.computeIfAbsent(number, k -> new ArrayList<>()).add(input);
            }
        }

        processPipeNumberPairs(numberToInputs, orphanPipeNumbers);
    }

    /**
     * input -> span 时保留展示相关属性，避免原样式丢失。
     *
     * @param input 源 input
     * @param span 目标 span
     */
    private void copyInputPresentationAttributes(Element input, Element span) {
        for (Attribute attribute : input.attributes()) {
            String key = attribute.getKey();
            if (isUnsupportedSpanAttr(key)) {
                continue;
            }
            span.attr(key, attribute.getValue());
        }
    }

    /**
     * 过滤掉 input 专属属性，避免复制后产生无效/误导属性。
     *
     * @param key 属性名
     * @return true 表示不复制
     */
    private boolean isUnsupportedSpanAttr(String key) {
        return "type".equalsIgnoreCase(key)
                || "value".equalsIgnoreCase(key)
                || "name".equalsIgnoreCase(key)
                || "checked".equalsIgnoreCase(key)
                || "readonly".equalsIgnoreCase(key)
                || "disabled".equalsIgnoreCase(key)
                || "maxlength".equalsIgnoreCase(key)
                || "minlength".equalsIgnoreCase(key)
                || "size".equalsIgnoreCase(key)
                || "autocomplete".equalsIgnoreCase(key);
    }

    /**
     * 处理 |数字| 成对标签
     * 成对存在的替换为 {% if(FO_数字,=,Y) %} 和 {% endif %}
     * 单体存在的直接删除 input，并记录到 orphanPipeNumbers
     *
     * @param numberToInputs 数字到 input 列表的映射
     * @param orphanPipeNumbers 孤立数字收集器
     */
    private void processPipeNumberPairs(Map<String, List<Element>> numberToInputs, List<String> orphanPipeNumbers) {
        for (Map.Entry<String, List<Element>> entry : numberToInputs.entrySet()) {
            String number = entry.getKey();
            List<Element> inputs = entry.getValue();

            int i = 0;
            for (; i + 1 < inputs.size(); i += 2) {
                Element startInput = inputs.get(i);
                Element endInput = inputs.get(i + 1);

                Element startP = new Element("p");
                startP.text("{% if(FO_" + number + ",=,Y) %}");
                startInput.replaceWith(startP);

                Element endP = new Element("p");
                endP.text("{% endif %}");
                endInput.replaceWith(endP);
            }

            if ((inputs.size() & 1) == 1) {
                // 奇数个，最后一个单体存在：删除孤立 input 并记录
                Element orphan = inputs.get(inputs.size() - 1);
                orphan.remove();
                orphanPipeNumbers.add(number);
            }
        }
    }
}
