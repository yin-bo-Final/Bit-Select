"use client";
import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { Alert, App, Button, Skeleton, Statistic, Table, Tag } from "antd";
import {
  ArrowClockwise,
  ArrowRight,
  BookOpenText,
  CheckCircle,
  FileText,
  Database,
  Stack,
  WarningCircle,
} from "@phosphor-icons/react";
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
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const { message, modal } = App.useApp();
  const load = useCallback(async () => {
    setLoading(true);
    try {
      setData(await api<KnowledgeStatus>("/ai/knowledge"));
      setLoaded(true);
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
      <div className="workspace-section-heading">
        <h2>导购知识库</h2>
        <p>从商品说明书到可引用的回答，查看每份资料的同步结果。</p>
      </div>
      <div className="admin-toolbar">
        <div className="knowledge-operation-state">
          <BookOpenText size={21} />
          <span>
            {data.running ? "正在同步商品资料" : "商品说明书管理"}
            <small>
              {data.running
                ? "同步过程中每 5 秒刷新状态"
                : "同步新增或变更的说明书，可重试失败文档"}
            </small>
          </span>
        </div>
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
      {error && (
        <Alert type="error" showIcon title={error} className="form-alert" />
      )}
      {data.running && (
        <Alert
          type="info"
          title="正在同步商品说明书，状态每 5 秒更新。"
          showIcon
          className="form-alert"
        />
      )}
      <ol className="knowledge-pipeline" aria-label="知识库资料处理流程">
        {[
          { title: "解析说明书", icon: <FileText size={19} /> },
          { title: "语义切块", icon: <Stack size={19} /> },
          { title: "向量索引", icon: <Database size={19} /> },
          { title: "回答引用", icon: <BookOpenText size={19} /> },
        ].map((step, index) => (
          <li key={step.title}>
            <span>{step.icon}</span>
            <strong>{step.title}</strong>
            {index < 3 && <ArrowRight size={15} aria-hidden />}
          </li>
        ))}
      </ol>
      <div className="knowledge-stats">
        {[
          {
            title: "文档总数",
            value: data.documents.length,
            icon: <FileText size={18} />,
          },
          {
            title: "已完成",
            value: data.documents.filter((item) => item.status === "READY")
              .length,
            icon: <CheckCircle size={18} />,
          },
          {
            title: "知识片段",
            value: data.documents.reduce(
              (sum, item) => sum + item.chunkCount,
              0,
            ),
            icon: <Stack size={18} />,
          },
          {
            title: "待重试",
            value: data.documents.filter((item) => item.status === "FAILED")
              .length,
            icon: <WarningCircle size={18} />,
          },
        ].map(({ title, value, icon }) => (
          <div className="knowledge-stat-item" key={title}>
            <div className="admin-stat-label">
              {title}
              {icon}
            </div>
            {loaded ? (
              <Statistic value={value} />
            ) : (
              <Skeleton.Input active size="small" />
            )}
          </div>
        ))}
      </div>
      <Table<KnowledgeDocument>
        rowKey="id"
        dataSource={data.documents}
        pagination={{
          defaultPageSize: 10,
          showSizeChanger: true,
          responsive: true,
          showLessItems: true,
        }}
        loading={loading && !data.documents.length}
        scroll={{ x: 900 }}
        locale={{
          emptyText: loaded
            ? "还没有商品资料。点击同步知识库，导入说明书。"
            : "正在读取知识库状态",
        }}
        columns={[
          {
            title: "商品说明书",
            dataIndex: "title",
            width: 320,
            render: (title, item) => (
              <Link
                className="knowledge-document-link"
                href={`/products/${item.productId}`}
              >
                <FileText size={19} />
                <span>
                  {title}
                  <small>商品 ID {item.productId}</small>
                </span>
              </Link>
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
