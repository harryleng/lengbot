# model_provider.api_key 加密落库 · 上线说明

> 关联改动：`lengbot-common/util/SecretCipher.java`（新增）、`lengbot-ai/service/impl/ModelProviderServiceImpl.java`（加解密出口）。
> 不改动任何 `application.yml`，密钥完全走环境变量。

## 1. 机制

- 写库出口（create / update）：明文 api_key → AES-GCM 加密 → 落库（列类型不变，仍是 `VARCHAR(512)`，密文远小于明文）。
- 读库出口（getById / listPage / listAllActive / listWithModels）：库内密文 → 解密为明文 → 交给调用方。
- 内存与 Redis 缓存中保持**明文**（内网可信存储，不破坏运行时与缓存语义）。
- 接口出参：实体 `api_key` 字段已加 `WRITE_ONLY`，不会序列化到响应体，密文/明文均不外泄。

## 2. 必须配置的环境变量

```
LENGBOT_CIPHER_KEY=<Base64 编码的 32 字节（256 位）AES 密钥>
LENGBOT_CIPHER_KEY_VERSION=v1   # 可选，默认 v1
```

生成密钥示例（本机执行，仅一次）：

```bash
# 输出 32 字节随机数的 Base64
openssl rand -base64 32
```

把输出填入部署环境的 `LENGBOT_CIPHER_KEY`。**未配置则该服务启动后首次对 model_provider 读写会抛 500**（SecretCipher 显式报错，不会静默落明文）。

## 3. 存量数据（legacy 明文）处理

代码已做向后兼容：解密时若字段不含 `:` 分隔符，视为 legacy 明文**直接返回**，不会报错。

- 不强制停机迁移。
- 任意一次 **update**（用户重新填了 api_key）会自动把明文转密文落库。
- 若想一次性全量加密（推荐，避免库里长期裸奔），在配置好 `LENGBOT_CIPHER_KEY` 后执行：

```sql
-- 仅把疑似明文（不含冒号分隔符）的转为密文需要应用层完成，
-- 纯 SQL 无法做 AES-GCM；请用以下脚本思路由后端批量触发，
-- 或临时调用每个 provider 的 update 接口（传入同样的 api_key 明文即可被重新加密）。
-- 注意：update 接口写入前会加密；若传入已是密文则 isCiphertext 跳过，避免二次加密。
```

最简做法：配置密钥后，对每个存量 provider 调一次 `PUT /api/model-providers`（传入其当前明文 key，或后端提供“重加密”管理接口）。**新写入一律密文。**

## 4. 验证

1. 配置 `LENGBOT_CIPHER_KEY` 后启动。
2. 新增一个 provider，填真实 api_key。
3. 直连数据库 `SELECT api_key FROM model_provider WHERE name='...';` → 应是 `xxxx:yyyy` 密文格式（非明文）。
4. 调用聊天/embedding，确认功能正常（运行时拿到解密后的明文 key）。
5. 调 `GET /api/model-providers/{id}` → 响应体里**不应**出现 api_key 字段。
