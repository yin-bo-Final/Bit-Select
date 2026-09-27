export const orderLabels: Record<string, string> = {
  PENDING_PAYMENT: "待付款",
  PAID: "待发货",
  SHIPPED: "配送中",
  COMPLETED: "已完成",
  CANCELLED: "已取消",
  REFUNDED: "已退款",
};
export const orderActions: Record<
  string,
  { action: string; label: string; confirm: string }[]
> = {
  PENDING_PAYMENT: [
    {
      action: "pay",
      label: "余额付款",
      confirm: "确认使用钱包余额支付此订单？",
    },
    {
      action: "cancel",
      label: "取消订单",
      confirm: "确认取消此订单？预留库存将被释放。",
    },
  ],
  PAID: [
    {
      action: "refund",
      label: "申请退款",
      confirm: "商品尚未发货，确认退款？金额将原路退回平台钱包。",
    },
  ],
  SHIPPED: [
    {
      action: "confirm",
      label: "确认收货",
      confirm: "请确认你已收到商品。确认后订单将完成。",
    },
  ],
};
