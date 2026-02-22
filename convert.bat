@echo off
chcp 65001 >nul
REM Word 转 HTML 转换脚本 (Windows)
REM 用法: convert.bat <Word文件路径> [输出HTML路径]

if "%~1"=="" (
    echo 用法: convert.bat ^<Word文件路径^> [输出HTML路径]
    echo 示例:
    echo   convert.bat document.docx
    echo   convert.bat document.docx output.html
    exit /b 1
)

REM 检查 Maven 是否已安装
where mvn >nul 2>&1
if %errorlevel% neq 0 (
    echo 错误: 未找到 Maven，请先安装 Maven 并添加到 PATH
    exit /b 1
)

REM 运行 Maven exec 插件执行转换
mvn exec:java -Dexec.args=""%1" "%2""
