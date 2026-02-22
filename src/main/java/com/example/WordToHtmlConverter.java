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

/**
 * Word 转 HTML 转换器
 * 使用 Aspose.Words for Java 24.1 (JDK 17)
 */
public class WordToHtmlConverter {

    public static void main(String[] args) {


        // 检查命令行参数
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        String inputPath = args[0];
        String outputPath = (args.length >= 2) ? args[1] : getDefaultOutputPath(inputPath);

        // 验证输入文件
        Path inputFilePath;
        try {
            inputFilePath = Paths.get(inputPath);
            if (!Files.exists(inputFilePath)) {
                System.err.println("错误: 输入文件不存在 - " + inputPath);
                System.exit(1);
            }
            if (!Files.isReadable(inputFilePath)) {
                System.err.println("错误: 无法读取输入文件 - " + inputPath);
                System.exit(1);
            }
        } catch (InvalidPathException e) {
            System.err.println("错误: 无效的文件路径 - " + inputPath);
            System.exit(1);
            return;
        }

        // 执行转换
        try {
            System.out.println("正在转换: " + inputPath);
            System.out.println("输出路径: " + outputPath);

            convert(inputPath, outputPath);

            System.out.println("转换成功完成!");
            System.out.println("输出文件: " + Paths.get(outputPath).toAbsolutePath());

        } catch (IOException e) {
            System.err.println("IO 错误: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("转换失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 打印使用说明
     */
    private static void printUsage() {
        System.out.println("用法: java -jar word-to-html.jar <Word文件路径> [输出HTML路径]");
        System.out.println("示例:");
        System.out.println("  java -jar word-to-html.jar document.docx");
        System.out.println("  java -jar word-to-html.jar document.docx output.html");
    }

    /**
     * 根据输入文件路径生成默认的输出 HTML 路径
     *
     * @param inputPath 输入文件路径
     * @return 默认的 HTML 输出路径
     */
    private static String getDefaultOutputPath(String inputPath) {
        int lastDotIndex = inputPath.lastIndexOf('.');
        if (lastDotIndex > 0) {
            return inputPath.substring(0, lastDotIndex) + ".html";
        }
        return inputPath + ".html";
    }

    /**
     * 修复 HTML 中 header/footer 的样式问题
     * 将 Aspose 生成的 tab stop 样式改为 flex 布局，确保一行显示
     *
     * @param htmlPath HTML 文件路径
     * @throws IOException 当文件读取/写入失败时抛出
     */
    private static void fixHeaderStyles(String htmlPath) throws IOException {
        Path path = Paths.get(htmlPath);

        // 使用 try-with-resources 自动关闭资源
        String content = Files.readString(path, StandardCharsets.UTF_8);

        // 1. 匹配 header/footer 中的 p 标签，添加 flex 布局
        content = content.replaceAll(
                "(-aw-headerfooter-type:header[^>]*>\\s*<p)([^>]*style=\"[^\"]*)(border-bottom[^\"]*)(\")",
                "$1$2$3; display:flex; justify-content:space-between; align-items:center$4");
        content = content.replaceAll(
                "(-aw-headerfooter-type:footer[^>]*>\\s*<p)([^>]*style=\"[^\"]*)(border-top[^\"]*)(\")",
                "$1$2$3; display:flex; justify-content:space-between; align-items:center$4");

        // 2. 移除所有 -aw-tabstop-* 相关的属性
        content = content.replaceAll("-aw-tabstop-align:[^;\"]+;?\\s*", "");
        content = content.replaceAll("-aw-tabstop-pos:[^;\"]+;?\\s*", "");

        // 3. 修复占位 span：将 width:xxx; display:inline-block; 改为 flex:1
        content = content.replaceAll(
                "(<div style=\"-aw-headerfooter-type:[^\"]*\"[^>]*>.*?<p[^>]*style=\"[^\"]*display:flex[^\"]*\"[^>]*>.*?)(<span style=\"width:[\\d\\.]+pt;\\s*display:inline-block;[^\"]*\")(>[^<]*</span>)",
                "$1<span style=\"flex:1\"$3");

        // 4. 再次尝试修复 footer 中的占位 span
        content = content.replaceAll(
                "(style=\"width:[\\d\\.]+pt;\\s*display:inline-block;\\s*\")(>&#xa0;</span>)",
                "style=\"flex:1\"$2");

        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    /**
     * 编程方式转换 Word 到 HTML
     *
     * @param inputPath  Word 文件路径
     * @param outputPath HTML 输出路径
     * @throws Exception 转换过程中的异常
     */
    public static void convert(String inputPath, String outputPath) throws Exception {
        // 加载 Word 文档
        Document doc = new Document(inputPath);

        // 创建并配置 HTML 保存选项
        HtmlSaveOptions saveOptions = createHtmlSaveOptions();

        // 保存为 HTML
        doc.save(outputPath, saveOptions);

        // // 修复 header 样式问题
        fixHeaderStyles(outputPath);
    }

    /**
     * 创建 HTML 保存选项
     *
     * @return 配置好的 HtmlSaveOptions
     */
    private static HtmlSaveOptions createHtmlSaveOptions() {
        HtmlSaveOptions saveOptions = new HtmlSaveOptions();

        // 配置 HTML 导出选项
        saveOptions.setExportImagesAsBase64(true); // 将图片转为 Base64 嵌入 HTML
        saveOptions.setExportFontResources(false); // 不导出字体资源
        saveOptions.setCssStyleSheetType(CssStyleSheetType.EMBEDDED); // 嵌入式 CSS
        saveOptions.setExportPageMargins(true); // 导出页边距
        saveOptions.setExportHeadersFootersMode(ExportHeadersFootersMode.PER_SECTION); // 导出每节的页眉页脚

        return saveOptions;
    }
}
