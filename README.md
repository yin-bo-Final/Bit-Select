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

## 启动应用

先启动中间件，再构建业务代码。可以在 IDEA 打开 `backend/pom.xml`，设置项目 SDK 与 Maven JDK 均为 Java 21，执行以下脚本生成不含密钥的本地运行配置：

```powershell
pwsh ./scripts/New-IdeaRunConfigurations.ps1
```

四个运行入口为 `CatalogApplication`、`CommerceApplication`、`AiApplication` 和 `GatewayApplication`。将 `infra/.env` 与根目录 `.env.local` 的变量填入各自的**本地**运行环境，或让全新打开的 IDEA 进程继承已加载这些变量的终端环境；已经运行的 IDEA 不会自动获得另一个终端的新环境变量。生成的 `.idea` 文件被 Git 忽略，不会包含 API Key。

也可直接通过 Windows 后台进程运行同一套 Java 21 应用：

```powershell
# 初次安装前端依赖
npm --prefix web ci
# 构建和测试后端，隐藏启动四个 Java 进程与前端
pwsh ./scripts/Start-Application.ps1 -Build -Frontend
# 仅启动商城，不启动 AI
pwsh ./scripts/Start-Application.ps1 -SkipAi -Frontend
# 只停止启动脚本登记且通过 PID、启动时间、路径核验的本项目进程
pwsh ./scripts/Stop-Application.ps1
```

启动脚本优先使用 `JAVA_HOME` 中的 Java 21，也能发现当前用户 `.jdks` 下的 JDK 21；可通过 `-JavaHome` 显式指定。运行时将 JAR 复制到 `.local/runtime`，避免 Windows 锁住 Maven `target` 输出影响后续构建。日志位于 `.local/logs`，进程登记位于 `.local/application-processes.json`。若端口被未登记的 IDEA 或其他应用占用，脚本明确停止启动，不会杀掉该进程。前端生产模式先执行 `npm --prefix web run build`，再使用 `-Frontend -FrontendMode production`。

| 入口 | 地址 | 用途 |
| --- | --- | --- |
| 商城 | <http://localhost:3000> | 200 个演示商品、搜索、分类、商品详情 |
| AI 导购 | <http://localhost:3000/assistant> | 对话、商品知识引用、检索与记忆状态 |
| 账户 | <http://localhost:3000/account> | 钱包、地址与账户信息 |
| 订单 | <http://localhost:3000/orders> | 支付、取消、确认收货与售后 |
| 管理后台 | <http://localhost:3000/admin> | 管理员分配余额、商品库存、发货与售后审核 |
| API 网关 | `127.0.0.1:8080` | 前端唯一业务入口 |

管理员账号默认 `admin`，初始密码在本机 `infra/.env` 的 `ADMIN_PASSWORD`，由首次初始化随机生成。普通用户注册后余额为 0，需要管理员分配演示余额。所有金额使用整数分；钱包、库存、订单位于同一交易微服务，以数据库事务和幂等键约束重复操作。登录凭证在 Redis 保存七天，每次认证刷新有效期。接口与订单状态机见 [接口契约](backend/API-CONTRACT.md)。

## 验证与协作

```powershell
. ./scripts/Use-LocalEnvironment.ps1
python scripts/test-ai-infrastructure.py
python scripts/smoke-test.py
python scripts/real-concurrency-test.py
```

AI 中间件验证真实执行 RustFS 签名上传/下载、Tika 中文解析、Neo4j 事务、Milvus 向量插入与余弦检索，不调用付费 AI 模型；只清理由脚本自己创建的临时资源。后续业务服务就绪后，`python scripts/smoke-test.py` 验证真实 HTTP 交易链路，测试用户和审计记录保留。

并发验证创建独立的测试商品和用户，真实检查双用户抢最后一件库存、同余额并发支付不同订单、同一订单并发支付只扣款一次；结束后下架测试商品，保留交易审计记录。离线学习排序的标签来源、无泄漏切分与质量限制见 [LambdaMART 训练说明](data/ranking/README.md)。

每一项较大功能从最新 `main` 新建分支，提交并推送到 GitHub，经中文 PR 与 CI 验证通过后再合并。PR 和 commit 标题统一格式：`feat:add product catalog/新增商品目录` 或 `fix:fix a bug/修改一个错误`。CI 在组件实际加入仓库后执行对应的 Java 测试、前端测试与构建、Compose 校验和真实交易集成；不会将尚未提交的组件当作已经通过验证。

开发环境采用单实例数据库与中间件，用于项目演示和功能验证；生产集群、真实支付、物流、短信注册及真实商品安全认证均不属于本演示部署的能力范围。
