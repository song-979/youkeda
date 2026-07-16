# wechatproject

This project now uses the open source Java SDK from `lith0924/wechat-ilink-sdk-java`
to connect a WeChat iLink bot and send a fixed reply.

## Current behavior

- Starts a Spring Boot app
- Logs in to iLink by printing QR-code content
- Polls messages with `getUpdates()`
- Replies to any text message with a fixed built-in sentence

## SDK dependency

```xml
<dependency>
    <groupId>io.github.lith0924</groupId>
    <artifactId>wechat-ilink-sdk</artifactId>
    <version>2.3.3</version>
</dependency>
```

Source repository:
https://github.com/lith0924/wechat-ilink-sdk-java

## How to run

1. Make sure Maven is available on your machine.
2. In `src/main/resources/application.properties`, change:

```properties
ilink.bot.enabled=true
```

3. Start the application.
4. Render the printed login content as a QR code and scan it with WeChat.
5. Send the bot a text message first.
6. The bot will reply with the configured fixed text.
7. If you want a Chinese fixed reply, edit `ilink.bot.fixed-reply` directly.

## Config items

```properties
server.port=8080
ilink.bot.enabled=false
ilink.bot.fixed-reply=Hello, this is a fixed reply from the iLink test bot.
ilink.bot.poll-interval-ms=1000
ilink.bot.typing-delay-ms=800
```

## Important note

The SDK caches the latest `contextToken` after `getUpdates()` receives a message.
So the user must message the bot first before the bot can send a reply successfully.
