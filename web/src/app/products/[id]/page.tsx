"use client";
import { use, useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  App,
  Breadcrumb,
  Button,
  Descriptions,
  InputNumber,
  Tag,
  Tabs,
} from "antd";
import {
  ChatCircleDots,
  ShoppingBag,
  FileText,
  ArrowRight,
  ArrowUpRight,
} from "@phosphor-icons/react";
import { api, money, errorText } from "@/lib/api";
import type { Product } from "@/lib/types";
import { ErrorState, LoadingState } from "@/components/common";
import { ProductStage } from "@/components/product-stage";
import { useSession } from "@/components/providers";

export default function ProductDetail({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = use(params);
  const [product, setProduct] = useState<Product | null>(null);
  const [error, setError] = useState("");
  const [quantity, setQuantity] = useState(1);
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);
  const { user } = useSession();
  const { message } = App.useApp();
  const router = useRouter();
  useEffect(() => {
    const controller = new AbortController();
    setError("");
    setProduct(null);
    setQuantity(1);
    void api<Product>(`/products/${id}`, { signal: controller.signal })
      .then((result) => {
        if (!controller.signal.aborted) setProduct(result);
      })
      .catch((e) => {
        if (!controller.signal.aborted) setError(errorText(e));
      });
    return () => controller.abort();
  }, [id, revision]);
  const addToCart = async (checkout: boolean) => {
    if (!product || product.id !== Number(id) || busy) return;
    if (!user) {
      router.push("/login");
      return;
    }
    setBusy(true);
    try {
      await api(`/cart/items/${id}`, {
        method: "PUT",
        body: JSON.stringify({ quantity }),
      });
      if (checkout) router.push("/cart");
      else message.success("已更新购物袋");
    } catch (e) {
      message.error(errorText(e));
    } finally {
      setBusy(false);
    }
  };
  if (error)
    return (
      <ErrorState
        error={error}
        retry={() => setRevision((value) => value + 1)}
      />
    );
  if (!product || product.id !== Number(id)) return <LoadingState />;
  return (
    <div className="product-detail-page bs-detail-page">
      <Breadcrumb
        items={[
          { title: <Link href="/">精选好物</Link> },
          { title: product.categoryName || product.category },
          { title: product.name },
        ]}
      />
      <section className="bs-product-detail" aria-labelledby="product-title">
        <div className="bs-detail-visual">
          <ProductStage product={product} variant="detail" priority />
          <p className="bs-detail-art-note">
            商品概念插图 · 以规格与说明书为准
          </p>
        </div>
        <div className="bs-detail-copy">
          <p className="bs-detail-category">
            {product.categoryName || product.category}
          </p>
          <h1 id="product-title">
            {product.name.split(" · ").map((part, index) => (
              <span className="bs-product-name-part" key={`${index}-${part}`}>
                {index > 0 ? " · " : ""}
                {part}
              </span>
            ))}
          </h1>
          <p className="bs-detail-description">{product.description}</p>
          <div className="bs-detail-tags">
            {product.tags?.map((tag) => (
              <Tag key={tag}>{tag}</Tag>
            ))}
          </div>
          <div className="bs-purchase-panel">
            <p className="bs-purchase-label">把喜欢的，带回日常。</p>
            <div className="bs-detail-price">
              <strong>{money(product.priceCents)}</strong>
              {!!product.originalPriceCents &&
                product.originalPriceCents > product.priceCents && (
                  <del>{money(product.originalPriceCents)}</del>
                )}
            </div>
            <p className="bs-stock-note">
              {product.stock > 0 ? `现货 ${product.stock} 件` : "商品暂时售罄"}{" "}
              · 平台余额支付
            </p>
            <div className="bs-quantity-control">
              <label htmlFor="quantity">购买数量</label>
              <InputNumber
                id="quantity"
                min={1}
                max={Math.min(99, product.stock)}
                value={quantity}
                onChange={(value) => setQuantity(value || 1)}
                disabled={!product.stock}
              />
            </div>
            <div className="bs-purchase-actions">
              <Button
                type="primary"
                size="large"
                disabled={!product.stock}
                loading={busy}
                onClick={() => addToCart(true)}
              >
                立即选购 <ArrowRight size={18} />
              </Button>
              <Button
                size="large"
                icon={<ShoppingBag size={19} />}
                disabled={!product.stock}
                loading={busy}
                onClick={() => addToCart(false)}
              >
                加入购物袋
              </Button>
            </div>
          </div>
          <Link
            className="bs-ask-product"
            href={`/assistant?product=${product.id}&name=${encodeURIComponent(product.name)}`}
          >
            <ChatCircleDots size={21} />
            <span>想知道是否适合你？问问导购助手</span>
            <ArrowUpRight size={20} />
          </Link>
        </div>
      </section>
      <section className="detail-information bs-detail-information">
        <div className="bs-detail-information-heading">
          <h2>每一处，了解清楚。</h2>
          <p>查看规格、使用说明与售后规则，再做决定。</p>
        </div>
        <Tabs
          items={[
            {
              key: "specs",
              label: "规格参数",
              children: (
                <Descriptions
                  column={{ xs: 1, sm: 2, md: 3 }}
                  items={Object.entries(product.specifications || {}).map(
                    ([label, value]) => ({
                      key: label,
                      label,
                      children: String(value),
                    }),
                  )}
                />
              ),
            },
            {
              key: "manual",
              label: "说明与售后",
              children: (
                <div className="manual-info bs-manual-info">
                  <p>{product.description}</p>
                  <p>
                    付款前可随时取消订单；已付款、未发货的订单支持退款。发货后可查询物流、确认收货或提交整单退货申请，由管理员验收后退款。
                  </p>
                  {product.manualUrl && (
                    <a
                      href={product.manualUrl}
                      target="_blank"
                      rel="noreferrer"
                      className="text-link"
                    >
                      <FileText size={18} />
                      查看商品说明书
                    </a>
                  )}
                </div>
              ),
            },
          ]}
        />
      </section>
    </div>
  );
}
