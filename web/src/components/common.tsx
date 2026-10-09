"use client";
import { Alert, Button, Empty, Skeleton } from "antd";
import Link from "next/link";
import Image from "next/image";
import { useState } from "react";
import { useSession } from "./providers";
import { money } from "@/lib/api";
import type { Product } from "@/lib/types";
import { ArrowUpRight, ShoppingBag } from "@phosphor-icons/react";

export function ErrorState({
  error,
  retry,
}: {
  error: string;
  retry?: () => void;
}) {
  return (
    <Alert
      className="state-error"
      type="error"
      showIcon
      title="暂时无法完成"
      description={error}
      action={retry && <Button onClick={retry}>重试</Button>}
    />
  );
}
export function LoadingState() {
  return (
    <div className="loading-state" aria-label="正在加载">
      <Skeleton active paragraph={{ rows: 5 }} />
    </div>
  );
}
export function LoginGate({ children }: { children: React.ReactNode }) {
  const { user, loading } = useSession();
  if (loading) return <LoadingState />;
  if (!user)
    return (
      <div className="login-gate">
        <div className="gate-symbol">
          <ShoppingBag size={36} weight="light" />
        </div>
        <h1>登录后即可继续</h1>
        <p>你的购物袋、订单与专属导购，都在这里。</p>
        <Link href="/login" className="navigation-button">
          登录 / 注册
        </Link>
      </div>
    );
  return children;
}
export function ProductImage({
  product,
  priority = false,
  sizes = "(max-width: 640px) 50vw, (max-width: 1024px) 45vw, 31vw",
}: {
  product: Pick<Product, "imageUrl" | "name">;
  priority?: boolean;
  sizes?: string;
}) {
  const [failed, setFailed] = useState(false);
  if (failed || !product.imageUrl)
    return (
      <div
        className="image-unavailable"
        role="img"
        aria-label={`${product.name}，暂无图片`}
      >
        商品图片暂不可用
      </div>
    );
  return (
    <Image
      src={product.imageUrl}
      alt={product.name}
      fill
      sizes={sizes}
      className="product-image"
      unoptimized
      loading={priority ? "eager" : "lazy"}
      fetchPriority={priority ? "high" : undefined}
      onError={() => setFailed(true)}
    />
  );
}
export function ProductCard({
  product,
  priority = false,
}: {
  product: Product;
  priority?: boolean;
}) {
  return (
    <article className="product-card">
      <Link href={`/products/${product.id}`} className="product-card-link">
        <div className="product-art">
          <span className="product-art-orbit" aria-hidden="true" />
          <ProductImage product={product} priority={priority} />
        </div>
        <div className="product-copy">
          <p className="product-category">
            {product.categoryName || product.category}
            {product.featured && <span>精选</span>}
            {product.stock <= 0 && <span>售罄</span>}
          </p>
          <h3>{product.name}</h3>
          <p className="product-description">{product.description}</p>
          <div className="product-bottom">
            <strong>{money(product.priceCents)}</strong>
            <span className="product-visit">
              <span className="product-visit-label">
                {product.stock > 0 ? "查看好物" : "暂时售罄"}
              </span>
              <span className="product-visit-icon" aria-hidden="true">
                <ArrowUpRight size={20} />
              </span>
            </span>
          </div>
        </div>
      </Link>
    </article>
  );
}
