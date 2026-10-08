"use client";

import Link from "next/link";
import { Alert, Empty } from "antd";
import {
  ArrowRight,
  BookOpenText,
  ChatCircleDots,
  Database,
  Receipt,
  UsersThree,
} from "@phosphor-icons/react";
import { useOperationsPolling } from "@/lib/operations-polling";
import type {
  OperationsOverview,
  OperationsRequestSummary,
} from "@/lib/operations-types";
import {
  OperationsDataState,
  OperationsFlow,
  OperationsRefresh,
  OperationsShell,
  operationsTime,
} from "@/components/operations-shared";
import { OperationsTopology } from "@/components/operations-topology";
import { useEffect } from "react";

export default function OperationsOverviewPage() {
  const overview = useOperationsPolling<OperationsOverview>(
    "/admin/ops/overview",
  );
  const ai = useOperationsPolling<OperationsRequestSummary>(
    "/ai/admin/ops/summary",
  );
  useEffect(() => {
    ai.setAutoRefresh(overview.autoRefresh);
  }, [overview.autoRefresh, ai.setAutoRefresh]);
  const state = {
    ...overview,
    refreshing: overview.refreshing || ai.refreshing,
    refresh: () => {
      overview.refresh();
      ai.refresh();
    },
  };
  const data = overview.data;
  const counts = data?.commerce.ordersByStatus;
  return (
    <OperationsShell
      title="运行总览"
      description="沿着服务、交易和异步任务，查看比特严选此刻的运行状态。"
      actions={<OperationsRefresh state={state} />}
    >
      <OperationsDataState
        loading={overview.loading}
        error={overview.error}
        refresh={overview.refresh}
      >
        {data && (
          <>
            <section
              className="operations-metric-strip"
              aria-label="平台运行概览"
            >
              {[
                {
                  label: "平台用户",
                  value: data.commerce.users,
                  detail: `${data.commerce.customers} 位顾客`,
                  icon: <UsersThree size={20} />,
                },
                {
                  label: "订单记录",
                  value: data.commerce.orders,
                  detail: "当前全部订单",
                  icon: <Receipt size={20} />,
                },
                {
                  label: "已索引说明书",
                  value: data.knowledge.indexed,
                  detail: `共 ${data.knowledge.documents} 份 · ${data.knowledge.chunks} 个片段`,
                  icon: <BookOpenText size={20} />,
                },
                {
                  label: "可靠任务记录",
                  value: data.queues.reduce(
                    (sum, queue) => sum + queue.total,
                    0,
                  ),
                  detail: "数据库投递 / 处理记录",
                  icon: <Database size={20} />,
                },
              ].map((metric) => (
                <div key={metric.label}>
                  <span>
                    {metric.label}
                    {metric.icon}
                  </span>
                  <strong>{metric.value.toLocaleString()}</strong>
                  <small>{metric.detail}</small>
                </div>
              ))}
            </section>
            <section className="operations-panel">
              <div className="operations-section-heading">
                <div>
                  <h2>服务拓扑</h2>
                  <p>从网关到业务服务，再到数据与消息基础设施。</p>
                </div>
                <span className="operations-caption">
                  探测缓存 {data.serviceCacheSeconds} 秒
                </span>
              </div>
              {data.services.length ? (
                <OperationsTopology services={data.services} />
              ) : (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="暂无服务探测记录"
                />
              )}
            </section>
            <section className="operations-panel">
              <div className="operations-section-heading">
                <div>
                  <h2>用户交易流程</h2>
                  <p>数字表示当前处于该状态的订单数，不是历史转化率。</p>
                </div>
                <Link href="/admin">
                  查看商城订单 <ArrowRight size={16} />
                </Link>
              </div>
              <OperationsFlow
                label="订单交易主流程"
                steps={[
                  {
                    id: "pending",
                    label: "创建订单",
                    description: "等待用户支付",
                    value: counts?.PENDING_PAYMENT,
                  },
                  {
                    id: "paid",
                    label: "余额支付",
                    description: "已支付，等待发货",
                    value: counts?.PAID,
                  },
                  {
                    id: "shipped",
                    label: "订单发货",
                    description: "商品已发出",
                    value: counts?.SHIPPED,
                  },
                  {
                    id: "completed",
                    label: "确认收货",
                    description: "交易完成",
                    value: counts?.COMPLETED,
                  },
                ]}
              />
              <div className="operations-order-branches">
                <div>
                  <span className="operations-branch-line" aria-hidden />
                  <strong>取消分支</strong>
                  <p>待支付订单取消或超时关闭</p>
                  <span>{counts?.CANCELLED.toLocaleString()} 笔已取消</span>
                </div>
                <div>
                  <span className="operations-branch-line" aria-hidden />
                  <strong>退款分支</strong>
                  <p>支付后退款，或售后审核完成</p>
                  <span>{counts?.REFUNDED.toLocaleString()} 笔已退款</span>
                </div>
              </div>
            </section>
            <section className="operations-overview-bottom">
              <div className="operations-panel">
                <div className="operations-section-heading">
                  <div>
                    <h2>AI 请求</h2>
                    <p>仅统计开始记录运维数据之后的请求。</p>
                  </div>
                  <Link href="/admin/ops/conversations">
                    查看步骤 <ArrowRight size={16} />
                  </Link>
                </div>
                {ai.error && (
                  <Alert
                    showIcon
                    type="warning"
                    title="AI 汇总暂时无法读取"
                    description={ai.error}
                  />
                )}
                {ai.data ? (
                  <>
                    <div className="operations-ai-counts">
                      <div>
                        <span>已记录请求</span>
                        <strong>{ai.data.total.toLocaleString()}</strong>
                      </div>
                      <div>
                        <span>执行中</span>
                        <strong>{ai.data.running.toLocaleString()}</strong>
                      </div>
                      <div>
                        <span>已完成</span>
                        <strong>{ai.data.completed.toLocaleString()}</strong>
                      </div>
                      <div>
                        <span>失败 / 停止</span>
                        <strong>
                          {ai.data.failed} / {ai.data.cancelled}
                        </strong>
                      </div>
                    </div>
                    {ai.data.stale > 0 && (
                      <p className="operations-inline-warning">
                        {ai.data.stale} 条执行中记录长时间未更新，需进一步排查。
                      </p>
                    )}
                    <p className="operations-caption">
                      最早记录：{operationsTime(ai.data.firstRecordedAt)}
                    </p>
                  </>
                ) : (
                  !ai.error && (
                    <p className="operations-caption">
                      {ai.loading ? "正在读取请求汇总…" : "暂无请求汇总"}
                    </p>
                  )
                )}
              </div>
              <div className="operations-panel">
                <div className="operations-section-heading">
                  <div>
                    <h2>异步任务</h2>
                    <p>观察可靠事件与个人记忆处理。</p>
                  </div>
                  <Link href="/admin/ops/queues">
                    查看队列 <ArrowRight size={16} />
                  </Link>
                </div>
                {data.queues.map((queue) => (
                  <div
                    className="operations-queue-summary-row"
                    key={queue.kind}
                  >
                    <div>
                      <strong>{queue.name}</strong>
                      <span>
                        待处理 {queue.pending} · 处理中 {queue.processing} ·
                        待重试 {queue.retrying}
                      </span>
                    </div>
                    <span className="operations-queue-failed">
                      {queue.failed > 0
                        ? `${queue.failed} 条需排查`
                        : `${queue.completed} 条已完成`}
                    </span>
                  </div>
                ))}
                {!data.queues.length && (
                  <Empty
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                    description="暂无任务记录"
                  />
                )}
              </div>
            </section>
            <p className="operations-sampled">
              <ChatCircleDots size={14} />
              数据采样于 {operationsTime(data.sampledAt)} ·
              只读观察，不修改交易或任务状态。
            </p>
          </>
        )}
      </OperationsDataState>
    </OperationsShell>
  );
}
