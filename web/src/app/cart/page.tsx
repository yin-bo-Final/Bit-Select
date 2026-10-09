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
import {
  ArrowLeft,
  ArrowRight,
  Trash,
  ShoppingBag,
  MapPin,
  Wallet,
  Clock,
} from "@phosphor-icons/react";
import { api, post, money, errorText } from "@/lib/api";
import type { Address, CartItem, Order, SavedAddress } from "@/lib/types";
import {
  ErrorState,
  LoadingState,
  LoginGate,
  ProductImage,
} from "@/components/common";
import { useSession } from "@/components/providers";
import {
  isPurchaseQuantity,
  isCartQuantitySynced,
  parsePurchaseQuantity,
  validatePhone,
} from "@/lib/commerce-input";

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
  const [loaded, setLoaded] = useState(false);
  const [quantityDrafts, setQuantityDrafts] = useState<Record<number, string>>(
    {},
  );
  const [quantityValues, setQuantityValues] = useState<
    Record<number, number | null>
  >({});
  const [quantityErrors, setQuantityErrors] = useState<
    Record<number, { message: string; quantity: number }>
  >({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [addresses, setAddresses] = useState<SavedAddress[]>([]);
  const [selectedAddress, setSelectedAddress] = useState<number>();
  const addressInitialized = useRef(false);
  const [addressForm] = Form.useForm<Address>();
  const retryKey = useRef({ signature: "", key: "" });
  const request = useRef<AbortController | null>(null);
  const mutation = useRef(false);
  const { user, refresh } = useSession();
  const { message } = App.useApp();
  const router = useRouter();
  const load = useCallback(async () => {
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    setLoading(true);
    setError("");
    try {
      const result = await api<{ items: CartItem[]; totalCents: number }>(
        "/cart",
        { signal: controller.signal },
      );
      if (controller.signal.aborted) return;
      setItems(result.items);
      setTotalCents(result.totalCents);
      setQuantityDrafts({});
      setQuantityValues({});
      setQuantityErrors({});
      setLoaded(true);
    } catch (e) {
      if (!controller.signal.aborted) setError(errorText(e));
    } finally {
      if (!controller.signal.aborted && request.current === controller)
        setLoading(false);
    }
  }, []);
  useEffect(() => {
    void load();
    return () => request.current?.abort();
  }, [load]);
  useEffect(() => {
    void refresh();
  }, [refresh]);
  const hasItems = items.length > 0;
  useEffect(() => {
    if (!hasItems) return;
    const controller = new AbortController();
    void api<{ items: SavedAddress[] }>("/addresses", {
      signal: controller.signal,
    })
      .then((result) => {
        if (controller.signal.aborted) return;
        setAddresses(result.items);
        if (addressInitialized.current) return;
        addressInitialized.current = true;
        if (addressForm.isFieldsTouched()) return;
        const address = result.items.find((item) => item.isDefault);
        if (address) {
          setSelectedAddress(address.id);
          addressForm.setFieldsValue({
            recipient: address.recipient,
            phone: address.phone,
            detail: address.detail,
          });
        }
      })
      .catch(() => {});
    return () => controller.abort();
  }, [addressForm, hasItems]);
  const update = async (productId: number, quantity: number) => {
    if (mutation.current || loading) return;
    const item = items.find((item) => item.productId === productId);
    if (
      quantity !== 0 &&
      (!item?.product.enabled ||
        !isPurchaseQuantity(quantity, item.product.stock))
    )
      return;
    mutation.current = true;
    setBusy(true);
    setQuantityErrors((errors) => {
      const next = { ...errors };
      delete next[productId];
      return next;
    });
    try {
      await api(`/cart/items/${productId}`, {
        method: "PUT",
        body: JSON.stringify({ quantity }),
      });
      await load();
    } catch (e) {
      setQuantityErrors((errors) => ({
        ...errors,
        [productId]: { message: errorText(e), quantity },
      }));
      message.error(errorText(e));
    } finally {
      mutation.current = false;
      setBusy(false);
    }
  };
  const checkout = async (address: Address) => {
    if (mutation.current || loading || !loaded || error || !items.length)
      return;
    if (
      items.some(
        (item) =>
          !item.product.enabled ||
          !!quantityErrors[item.productId] ||
          !isCartQuantitySynced(
            quantityDrafts[item.productId],
            item.quantity,
            item.product.stock,
          ),
      )
    ) {
      message.error("请先调整商品数量或移除已下架商品");
      return;
    }
    mutation.current = true;
    setBusy(true);
    setError("");
    const payload = {
      items: items.map((item) => ({
        productId: item.productId,
        quantity: item.quantity,
      })),
      address: { ...address, phone: address.phone.trim() },
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
      mutation.current = false;
      setBusy(false);
    }
  };
  if (loading && !loaded) return <LoadingState />;
  const checkoutBlocked =
    loading ||
    !!error ||
    items.some(
      (item) =>
        !item.product.enabled ||
        !!quantityErrors[item.productId] ||
        !isCartQuantitySynced(
          quantityDrafts[item.productId],
          item.quantity,
          item.product.stock,
        ),
    );
  return (
    <div className="cart-page commerce-page">
      <div className="page-heading">
        <Link href="/" className="back-link">
          <ArrowLeft size={17} />
          继续挑选
        </Link>
        <h1>
          购物袋
          {items.length > 0 && (
            <span className="commerce-heading-count">
              {items.reduce((sum, item) => sum + item.quantity, 0)}
            </span>
          )}
        </h1>
        <p>喜欢的好物，准备带回日常。</p>
      </div>
      {error && <ErrorState error={error} retry={load} />}
      {loading && loaded && (
        <p className="muted" role="status">
          正在刷新购物袋…
        </p>
      )}
      {loaded && !items.length && !error ? (
        <div className="empty-area">
          <Empty
            image={<ShoppingBag size={72} weight="thin" />}
            description="购物袋还是空的"
          />
          <Link href="/" className="navigation-button">
            去挑些喜欢的
          </Link>
        </div>
      ) : items.length > 0 ? (
        <>
          <div className="commerce-checkout-steps" aria-label="购物流程">
            <span className="is-current">
              <b>01</b>确认商品
            </span>
            <span>
              <b>02</b>提交订单
            </span>
            <span>
              <b>03</b>余额付款
            </span>
          </div>
          <div className="checkout-layout">
            <section className="cart-items" aria-label="已选商品">
              <div className="commerce-cart-heading">
                <h2>
                  <ShoppingBag size={21} />
                  已选商品
                </h2>
                <span>{items.length} 款好物</span>
              </div>
              {items.map((item) => (
                <article className="cart-item" key={item.productId}>
                  <Link
                    className="cart-art"
                    href={`/products/${item.productId}`}
                  >
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
                      changeOnBlur={false}
                      value={
                        Object.hasOwn(quantityValues, item.productId)
                          ? quantityValues[item.productId]
                          : item.quantity
                      }
                      status={
                        quantityDrafts[item.productId] !== undefined &&
                        parsePurchaseQuantity(
                          quantityDrafts[item.productId],
                          item.product.stock,
                        ) === null
                          ? "error"
                          : undefined
                      }
                      disabled={
                        busy ||
                        loading ||
                        !item.product.enabled ||
                        item.product.stock <= 0
                      }
                      onChange={(value) => {
                        setQuantityValues((values) => ({
                          ...values,
                          [item.productId]: value,
                        }));
                        setQuantityDrafts((drafts) => ({
                          ...drafts,
                          [item.productId]: value === null ? "" : String(value),
                        }));
                      }}
                      onInput={(input) => {
                        setQuantityDrafts((drafts) => ({
                          ...drafts,
                          [item.productId]: input,
                        }));
                        // Editing starts a new intent; a retry must not replay an older quantity.
                        setQuantityErrors((errors) => {
                          const next = { ...errors };
                          delete next[item.productId];
                          return next;
                        });
                      }}
                      onBlur={() => {
                        const draft = quantityDrafts[item.productId];
                        if (draft === undefined) return;
                        const quantity = parsePurchaseQuantity(
                          draft,
                          item.product.stock,
                        );
                        if (quantity !== null && quantity !== item.quantity)
                          void update(item.productId, quantity);
                      }}
                      onStep={(value) => {
                        if (
                          isPurchaseQuantity(value, item.product.stock) &&
                          value !== item.quantity
                        )
                          void update(item.productId, value);
                      }}
                      onKeyDownCapture={(event) => {
                        if (event.key === "Enter") {
                          event.preventDefault();
                          event.stopPropagation();
                          const draft = quantityDrafts[item.productId];
                          if (draft === undefined) return;
                          const quantity = parsePurchaseQuantity(
                            draft,
                            item.product.stock,
                          );
                          if (quantity !== null && quantity !== item.quantity)
                            void update(item.productId, quantity);
                        }
                      }}
                    />
                    <Button
                      type="text"
                      aria-label={`移除${item.product.name}`}
                      icon={<Trash size={19} />}
                      disabled={busy || loading}
                      onClick={() => update(item.productId, 0)}
                    />
                  </div>
                  <strong className="cart-subtotal">
                    {money(item.product.priceCents * item.quantity)}
                  </strong>
                  {!item.product.enabled ? (
                    <Alert
                      type="warning"
                      title="商品已下架，请移除后再结算"
                      className="cart-stock-warning"
                    />
                  ) : item.product.stock <= 0 ? (
                    <Alert
                      type="warning"
                      title="商品暂时售罄，请移除后再结算"
                      className="cart-stock-warning"
                    />
                  ) : !isPurchaseQuantity(item.quantity, item.product.stock) ? (
                    <Alert
                      type="warning"
                      title="库存不足，请调整数量"
                      className="cart-stock-warning"
                    />
                  ) : quantityDrafts[item.productId] !== undefined &&
                    parsePurchaseQuantity(
                      quantityDrafts[item.productId],
                      item.product.stock,
                    ) === null ? (
                    <Alert
                      type="warning"
                      title={`请输入 1–${Math.min(99, item.product.stock)} 的整数数量`}
                      className="cart-stock-warning"
                    />
                  ) : quantityErrors[item.productId] ? (
                    <Alert
                      type="error"
                      title={`${quantityErrors[item.productId].quantity === 0 ? "移除失败" : "数量未能保存"}：${quantityErrors[item.productId].message}`}
                      className="cart-stock-warning"
                      action={
                        <Button
                          size="small"
                          disabled={busy || loading}
                          onClick={() => {
                            void update(
                              item.productId,
                              quantityErrors[item.productId].quantity,
                            );
                          }}
                        >
                          重试
                        </Button>
                      }
                    />
                  ) : null}
                </article>
              ))}
            </section>
            <aside className="checkout-summary">
              <div className="commerce-summary-heading">
                <div className="commerce-checkout-symbol">
                  <ShoppingBag size={27} weight="duotone" />
                </div>
                <div>
                  <h2>确认订单</h2>
                  <span>让喜欢的好物来到身边</span>
                </div>
              </div>
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
                <Wallet size={18} />
                当前钱包余额 <strong>{money(user?.balanceCents)}</strong>
              </p>
              <h3 className="commerce-address-heading">
                <MapPin size={20} />
                配送信息
              </h3>
              {!!addresses.length && (
                <div className="saved-address-picker">
                  <label htmlFor="saved-address">使用常用地址</label>
                  <Select
                    id="saved-address"
                    value={selectedAddress}
                    className="full-width"
                    placeholder="选择已保存的收货地址"
                    options={addresses.map((item) => ({
                      value: item.id,
                      label: `${item.recipient} · ${item.detail}`,
                    }))}
                    onChange={(id) => {
                      setSelectedAddress(id);
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
                className="commerce-delivery-form"
                form={addressForm}
                layout="vertical"
                onFinish={checkout}
                onValuesChange={() => setSelectedAddress(undefined)}
                requiredMark={false}
                disabled={busy || loading}
              >
                <Form.Item
                  name="recipient"
                  label="收货人"
                  rules={[
                    {
                      required: true,
                      whitespace: true,
                      message: "请输入收货人",
                    },
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
                    {
                      required: true,
                      whitespace: true,
                      message: "请输入联系电话",
                    },
                    { validator: validatePhone },
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
                  disabled={checkoutBlocked}
                >
                  提交订单 <ArrowRight size={18} />
                </Button>
              </Form>
              <p className="form-note commerce-checkout-note">
                <Clock size={18} />
                <span>
                  提交后保留库存 15
                  分钟，请在订单页面完成余额支付。余额不足时可联系管理员分配。
                </span>
              </p>
            </aside>
          </div>
        </>
      ) : null}
    </div>
  );
}
