# Word Header/Content/Footer 批量导出

基于 Aspose.Words（24.12）批量扫描目录中的 `.doc/.docx`，并导出：

- `header.html`
- `content.html`
- `footer.html`
- `word.css`
- `images/*`
- `manifest.json`

## 环境要求

- Java 17+
- Maven 3.8+
- `lib/aspose-words-24.12-jdk17.jar`

## 核心行为

- 默认扫描 `--root` 下一级子目录（`--recursive=true` 可递归）
- 每个目录按规则选一个 Word 文件（默认优先 `docx`）
- 拆分逻辑：
  - `header/footer`：取 first section，`First` 非空优先，否则 `Primary`
  - `content`：拼接所有 section 的 body 块级节点（段落/表格）
- 导出 HTML 后处理：只保留可注入 body 片段，并包容器：
  - `<div class="word-header">...</div>`
  - `<div class="word-content">...</div>`
  - `<div class="word-footer">...</div>`
- 图片输出支持两种模式（`--imagesBase64`）：
  - `true`（默认）：图片内嵌为 base64
  - `false`：图片落盘到 `result/images/`
- CSS 汇总输出到 `result/word.css`

## 输出目录结构

每个处理目录会生成：

```text
<dir>/
  result/
    header.html
    content.html
    footer.html
    word.css
    images/
    manifest.json
```

## CLI 参数

- `--root=.` 根目录（也支持位置参数）
- `--recursive=false` 是否递归扫描子目录
- `--docPattern=*.doc,*.docx` 匹配 Word 文件模式
- `--prefer=docx` 候选优先级（`docx|doc`）
- `--acceptRevisions=false` 是否接受修订
- `--updateFields=true` 是否更新字段
- `--imagesBase64=true` 是否将图片内嵌为 base64（`false` 则输出到 `images/`）
- `--overwrite=true` result 已存在时是否覆盖
- `--log=info|debug` 日志级别
- `--failFast=false` 出错是否立即停止

## 快速使用

### 1. 编译

```bash
mvn clean package
```

### 2. 运行（Maven）

```bash
mvn -q exec:java -Dexec.args="--root=./convert --recursive=false --prefer=docx"

# 图片输出到 images/ 目录（不使用 base64）
mvn -q exec:java -Dexec.args="--root=./convert --recursive=false --imagesBase64=false"
```

### 3. 运行（脚本）

```bash
# Windows
convert.bat --root=.\convert --recursive=false

# Linux/Mac
./convert.sh --root=./convert --recursive=false
```

## 说明

- `manifest.json` 会记录 source 文档、生成时间、输出路径和 warnings
- 默认会跳过没有 Word 文件的目录并记录 warning
- 如果目录中有多个 Word 文件，会按固定规则自动选中并记录 warning
