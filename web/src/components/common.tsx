"use client";
import { Alert, Button, Empty, Skeleton } from "antd";
import Link from "next/link";
import Image from "next/image";
import { useState } from "react";
import { useSession } from "./providers";
import { money } from "@/lib/api";
import type { Product } from "@/lib/types";

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
        <Empty description="登录后即可继续" />
        <Link href="/login">
          <Button type="primary">登录 / 注册</Button>
        </Link>
      </div>
    );
  return children;
}
export function ProductImage({
  product,
  priority = false,
}: {
  product: Pick<Product, "imageUrl" | "name">;
  priority?: boolean;
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
      sizes="(max-width: 640px) 50vw, (max-width: 1024px) 33vw, 25vw"
      className="product-image"
      unoptimized
      preload={priority}
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
          <ProductImage product={product} priority={priority} />
        </div>
        <div className="product-copy">
          <p className="product-category">
            {product.categoryName || product.category}
          </p>
          <h3>{product.name}</h3>
          <p className="product-description">{product.description}</p>
          <div className="product-bottom">
            <strong>{money(product.priceCents)}</strong>
            <span>{product.stock > 0 ? "查看好物" : "暂时售罄"}</span>
          </div>
        </div>
      </Link>
    </article>
  );
}
