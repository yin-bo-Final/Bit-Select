# 本地中间件

业务 Java 服务在 Windows / IDEA 中运行，中间件在 WSL Ubuntu 的 Docker 中运行。Compose 项目名为 `bit-select`，卷名自动带 `bit-select_` 前缀；不会复用或修改其他项目的容器及卷。

```powershell
pwsh ./scripts/Start-LocalInfrastructure.ps1 -Profile core
pwsh ./scripts/Start-LocalInfrastructure.ps1 -Profile ai
pwsh ./scripts/Start-LocalInfrastructure.ps1 -Profile full
pwsh ./scripts/Test-LocalInfrastructure.ps1 -Profile full
```

- `core`：MySQL、Redis、Nacos、RocketMQ。
- `ai`：在 core 基础上增加 RustFS、etcd、Milvus、Neo4j、Tika。
- `full`：在 ai 基础上增加 Sentinel 控制台。

初始化脚本仅在 `infra/.env` 不存在时生成随机凭证；重复执行不会轮换已有数据库密码。所有配置只能用于绑定本地回环地址的开发环境。Nacos 与 Milvus 开发配置未开启账号鉴权，不得改为公网监听；生产部署需要独立安全配置。

## 版本清单

以下为 `compose.yml` 的明确版本标签与本机已运行版本，核验日期为 2026-09-27。Docker Engine 29.4.1、Docker Compose 5.1.3 为本机运行时版本；仓库不自动安装或替换 Docker。

| Compose 服务 | 镜像与版本 | 作用 |
| --- | --- | --- |
| mysql | `mysql:8.4.8` | 业务事务、知识原文及记忆元数据 |
| redis | `redis:7.4.5-alpine` | 七天会话、网关限流及会话锁 |
| nacos | `nacos/nacos-server:v2.5.1` | 服务发现及持久化网关流控规则 |
| sentinel | `bit-select/sentinel-dashboard:1.8.10` | Sentinel 控制台，本仓库构建 |
| rocketmq-namesrv、rocketmq-broker、rocketmq-init | `apache/rocketmq:5.3.3` | 业务事件、异步记忆与 Topic 初始化 |
| rustfs | `rustfs/rustfs:1.0.0` | 文档原件与 Milvus 对象存储 |
| etcd | `quay.io/coreos/etcd:v3.5.25` | Milvus 元数据 |
| milvus | `milvusdb/milvus:v2.6.24` | 知识与记忆向量检索 |
| neo4j | `neo4j:5.26.12-community` | 用户、实体与记忆关系 |
| tika | `apache/tika:3.2.3.0-full` | 手册内容解析 |
| storage-permissions、object-storage-init | `python:3.12.10-alpine` | 新卷权限与对象桶初始化 |

Sentinel 的基础镜像为 `eclipse-temurin:21.0.8_9-jre-jammy`，官方 1.8.10 JAR 下载使用 SHA-256 校验。所有 14 个服务均有明确镜像版本，没有 `latest` 或无标签镜像；基础镜像使用版本标签，尚未逐一按镜像 digest 固定，因此不宣称镜像来源永久不可变。

本次只读验收：11 个长期服务全部 healthy，3 个初始化服务全部正常退出；实际已发布端口均为 `127.0.0.1`，etcd 没有宿主机映射，Windows 默认服务端口连接通过。`check-compose.py` 同时通过版本、监听、内存与健康检查配置校验。本轮没有重启、重建或删除任何容器和数据。

这些是检查时的状态，不是全周期零故障承诺。RocketMQ Broker 在本次检查前留有 10 次累计重启记录，最后启动时间为 2026-09-27 07:27:35 UTC，本次检查为 healthy、未报告 OOMKilled；其余容器累计重启为 0。

## 端口与数据

| 服务 | Windows 地址 | 凭证来源 |
| --- | --- | --- |
| MySQL | `127.0.0.1:13306`，库/用户 `bit_select` | `MYSQL_PASSWORD` |
| Redis | `127.0.0.1:16379` | `REDIS_PASSWORD` |
| Nacos | <http://localhost:18848/nacos>，gRPC `19848` | 本地无鉴权 |
| Sentinel | <http://localhost:18880> | `SENTINEL_USERNAME` / `SENTINEL_PASSWORD` |
| RocketMQ Namesrv / Broker | `19876` / `20911` | 仅本地 |
| RocketMQ VIP | `20909` | 仅本地 |
| RustFS API / 控制台 | <http://localhost:19000> / <http://localhost:19001> | `RUSTFS_ACCESS_KEY` / `RUSTFS_SECRET_KEY` |
| Milvus / 健康检查 | `19530` / <http://localhost:19091/healthz> | 本地无鉴权 |
| Neo4j / Bolt | <http://localhost:17474> / `17687` | `neo4j` / `NEO4J_PASSWORD` |
| Tika | <http://localhost:19998/version> | 仅本地 |
| etcd | 仅 Compose 网络内部 `2379` | 不映射宿主机 |

容器绑定 `127.0.0.1`，IDEA 使用 Windows 的 `localhost` 转发连接。Nacos 客户端 gRPC 端口固定为服务端 HTTP 端口 +1000；调整端口时必须同时调整。RocketMQ 使用主机 `127.0.0.1:20911` 注册，适配 Windows 本地应用；如调整 Broker 端口，需要同步修改 `rocketmq/broker.conf`。不能将这一注册地址用于跨主机生产集群。

RustFS 通过 AWS SigV4 / path-style S3 提供对象存储。初始化创建 `bit-select-documents` 和 `bit-select-milvus`；Milvus 使用独立桶，文档系统只操作文档桶。Milvus 的队列固定为 RocksMQ，避免切换默认 WAL 实现导致数据不兼容；它与业务 RocketMQ 无关。

机器实际为 16 GB 物理内存，WSL 当前可用上限约 7.4 GiB。本配置限制 JVM 与各容器内存，适合 200 个产品演示数据；这不代表达到 Milvus 官方建议的独立生产内存配置。建议关闭不必要应用后按 core → ai 分阶段启动，观察 `docker stats`，不自动修改 `.wslconfig`。所有数据库卷保存在 WSL Linux 文件系统，避免 Windows 挂载盘数据库性能问题。

停止使用 `pwsh ./scripts/Stop-LocalInfrastructure.ps1`，只停止本项目，保留数据。不要使用 `down -v`、`docker system prune --volumes` 或手工删除数据库卷。备份需要同时保存 MySQL、RustFS、Milvus/etcd、Neo4j 的一致快照。

官方配置依据：[RustFS 容器部署](https://docs.rustfs.com/en/installation/container)、[Milvus Compose](https://milvus.io/docs/v2.6.x/install_standalone-docker-compose.md)、[Nacos 2.5 单机部署](https://nacos.io/en/docs/v2.5/manual/admin/deployment/deployment-standalone/)、[Sentinel Dashboard](https://github.com/alibaba/Sentinel/wiki/Dashboard)。镜像全部固定版本；升级前备份并重新运行集成验证。
