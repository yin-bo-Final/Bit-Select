"use client";

import { useEffect, useState } from "react";
import { Alert, Button, Drawer, Select, Table } from "antd";
import type { ColumnsType } from "antd/es/table";
import { ArrowRight, Clock, Info } from "@phosphor-icons/react";
import type {
  OperationsOverview,
  OperationsPage,
  OperationsQueueEvent,
} from "@/lib/operations-types";
import { useOperationsPolling } from "@/lib/operations-polling";
import {
  OperationsDataState,
  OperationsFlow,
  OperationsRefresh,
  OperationsShell,
  OperationsStatus,
  operationsTime,
} from "@/components/operations-shared";

export default function OperationsQueuesPage() {
  const [kind, setKind] = useState<"commerce" | "memory">("commerce");
  const [status, setStatus] = useState("all");
  const [page, setPage] = useState(1);
  const [selected, setSelected] = useState<string | null>(null);
  const overview = useOperationsPolling<OperationsOverview>(
    "/admin/ops/overview",
  );
  const query = new URLSearchParams({
    kind,
    status,
    page: String(page),
    pageSize: "20",
  });
  const events = useOperationsPolling<OperationsPage<OperationsQueueEvent>>(
    `/admin/ops/events?${query}`,
  );
  const detail = useOperationsPolling<OperationsQueueEvent>(
    selected
      ? `/admin/ops/events/${kind}/${encodeURIComponent(selected)}`
      : null,
  );
  useEffect(() => {
    overview.setAutoRefresh(events.autoRefresh);
    detail.setAutoRefresh(events.autoRefresh);
  }, [events.autoRefresh, overview.setAutoRefresh, detail.setAutoRefresh]);
  useEffect(() => {
    if (!events.data) return;
    const lastPage = Math.max(1, Math.ceil(events.data.total / 20));
    if (page > lastPage) setPage(lastPage);
  }, [events.data, page]);
  const queue = overview.data?.queues.find((item) => item.kind === kind);
  const currentEvent = detail.data?.id === selected ? detail.data : null;
  const refreshState = {
    ...events,
    refreshing: events.refreshing || overview.refreshing || detail.refreshing,
    refresh: () => {
      events.refresh();
      overview.refresh();
      detail.refresh();
    },
  };
  const columns: ColumnsType<OperationsQueueEvent> = [
    {
      title: "事件 / 任务",
      key: "event",
      render: (_, row) => (
        <div className="operations-request-question">
          <button onClick={() => setSelected(row.id)}>
            {row.eventType || "未命名事件"}
          </button>
          <small className="operations-monospace" title={String(row.id)}>
            {row.id}
          </small>
        </div>
      ),
    },
    {
      title: "状态",
      key: "status",
      width: 140,
      render: (_, row) => (
        <div className="operations-status-stack">
          <OperationsStatus status={row.status} />
          <small>{row.rawStatus}</small>
        </div>
      ),
    },
    {
      title: "尝试计数",
      dataIndex: "attempts",
      width: 100,
      render: (value: number) => (
        <span className="operations-monospace">{value}</span>
      ),
    },
    {
      title: "创建 / 最近更新",
      key: "time",
      width: 205,
      render: (_, row) => (
        <div className="operations-duration-cell">
          <span>{operationsTime(row.createdAt)}</span>
          <small>{operationsTime(row.updatedAt)}</small>
        </div>
      ),
    },
    {
      title: "错误代码",
      dataIndex: "errorCode",
      width: 160,
      render: (value: string | null) => (
        <span className="operations-error-code">{value || "—"}</span>
      ),
    },
    {
      title: "详情",
      key: "details",
      width: 66,
      render: (_, row) => (
        <Button
          type="text"
          aria-label={`查看任务 ${row.id}`}
          icon={<ArrowRight size={18} />}
          onClick={() => setSelected(row.id)}
        />
      ),
    },
  ];
  return (
    <OperationsShell
      title="可靠任务队列"
      description="追踪交易事件与长期记忆任务，查看当前处理位置、重试记录和需要排查的失败。"
      actions={
        <OperationsRefresh state={refreshState}>
          <Select
            value={kind}
            aria-label="任务类型"
            onChange={(value) => {
              setKind(value);
              setPage(1);
              setSelected(null);
            }}
            options={[
              { value: "commerce", label: "交易可靠事件" },
              { value: "memory", label: "长期记忆任务" },
            ]}
          />
          <Select
            value={status}
            aria-label="任务状态"
            onChange={(value) => {
              setStatus(value);
              setPage(1);
              setSelected(null);
            }}
            options={[
              { value: "all", label: "全部状态" },
              { value: "pending", label: "待处理" },
              { value: "processing", label: "处理中" },
              { value: "retrying", label: "等待重试" },
              { value: "completed", label: "已完成" },
              { value: "failed", label: "失败" },
            ]}
          />
        </OperationsRefresh>
      }
    >
      <section className="operations-panel operations-queue-flow-panel">
        <div className="operations-section-heading">
          <div>
            <h2>
              {queue?.name ||
                (kind === "commerce" ? "交易可靠事件" : "长期记忆任务")}
            </h2>
            <p>
              {kind === "commerce"
                ? "事务先保存事件，再由后台任务投递消息。"
                : "对话结束后，异步提取、去重并写入长期记忆。"}
            </p>
          </div>
          <span className="operations-record-count">
            {queue ? `${queue.total.toLocaleString()} 条记录` : "—"}
          </span>
        </div>
        {overview.error && (
          <Alert
            showIcon
            type="warning"
            title="队列汇总暂时无法读取"
            description={overview.error}
          />
        )}
        <OperationsFlow
          label={kind === "commerce" ? "可靠事件处理链路" : "长期记忆处理链路"}
          steps={[
            {
              id: "origin",
              label: kind === "commerce" ? "交易事务" : "完成对话",
              description:
                kind === "commerce" ? "保存业务及事件记录" : "创建个人记忆任务",
            },
            {
              id: "pending",
              label: "等待处理",
              description: "数据库待处理状态",
              value: queue?.pending,
            },
            {
              id: "processing",
              label: kind === "commerce" ? "消息投递" : "记忆处理",
              description:
                kind === "commerce"
                  ? "后台投递至 RocketMQ"
                  : "提取、去重与更新",
              value: kind === "memory" ? queue?.processing : undefined,
            },
            {
              id: "completed",
              label: "记录完成",
              description:
                kind === "commerce" ? "投递记录标记完成" : "记忆任务标记完成",
              value: queue?.completed,
            },
          ]}
        />
        <div className="operations-queue-exceptions">
          <div>
            <span>等待重试</span>
            <strong>{queue?.retrying.toLocaleString() ?? "—"}</strong>
            <p>已执行过，等待后续处理</p>
          </div>
          <div>
            <span>失败待排查</span>
            <strong>{queue?.failed.toLocaleString() ?? "—"}</strong>
            <p>
              {kind === "memory"
                ? "已达重试上限，需人工排查"
                : "当前投递记录没有终止失败策略"}
            </p>
          </div>
          <div>
            <span>最早待处理记录</span>
            <strong className="operations-oldest-time">
              {operationsTime(queue?.oldestPendingAt)}
            </strong>
            <p>依据数据库中的任务状态</p>
          </div>
        </div>
        <p className="operations-scope">
          <Info size={17} />
          <span>
            {queue?.scope ||
              "统计口径为数据库投递 / 处理状态，不是消息代理的消费积压。"}
          </span>
        </p>
      </section>
      <section className="operations-panel">
        <div className="operations-section-heading">
          <div>
            <h2>任务记录</h2>
            <p>不展示消息载荷，仅查看任务标识、状态和时间。</p>
          </div>
        </div>
        <OperationsDataState
          loading={events.loading}
          error={events.error}
          refresh={events.refresh}
          empty={!events.data?.total}
          emptyText="暂无符合条件的任务记录"
        >
          <Table
            rowKey="id"
            size="small"
            columns={columns}
            dataSource={events.data?.items || []}
            loading={events.refreshing}
            pagination={{
              current: page,
              pageSize: 20,
              total: events.data?.total || 0,
              showSizeChanger: false,
              showTotal: (total) => `共 ${total} 条`,
              onChange: (value) => {
                setPage(value);
                setSelected(null);
              },
            }}
            scroll={{ x: 920 }}
          />
        </OperationsDataState>
      </section>
      <Drawer
        rootClassName="precision-drawer operations-event-drawer"
        title="任务记录详情"
        size={480}
        open={!!selected}
        onClose={() => setSelected(null)}
        extra={
          <Button onClick={detail.refresh} loading={detail.refreshing}>
            刷新详情
          </Button>
        }
      >
        <OperationsDataState
          loading={detail.loading}
          error={detail.error}
          refresh={detail.refresh}
        >
          {detail.updatedAt && (
            <p className="operations-caption">
              {detail.error
                ? "刷新失败，以下保留上次成功读取的记录"
                : "详情读取时间"}
              ：{detail.updatedAt.toLocaleString("zh-CN", { hour12: false })}
            </p>
          )}
          {currentEvent && (
            <>
              <div className="operations-event-heading">
                <OperationsStatus status={currentEvent.status} />
                <h2>{currentEvent.eventType}</h2>
                <p>原始状态：{currentEvent.rawStatus}</p>
              </div>
              {currentEvent.status === "failed" && (
                <Alert
                  type="warning"
                  showIcon
                  title="此任务需要人工排查"
                  description={
                    currentEvent.errorCode
                      ? `错误代码：${currentEvent.errorCode}`
                      : "任务已达重试上限。此页面不执行重试或修改。"
                  }
                />
              )}
              <ol className="operations-timeline">
                <li>
                  <span className="operations-timeline-marker">
                    <Clock size={16} />
                  </span>
                  <div className="operations-timeline-content">
                    <h3>创建任务记录</h3>
                    <p>{operationsTime(currentEvent.createdAt)}</p>
                  </div>
                </li>
                {currentEvent.updatedAt && (
                  <li>
                    <span className="operations-timeline-marker">
                      <Clock size={16} />
                    </span>
                    <div className="operations-timeline-content">
                      <h3>最近一次更新</h3>
                      <p>{operationsTime(currentEvent.updatedAt)}</p>
                      <OperationsStatus status={currentEvent.status} />
                    </div>
                  </li>
                )}
                {currentEvent.completedAt && (
                  <li data-status="completed">
                    <span className="operations-timeline-marker">
                      <Clock size={16} />
                    </span>
                    <div className="operations-timeline-content">
                      <h3>记录标记完成</h3>
                      <p>{operationsTime(currentEvent.completedAt)}</p>
                    </div>
                  </li>
                )}
              </ol>
              <dl className="operations-metadata">
                <div>
                  <dt>任务 ID</dt>
                  <dd>{currentEvent.id}</dd>
                </div>
                <div>
                  <dt>关联标识</dt>
                  <dd>{currentEvent.aggregateId || "—"}</dd>
                </div>
                <div>
                  <dt>主题</dt>
                  <dd>{currentEvent.topic || "—"}</dd>
                </div>
                <div>
                  <dt>尝试计数</dt>
                  <dd>{currentEvent.attempts}</dd>
                </div>
                <div>
                  <dt>错误代码</dt>
                  <dd>{currentEvent.errorCode || "—"}</dd>
                </div>
              </dl>
              <p className="operations-caption">
                时间轴仅呈现数据库现有时间字段，不能用于还原每一次尝试。
              </p>
            </>
          )}
        </OperationsDataState>
      </Drawer>
    </OperationsShell>
  );
}
