@echo off
title 微信 AI 助手 - 打包
cd /d "%~dp0"
setlocal

echo ========================================
echo   微信 AI 助手 - 构建分发包
echo ========================================
echo.

:: Auto-detect Java from JAVA_HOME first
if defined JAVA_HOME (
    set "PATH=%JAVA_HOME%\bin;%PATH%"
)

:: 1. Build jar
echo [1/4] 编译构建...
call mvn package -DskipTests -q
if %errorlevel% neq 0 (
    echo [错误] Maven 构建失败
    pause
    exit /b 1
)
echo   完成: wechatproject-0.0.1-SNAPSHOT.jar

:: 2. Create minimal JRE
echo.
echo [2/4] 创建最小 JRE (仅 java.base, java.desktop, java.net.http)...
if exist dist\wechat-ai\jre (
    rd /s /q dist\wechat-ai\jre
)
call jlink --add-modules java.base,java.desktop,java.net.http,jdk.crypto.ec --output dist\wechat-ai\jre --strip-debug --no-man-pages --no-header-files --compress=2 2>&1
if %errorlevel% neq 0 (
    echo [错误] jlink 创建失败，检查 JDK 版本 (需要 JDK 21+)
    pause
    exit /b 1
)
echo   完成

:: 3. Copy files
echo.
echo [3/4] 复制文件...
:: clean dist (keep jre)
if not exist dist\wechat-ai\config mkdir dist\wechat-ai\config
if not exist dist\wechat-ai\data\ilink-resume mkdir dist\wechat-ai\data\ilink-resume
if not exist dist\wechat-ai\data\tool-automation mkdir dist\wechat-ai\data\tool-automation
if not exist dist\wechat-ai\data\skills mkdir dist\wechat-ai\data\skills
xcopy /y /q target\wechatproject-0.0.1-SNAPSHOT.jar dist\wechat-ai\wechat-ai.jar*
xcopy /y /q start.bat dist\wechat-ai\*
xcopy /y /q start.sh dist\wechat-ai\*
:: copy skills
xcopy /y /q /s data\skills dist\wechat-ai\data\skills\* >nul 2>&1
echo   完成

:: 4. Update start scripts to use bundled JRE
echo.
echo [4/4] 生成启动脚本(使用内置 JRE)...

:: Windows script
(
echo @echo off
echo title 微信 AI 助手
echo cd /d "%%~dp0"
echo.
echo echo ========================================
echo echo   微信 AI 助手 - 启动中...
echo echo ========================================
echo echo.
echo.
echo :: Use bundled JRE
echo set JRE_PATH=jre\bin
echo if not exist "%%JRE_PATH%%\java.exe" (
echo     echo [错误] 未找到内置 JRE，请重新下载安装包
echo     pause
echo     exit /b 1
echo )
echo.
echo echo 首次使用请浏览器打开 http://localhost:8080 完成配置
echo echo 按 Ctrl+C 停止
echo echo ========================================
echo echo.
echo.
echo "%%JRE_PATH%%\java.exe" -jar wechat-ai.jar --spring.config.additional-location=optional:file:./config/application-local.yaml
echo pause
) > dist\wechat-ai\start.bat

:: Mac/Linux script
(
echo #!/bin/bash
echo set -e
echo cd "$(dirname "$0")"
echo.
echo echo "========================================"
echo echo "  微信 AI 助手 - 启动中..."
echo echo "========================================"
echo echo ""
echo.
echo JRE_PATH="jre/bin"
echo if [ ! -f "$JRE_PATH/java" ]; then
echo     echo "[错误] 未找到内置 JRE，请重新下载安装包"
echo     exit 1
echo fi
echo.
echo echo "首次使用请浏览器打开 http://localhost:8080 完成配置"
echo echo "按 Ctrl+C 停止"
echo echo "========================================"
echo echo ""
echo.
echo "$JRE_PATH/java" -jar wechat-ai.jar --spring.config.additional-location=optional:file:./config/application-local.yaml
) > dist\wechat-ai\start.sh

echo   完成

:: 5. Summary
echo.
echo ========================================
echo   打包完成！
echo ========================================
echo.
echo 输出目录: dist\wechat-ai\
for %%A in (dist\wechat-ai) do (
    for /f "usebackq tokens=3" %%B in (`du -sh "%%A" 2^>nul ^|^| echo 未知大小`) do echo 总大小: %%B
)
echo.
echo 分发: 将 dist\wechat-ai\ 文件夹压缩为 zip 即可分发给用户
echo 用户: 解压后双击 start.bat (Win) 或 ./start.sh (Mac/Linux)
echo.
pause
