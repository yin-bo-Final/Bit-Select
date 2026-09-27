# 比特严选前端

Next.js App Router + TypeScript + Ant Design。前端通过同源 `/api` 转发至 Spring Cloud Gateway，登录凭证由后端 HttpOnly Cookie 管理，浏览器不会保存令牌。

## 本地运行

安装 Node.js 22 或更高版本。首次执行 `npm ci`，将 `.env.example` 复制为 `.env.local`，确认网关地址，然后执行 `npm run dev`。访问 http://127.0.0.1:3000 。后端与中间件需先启动。

## 验证

`npm run typecheck` 检查 TypeScript，`npm test` 验证流式传输分帧处理，`npm run build` 执行生产构建。

## 页面

- `/`：搜索、分类、排序、分页商品目录。
- `/products/{id}`：商品规格、说明书、购物袋。
- `/login`：用户注册与登录。
- `/cart`：修改购物袋与填写收货地址，创建待付款订单。
- `/orders`：余额支付、取消、退款、物流、确认收货、发货后整单退货申请。
- `/account`：账户信息、常用收货地址与余额流水。
- `/assistant`：流式导购、对话历史、资料来源、个人记忆查看/开关/删除。
- `/admin`：管理员余额分配、商品维护、库存调整、模拟发货、售后验收退款、知识库入库状态与同步。

所有金额接口均使用整数分。下单、分配余额与库存调整为同一操作保留幂等键，网络失败重试时不会生成新键。

首页使用指定 design-taste-frontend 技能中适用于商城的原则：银灰中性底色、单一品牌蓝、商品优先、低动效。DESIGN_VARIANCE=4、MOTION_INTENSITY=2、VISUAL_DENSITY=5。后台遵循 Ant Design 表格与表单规范。支持系统深浅色偏好、手动切换、键盘操作、窄屏布局与减少动画设置。
