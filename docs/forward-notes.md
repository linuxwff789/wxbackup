# 微信消息 → 本地笔记（wxnotes）

把微信聊天里手动挑选的消息，通过 Xposed 插件转发到独立的「微信笔记」App，落成本地知识库：
笔记时间线、全文搜索、标签、Markdown / Obsidian 导出。全程离线，不经过任何服务器。

## 组成

| 模块 | 包名 | 作用 |
|------|------|------|
| `:xposed` | `com.nous.wxhook.xposed` | 运行在微信进程：长按菜单注入「保存到笔记」，读取 MsgInfo，打包转发 |
| `:notes` | `com.nous.wxnotes` | 接收端：Room 存笔记/消息/标签，Compose UI，Markdown 导出 |
| `:shared` | `com.nous.wxshared` | 双端共用：转发协议 `ForwardProtocol` + 消息 XML 解析 `WxMessageParser` |

## 使用

1. LSPosed 启用 `com.nous.wxhook.xposed`，作用域勾选「微信」，重启微信
2. 微信聊天里长按一条消息 →「保存到笔记」
3. 多选若干条 →「保存所选到笔记」（批量）
4. 打开「微信笔记」App 查看、搜索、打标签、导出

同一会话在时间窗口内（默认 30 分钟，可设置）的多次保存会自动追加到同一篇笔记。

## 转发协议（第三方 App 接入）

显式广播：

```
action : com.nous.wxnotes.action.RECEIVE_BATCH
extra  : batch_json  (String, 完整 JSON)
extra  : source_package (String)
```

单批最多 100 条（`ForwardProtocol.MAX_MESSAGES_PER_BATCH`），避免 Binder 1MB 限制。

同时会尽力写一份 `Download/wxfwd/outbox/<batchId>.json`，供轮询式接入。

### JSON 结构

```json
{
  "v": 1,
  "batchId": "uuid",
  "sentAt": 1763000000000,
  "pluginVersion": "1.0.0",
  "noteTitle": "",
  "conversation": {
    "talker": "123456@chatroom",
    "title": "群名或 wxid",
    "isGroup": true,
    "memberCount": 0
  },
  "messages": [
    {
      "msgId": 123,
      "msgSvrId": 456,
      "type": 1,
      "subType": 0,
      "createTime": 1763000000,
      "isSend": false,
      "sender": "wxid_xxx",
      "senderName": "",
      "text": "解析后的展示文本",
      "rawXml": "<msg>...</msg>",
      "mediaKind": "image|voice|video|file|emoji|link|none",
      "localPath": "http://127.0.0.1:PORT/m/TOKEN/...",
      "fileName": "xxx.jpg",
      "fileSize": 12345,
      "durationMs": 3000,
      "thumbPath": "",
      "dedupeKey": "..."
    }
  ]
}
```

要点：

- `createTime` 是微信的**秒级**时间戳
- `text` 已经过解析（图片 → `[图片]`、链接 → `[链接] 标题`、引用 → `[引用 xxx] ...`），可直接用于展示/搜索
- `rawXml` 是原始 content，可离线重放 `WxMessageParser` 得到更多字段
- 媒体不内嵌：插件在微信进程内起一个只绑定 `127.0.0.1` 的 HTTP 服务（随机端口 + 随机 token），`localPath` 就是下载地址；接收方应立即下载（微信进程存活期间有效）
- `dedupeKey` 用于去重：优先 `msgSvrId`，退化为 `msgId`

## 兼容性

- 微信 **8.0.79**（代码 3200）实测锚点：
  - 消息类：`com.tencent.mm.storage.e9`（字段 `field_msgId/field_talker/field_content/field_type/field_createTime/field_isSend/field_imgPath`，字段在父类）
  - ChattingContext：`el5.d`
  - 长按菜单构建：`com.tencent.mm.ui.chatting.viewitems.*.S(h4 menu, View itemView, d ctx)`
  - 菜单对象：`gl5.h4`（实现 `android.view.ContextMenu`）
  - 菜单点击：`onMMMenuItemSelected(MenuItem,int)`（方法名未混淆）
- 其它版本通过 `onCreateMMMenu` / `onMMMenuItemSelected` 方法名 + `field_*` 字段做兼容；探针日志（`[wxhook:Probe]`）可直接定位新版本锚点

## 已知限制

- 群聊消息发送者昵称：插件只拿到 wxid（群消息前缀），昵称需查联系人库，当前留空
- 微信语音是 SILK 编码，系统播放器可能无法直接播放（界面已提示，后续接 SilkCodec 转码）
- 多选批量依赖微信多选模式的菜单挂点，正在适配
