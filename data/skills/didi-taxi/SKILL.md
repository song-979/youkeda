---
name: didi-taxi
description: 滴滴打车/叫车/出行。当用户提到打车、叫车、出行、滴滴、叫个车、怎么去（需要乘车前往）时触发。
allowed-categories: didi_taxi, map_navigation, information
priority: 10
---

# 滴滴打车

## 首选工具
- **query_amap_place_ids**：解析起终点名称，获取坐标（经纬度）。坐标是滴滴工具的必填参数。
- **didi_taxi_estimate**：价格预估，返回可用车型和预估价格。
- **didi_taxi_create_order**：创建打车订单（真实下单，测试环境为模拟订单）。
- **didi_taxi_query_order**：查询订单状态和司机信息（车牌、电话、位置、预计到达时间）。
- **didi_taxi_cancel_order**：取消订单。
- **didi_taxi_get_driver_location**：获取司机实时位置坐标。
- **didi_taxi_generate_ride_app_link**：生成跳转滴滴App的行程链接（让用户在App内自行下单）。
- **get_current_datetime**：获取当前时间（如果需要判断预估是否过期）。

## 工具使用策略
1. **优先调用滴滴 + 高德系列工具**：官方API数据最准确。
2. **失败降级**：如果滴滴工具调用失败、超时或返回错误，改用 web_search / brave_web_search 搜索打车参考信息（如大概价格范围、可选平台等），并告知用户数据来源为网络搜索，建议直接使用滴滴App叫车。

---

## 场景一：用户想打车（价格预估）

### 执行流程
1. **确认起终点**：明确出发地和目的地名称（如"北京西站"、"国贸"）。如果用户没提供，反问补充。
2. **解析坐标**：调用 `query_amap_place_ids` 分别获取出发地和目的地的坐标（lng, lat）。
3. **价格预估**：拿到坐标后调用 `didi_taxi_estimate`，传入 fromLng/fromLat/fromName 和 toLng/toLat/toName。
4. **展示车型**：将预估结果中的车型列表和价格整理成易读格式展示给用户，询问用户选择哪个车型。
5. **等待用户确认**：⚠️ 绝对禁止在调用 didi_taxi_estimate 的同一轮工具调用链中继续调用 didi_taxi_create_order。必须先展示车型让用户选择，等下一轮对话中用户明确说"选XX车型"后再下单。

### 展示格式示例
返回结果后整理为：
```
【滴滴打车】价格预估
路线：XX → XX

可选车型：
- 特惠快车：约XX元
- 快车：约XX元
- 专车：约XX元
...

请选择您想要的车型，回复"选XX"即可下单。
```

---

## 场景二：用户确认车型后下单

### 执行流程
1. 用户明确说了选择（如"选特惠快车"、"快车"、"第一个"、"下单"）。
2. 如果估價已超过5分钟，告知用户预估已过期，建议重新估價。
3. 调用 `didi_taxi_create_order`，传入用户选择的 productCategory。
4. 展示订单结果，包含订单ID（orderId），提醒用户保存用于后续查询。

---

## 场景三：查询订单 / 取消订单 / 查看司机位置

### 执行流程
1. 如果有订单ID，直接用对应工具查询。
2. 如果没有订单ID：
   - 查询订单：调用 `didi_taxi_query_order` 不传 orderId，查询当前未完成订单。
   - 取消订单：先调用 `didi_taxi_query_order` 查询当前订单，拿到 orderId 后再取消。
   - 司机位置：先拿到 orderId，再调用 `didi_taxi_get_driver_location`。
3. 查询订单建议轮询间隔：匹配中30秒，已接单30秒，司机已到达60秒，行程中60秒。

---

## 场景四：生成App行程链接

适用于用户想自己在滴滴App内下单的场景。调用 `query_amap_place_ids` 获取坐标后，调用 `didi_taxi_generate_ride_app_link` 生成跳转链接。

⚠️ `didi_taxi_generate_ride_app_link` 和 `didi_taxi_create_order` 是二选一的关系，不要同时调用两个。

---

## 严格禁止的行为
1. ❌ **禁止在同一轮对话中连续调用 estimate + create_order**：系统底层有硬限制，会被直接拒绝。
2. ❌ **禁止跳过估價直接下单**：必须先估價，拿到车型列表并等用户确认后才能下单。
3. ❌ **禁止替用户选择车型**：必须展示所有车型让用户自己选，不能默认帮用户选第一个。
4. ❌ **禁止在用户没明确确认车型的情况下调用 create_order**：即使估價结果还在5分钟有效期内，也需要用户明确说"选XX"。
5. ❌ **禁止用 web_search 替代滴滴工具**（除非滴滴工具调用失败作为降级方案）。
