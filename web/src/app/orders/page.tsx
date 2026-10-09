"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { Alert, App, Button, Empty, Pagination, Tag } from "antd";
import {
  Package,
  Truck,
  ArrowClockwise,
  MapPin,
  Check,
  CreditCard,
  CheckCircle,
} from "@phosphor-icons/react";
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

function OrderProgress({ status }: { status: string }) {
  const position: Record<string, number> = {
    PENDING_PAYMENT: 0,
    PAID: 1,
    SHIPPED: 2,
    COMPLETED: 4,
  };
  const current = position[status];
  if (current === undefined) return null;
  const steps = [
    { label: "余额支付", Icon: CreditCard },
    { label: "商品出库", Icon: Package },
    { label: "配送途中", Icon: Truck },
    { label: "确认收货", Icon: CheckCircle },
  ];
  return (
    <ol
      className="commerce-order-progress"
      aria-label={`订单进度：${orderLabels[status]}`}
    >
      {steps.map(({ label, Icon }, index) => (
        <li
          key={label}
          className={
            index < current
              ? "is-complete"
              : index === current
                ? "is-current"
                : ""
          }
        >
          <span>
            {index < current ? (
              <Check size={15} weight="bold" />
            ) : (
              <Icon size={17} />
            )}
          </span>
          <span>{label}</span>
        </li>
      ))}
    </ol>
  );
}

export default function OrdersPage() {
  return (
    <LoginGate>
      <Orders />
    </LoginGate>
  );
}
function Orders() {
  const request = useRef<AbortController | null>(null);
  const [data, setData] = useState<PageResult<Order>>({ items: [], total: 0 });
  const [page, setPage] = useState(1);
  const currentPage = useRef(page);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [refundRevision, setRefundRevision] = useState(0);
  const { modal, message } = App.useApp();
  const { refresh } = useSession();
  const load = useCallback(async () => {
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    setLoading(true);
    setError("");
    try {
      const result = await api<PageResult<Order>>(
        `/orders?page=${currentPage.current}&pageSize=10`,
        { signal: controller.signal },
      );
      if (!controller.signal.aborted) setData(result);
    } catch (e) {
      if (!controller.signal.aborted) setError(errorText(e));
    } finally {
      if (request.current === controller && !controller.signal.aborted)
        setLoading(false);
    }
  }, []);
  useEffect(() => {
    currentPage.current = page;
    void load();
    return () => request.current?.abort();
  }, [load, page]);
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
    <div className="orders-page commerce-page">
      <div className="page-heading heading-with-action">
        <div>
          <h1>
            我的订单
            {!loading && (
              <span className="commerce-heading-count">{data.total}</span>
            )}
          </h1>
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
              <article
                className={`order-card commerce-order-${order.status.toLowerCase()}`}
                key={order.id}
              >
                <div className="order-header">
                  <span className="commerce-order-number">
                    <Package size={17} />
                    <span>
                      订单 <b>{order.orderNo}</b>
                    </span>
                  </span>
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
                <OrderProgress status={order.status} />
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
                  <MapPin size={18} />
                  <div>
                    <span>
                      <strong>{order.address.recipient}</strong>
                      <span className="commerce-mono">
                        {order.address.phone}
                      </span>
                    </span>
                    <p>{order.address.detail}</p>
                  </div>
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
                  <div className="commerce-order-total">
                    <span>
                      共{" "}
                      {order.items.reduce(
                        (sum, item) => sum + item.quantity,
                        0,
                      )}{" "}
                      件商品
                    </span>{" "}
                    <strong>
                      <small>合计</small> {money(order.totalCents)}
                    </strong>
                  </div>
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
              responsive
              showLessItems
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
