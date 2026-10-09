"use client";
import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import {
  Alert,
  App,
  Button,
  Form,
  Input,
  Modal,
  Select,
  Skeleton,
  Space,
  Statistic,
  Switch,
  Table,
  Tabs,
  Tag,
} from "antd";
import type { ColumnsType } from "antd/es/table";
import {
  ArrowClockwise,
  BookOpenText,
  Package,
  Plus,
  Receipt,
  ShieldCheck,
  Pulse,
  Truck,
  UsersThree,
  Wallet,
} from "@phosphor-icons/react";
import { api, post, date, money, errorText } from "@/lib/api";
import {
  parseMoneyCents,
  parseReferencePrice,
  parseInventoryDelta,
} from "@/lib/admin-input";
import type { Order, PageResult, Product, User } from "@/lib/types";
import { orderLabels } from "@/lib/orders";
import { ErrorState, LoginGate, ProductImage } from "@/components/common";
import { useSession } from "@/components/providers";
import { Refunds } from "@/components/refunds";
import { KnowledgeAdmin } from "@/components/knowledge-admin";

export default function AdminPage() {
  return (
    <LoginGate>
      <Admin />
    </LoginGate>
  );
}
function Admin() {
  const { user } = useSession();
  const [overview, setOverview] = useState<{
    userCount: number;
    productCount: number;
    orderCount: number;
    paidRevenueCents: number;
    pendingOrderCount: number;
  } | null>(null);
  const [error, setError] = useState("");
  const [refreshing, setRefreshing] = useState(false);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  const load = useCallback(async () => {
    if (user?.role !== "ADMIN") return;
    setRefreshing(true);
    try {
      setOverview(await api("/admin/overview"));
      setUpdatedAt(new Date());
      setError("");
    } catch (e) {
      setError(errorText(e));
    } finally {
      setRefreshing(false);
    }
  }, [user?.role]);
  useEffect(() => {
    void load();
  }, [load]);
  if (user?.role !== "ADMIN")
    return (
      <div className="empty-area">
        <Alert
          type="warning"
          title="此页面仅向管理员开放"
          description="你可以继续浏览商城，或联系管理员分配平台余额。"
          showIcon
        />
      </div>
    );
  return (
    <div className="admin-page precision-admin">
      <div className="workspace-page-heading">
        <div>
          <span className="workspace-section-label">
            <ShieldCheck size={17} />
            运营工作台
          </span>
          <h1>商城管理</h1>
          <p>从商品上架到订单完成，让每一笔交易有条不紊。</p>
        </div>
        <div className="overview-refresh">
          <Link href="/admin/ops" className="navigation-button">
            <Pulse size={18} aria-hidden="true" />
            运维工作台
          </Link>
          {updatedAt && (
            <span>
              概览更新于{" "}
              {updatedAt.toLocaleTimeString("zh-CN", {
                hour: "2-digit",
                minute: "2-digit",
              })}
            </span>
          )}
          <Button
            icon={<ArrowClockwise size={18} />}
            onClick={load}
            loading={refreshing}
          >
            刷新概览
          </Button>
        </div>
      </div>
      {error && <ErrorState error={error} retry={load} />}
      <section
        className="admin-stats"
        aria-label="商城概览"
        aria-busy={refreshing}
      >
        {[
          {
            title: "平台用户",
            value: overview?.userCount,
            icon: <UsersThree size={20} />,
          },
          {
            title: "在库商品",
            value: overview?.productCount,
            icon: <Package size={20} />,
          },
          {
            title: "全部订单",
            value: overview?.orderCount,
            icon: <Receipt size={20} />,
          },
          {
            title: "待处理订单",
            value: overview?.pendingOrderCount,
            icon: <Truck size={20} />,
          },
          {
            title: "已支付金额",
            value: overview ? money(overview.paidRevenueCents) : undefined,
            icon: <Wallet size={20} />,
          },
        ].map(({ title, value, icon }) => (
          <div className="admin-stat-item" key={title}>
            <div className="admin-stat-label">
              {title}
              {icon}
            </div>
            {value === undefined ? (
              <Skeleton.Input active size="small" />
            ) : (
              <Statistic value={value} />
            )}
          </div>
        ))}
      </section>
      <div className="admin-workbench">
        <Tabs
          className="admin-workspace-tabs"
          defaultActiveKey="users"
          items={[
            {
              key: "users",
              label: (
                <span>
                  <UsersThree size={18} />
                  用户与余额
                </span>
              ),
              children: <Users />,
            },
            {
              key: "products",
              label: (
                <span>
                  <Package size={18} />
                  商品与库存
                </span>
              ),
              children: <Products />,
            },
            {
              key: "orders",
              label: (
                <span>
                  <Truck size={18} />
                  订单与发货
                </span>
              ),
              children: <AdminOrders />,
            },
            {
              key: "refunds",
              label: (
                <span>
                  <Receipt size={18} />
                  售后审核
                </span>
              ),
              children: <Refunds admin />,
            },
            {
              key: "knowledge",
              label: (
                <span>
                  <BookOpenText size={18} />
                  导购知识库
                </span>
              ),
              children: <KnowledgeAdmin />,
            },
          ]}
        />
      </div>
    </div>
  );
}
function usePaged<T>(path: string, query = "") {
  const request = useRef<AbortController | null>(null);
  const [page, setPage] = useState(1);
  const [data, setData] = useState<PageResult<T>>({ items: [], total: 0 });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const resource = useRef({ path, page, query });
  const load = useCallback(async () => {
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    setLoading(true);
    try {
      const current = resource.current;
      const result = await api<PageResult<T>>(
        `${current.path}?page=${current.page}&pageSize=10&q=${encodeURIComponent(current.query)}`,
        { signal: controller.signal },
      );
      if (controller.signal.aborted) return;
      setData(result);
      setError("");
    } catch (e) {
      if (!controller.signal.aborted) setError(errorText(e));
    } finally {
      if (request.current === controller && !controller.signal.aborted)
        setLoading(false);
    }
  }, []);
  useEffect(() => {
    resource.current = { path, page, query };
    void load();
    return () => request.current?.abort();
  }, [load, path, page, query]);
  return {
    data,
    loading,
    error,
    load,
    setPage,
    pagination: {
      current: page,
      pageSize: 10,
      total: data.total,
      onChange: setPage,
      showSizeChanger: false,
      responsive: true,
      showLessItems: true,
    },
  };
}
function useRequestKey() {
  const previous = useRef({ signature: "", key: "" });
  return (payload: unknown) => {
    const signature = JSON.stringify(payload);
    if (signature !== previous.current.signature)
      previous.current = { signature, key: crypto.randomUUID() };
    return previous.current.key;
  };
}
function Users() {
  const [query, setQuery] = useState("");
  const list = usePaged<User>("/admin/users", query);
  const [target, setTarget] = useState<User | null>(null);
  const [busy, setBusy] = useState(false);
  const [form] = Form.useForm();
  const amountCents = parseMoneyCents(
    Form.useWatch("amount", form),
    1,
    10_000_000,
  );
  const requestKey = useRequestKey();
  const operation = useRef("");
  const { message } = App.useApp();
  const { refresh } = useSession();
  const credit = async (values: { amount: string; reason: string }) => {
    if (!target || busy) return;
    const parsedAmount = parseMoneyCents(values.amount, 1, 10_000_000);
    if (parsedAmount === null) return;
    setBusy(true);
    const payload = {
      amountCents: parsedAmount,
      reason: values.reason,
    };
    try {
      await post(`/admin/users/${target.id}/credit`, {
        ...payload,
        idempotencyKey: requestKey({
          ...payload,
          user: target.id,
          operation: operation.current,
        }),
      });
      message.success("余额分配成功");
      setTarget(null);
      await Promise.all([list.load(), refresh()]);
    } catch (e) {
      message.error(errorText(e));
    } finally {
      setBusy(false);
    }
  };
  const columns: ColumnsType<User> = [
    { title: "用户 ID", dataIndex: "id", width: 90 },
    { title: "用户名", dataIndex: "username" },
    { title: "昵称", dataIndex: "nickname" },
    {
      title: "角色",
      dataIndex: "role",
      render: (value) => <Tag>{value === "ADMIN" ? "管理员" : "用户"}</Tag>,
    },
    {
      title: "当前余额",
      dataIndex: "balanceCents",
      render: money,
      align: "right",
    },
    {
      title: "操作",
      key: "action",
      render: (_, item) => (
        <Button
          onClick={() => {
            operation.current = crypto.randomUUID();
            form.resetFields();
            setTarget(item);
          }}
        >
          分配余额
        </Button>
      ),
    },
  ];
  return (
    <>
      <div className="workspace-section-heading">
        <h2>用户与余额</h2>
        <p>查看用户账户，为演示购物分配平台余额。</p>
      </div>
      <div className="admin-toolbar">
        <Input.Search
          placeholder="搜索用户名或昵称"
          aria-label="搜索用户"
          allowClear
          onSearch={(value) => {
            setQuery(value);
            list.setPage(1);
          }}
        />
        <Button
          icon={<ArrowClockwise size={17} />}
          onClick={list.load}
          loading={list.loading}
        >
          刷新
        </Button>
      </div>
      {list.error && <ErrorState error={list.error} retry={list.load} />}
      <Table<User>
        rowKey="id"
        columns={columns}
        dataSource={list.data.items}
        loading={list.loading}
        pagination={list.pagination}
        scroll={{ x: 760 }}
      />
      <Modal
        rootClassName="precision-admin-modal"
        title={`为 ${target?.nickname || target?.username || "用户"} 分配余额`}
        open={!!target}
        onCancel={() => {
          if (!busy) setTarget(null);
        }}
        footer={null}
        forceRender
      >
        <Alert
          type="info"
          title={`当前余额 ${money(target?.balanceCents)}`}
          className="form-alert"
        />
        <Form
          form={form}
          name="admin-credit"
          layout="vertical"
          onFinish={credit}
          requiredMark={false}
          disabled={busy}
        >
          <Form.Item
            name="amount"
            label="分配金额（元）"
            rules={[
              {
                validator: async (_, value) => {
                  if (parseMoneyCents(value, 1, 10_000_000) === null)
                    throw new Error("金额为 0.01-100000 元，最多保留两位小数");
                },
              },
            ]}
          >
            <Input
              inputMode="decimal"
              prefix="¥"
              placeholder="0.00"
              className="full-width"
            />
          </Form.Item>
          <Form.Item
            name="reason"
            label="分配说明"
            rules={[
              { required: true, whitespace: true, message: "请填写操作原因" },
              { max: 200 },
            ]}
          >
            <Input maxLength={200} placeholder="例如：演示购物余额" />
          </Form.Item>
          <Button
            block
            type="primary"
            htmlType="submit"
            loading={busy}
            disabled={busy || amountCents === null}
          >
            确认分配
          </Button>
        </Form>
      </Modal>
    </>
  );
}
type ProductFormValues = {
  name: string;
  category: string;
  description: string;
  price: string;
  originalPrice?: string;
  imageUrl: string;
  manualUrl?: string;
  tags?: string;
  specifications?: string;
  enabled: boolean;
  featured: boolean;
};
function Products() {
  const [query, setQuery] = useState("");
  const list = usePaged<Product>("/admin/products", query);
  const [categories, setCategories] = useState<{ id: string; name: string }[]>(
    [],
  );
  const [categoriesLoading, setCategoriesLoading] = useState(true);
  const [categoriesError, setCategoriesError] = useState("");
  const categoriesRequest = useRef<AbortController | null>(null);
  const [editing, setEditing] = useState<Product | "new" | null>(null);
  const [inventory, setInventory] = useState<Product | null>(null);
  const [busy, setBusy] = useState(false);
  const [form] = Form.useForm<ProductFormValues>();
  const [stockForm] = Form.useForm();
  const priceCents = parseMoneyCents(
    Form.useWatch("price", form),
    1,
    100_000_000,
  );
  const referencePriceCents = parseReferencePrice(
    Form.useWatch("originalPrice", form),
  );
  const inventoryDelta = parseInventoryDelta(
    Form.useWatch("delta", stockForm),
    inventory?.stock || 0,
  );
  const { message } = App.useApp();
  const requestKey = useRequestKey();
  const operation = useRef("");
  const loadCategories = useCallback(async () => {
    categoriesRequest.current?.abort();
    const controller = new AbortController();
    categoriesRequest.current = controller;
    setCategoriesLoading(true);
    setCategoriesError("");
    try {
      const result = await api<{ id: string; name: string }[]>("/categories", {
        signal: controller.signal,
      });
      if (controller.signal.aborted) return;
      if (!Array.isArray(result) || !result.length)
        throw new Error("未读取到商品分类，请重新加载。");
      setCategories(result);
    } catch (e) {
      if (!controller.signal.aborted) setCategoriesError(errorText(e));
    } finally {
      if (categoriesRequest.current === controller) {
        categoriesRequest.current = null;
        if (!controller.signal.aborted) setCategoriesLoading(false);
      }
    }
  }, []);
  useEffect(() => {
    void loadCategories();
    return () => {
      categoriesRequest.current?.abort();
      categoriesRequest.current = null;
    };
  }, [loadCategories]);
  const openProduct = (item: Product | "new") => {
    form.resetFields();
    if (item !== "new")
      form.setFieldsValue({
        ...item,
        price: (item.priceCents / 100).toFixed(2),
        originalPrice: ((item.originalPriceCents || 0) / 100).toFixed(2),
        tags: item.tags?.join("，"),
        specifications: JSON.stringify(item.specifications || {}, null, 2),
      });
    else
      form.setFieldsValue({
        enabled: true,
        featured: false,
        specifications: "{}",
      });
    setEditing(item);
  };
  const save = async (values: ProductFormValues) => {
    if (!editing || busy) return;
    if (categoriesLoading || categoriesError || !categories.length) {
      message.warning("请先成功加载商品分类，再保存商品。");
      return;
    }
    const parsedPrice = parseMoneyCents(values.price, 1, 100_000_000);
    const parsedReference = parseReferencePrice(values.originalPrice);
    if (parsedPrice === null || parsedReference === null) return;
    setBusy(true);
    try {
      const payload = {
        name: values.name,
        category: values.category,
        categoryName:
          categories.find((item) => item.id === values.category)?.name ||
          values.category,
        description: values.description,
        priceCents: parsedPrice,
        originalPriceCents: parsedReference,
        imageUrl: values.imageUrl,
        manualUrl: values.manualUrl || "",
        tags: (values.tags || "")
          .split(/[,，]/)
          .map((value) => value.trim())
          .filter(Boolean),
        specifications: JSON.parse(values.specifications || "{}"),
        enabled: values.enabled,
        featured: values.featured,
      };
      if (editing === "new") await post("/admin/products", payload);
      else if (editing)
        await api(`/admin/products/${editing.id}`, {
          method: "PUT",
          body: JSON.stringify(payload),
        });
      message.success("商品已保存");
      setEditing(null);
      await list.load();
    } catch (e) {
      message.error(errorText(e));
    } finally {
      setBusy(false);
    }
  };
  const adjust = async (values: { delta: string; reason: string }) => {
    if (!inventory || busy) return;
    const delta = parseInventoryDelta(values.delta, inventory.stock);
    if (delta === null) return;
    const payload = { ...values, delta };
    setBusy(true);
    try {
      await post(`/admin/products/${inventory.id}/inventory`, {
        ...payload,
        idempotencyKey: requestKey({
          ...payload,
          product: inventory.id,
          operation: operation.current,
        }),
      });
      message.success("库存已调整");
      setInventory(null);
      await list.load();
    } catch (e) {
      message.error(errorText(e));
    } finally {
      setBusy(false);
    }
  };
  const columns: ColumnsType<Product> = [
    {
      title: "商品",
      dataIndex: "name",
      width: 300,
      render: (_, item) => (
        <div className="admin-product-cell">
          <span className="admin-product-thumbnail">
            <ProductImage product={item} />
          </span>
          <span>
            <strong>{item.name}</strong>
            <small>商品 ID {item.id}</small>
          </span>
        </div>
      ),
    },
    { title: "分类", dataIndex: "categoryName" },
    { title: "价格", dataIndex: "priceCents", render: money, align: "right" },
    { title: "库存", dataIndex: "stock", align: "right" },
    {
      title: "状态",
      dataIndex: "enabled",
      render: (enabled) => (
        <Tag color={enabled ? "blue" : "default"}>
          {enabled ? "上架" : "下架"}
        </Tag>
      ),
    },
    {
      title: "操作",
      key: "actions",
      render: (_, item) => (
        <Space>
          <Button size="small" onClick={() => openProduct(item)}>
            编辑
          </Button>
          <Button
            size="small"
            onClick={() => {
              operation.current = crypto.randomUUID();
              stockForm.resetFields();
              setInventory(item);
            }}
          >
            调整库存
          </Button>
        </Space>
      ),
    },
  ];
  return (
    <>
      <div className="workspace-section-heading">
        <h2>商品与库存</h2>
        <p>维护在售商品和规格，记录每一次库存变更。</p>
      </div>
      <div className="admin-toolbar">
        <Input.Search
          placeholder="搜索商品"
          aria-label="搜索管理商品"
          allowClear
          onSearch={(value) => {
            setQuery(value);
            list.setPage(1);
          }}
        />
        <Button
          type="primary"
          icon={<Plus size={18} />}
          onClick={() => openProduct("new")}
          disabled={
            categoriesLoading || !!categoriesError || !categories.length
          }
        >
          新建商品
        </Button>
      </div>
      {categoriesError && (
        <Alert
          className="form-alert"
          type="error"
          showIcon
          title="商品分类加载失败"
          description={categoriesError}
          action={
            <Button onClick={loadCategories} loading={categoriesLoading}>
              重新加载分类
            </Button>
          }
        />
      )}
      {list.error && <ErrorState error={list.error} retry={list.load} />}
      <Table<Product>
        rowKey="id"
        dataSource={list.data.items}
        columns={columns}
        pagination={list.pagination}
        loading={list.loading}
        scroll={{ x: 950 }}
      />
      <Modal
        rootClassName="precision-admin-modal"
        open={!!editing}
        title={editing === "new" ? "新建商品" : "编辑商品"}
        onCancel={() => {
          if (!busy) setEditing(null);
        }}
        width={720}
        footer={null}
        forceRender
      >
        {categoriesError && (
          <Alert
            className="form-alert"
            type="error"
            showIcon
            title="商品分类加载失败，暂时无法保存商品"
            action={
              <Button onClick={loadCategories} loading={categoriesLoading}>
                重新加载分类
              </Button>
            }
          />
        )}
        <Form
          form={form}
          name="admin-product"
          layout="vertical"
          onFinish={save}
          requiredMark={false}
          disabled={busy}
        >
          <div className="form-grid">
            <Form.Item
              name="name"
              label="商品名称"
              rules={[
                { required: true, whitespace: true, message: "请输入商品名称" },
                { max: 150 },
              ]}
            >
              <Input maxLength={150} />
            </Form.Item>
            <Form.Item
              name="category"
              label="分类"
              rules={[{ required: true, message: "请选择分类" }]}
            >
              <Select
                loading={categoriesLoading}
                disabled={categoriesLoading || !!categoriesError}
                options={categories.map((category) => ({
                  value: category.id,
                  label: category.name,
                }))}
              />
            </Form.Item>
          </div>
          <Form.Item
            name="description"
            label="商品介绍"
            rules={[
              { required: true, whitespace: true, message: "请输入商品介绍" },
            ]}
          >
            <Input.TextArea rows={3} maxLength={4000} />
          </Form.Item>
          <div className="form-grid">
            <Form.Item
              name="price"
              label="售价（元）"
              rules={[
                {
                  validator: async (_, value) => {
                    if (parseMoneyCents(value, 1, 100_000_000) === null)
                      throw new Error(
                        "售价为 0.01-1000000 元，最多保留两位小数",
                      );
                  },
                },
              ]}
            >
              <Input inputMode="decimal" prefix="¥" className="full-width" />
            </Form.Item>
            <Form.Item
              name="originalPrice"
              label="参考价（元）"
              rules={[
                {
                  validator: async (_, value) => {
                    if (parseReferencePrice(value) === null)
                      throw new Error(
                        "参考价为 0-1000000 元，最多保留两位小数",
                      );
                  },
                },
              ]}
            >
              <Input inputMode="decimal" prefix="¥" className="full-width" />
            </Form.Item>
          </div>
          <Form.Item
            name="imageUrl"
            label="商品图片地址"
            rules={[
              { required: true, message: "请输入图片地址" },
              {
                pattern: /^(\/[^/]|https:\/\/)/,
                message: "使用站内路径或 HTTPS 地址",
              },
            ]}
          >
            <Input placeholder="/products/商品编号.svg" />
          </Form.Item>
          <Form.Item name="manualUrl" label="说明书地址">
            <Input placeholder="/manuals/商品编号.md" />
          </Form.Item>
          <Form.Item name="tags" label="商品标签">
            <Input placeholder="用逗号分隔，例如：无线，轻巧，便携" />
          </Form.Item>
          <Form.Item
            name="specifications"
            label="规格参数（JSON 对象）"
            rules={[
              {
                validator: async (_, value) => {
                  try {
                    const parsed = JSON.parse(value || "{}");
                    if (
                      !parsed ||
                      Array.isArray(parsed) ||
                      typeof parsed !== "object"
                    )
                      throw new Error();
                    for (const entry of Object.values(parsed))
                      if (
                        typeof entry !== "string" &&
                        typeof entry !== "number"
                      )
                        throw new Error();
                  } catch {
                    throw new Error(
                      '请输入规格名称与值构成的 JSON，例如 {"颜色":"银灰"}',
                    );
                  }
                },
              },
            ]}
          >
            <Input.TextArea rows={4} />
          </Form.Item>
          <Space size={32}>
            <Form.Item name="enabled" label="商品上架" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item name="featured" label="精选推荐" valuePropName="checked">
              <Switch />
            </Form.Item>
          </Space>
          <Button
            block
            type="primary"
            htmlType="submit"
            loading={busy}
            disabled={
              busy ||
              categoriesLoading ||
              !!categoriesError ||
              !categories.length ||
              priceCents === null ||
              referencePriceCents === null
            }
          >
            保存商品
          </Button>
        </Form>
      </Modal>
      <Modal
        rootClassName="precision-admin-modal"
        open={!!inventory}
        title={`调整库存：${inventory?.name || ""}`}
        onCancel={() => {
          if (!busy) setInventory(null);
        }}
        footer={null}
        forceRender
      >
        <p>当前可售库存：{inventory?.stock} 件。正数入库，负数调减。</p>
        <Form
          form={stockForm}
          name="admin-inventory"
          layout="vertical"
          onFinish={adjust}
          requiredMark={false}
          disabled={busy}
        >
          <Form.Item
            name="delta"
            label="变更数量"
            rules={[
              {
                validator: async (_, value) => {
                  if (
                    parseInventoryDelta(value, inventory?.stock || 0) === null
                  )
                    throw new Error(
                      `请输入 -${inventory?.stock || 0} 到 100000 的非零整数`,
                    );
                },
              },
            ]}
          >
            <Input
              inputMode="text"
              placeholder="例如：10 或 -2"
              className="full-width"
            />
          </Form.Item>
          <Form.Item
            name="reason"
            label="操作原因"
            rules={[
              { required: true, whitespace: true, message: "请填写操作原因" },
            ]}
          >
            <Input maxLength={200} />
          </Form.Item>
          <Button
            block
            type="primary"
            htmlType="submit"
            loading={busy}
            disabled={busy || inventoryDelta === null}
          >
            确认调整
          </Button>
        </Form>
      </Modal>
    </>
  );
}
function AdminOrders() {
  const list = usePaged<Order>("/admin/orders");
  const [target, setTarget] = useState<Order | null>(null);
  const [busy, setBusy] = useState(false);
  const [form] = Form.useForm();
  const { message } = App.useApp();
  const ship = async (values: { trackingNo: string }) => {
    if (!target) return;
    setBusy(true);
    try {
      await post(`/admin/orders/${target.id}/ship`, values);
      message.success("订单已发货");
      setTarget(null);
      await list.load();
    } catch (e) {
      message.error(errorText(e));
    } finally {
      setBusy(false);
    }
  };
  const columns: ColumnsType<Order> = [
    { title: "订单号", dataIndex: "orderNo", width: 200 },
    { title: "创建时间", dataIndex: "createdAt", render: date },
    {
      title: "商品",
      key: "items",
      width: 240,
      render: (_, item) =>
        item.items
          .map((product) => `${product.name} × ${product.quantity}`)
          .join("、"),
    },
    { title: "金额", dataIndex: "totalCents", render: money, align: "right" },
    {
      title: "状态",
      dataIndex: "status",
      render: (status) => <Tag>{orderLabels[status] || status}</Tag>,
    },
    {
      title: "操作",
      key: "actions",
      render: (_, item) =>
        item.status === "PAID" ? (
          <Button
            type="primary"
            size="small"
            onClick={() => {
              form.resetFields();
              form.setFieldsValue({ trackingNo: `BIT${Date.now()}` });
              setTarget(item);
            }}
          >
            模拟发货
          </Button>
        ) : (
          <span className="muted">{item.trackingNo || "无需处理"}</span>
        ),
    },
  ];
  return (
    <>
      <div className="workspace-section-heading">
        <h2>订单与发货</h2>
        <p>查看订单明细与收货信息，处理已付款的订单。</p>
      </div>
      <div className="admin-toolbar">
        <p className="muted">
          仅已付款的订单可以发货。此演示使用模拟物流单号。
        </p>
        <Button
          icon={<ArrowClockwise size={17} />}
          onClick={list.load}
          loading={list.loading}
        >
          刷新订单
        </Button>
      </div>
      {list.error && <ErrorState error={list.error} retry={list.load} />}
      <Table<Order>
        rowKey="id"
        columns={columns}
        dataSource={list.data.items}
        loading={list.loading}
        pagination={list.pagination}
        scroll={{ x: 1100 }}
        expandable={{
          expandedRowRender: (item) => (
            <p>
              收货人：{item.address.recipient}　电话：{item.address.phone}
              　地址：{item.address.detail}
            </p>
          ),
        }}
      />
      <Modal
        rootClassName="precision-admin-modal"
        title="模拟发货"
        open={!!target}
        onCancel={() => {
          if (!busy) setTarget(null);
        }}
        footer={null}
        forceRender
      >
        <p>订单：{target?.orderNo}</p>
        <Form
          name="admin-shipping"
          form={form}
          layout="vertical"
          onFinish={ship}
        >
          <Form.Item
            name="trackingNo"
            label="物流单号"
            rules={[
              { required: true, whitespace: true, message: "请输入物流单号" },
              { max: 80 },
            ]}
          >
            <Input maxLength={80} />
          </Form.Item>
          <Button block type="primary" htmlType="submit" loading={busy}>
            确认发货
          </Button>
        </Form>
      </Modal>
    </>
  );
}
