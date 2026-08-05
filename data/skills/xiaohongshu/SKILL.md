---
name: xiaohongshu
agent: CHAT
description: 小红书账号登录、搜索笔记、读取笔记详情/评论、用户主页、评论/回复、点赞/取消点赞、收藏/取消收藏、发布图文/视频。当用户提到小红书、笔记、博主、作者主页、点赞、评论、收藏、登录账号、扫码登录时触发。
allowed-categories: xiaohongshu, skill
priority: 10
---

# 小红书工具执行指南

## 核心原则

涉及小红书真实账号动作时，必须实际调用对应工具或 MCP 接口；不能只根据大模型文字回复声称已完成。

## 意图到工具

| 用户意图 | 工具 |
| --- | --- |
| 登录、登陆账号、扫码登录、获取二维码 | `xiaohongshu_request_login_qrcode` |
| 查看登录状态、当前账号、发到哪个账号 | `xiaohongshu_check_login_status` |
| 搜索笔记、找小红书内容 | `xiaohongshu_search_notes` |
| 获取笔记详情、互动数据、评论列表、子评论 | `xiaohongshu_get_note_detail_with_comments` |
| 获取博主/作者/用户主页、粉丝数、作品列表 | `xiaohongshu_get_user_profile` |
| 给笔记发表评论 | `xiaohongshu_comment_note` |
| 回复某条评论、回复某个用户 | `xiaohongshu_reply_comment` |
| 给作品/笔记点赞 | `xiaohongshu_like_note` |
| 取消作品/笔记点赞 | `xiaohongshu_unlike_note` |
| 收藏作品/笔记 | `xiaohongshu_favorite_note` |
| 取消收藏作品/笔记 | `xiaohongshu_unfavorite_note` |
| 发布图文笔记 | `xiaohongshu_publish_image_note` |
| 发布视频笔记 | `xiaohongshu_publish_video_note` |

## 参数规则

- 对笔记执行评论、点赞、收藏等动作时，需要 `feed_id` 和 `xsec_token`。
- 用户说“第 N 条”时，优先从最近一次搜索结果或用户主页作品列表中取得对应笔记的 `feed_id` 和 `xsec_token`。
- 如果上下文里没有可定位的搜索结果、主页结果、笔记链接或参数，先让用户搜索、提供链接，或提供 `feed_id` 和 `xsec_token`。
- 对特定作者的作品操作时，先获取作者主页，再从作品列表中选择目标笔记，最后用该笔记参数执行动作。
- 回复评论需要 `feed_id`、`xsec_token`，并用 `comment_id` 或 `user_id` 精准定位目标评论；缺少评论定位信息时，先获取评论列表。

## 成功与失败

- 工具返回成功后，再回复用户操作已完成，并简要说明目标笔记/作者/动作。
- 工具返回失败、未登录、权限异常、风控或参数缺失时，明确告诉用户失败原因和下一步。
- 如果真实工具没有被调用，不得说“已点赞”“已评论”“已收藏”“已发布”。
