# Chat 图片使用阿里云 OSS

聊天中的 URL 图片、粘贴图片和上传图片可以统一转存到用户自己的私有
阿里云 OSS Bucket：

- 数据库只保存附件 ID、Bucket、objectKey、MIME、大小、哈希等元数据
- 页面和多模态模型读取图片时，由后端生成短时有效的签名 GET URL
- OSS 默认关闭，不影响仅文本部署启动

## RAM 最小权限

创建只供 TAgent 程序使用的 RAM 用户，并将下面策略中的
`your-bucket` 和 `chat-images` 替换成实际 Bucket 与 Object 前缀：

```json
{
  "Version": "1",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "oss:PutObject",
        "oss:GetObject",
        "oss:DeleteObject"
      ],
      "Resource": [
        "acs:oss:*:*:your-bucket/chat-images/*"
      ]
    }
  ]
}
```

该策略不能列举 Bucket、修改 Bucket 配置、修改 ACL 或删除 Bucket。

## 环境变量

SDK 从进程环境读取凭证，不要把 AccessKey 写进 YAML、SQL、Git 或前端：

```powershell
$env:OSS_ACCESS_KEY_ID = "替换为RAM用户的AccessKey ID"
$env:OSS_ACCESS_KEY_SECRET = "替换为RAM用户的AccessKey Secret"
$env:TAGENT_OSS_ENABLED = "true"
$env:TAGENT_OSS_REGION = "cn-beijing"
$env:TAGENT_OSS_ENDPOINT = "https://oss-cn-beijing.aliyuncs.com"
$env:TAGENT_OSS_BUCKET = "your-bucket"
$env:TAGENT_OSS_OBJECT_PREFIX = "chat-images"
$env:TAGENT_OSS_SIGNED_URL_TTL_SECONDS = "1800"
```

设置后重启 IDEA、终端和应用进程。若从 IDEA 启动，也可以在 Run/Debug
Configuration 的 Environment variables 中配置。

`TAGENT_OSS_MIGRATE_LEGACY_ON_STARTUP` 仅用于一次性迁移旧数据库图片，平时应保持
`false`。
