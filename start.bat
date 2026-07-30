@echo off
title 微信 AI 助手
cd /d "%~dp0"

echo ========================================
echo   微信 AI 助手 - 启动中...
echo ========================================
echo.

:: Auto-detect Java from JAVA_HOME first
if defined JAVA_HOME (
    set "PATH=%JAVA_HOME%\bin;%PATH%"
)
:: Check Java
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未找到 Java，请安装 JDK 21 或更高版本
    echo 下载地址: https://adoptium.net/
    echo 如果已安装 JDK，请设置 JAVA_HOME 环境变量指向 JDK 安装目录
    pause
    exit /b 1
)

:: Check Java version
for /f "tokens=3" %%i in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set JAVA_VER=%%i
)
echo Java 版本: %JAVA_VER%

:: Check if this is first run
if not exist "config\application-local.yaml" (
    echo.
    echo [首次运行] 请先在浏览器中完成配置
    echo.
)

:: Start application
echo.
echo 正在启动服务...
echo 配置页面: http://localhost:8080/setup
echo 按 Ctrl+C 停止
echo ========================================
echo.

java -jar wechat-ai.jar --spring.config.additional-location=optional:file:./config/application-local.yaml

pause
