package com.example;

import java.util.Collections;
import java.util.List;

/**
 * HTML 处理结果
 */
public class HtmlProcessingResult {

    private final String html;
    private final List<String> orphanPipeNumbers;

    public HtmlProcessingResult(String html, List<String> orphanPipeNumbers) {
        this.html = html;
        this.orphanPipeNumbers = orphanPipeNumbers != null ? orphanPipeNumbers : Collections.emptyList();
    }

    /**
     * 获取处理后的 HTML
     *
     * @return HTML 字符串
     */
    public String getHtml() {
        return html;
    }

    /**
     * 获取单体存在的 |数字| 列表
     *
     * @return 数字列表
     */
    public List<String> getOrphanPipeNumbers() {
        return Collections.unmodifiableList(orphanPipeNumbers);
    }

    /**
     * 是否存在单体 |数字|
     *
     * @return true 如果存在
     */
    public boolean hasOrphanPipeNumbers() {
        return !orphanPipeNumbers.isEmpty();
    }
}
