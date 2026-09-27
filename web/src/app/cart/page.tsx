"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  Alert,
  App,
  Button,
  Empty,
  Form,
  Input,
  InputNumber,
  Select,
} from "antd";
import { ArrowLeft, ArrowRight, Trash } from "@phosphor-icons/react";
import { api, post, money, errorText } from "@/lib/api";
import type { Address, CartItem, Order, SavedAddress } from "@/lib/types";
import {
  ErrorState,
  LoadingState,
  LoginGate,
  ProductImage,
} from "@/components/common";
import { useSession } from "@/components/providers";

export default function CartPage() {
  return (
    <LoginGate>
      <Cart />
    </LoginGate>
  );
}
function Cart() {
  const [items, setItems] = useState<CartItem[]>([]);
  const [totalCents, setTotalCents] = useState(0);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [addresses, setAddresses] = useState<SavedAddress[]>([]);
  const [addressForm] = Form.useForm<Address>();
  const retryKey = useRef({ signature: "", key: "" });
  const { user, refresh } = useSession();
  const { message } = App.useApp();
  const router = useRouter();
  const load = useCallback(async () => {
    setError("");
    try {
      const result = await api<{ items: CartItem[]; totalCents: number }>(
        "/cart",
      );
      setItems(result.items);
      setTotalCents(result.totalCents);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => {
    void load();
  }, [load]);
  useEffect(() => {
    void refresh();
  }, [refresh]);
  useEffect(() => {
    if (!items.length) return;
    void api<{ items: SavedAddress[] }>("/addresses")
      .then((result) => {
        setAddresses(result.items);
        const address = result.items.find((item) => item.isDefault);
        if (address)
          addressForm.setFieldsValue({
            recipient: address.recipient,
            phone: address.phone,
            detail: address.detail,
          });
      })
      .catch(() => {});
  }, [addressForm, items.length]);
  const update = async (productId: number, quantity: number) => {
    setBusy(true);
    try {
      await api(`/cart/items/${productId}`, {
        method: "PUT",
        body: JSON.stringify({ quantity }),
      });
      await load();
    } catch (e) {
      message.error(errorText(e));
    } finally {
      setBusy(false);
    }
  };
  const checkout = async (address: Address) => {
    setBusy(true);
    setError("");
    const payload = {
      items: items.map((item) => ({
        productId: item.productId,
        quantity: item.quantity,
      })),
      address,
    };
    const signature = JSON.stringify(payload);
    if (retryKey.current.signature !== signature)
      retryKey.current = { signature, key: crypto.randomUUID() };
    try {
      const order = await post<Order>("/orders", {
        ...payload,
        idempotencyKey: retryKey.current.key,
      });
      router.push(`/orders?highlight=${order.id}`);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };
  if (loading) return <LoadingState />;
  return (
    <div className="cart-page">
      <div className="page-heading">
        <Link href="/" className="back-link">
          <ArrowLeft size={17} />
          继续挑选
        </Link>
        <h1>购物袋</h1>
        <p>喜欢的好物，准备带回日常。</p>
      </div>
      {error && <ErrorState error={error} retry={load} />}
      {!items.length ? (
        <div className="empty-area">
          <Empty description="购物袋还是空的" />
          <Link href="/">
            <Button type="primary">去挑些喜欢的</Button>
          </Link>
        </div>
      ) : (
        <div className="checkout-layout">
          <section className="cart-items" aria-label="已选商品">
            {items.map((item) => (
              <article className="cart-item" key={item.productId}>
                <Link className="cart-art" href={`/products/${item.productId}`}>
                  <ProductImage product={item.product} />
                </Link>
                <div className="cart-item-info">
                  <span className="muted">
                    {item.product.categoryName || item.product.category}
                  </span>
                  <Link href={`/products/${item.productId}`}>
                    <h3>{item.product.name}</h3>
                  </Link>
                  <strong>{money(item.product.priceCents)}</strong>
                  <span className="mobile-subtotal">
                    小计 {money(item.product.priceCents * item.quantity)}
                  </span>
                </div>
                <div className="cart-item-controls">
                  <InputNumber
                    aria-label={`${item.product.name}数量`}
                    min={1}
                    max={Math.max(1, Math.min(99, item.product.stock))}
                    value={item.quantity}
                    disabled={busy}
                    onChange={(value) => {
                      if (value) void update(item.productId, value);
                    }}
                  />
                  <Button
                    type="text"
                    aria-label={`移除${item.product.name}`}
                    icon={<Trash size={19} />}
                    disabled={busy}
                    onClick={() => update(item.productId, 0)}
                  />
                </div>
                <strong className="cart-subtotal">
                  {money(item.product.priceCents * item.quantity)}
                </strong>
                {item.quantity > item.product.stock && (
                  <Alert
                    type="warning"
                    title="库存不足，请调整数量"
                    className="cart-stock-warning"
                  />
                )}
              </article>
            ))}
          </section>
          <aside className="checkout-summary">
            <h2>确认订单</h2>
            <div className="summary-line">
              <span>商品合计</span>
              <strong>{money(totalCents)}</strong>
            </div>
            <div className="summary-line">
              <span>运费</span>
              <span>免运费</span>
            </div>
            <div className="summary-total">
              <span>应付金额</span>
              <strong>{money(totalCents)}</strong>
            </div>
            <p className="balance-note">
              当前钱包余额 {money(user?.balanceCents)}
            </p>
            {!!addresses.length && (
              <div className="saved-address-picker">
                <label htmlFor="saved-address">使用常用地址</label>
                <Select
                  id="saved-address"
                  className="full-width"
                  placeholder="选择已保存的收货地址"
                  options={addresses.map((item) => ({
                    value: item.id,
                    label: `${item.recipient} · ${item.detail}`,
                  }))}
                  onChange={(id) => {
                    const address = addresses.find((item) => item.id === id);
                    if (address)
                      addressForm.setFieldsValue({
                        recipient: address.recipient,
                        phone: address.phone,
                        detail: address.detail,
                      });
                  }}
                />
              </div>
            )}
            <Form
              form={addressForm}
              layout="vertical"
              onFinish={checkout}
              requiredMark={false}
            >
              <Form.Item
                name="recipient"
                label="收货人"
                rules={[
                  { required: true, whitespace: true, message: "请输入收货人" },
                  { max: 40 },
                ]}
              >
                <Input
                  autoComplete="name"
                  maxLength={40}
                  placeholder="收货人姓名"
                />
              </Form.Item>
              <Form.Item
                name="phone"
                label="联系电话"
                rules={[
                  { required: true, message: "请输入联系电话" },
                  {
                    pattern: /^[+\d\s-]{6,20}$/,
                    message: "请输入有效的联系电话",
                  },
                ]}
              >
                <Input
                  autoComplete="tel"
                  maxLength={20}
                  placeholder="用于配送联系"
                />
              </Form.Item>
              <Form.Item
                name="detail"
                label="收货地址"
                rules={[
                  {
                    required: true,
                    whitespace: true,
                    message: "请输入完整收货地址",
                  },
                  { min: 5, max: 300, message: "地址为 5-300 个字符" },
                ]}
              >
                <Input.TextArea
                  autoComplete="street-address"
                  rows={3}
                  maxLength={300}
                  placeholder="省市区、街道及门牌号"
                />
              </Form.Item>
              <Button
                type="primary"
                htmlType="submit"
                block
                size="large"
                loading={busy}
                disabled={items.some(
                  (item) => item.quantity > item.product.stock,
                )}
              >
                提交订单 <ArrowRight size={18} />
              </Button>
            </Form>
            <p className="form-note">
              提交后保留库存 15
              分钟，请在订单页面完成余额支付。余额不足时可联系管理员分配。
            </p>
          </aside>
        </div>
      )}
    </div>
  );
}
