"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { Button, Empty, Input, Pagination, Select, Skeleton } from "antd";
import {
  ArrowRight,
  ArrowUpRight,
  ChatCircleDots,
  MagnifyingGlass,
} from "@phosphor-icons/react";
import { api, errorText } from "@/lib/api";
import type { PageResult, Product } from "@/lib/types";
import { ErrorState, ProductCard } from "@/components/common";
import { ProductStage } from "@/components/product-stage";
import type { HomeCatalog } from "@/lib/home-catalog";

const catalogKey = (
  page: number,
  sort: string,
  search: string,
  category: string,
) => JSON.stringify([page, sort, search, category]);

export function StorefrontHome({ initial }: { initial: HomeCatalog }) {
  const [categories, setCategories] = useState(initial.categories);
  const [category, setCategory] = useState("");
  const [query, setQuery] = useState("");
  const [search, setSearch] = useState("");
  const [sort, setSort] = useState("featured");
  const [page, setPage] = useState(1);
  const [data, setData] = useState<PageResult<Product>>(
    initial.products || { items: [], total: 0 },
  );
  const [hero, setHero] = useState<Product | null>(
    initial.products?.items.find((product) => product.category === "audio") ||
      initial.products?.items[0] ||
      null,
  );
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(!initial.products);
  const [revision, setRevision] = useState(0);
  const displayedQuery = useRef(
    initial.products ? catalogKey(1, "featured", "", "") : null,
  );
  useEffect(() => {
    const controller = new AbortController();
    void api<HomeCatalog["categories"]>("/categories", {
      signal: controller.signal,
    })
      .then((result) => {
        if (!controller.signal.aborted) setCategories(result);
      })
      .catch(() => {});
    return () => controller.abort();
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    const requestedQuery = catalogKey(page, sort, search, category);
    // A matching server snapshot stays visible while actual inventory refreshes.
    setLoading(displayedQuery.current !== requestedQuery);
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
        if (controller.signal.aborted) return;
        displayedQuery.current = requestedQuery;
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
        if (!controller.signal.aborted) setError(errorText(e));
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
    <div className="storefront bs-storefront">
      <section className="bs-home-hero" aria-labelledby="home-title">
        <div className="bs-hero-copy">
          <p className="bs-hero-eyebrow">让日常，刚刚好</p>
          <h1 id="home-title">
            <span>好好选，</span>
            <span className="bs-hero-title-accent">慢慢用。</span>
          </h1>
          <p className="bs-hero-description">
            从桌面到生活，找到值得留下的好东西。
          </p>
          <a href="#collection" className="bs-hero-link">
            探索精选 <ArrowRight size={22} />
          </a>
        </div>
        <div className="bs-hero-visual">
          {hero ? (
            <Link
              className="bs-hero-product"
              href={`/products/${hero.id}`}
              aria-label={`查看${hero.categoryName || "日常精选"}：${hero.name}`}
            >
              <ProductStage product={hero} priority />
              <div className="bs-hero-caption">
                <div>
                  <span>{hero.categoryName || "日常精选"}</span>
                  <strong>{hero.name}</strong>
                </div>
                <span className="bs-product-open">
                  <ArrowUpRight size={23} aria-hidden="true" />
                </span>
              </div>
            </Link>
          ) : loading ? (
            <div className="bs-hero-placeholder">
              <Skeleton.Image active />
            </div>
          ) : (
            <div className="bs-hero-placeholder" role="status">
              {error ? "精选商品暂时未能加载，请在下方重试" : "好物正在准备中"}
            </div>
          )}
        </div>
      </section>
      <section className="bs-assistant-banner">
        <div className="bs-assistant-banner-icon">
          <ChatCircleDots size={34} weight="duotone" />
        </div>
        <div>
          <h2>合不合适，聊聊就知道。</h2>
          <p>告诉导购你的预算和习惯，让选择更有依据。</p>
        </div>
        <Link href="/assistant" className="bs-assistant-banner-link">
          开始聊聊 <ArrowUpRight size={19} aria-hidden />
        </Link>
      </section>
      <section
        id="collection"
        className="collection bs-collection"
        aria-labelledby="collection-title"
      >
        <div className="bs-collection-heading">
          <h2 id="collection-title">日常好物</h2>
          <p>电子产品与生活用品，用得到，也用得久。商品与概念插图用于演示。</p>
        </div>
        <div className="bs-collection-controls">
          <div className="bs-collection-tools">
            <form
              className="bs-search-form"
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
              <Button htmlType="submit" type="primary">
                搜索
              </Button>
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
          <div className="bs-category-tabs" role="group" aria-label="商品分类">
            <button
              type="button"
              className={category === "" ? "selected" : ""}
              aria-pressed={category === ""}
              onClick={() => selectCategory("")}
            >
              全部好物
            </button>
            {categories.map((item) => (
              <button
                type="button"
                key={item.id}
                className={category === item.id ? "selected" : ""}
                aria-pressed={category === item.id}
                onClick={() => selectCategory(item.id)}
              >
                {item.name}
              </button>
            ))}
          </div>
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
            <div className="product-grid bs-product-grid">
              {data.items.map((product) => (
                <ProductCard key={product.id} product={product} />
              ))}
            </div>
            <div className="pagination">
              <span>共 {data.total} 件好物</span>
              <Pagination
                responsive
                showLessItems
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
