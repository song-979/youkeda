# Meituan Paotui Script - Youkeda Test Build

Source: official `meituan-paotui.zip`, `paotui.js` 1.0.5.

This test build contains two compatibility fixes:

1. Decode `base64:` values for `--sender`, `--recipient`, and `--goods` before the official argument parser runs. This prevents Windows from removing JSON quotes.
2. Keep `purchaseGoodDetail`, `bizTypeSceneTag`, and `businessTypeTag` in the `submit_order` payload so submission uses the same business parameters as preview.

The Java adapter in this project already Base64-encodes the three JSON arguments. Normal, non-Base64 JSON arguments remain supported by the script.

Local test configuration:

```properties
meituan.paotui.script-path=D:/youkeda/tools/meituan-paotui/paotui.js
meituan.paotui.node-command=C:/Program Files/nodejs/node.exe
```

Passport authorization is still required. No Passport token or authorization cache is included in this directory.

Before a real order is submitted, verify that the fee preview contains the expected store, recipient address, item, and fee. Submission must only run after the user explicitly confirms the order.

## Safe submit request capture

To inspect the final serialized `submit_order` request without creating an order, preload `capture-submit-request.js` when running `paotui.js`. The preview request is still sent normally, but the submit request is intercepted locally and replaced with diagnostic code `19998`.

The captured request is written to stderr with the prefix `YOUKEDA_SUBMIT_CAPTURE`. Authorization values and phone numbers are redacted. Do not use this preloader for normal order submission.

### Capture result (2026-07-23)

The transport-level capture confirmed that both requests contain the same complete product data:

- `goods.goodsName`, `goods.goodsWeight`, `goods.goodTypes`, and `goods.goodTypeNames`
- `purchaseGoodDetail`
- `businessType`, `bizTypeSceneTag`, and `tipFee`

The submit request additionally contains the preview-generated `orderToken`, `deliveryFee`, `businessTypeTag`, and `remark`. Therefore error `10001` is not caused by the Java adapter, Windows argument quoting, Base64 decoding, or use of the wrong script path. The current backend is rejecting a request that matches the parameter contract bundled with official Skill version 1.0.5; the remaining issue requires the current `submit_order` server schema or Meituan support confirmation.
