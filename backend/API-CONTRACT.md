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
