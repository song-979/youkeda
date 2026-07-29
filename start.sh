#!/bin/bash
set -e

echo "========================================"
echo "  微信 AI 助手 - 启动中..."
echo "========================================"
echo ""

# Check Java
if ! command -v java &> /dev/null; then
    echo "[错误] 未找到 Java，请安装 JDK 21 或更高版本"
    echo "下载地址: https://adoptium.net/"
    exit 1
fi

JAVA_VER=$(java -version 2>&1 | head -1 | cut -d'"' -f2)
echo "Java 版本: $JAVA_VER"

# Check if first run
if [ ! -f "config/application-local.yaml" ]; then
    echo ""
    echo "[首次运行] 请先在浏览器中完成配置"
    echo ""
fi

# Start application
echo ""
echo "正在启动服务..."
echo "配置页面: http://localhost:8080/setup"
echo "按 Ctrl+C 停止"
echo "========================================"
echo ""

java -jar wechat-ai.jar --spring.config.additional-location=optional:file:./config/application-local.yaml
