"use client";
import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { Alert, App, Button, Empty, Pagination, Tag } from "antd";
import { Package, Truck, ArrowClockwise } from "@phosphor-icons/react";
import { api, post, money, date, errorText } from "@/lib/api";
import type { Order, PageResult } from "@/lib/types";
import { orderActions, orderLabels } from "@/lib/orders";
import {
  ErrorState,
  LoadingState,
  LoginGate,
  ProductImage,
} from "@/components/common";
import { useSession } from "@/components/providers";
import { RefundRequestButton, Refunds } from "@/components/refunds";

export default function OrdersPage() {
  return (
    <LoginGate>
      <Orders />
    </LoginGate>
  );
}
function Orders() {
  const [data, setData] = useState<PageResult<Order>>({ items: [], total: 0 });
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [refundRevision, setRefundRevision] = useState(0);
  const { modal, message } = App.useApp();
  const { refresh } = useSession();
  const load = useCallback(async () => {
    setError("");
    try {
      setData(await api<PageResult<Order>>(`/orders?page=${page}&pageSize=10`));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [page]);
  useEffect(() => {
    void load();
  }, [load]);
  const action = (
    order: Order,
    item: { action: string; label: string; confirm: string },
  ) =>
    modal.confirm({
      title: item.confirm,
      content:
        item.action === "pay"
          ? `订单 ${order.orderNo}，应付 ${money(order.totalCents)}`
          : undefined,
      okText: item.label,
      cancelText: "再想想",
      onOk: async () => {
        setBusy(order.id);
        try {
          await post(`/orders/${order.id}/${item.action}`);
          message.success("订单已更新");
          await Promise.all([load(), refresh()]);
        } catch (e) {
          message.error(errorText(e));
          throw e;
        } finally {
          setBusy(null);
        }
      },
    });
  return (
    <div className="orders-page">
      <div className="page-heading heading-with-action">
        <div>
          <h1>我的订单</h1>
          <p>每一件期待，都有迹可循。</p>
        </div>
        <Button icon={<ArrowClockwise size={18} />} onClick={load}>
          刷新订单
        </Button>
      </div>
      {error && <ErrorState error={error} retry={load} />}{" "}
      {loading ? (
        <LoadingState />
      ) : !data.items.length ? (
        <div className="empty-area">
          <Empty
            image={<Package size={72} weight="thin" />}
            description="还没有订单，去发现你的第一件好物"
          />
          <Link href="/">
            <Button type="primary">浏览商品</Button>
          </Link>
        </div>
      ) : (
        <>
          <div className="order-list">
            {data.items.map((order) => (
              <article className="order-card" key={order.id}>
                <div className="order-header">
                  <span>订单 {order.orderNo}</span>
                  <time>{date(order.createdAt)}</time>
                  <Tag
                    color={
                      ["PAID", "SHIPPED"].includes(order.status)
                        ? "blue"
                        : "default"
                    }
                  >
                    {orderLabels[order.status] || order.status}
                  </Tag>
                </div>
                <div className="order-items">
                  {order.items.map((item) => (
                    <div className="order-item" key={item.productId}>
                      <Link
                        href={`/products/${item.productId}`}
                        className="order-art"
                      >
                        <ProductImage
                          product={{
                            name: item.name,
                            imageUrl: item.imageUrl || "",
                          }}
                        />
                      </Link>
                      <div>
                        <Link href={`/products/${item.productId}`}>
                          <h3>{item.name}</h3>
                        </Link>
                        <span className="muted">
                          {money(item.priceCents)} × {item.quantity}
                        </span>
                      </div>
                      <strong>{money(item.priceCents * item.quantity)}</strong>
                    </div>
                  ))}
                </div>
                <div className="order-address">
                  {order.address.recipient} / {order.address.phone} /{" "}
                  {order.address.detail}
                </div>
                {order.status === "PENDING_PAYMENT" && (
                  <Alert
                    title={`请在 ${date(order.expiresAt)} 前付款，超时将自动取消。`}
                    type="info"
                    showIcon
                  />
                )}
                {order.trackingNo && (
                  <div className="tracking-note">
                    <Truck size={20} />
                    <span>模拟物流单号：{order.trackingNo}</span>
                  </div>
                )}
                <div className="order-footer">
                  <span>
                    共{" "}
                    {order.items.reduce((sum, item) => sum + item.quantity, 0)}{" "}
                    件商品 <strong>合计 {money(order.totalCents)}</strong>
                  </span>
                  <div className="order-actions">
                    {["SHIPPED", "COMPLETED"].includes(order.status) && (
                      <RefundRequestButton
                        orderId={order.id}
                        onSuccess={() =>
                          setRefundRevision((value) => value + 1)
                        }
                      />
                    )}
                    {(orderActions[order.status] || []).map((item) => (
                      <Button
                        key={item.action}
                        type={
                          item.action === "pay" || item.action === "confirm"
                            ? "primary"
                            : "default"
                        }
                        loading={busy === order.id}
                        onClick={() => action(order, item)}
                      >
                        {item.label}
                      </Button>
                    ))}
                  </div>
                </div>
              </article>
            ))}
          </div>
          <div className="pagination">
            <Pagination
              current={page}
              pageSize={10}
              total={data.total}
              showSizeChanger={false}
              onChange={setPage}
            />
          </div>
        </>
      )}
      <Refunds revision={refundRevision} />
    </div>
  );
}
