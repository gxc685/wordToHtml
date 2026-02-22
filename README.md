# Word 转 HTML 转换器

使用 Aspose.Words for Java 21.6 将 Word 文档转换为 HTML。

## 环境要求

- Java 8 或更高版本
- Maven 3.6 或更高版本

## 项目结构

```
.
├── pom.xml                           # Maven 配置文件
├── convert.bat                       # Windows 运行脚本
├── convert.sh                        # Linux/Mac 运行脚本
├── README.md                         # 说明文档
├── lib/                              # 存放本地 JAR 文件
│   └── aspose-words-21.6-jdk16.jar   # Aspose.Words 核心库
└── src/
    └── main/
        └── java/
            └── com/
                └── example/
                    └── WordToHtmlConverter.java  # 主程序
```

## 所需 JAR 文件

从 Aspose 下载以下文件：

| 文件名 | 说明 | 必需 |
|--------|------|------|
| `aspose-words-21.6-jdk16.jar` | **核心运行时库** | ✅ 必须 |
| `aspose-words-21.6-javadoc.jar` | Java 文档 | ❌ 可选 |
| `aspose-words-21.6-shaping-harfbuzz-plugin.jar` | 高级文本塑形插件(阿拉伯语/泰语等) | ❌ 可选 |

### JAR 版本选择

- **`jdk16.jar`** - 兼容 JDK 6/7/8/11+，推荐选用 ✅
- `jdk17.jar` - 需要 JDK 7+，功能更新但兼容性稍差

## 快速开始

### 1. 放置 JAR 文件

将下载的 `aspose-words-21.6-jdk16.jar` 复制到 `lib/` 目录：

```bash
lib/
└── aspose-words-21.6-jdk16.jar
```

### 1. 编译项目

```bash
mvn clean compile
```

### 2. 打包为可执行 JAR

```bash
mvn clean package
```

生成的 JAR 文件位于 `target/word-to-html-1.0-SNAPSHOT-jar-with-dependencies.jar`

### 3. 运行转换

#### 方式一: 使用 Maven exec 插件

```bash
# 转换单个文件
mvn exec:java -Dexec.args="input.docx"

# 指定输出路径
mvn exec:java -Dexec.args="input.docx output.html"
```

#### 方式二: 使用脚本

```bash
# Windows
covert.bat input.docx output.html

# Linux/Mac
./convert.sh input.docx output.html
```

#### 方式三: 直接运行 JAR

```bash
# 基本用法
java -jar target/word-to-html-1.0-SNAPSHOT-jar-with-dependencies.jar input.docx

# 指定输出路径
java -jar target/word-to-html-1.0-SNAPSHOT-jar-with-dependencies.jar input.docx output.html
```

## 功能特性

- ✅ 支持 .doc, .docx, .rtf, .odt 等 Word 格式
- ✅ 图片自动转为 Base64 嵌入 HTML
- ✅ 保留文档格式和样式 (CSS 嵌入)
- ✅ 支持页眉页脚导出
- ✅ 支持页边距设置

## 注意事项

1. Aspose.Words 需要有效的许可证才能去除水印和评估限制
2. 大文件转换可能需要较长时间
3. 某些复杂格式可能在 HTML 中不能完全保留

## 添加许可证 (可选)

如果你有 Aspose.Words 许可证，可以将许可证文件添加到项目中：

```java
License license = new License();
license.setLicense("Aspose.Words.lic");
```
