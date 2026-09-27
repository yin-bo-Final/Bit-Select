# 比特严选接口契约

所有接口以 `/api` 开始，JSON 直接返回业务对象（不套 data），错误 `{code,message}`。金额全部为整数分（`priceCents`,`balanceCents`,`totalCents`）。分页 `{items,total,page,pageSize}`，页码从 1 开始。浏览器 `credentials: include`；登录写入 HttpOnly Cookie `bit_session`（SameSite=Lax，开发 HTTP），测试可用 `Authorization: Bearer <token>`，禁止前端把凭证写到 localStorage。

## 身份与会话
- `POST /auth/register` `{username,password,nickname}` → `{user:{id,username,nickname,role,balanceCents}}`，同时登录
- `POST /auth/login` `{username,password}` → 同上
- `GET /auth/me` → 用户对象 `{id,username,nickname,role,balanceCents}`
- `POST /auth/logout` → `{ok:true}`
- 用户角色 `USER|ADMIN`。Redis key `bit:session:<sha256(token)>` 值为用户 ID 十进制字符串，每次认证原子 GETEX 刷新 7 天 TTL；明文 token 从不入库。共用 `contracts` 模块提供 `SessionService` 和 `ApiException`；AI 从会话取 ID，不能信任请求体 userId。

## 商品
- `GET /categories` → `[{id,name,count}]`，id 为字符串 slug
- `GET /products?page=1&pageSize=20&q=&category=&sort=featured|price_asc|price_desc|newest` → 商品分页
- `GET /products/{id}` → 商品对象
- 商品 `{id,name,category,categoryName,description,priceCents,originalPriceCents,imageUrl,stock,sold,featured,enabled,specifications,manualUrl,tags}`；specifications 对象，tags 字符串数组。

## 购物车、订单、钱包
- `GET /cart` → `{items:[{productId,quantity,product}],totalCents}`
- `PUT /cart/items/{productId}` `{quantity}` → `{ok:true}`；数量为0删除
- `DELETE /cart/items/{productId}` → `{ok:true}`
- `POST /orders` `{items:[{productId,quantity}],address:{recipient,phone,detail},idempotencyKey}` → 订单对象。下单锁定库存，15分钟未支付自动取消。
- `GET /orders` → `{items:[订单],total,page,pageSize}`
- `GET /orders/{id}` → 订单对象
- `POST /orders/{id}/pay|cancel|refund|confirm` → 更新后订单对象
- 订单 `{id,orderNo,status,totalCents,createdAt,expiresAt,paidAt,address,items:[{productId,name,imageUrl,priceCents,quantity}],trackingNo}`；状态 `PENDING_PAYMENT|PAID|SHIPPED|COMPLETED|CANCELLED|REFUNDED`。退款只允许已支付未发货，已发货不可取消。支付/取消/退款重复调用同一动作幂等。
- `GET /wallet` → `{balanceCents,ledger:[{id,type,amountCents,balanceAfterCents,referenceId,createdAt}]}`

## 管理后台（需要 ADMIN）
- `GET /admin/users?q=` → `{items:[用户],total,page,pageSize}`
- `POST /admin/users/{id}/credit` `{amountCents,idempotencyKey,reason}` → `{balanceCents}`
- `GET /admin/products` → 商品分页（包括下架）
- `POST /admin/products` → 创建商品，请求为商品字段（不含id/sold/stock），返回商品
- `PUT /admin/products/{id}` → 更新商品，请求字段同上（全量编辑）
- `POST /admin/products/{id}/inventory` `{delta,reason,idempotencyKey}` → `{stock}`；负数调减不能减到0以下
- `GET /admin/orders` → 订单分页（全站）
- `POST /admin/orders/{id}/ship` `{trackingNo}` → 订单
- `GET /admin/overview` → `{userCount,productCount,orderCount,paidRevenueCents,pendingOrderCount}`

默认管理员由环境变量 `ADMIN_USERNAME`（默认admin）、`ADMIN_PASSWORD`（必须配置，至少12位）启动时创建，不接受从普通注册接口指定 role。

## 本地服务
网关8080，交易/账户8081，目录8082，AI8083；前端3000通过Next重写 `/api` 到8080。Nacos127.0.0.1:18848，目录Dubbo20882。MySQL13306库bit_select，Redis16379。

## 地址簿与发货后售后
- `GET /addresses` → `{items:[{id,recipient,phone,detail,isDefault}]}`；`POST /addresses`、`PUT /addresses/{id}` body `{recipient,phone,detail,isDefault}` 返回地址；`DELETE /addresses/{id}` 返回 `{ok:true}`。最多20个，首地址自动默认，删除默认地址自动选剩余最新地址。
- `POST /orders/{id}/refund-request` `{reason}`：SHIPPED/COMPLETED订单创建整单退货申请，返回售后记录；被拒绝可重新提交。
- `GET /refund-requests`：本人售后；`GET /admin/refund-requests`：全站售后，返回 `{items:[{id,orderId,orderNo,userId,totalCents,reason,status,reviewReason,createdAt}]}`，状态 REQUESTED/REJECTED/COMPLETED。
- `POST /admin/refund-requests/{id}/approve` `{reason,goodsReceived:true}`：管理员确认退货已验收，原子退余额、恢复库存、订单变REFUNDED；重复批准不重复退钱。
- `POST /admin/refund-requests/{id}/reject` `{reason}`：拒绝售后，保留订单和余额。

## 内部 RPC 与网关规则
- `CatalogRpc.searchProducts(query,maxPriceCents,limit)` 用商品名实词查询实时目录；仅返回已上架、有库存、预算以内商品，金额始终为分；`maxPriceCents<=0` 表示不限预算，结果最多20条。`300元`应传入`30000`，展示层转换为元，不能直接把分作为元写入模型证据。
- 本地 Dubbo 服务绑定 IPv6 回环 `::1`，注册到 Nacos 的实例地址也是 `::1`；LAN 地址和全网卡监听被拒绝。Dubbo 3.3.5 将所有 `127.*` 地址判为无效，故使用操作系统回环 IPv6。Java 21 所在本机需要启用 IPv6 回环。
- Sentinel 网关限流规则持久化于 Nacos：分组 `BIT_SELECT`、Data ID `bit-gateway-flow-rules.json`。首次启动自动创建，之后订阅配置变化；重启重新读取。通过 Nacos 配置界面发布 JSON 即可动态调整，无需重启网关。需包含 catalog、commerce、ai、inventory 四条规则，每条 `{resource,count,intervalSec}`；count范围1–100000、intervalSec范围1–60。非法发布保留现行规则，Nacos临时不可用保持当前保护并定时重连。
- Sentinel Dashboard 中直接推送的临时修改不写入 Nacos；需要持久保存的调整必须发布到上述 Nacos 配置。

## AI 导购与个人记忆

以下均要求登录，身份只从服务端会话读取。普通用户只能访问自己的会话与记忆。

- `POST /ai/chat` `{conversationId?,message}`：流式 SSE，事件依次包含 `meta {conversationId}`、`phase {label}`、`sources {items}`、多条 `delta {content}`、`done {conversationId}`；失败返回 `error {message}`。同一会话未完成时返回 409，单实例并发满时返回 429，未配置模型返回 503。
- `GET /ai/conversations` → `{items:[{id,title,updatedAt}]}`。
- `GET /ai/conversations/{id}` → `{id,title,messages:[{role,content,createdAt,sources?}],stats:{contextCapacity,summaryThrough,summaryPresent,tokenizer}}`。引用随回答持久保存；`summaryThrough` 为摘要覆盖的数据库消息 ID，不是 token 数。
- `GET /ai/memories` → `{items:[{id,content,source,confidence,updatedAt}],enabled}`。
- `PUT /ai/memory-preference` `{enabled}` → `{ok:true}`。关闭后停用长期记忆的读取和新增，已有记录仍可查看、删除。
- `DELETE /ai/memories/{id}` → `{ok:true}`，仅影响本人记忆；删除通过代际标记防止已排队的旧任务恢复记录。
- `GET /ai/knowledge`（ADMIN）→ `{running,documents:[{id,productId,title,status,chunkCount,errorCode,updatedAt}]}`。
- `POST /ai/knowledge/reindex`（ADMIN）→ `{started}`。异步同步版本变化或失败的 200 份目录说明书，重复触发不会启动第二个本机任务。

AI 返回的业务证据将 `*Cents` 转为 `*Yuan` 十进制元字符串，原有商城 HTTP API 金额仍为整数分。AI 无付款、退款、调账等写交易工具。
