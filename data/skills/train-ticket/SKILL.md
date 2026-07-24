---
name: train-ticket
description: 查询火车票、高铁票、动车票余票信息。当用户提到车票、火车、高铁、动车、12306、车次时触发。
allowed-categories: information
priority: 10
---

# 火车票查询

## 首选工具
- **search_train_tickets**: 查询12306官方余票信息，数据最准确、实时。

## 工具使用策略
1. **优先调用 search_train_tickets**：12306 官方数据最准确、实时。
2. **失败降级**：如果 search_train_tickets 调用失败、超时或返回错误，则改用 web_search / brave_web_search 搜索车票信息（如班次、票价参考、替代出行方案等），并告知用户数据来源为网络搜索，建议前往 12306 官网确认。

## 执行流程
1. 确认用户是否提供了出发地、目的地、日期这三个要素
2. 如有缺失，友好地反问用户补充缺失的信息
3. 参数齐全后，立即调用 search_train_tickets
4. 将查询结果整理成清晰易读的格式回复用户
5. 如果用户问"xx到xx有没有票"，默认查询今天
