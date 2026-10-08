"use client";

import { useEffect, useState } from "react";
import { Alert, Button, Empty, InputNumber, Select, Table, Tag } from "antd";
import type { ColumnsType } from "antd/es/table";
import {
  ArrowRight,
  Check,
  CircleNotch,
  Clock,
  Stop,
  WarningCircle,
} from "@phosphor-icons/react";
import { useOperationsPolling } from "@/lib/operations-polling";
import type {
  OperationsPage,
  OperationsRequest,
  OperationsRequestDetail,
} from "@/lib/operations-types";
import {
  OperationsDataState,
  OperationsRefresh,
  OperationsShell,
  OperationsStatus,
  operationsDuration,
  operationsTime,
} from "@/components/operations-shared";

export default function OperationsConversationsPage() {
  const [status, setStatus] = useState("all");
  const [userFilter, setUserFilter] = useState<number | null>(null);
  const [userId, setUserId] = useState<number | null>(null);
  const [page, setPage] = useState(1);
  const [selected, setSelected] = useState<string | null>(null);
  const query = new URLSearchParams({
    page: String(page),
    pageSize: "10",
    status,
  });
  if (userId !== null) query.set("userId", String(userId));
  const list = useOperationsPolling<OperationsPage<OperationsRequest>>(
    `/ai/admin/ops/requests?${query}`,
  );
  const detail = useOperationsPolling<OperationsRequestDetail>(
    selected ? `/ai/admin/ops/requests/${encodeURIComponent(selected)}` : null,
  );
  useEffect(() => {
    detail.setAutoRefresh(list.autoRefresh);
  }, [list.autoRefresh, detail.setAutoRefresh]);
  useEffect(() => {
    if (!list.data) return;
    const lastPage = Math.max(1, Math.ceil(list.data.total / 10));
    if (page > lastPage) setPage(lastPage);
  }, [list.data, page]);

  const columns: ColumnsType<OperationsRequest> = [
    {
      title: "问题 / 用户",
      key: "question",
      render: (_, row) => (
        <div className="operations-request-question">
          <button
            onClick={() => setSelected(row.id)}
            aria-label={`查看请求：${row.questionPreview || row.id}`}
          >
            {row.questionPreview || "暂无问题摘要"}
          </button>
          <small>
            用户 {row.userId} · {operationsTime(row.startedAt)}
          </small>
        </div>
      ),
    },
    {
      title: "状态",
      key: "status",
      width: 130,
      render: (_, row) => (
        <div className="operations-status-stack">
          <OperationsStatus status={row.status} />
          {row.stale && <Tag color="warning">长时间未更新</Tag>}
        </div>
      ),
    },
    {
      title: "首字 / 总耗时",
      key: "duration",
      width: 138,
      render: (_, row) => (
        <div className="operations-duration-cell">
          <span>{operationsDuration(row.firstTokenMs)}</span>
          <small>总计 {operationsDuration(row.totalMs)}</small>
        </div>
      ),
    },
    {
      title: "步骤",
      key: "action",
      width: 64,
      render: (_, row) => (
        <Button
          type="text"
          icon={<ArrowRight size={18} />}
          aria-label={`查看请求 ${row.id} 的执行步骤`}
          onClick={() => setSelected(row.id)}
        />
      ),
    },
  ];
  return (
    <OperationsShell
      title="AI 请求追踪"
      description="从收到问题到保存回答，查看每次请求真实执行过的步骤与耗时。"
      actions={
        <OperationsRefresh state={list}>
          <Select
            aria-label="请求状态"
            value={status}
            onChange={(value) => {
              setStatus(value);
              setPage(1);
              setSelected(null);
            }}
            options={[
              { value: "all", label: "全部状态" },
              { value: "running", label: "执行中" },
              { value: "completed", label: "已完成" },
              { value: "failed", label: "失败" },
              { value: "cancelled", label: "已停止" },
            ]}
          />
          <InputNumber
            aria-label="按用户ID筛选请求"
            placeholder="用户 ID"
            value={userFilter}
            min={1}
            precision={0}
            onChange={setUserFilter}
          />
          <Button
            onClick={() => {
              setUserId(userFilter);
              setPage(1);
              setSelected(null);
            }}
          >
            筛选
          </Button>
          {userId !== null && (
            <Button
              type="text"
              onClick={() => {
                setUserId(null);
                setUserFilter(null);
                setPage(1);
                setSelected(null);
              }}
            >
              清除
            </Button>
          )}
        </OperationsRefresh>
      }
    >
      <div className="operations-trace-layout">
        <section
          className="operations-panel operations-request-list"
          aria-label="AI 请求列表"
        >
          <div className="operations-section-heading">
            <div>
              <h2>请求记录</h2>
              <p>仅展示问题摘要，不读取完整对话内容。</p>
            </div>
            <span className="operations-record-count">
              {list.data ? `${list.data.total} 条` : "—"}
            </span>
          </div>
          <OperationsDataState
            loading={list.loading}
            error={list.error}
            refresh={list.refresh}
            empty={!list.data?.total}
            emptyText="暂无符合条件的请求。新请求会在这里出现，历史对话没有阶段记录。"
          >
            <Table
              columns={columns}
              dataSource={list.data?.items || []}
              rowKey="id"
              size="small"
              loading={list.refreshing}
              rowClassName={(row) =>
                row.id === selected ? "operations-selected-row" : ""
              }
              pagination={{
                current: page,
                pageSize: 10,
                total: list.data?.total || 0,
                showSizeChanger: false,
                onChange: (value) => {
                  setPage(value);
                  setSelected(null);
                },
                showTotal: (total) => `共 ${total} 条`,
              }}
              scroll={{ x: 610 }}
            />
          </OperationsDataState>
        </section>
        <section
          className="operations-panel operations-trace-detail"
          aria-label="请求步骤详情"
        >
          <div className="operations-section-heading">
            <div>
              <h2>执行步骤</h2>
              <p>按实际发生顺序展示。</p>
            </div>
            {selected && (
              <Button
                size="small"
                onClick={detail.refresh}
                loading={detail.refreshing}
              >
                刷新详情
              </Button>
            )}
          </div>
          {!selected ? (
            <div className="operations-detail-placeholder">
              <Clock size={38} weight="light" />
              <h3>选择一条请求</h3>
              <p>查看处理阶段、首字延迟与最终结果。</p>
            </div>
          ) : (
            <OperationsDataState
              loading={detail.loading}
              error={detail.error}
              refresh={detail.refresh}
            >
              {detail.data && detail.data.id === selected && (
                <RequestDetail request={detail.data} />
              )}
            </OperationsDataState>
          )}
        </section>
      </div>
    </OperationsShell>
  );
}

function RequestDetail({ request }: { request: OperationsRequestDetail }) {
  const compactId = (value: string) =>
    value.length > 18 ? `${value.slice(0, 8)}…${value.slice(-6)}` : value;
  return (
    <div className="operations-request-detail-body">
      <div className="operations-request-detail-title">
        <OperationsStatus status={request.status} />
        <p>{request.questionPreview || "暂无问题摘要"}</p>
      </div>
      {request.stale && (
        <Alert
          type="warning"
          showIcon
          title="该请求长时间未更新"
          description="此标记表示记录可能遗留，并不等同于已确认失败。"
        />
      )}
      {request.errorCode && (
        <Alert type="error" title={`错误代码：${request.errorCode}`} />
      )}
      <dl className="operations-request-metrics">
        <div>
          <dt>首字延迟</dt>
          <dd>{operationsDuration(request.firstTokenMs)}</dd>
        </div>
        <div>
          <dt>总耗时</dt>
          <dd>{operationsDuration(request.totalMs)}</dd>
        </div>
        <div>
          <dt>输出字符</dt>
          <dd
            className={
              request.status === "running"
                ? "operations-pending-metric"
                : undefined
            }
          >
            {request.status === "running"
              ? "结束后统计"
              : request.outputChars.toLocaleString()}
          </dd>
        </div>
      </dl>
      {request.steps.length ? (
        <ol className="operations-timeline" aria-label="实际执行步骤">
          {request.steps.map((step, index) => (
            <li key={`${step.code}-${index}`} data-status={step.status}>
              <span className="operations-timeline-marker">
                {step.status === "completed" ? (
                  <Check size={16} />
                ) : step.status === "failed" ? (
                  <WarningCircle size={17} />
                ) : step.status === "cancelled" ? (
                  <Stop size={14} />
                ) : (
                  <CircleNotch size={17} />
                )}
              </span>
              <div className="operations-timeline-content">
                <div>
                  <h3>{step.label}</h3>
                  <OperationsStatus status={step.status} />
                </div>
                <p>{operationsTime(step.startedAt)}</p>
                <span>{operationsDuration(step.durationMs)}</span>
                {step.finishedAt && (
                  <small>结束于 {operationsTime(step.finishedAt)}</small>
                )}
              </div>
            </li>
          ))}
        </ol>
      ) : (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="尚未记录处理步骤"
        />
      )}
      <dl className="operations-metadata">
        <div>
          <dt>请求 ID</dt>
          <dd title={request.id}>{compactId(request.id)}</dd>
        </div>
        <div>
          <dt>会话 ID</dt>
          <dd title={request.conversationId}>
            {compactId(request.conversationId)}
          </dd>
        </div>
        <div>
          <dt>用户 ID</dt>
          <dd>{request.userId}</dd>
        </div>
        <div>
          <dt>开始时间</dt>
          <dd>{operationsTime(request.startedAt)}</dd>
        </div>
        <div>
          <dt>最后活动</dt>
          <dd>{operationsTime(request.lastActivityAt)}</dd>
        </div>
        <div>
          <dt>结束时间</dt>
          <dd>{operationsTime(request.finishedAt)}</dd>
        </div>
      </dl>
    </div>
  );
}
