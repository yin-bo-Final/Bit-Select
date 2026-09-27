"use client";
import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { Button, Empty, Input, Pagination, Select, Skeleton } from "antd";
import {
  ArrowRight,
  ChatCircleDots,
  MagnifyingGlass,
} from "@phosphor-icons/react";
import { api, errorText } from "@/lib/api";
import type { PageResult, Product } from "@/lib/types";
import { ErrorState, ProductCard, ProductImage } from "@/components/common";

type Category = { id: string; name: string; count: number };
export default function Home() {
  const [categories, setCategories] = useState<Category[]>([]);
  const [category, setCategory] = useState("");
  const [query, setQuery] = useState("");
  const [search, setSearch] = useState("");
  const [sort, setSort] = useState("featured");
  const [page, setPage] = useState(1);
  const [data, setData] = useState<PageResult<Product>>({
    items: [],
    total: 0,
  });
  const [hero, setHero] = useState<Product | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    void api<Category[]>("/categories")
      .then(setCategories)
      .catch(() => {});
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError("");
    const params = new URLSearchParams({
      page: String(page),
      pageSize: "12",
      sort,
      q: search,
      category,
    });
    void api<PageResult<Product>>(`/products?${params}`, {
      signal: controller.signal,
    })
      .then((result) => {
        setData(result);
        setHero(
          (current) =>
            current ||
            result.items.find((p) => p.category === "audio") ||
            result.items[0] ||
            null,
        );
      })
      .catch((e) => {
        if (e.name !== "AbortError") setError(errorText(e));
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [page, sort, search, category, revision]);
  const selectCategory = useCallback((value: string) => {
    setCategory(value);
    setPage(1);
  }, []);
  return (
    <div className="storefront">
      <section className="hero">
        <div className="hero-copy">
          <p className="eyebrow">让日常，刚刚好</p>
          <h1>
            好好选，
            <br />
            慢慢用。
          </h1>
          <p className="hero-description">
            从桌面到生活，找到值得留下的好东西。
          </p>
          <a href="#collection" className="hero-link">
            探索精选 <ArrowRight size={22} />
          </a>
        </div>
        <div className="hero-visual">
          {hero ? (
            <Link href={`/products/${hero.id}`} aria-label={`查看${hero.name}`}>
              <ProductImage product={hero} priority />
              <div className="hero-caption">
                <span>{hero.categoryName || "日常精选"}</span>
                <strong>{hero.name}</strong>
                <ArrowUpRightLink />
              </div>
            </Link>
          ) : (
            <Skeleton.Image active className="hero-skeleton" />
          )}
        </div>
      </section>
      <section className="assistant-banner">
        <div className="assistant-banner-icon">
          <ChatCircleDots size={32} weight="duotone" />
        </div>
        <div>
          <h2>合不合适，聊聊就知道。</h2>
          <p>告诉导购你的预算和习惯，让选择更有依据。</p>
        </div>
        <Link href="/assistant">
          <Button icon={<ArrowRight size={18} />} iconPlacement="end">
            开始聊聊
          </Button>
        </Link>
      </section>
      <section id="collection" className="collection">
        <div className="collection-heading">
          <h2>日常好物</h2>
          <p>电子产品与生活用品，用得到，也用得久。商品与概念插图用于演示。</p>
        </div>
        <div className="collection-tools">
          <form
            className="search-form"
            onSubmit={(event) => {
              event.preventDefault();
              setSearch(query.trim());
              setPage(1);
            }}
          >
            <Input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="搜索商品、功能或使用场景"
              prefix={<MagnifyingGlass size={20} />}
              allowClear
              aria-label="搜索商品"
            />
            <Button htmlType="submit">搜索</Button>
          </form>
          <Select
            value={sort}
            aria-label="商品排序"
            onChange={(value) => {
              setSort(value);
              setPage(1);
            }}
            options={[
              { value: "featured", label: "精选推荐" },
              { value: "price_asc", label: "价格从低到高" },
              { value: "price_desc", label: "价格从高到低" },
              { value: "newest", label: "最新上架" },
            ]}
          />
        </div>
        <div className="category-tabs" role="group" aria-label="商品分类">
          <button
            className={category === "" ? "selected" : ""}
            onClick={() => selectCategory("")}
          >
            全部好物
          </button>
          {categories.map((item) => (
            <button
              key={item.id}
              className={category === item.id ? "selected" : ""}
              onClick={() => selectCategory(item.id)}
            >
              {item.name}
            </button>
          ))}
        </div>
        {search && (
          <p className="search-caption">
            “{search}” 的搜索结果{" "}
            <Button
              type="link"
              onClick={() => {
                setSearch("");
                setQuery("");
                setPage(1);
              }}
            >
              清除搜索
            </Button>
          </p>
        )}
        {error ? (
          <ErrorState
            error={error}
            retry={() => setRevision((value) => value + 1)}
          />
        ) : loading ? (
          <div className="product-grid" aria-label="商品加载中">
            {Array.from({ length: 8 }, (_, index) => (
              <div key={index} className="product-skeleton">
                <Skeleton.Image active />
                <Skeleton active paragraph={{ rows: 1 }} />
              </div>
            ))}
          </div>
        ) : data.items.length ? (
          <>
            <div className="product-grid">
              {data.items.map((product) => (
                <ProductCard key={product.id} product={product} />
              ))}
            </div>
            <div className="pagination">
              <span>共 {data.total} 件好物</span>
              <Pagination
                current={page}
                pageSize={12}
                total={data.total}
                showSizeChanger={false}
                onChange={(next) => {
                  setPage(next);
                  document
                    .getElementById("collection")
                    ?.scrollIntoView({ behavior: "instant" });
                }}
              />
            </div>
          </>
        ) : (
          <div className="empty-area">
            <Empty description="没有找到合适的商品，试试其他关键词" />
            <Button
              onClick={() => {
                setSearch("");
                setQuery("");
                selectCategory("");
              }}
            >
              查看全部商品
            </Button>
          </div>
        )}
      </section>
    </div>
  );
}
function ArrowUpRightLink() {
  return <ArrowRight size={24} aria-hidden="true" />;
}
