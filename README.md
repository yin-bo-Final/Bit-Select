# 比特严选 · Bit Select

面向电子产品与生活用品的演示电商平台，以及具备商品知识检索、用户记忆和交易工具的 AI 导购助手。产品范围、架构与验收口径见 [项目书](docs/比特严选项目书-v0.1-评审稿.md)。

采用 Next.js、TypeScript、Ant Design、Java 21、Spring Boot 3、Spring Cloud Gateway / Sentinel、Dubbo、Nacos、Spring AI Alibaba、MySQL、Redis、RocketMQ、Milvus、Neo4j、Tika、RustFS 与 Docker。业务代码在 Windows / IDEA 运行，全部中间件在 WSL Ubuntu 的 Docker 中运行。

## 运行环境

- Windows 11、WSL 2 Ubuntu、Docker Engine 与 Docker Compose。
- JDK 21、Maven 3.9+、Node.js 24、PowerShell 7、Python 3.12+。
- 本机 16 GB 内存、WSL 当前上限约 7.4 GiB；使用分阶段启动和开发容量配置，不修改用户的 WSL 全局限制。

从仓库根目录执行：

```powershell
# 首次自动生成 infra/.env 的随机密码；已有配置会保留
pwsh ./scripts/Start-LocalInfrastructure.ps1 -Profile core
# 增加 AI 依赖
pwsh ./scripts/Start-LocalInfrastructure.ps1 -Profile ai
# 再增加 Sentinel 控制台
pwsh ./scripts/Start-LocalInfrastructure.ps1 -Profile full
pwsh ./scripts/Test-LocalInfrastructure.ps1 -Profile full
```

详细端口、数据卷、资源限制和停止方式见 [中间件说明](infra/README.md)。所有容器采用独立 `bit-select` 项目和持久卷，默认仅绑定本地回环地址。启动脚本会保留一个隐藏 WSL 进程，避免仅有 systemd 服务时 WSL 自动退出；停止脚本会关闭本项目保活进程。

## 私密配置

中间件密码与本地管理员初始密码保存在 `infra/.env`。硅基流动参数放在根目录 `.env.local`，不要写入源码、PR、提交或截图：

```dotenv
SILICONFLOW_API_KEY=填写你自己的密钥
SILICONFLOW_BASE_URL=https://api.siliconflow.cn/v1
SILICONFLOW_CHAT_MODEL=Qwen/Qwen3-30B-A3B-Instruct-2507
SILICONFLOW_EMBEDDING_MODEL=Qwen/Qwen3-Embedding-4B
SILICONFLOW_RERANK_MODEL=Qwen/Qwen3-Reranker-4B
```

在启动应用的 PowerShell 中执行 `. ./scripts/Use-LocalEnvironment.ps1`，只给当前进程加载配置。若从 IDEA 启动，将这些变量配置到运行环境，工作目录设置为仓库根目录；不把密钥写入共享的 IDEA 配置。没有提供有效 API Key 时，涉及模型的接口应明确报告未配置。

## 验证与协作

```powershell
. ./scripts/Use-LocalEnvironment.ps1
python scripts/test-ai-infrastructure.py
```

AI 中间件验证真实执行 RustFS 签名上传/下载、Tika 中文解析、Neo4j 事务、Milvus 向量插入与余弦检索，不调用付费 AI 模型；只清理由脚本自己创建的临时资源。后续业务服务就绪后，`python scripts/smoke-test.py` 验证真实 HTTP 交易链路，测试用户和审计记录保留。

每一项较大功能从最新 `main` 新建分支，提交并推送到 GitHub，经中文 PR 与 CI 验证通过后再合并。PR 和 commit 标题统一格式：`feat:add product catalog/新增商品目录` 或 `fix:fix a bug/修改一个错误`。CI 在组件实际加入仓库后执行对应的 Java 测试、前端测试与构建、Compose 校验和真实交易集成；不会将尚未提交的组件当作已经通过验证。

开发环境采用单实例数据库与中间件，用于项目演示和功能验证；生产集群、真实支付、物流、短信注册及真实商品安全认证均不属于本演示部署的能力范围。
