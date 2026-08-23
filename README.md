# lengbot 开发环境服务（PostgreSQL / Redis / MinIO）

本目录包含一键启动 / 停止脚本，用于在本地开发时拉起 lengbot 依赖的三个中间件。

> 说明：三个组件均为**绿色免安装版**，以普通用户进程方式运行，**不需要管理员权限**，
> 重启电脑后双击 `start-services.bat` 即可启动。数据全部保存在 `D:\lengbot\infra` 下。

## 目录结构

```
D:\lengbot\infra\
├── postgresql\        PostgreSQL 16.15 二进制（pgsql\bin）
├── pgdata\            PostgreSQL 数据目录（首次启动自动 initdb 初始化）
├── redis\             Redis 5.0.14.1 (Windows)
├── minio\             MinIO 服务端 (minio.exe)
└── minio-data\        MinIO 对象存储数据目录

D:\lengbot\lengbot\lengbot\
├── start-services.bat  一键启动（本文档主角）
├── stop-services.bat   一键停止
└── README.md
```

## 使用方法

1. **启动**：双击 `start-services.bat`。
   - 首次运行会自动初始化 PostgreSQL 数据库集群（只需一次），并自动创建应用所需的 `lengbot` 数据库（已存在则跳过）。
   - 脚本会检测端口，避免重复启动。
   - PostgreSQL / Redis / MinIO 各自在独立窗口中最小化运行，关闭本脚本窗口不影响它们。

2. **停止**：双击 `stop-services.bat`。

## 默认连接信息

| 服务       | 地址                    | 账号 / 密码              | 说明                |
|------------|-------------------------|--------------------------|---------------------|
| PostgreSQL | `localhost:5432`        | `postgres` / `postgres`  | 首次已初始化（密码已对齐 application.yml）|
| Redis      | `localhost:6379`        | 密码 `123456`（库 9）    | 密码已对齐 application.yml |
| MinIO API  | `http://localhost:9000` | `minioadmin` / `minioadmin` | S3 兼容对象存储（已对齐 application.yml）|
| MinIO 控制台 | `http://localhost:9001` | 同上                     | Web 管理界面        |

> 修改账号密码 / 端口：编辑 `start-services.bat` 顶部「路径配置」区即可。

## 版本

- PostgreSQL 16.15（满足 15+ 要求）
- Redis 5.0.14.1（Windows 构建，tporadowski）
- MinIO（官方 Windows 单文件版）

## 故障排查

- **PostgreSQL 启动失败**：查看 `D:\lengbot\infra\pgdata\pg.log`（运行日志）与 `%TEMP%\pg_initdb.log`（初始化日志）。
  常见原因：VC++ 运行库缺失。若报缺少 `VCRUNTIME140.dll` 等，请安装
  [Visual C++ Redistributable](https://learn.microsoft.com/zh-cn/cpp/windows/latest-supported-vc-redist)。
- **端口被占用**：修改 `start-services.bat` 中的端口配置后重启。
