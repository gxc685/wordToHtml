#!/bin/bash
# Word 转 HTML 转换脚本 (Linux/Mac)
# 用法: ./convert.sh <Word文件路径> [输出HTML路径]

if [ $# -lt 1 ]; then
    echo "用法: ./convert.sh <Word文件路径> [输出HTML路径]"
    echo "示例:"
    echo "  ./convert.sh document.docx"
    echo "  ./convert.sh document.docx output.html"
    exit 1
fi

# 检查 Maven 是否已安装
if ! command -v mvn &> /dev/null; then
    echo "错误: 未找到 Maven，请先安装 Maven 并添加到 PATH"
    exit 1
fi

INPUT_FILE="$1"
OUTPUT_FILE="${2:-}"

# 运行 Maven exec 插件执行转换
if [ -n "$OUTPUT_FILE" ]; then
    mvn exec:java -Dexec.args="\"$INPUT_FILE\" \"$OUTPUT_FILE\""
else
    mvn exec:java -Dexec.args="\"$INPUT_FILE\""
fi
