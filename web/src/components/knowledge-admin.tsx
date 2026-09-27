"use client";
import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { Alert, App, Button, Statistic, Table, Tag } from "antd";
import { ArrowClockwise } from "@phosphor-icons/react";
import { api, post, date, errorText } from "@/lib/api";
type KnowledgeDocument = {
  id: string;
  productId: number;
  title: string;
  status: string;
  chunkCount: number;
  errorCode?: string;
  updatedAt: string;
};
type KnowledgeStatus = { running: boolean; documents: KnowledgeDocument[] };
const labels: Record<string, string> = {
  READY: "已入库",
  PROCESSING: "处理中",
  FAILED: "入库失败",
  PENDING: "等待处理",
};
export function KnowledgeAdmin() {
  const [data, setData] = useState<KnowledgeStatus>({
    running: false,
    documents: [],
  });
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const { message, modal } = App.useApp();
  const load = useCallback(async () => {
    setLoading(true);
    try {
      setData(await api<KnowledgeStatus>("/ai/knowledge"));
      setError("");
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
    if (!data.running) return;
    const timer = setInterval(() => {
      void load();
    }, 5000);
    return () => clearInterval(timer);
  }, [data.running, load]);
  const reindex = () =>
    modal.confirm({
      title: "重新同步商品说明书？",
      content:
        "系统会解析说明书、重新切块并更新知识库。处理过程中可继续查看已有商品与订单。",
      okText: "开始同步",
      cancelText: "取消",
      onOk: async () => {
        setBusy(true);
        try {
          const result = await post<{ started: boolean }>(
            "/ai/knowledge/reindex",
          );
          message.success(
            result.started ? "知识库同步已启动" : "已有同步任务正在执行",
          );
          await load();
        } catch (e) {
          message.error(errorText(e));
          throw e;
        } finally {
          setBusy(false);
        }
      },
    });
  return (
    <section className="knowledge-admin">
      <div className="admin-toolbar">
        <p className="muted">说明书解析、语义切块和向量索引的实时入库状态。</p>
        <div className="order-actions">
          <Button
            icon={<ArrowClockwise size={17} />}
            onClick={load}
            loading={loading}
          >
            刷新
          </Button>
          <Button
            type="primary"
            onClick={reindex}
            loading={busy}
            disabled={data.running}
          >
            同步知识库
          </Button>
        </div>
      </div>
      {error && <Alert type="error" title={error} className="form-alert" />}
      {data.running && (
        <Alert
          type="info"
          title="正在同步商品说明书，状态每 5 秒更新。"
          showIcon
          className="form-alert"
        />
      )}
      <div className="knowledge-stats">
        <Statistic title="文档总数" value={data.documents.length} />
        <Statistic
          title="已完成"
          value={
            data.documents.filter((item) => item.status === "READY").length
          }
        />
        <Statistic
          title="知识片段"
          value={data.documents.reduce((sum, item) => sum + item.chunkCount, 0)}
        />
        <Statistic
          title="待重试"
          value={
            data.documents.filter((item) => item.status === "FAILED").length
          }
        />
      </div>
      <Table<KnowledgeDocument>
        rowKey="id"
        dataSource={data.documents}
        pagination={{ pageSize: 10, showSizeChanger: true }}
        loading={loading && !data.documents.length}
        scroll={{ x: 760 }}
        columns={[
          {
            title: "商品说明书",
            dataIndex: "title",
            render: (title, item) => (
              <Link href={`/products/${item.productId}`}>{title}</Link>
            ),
          },
          {
            title: "状态",
            dataIndex: "status",
            render: (status) => (
              <Tag
                color={
                  status === "READY"
                    ? "blue"
                    : status === "FAILED"
                      ? "error"
                      : "default"
                }
              >
                {labels[status] || status}
              </Tag>
            ),
          },
          { title: "片段数", dataIndex: "chunkCount", align: "right" },
          { title: "更新时间", dataIndex: "updatedAt", render: date },
          {
            title: "处理信息",
            dataIndex: "errorCode",
            render: (value) => value || "正常",
          },
        ]}
      />
    </section>
  );
}
