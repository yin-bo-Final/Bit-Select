"use client";

import type { ReactNode } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { Alert, Button, Empty, Skeleton, Switch, Tag } from "antd";
import {
  ArrowClockwise,
  ArrowLeft,
  ArrowRight,
  ChartLine,
  ChatCircleDots,
  Database,
  ShieldCheck,
} from "@phosphor-icons/react";
import { LoginGate } from "@/components/common";
import { useSession } from "@/components/providers";

export function OperationsAccess({ children }: { children: ReactNode }) {
  return (
    <LoginGate>
      <AdministratorOnly>{children}</AdministratorOnly>
    </LoginGate>
  );
}

function AdministratorOnly({ children }: { children: ReactNode }) {
  const { user } = useSession();
  if (user?.role !== "ADMIN")
    return (
      <div className="empty-area">
        <Alert
          showIcon
          type="warning"
          title="此页面仅向管理员开放"
          description="运维信息包含平台内部运行状态，请使用管理员账号访问。"
        />
        <Link href="/">返回商城</Link>
      </div>
    );
  return children;
}

export function OperationsShell({
  title,
  description,
  children,
  actions,
}: {
  title: string;
  description: string;
  children: ReactNode;
  actions?: ReactNode;
}) {
  const pathname = usePathname();
  const links = [
    { href: "/admin/ops", label: "运行总览", icon: <ChartLine size={18} /> },
    {
      href: "/admin/ops/conversations",
      label: "AI 请求追踪",
      icon: <ChatCircleDots size={18} />,
    },
    {
      href: "/admin/ops/queues",
      label: "可靠任务队列",
      icon: <Database size={18} />,
    },
  ];
  return (
    <div className="operations-page precision-admin">
      <Link className="operations-back" href="/admin">
        <ArrowLeft size={15} />
        商城管理
      </Link>
      <header className="workspace-page-heading operations-heading">
        <div>
          <span className="workspace-section-label">
            <ShieldCheck size={17} />
            管理员运维
          </span>
          <h1>{title}</h1>
          <p>{description}</p>
        </div>
        <Tag className="operations-readonly">只读观察</Tag>
      </header>
      <nav className="operations-navigation" aria-label="运维页面">
        {links.map((item) => (
          <Link
            key={item.href}
            href={item.href}
            aria-current={pathname === item.href ? "page" : undefined}
          >
            {item.icon}
            {item.label}
          </Link>
        ))}
      </nav>
      {actions && <div className="operations-controls">{actions}</div>}
      {children}
    </div>
  );
}

type RefreshState = {
  refreshing: boolean;
  updatedAt: Date | null;
  autoRefresh: boolean;
  visible: boolean;
  setAutoRefresh: (enabled: boolean) => void;
  refresh: () => void;
};
export function OperationsRefresh({
  state,
  children,
}: {
  state: RefreshState;
  children?: ReactNode;
}) {
  return (
    <>
      <div className="operations-filters">{children}</div>
      <div className="operations-refresh">
        <span className="operations-updated">
          {state.updatedAt
            ? `更新于 ${state.updatedAt.toLocaleTimeString("zh-CN", { hour12: false })}`
            : "等待首次读取"}
        </span>
        <label>
          <Switch
            size="small"
            checked={state.autoRefresh}
            onChange={state.setAutoRefresh}
            aria-label="每15秒自动刷新运维数据"
          />
          自动刷新
        </label>
        {!state.visible && <span>页面隐藏，已暂停</span>}
        <Button
          icon={<ArrowClockwise size={17} />}
          loading={state.refreshing}
          onClick={state.refresh}
        >
          刷新
        </Button>
      </div>
    </>
  );
}

export function OperationsDataState({
  loading,
  error,
  empty,
  emptyText,
  refresh,
  children,
}: {
  loading: boolean;
  error: string;
  empty?: boolean;
  emptyText?: string;
  refresh: () => void;
  children: ReactNode;
}) {
  if (loading)
    return (
      <div className="operations-loading" aria-label="正在读取运行数据">
        <Skeleton active paragraph={{ rows: 5 }} />
      </div>
    );
  return (
    <>
      {error && (
        <Alert
          className="operations-error"
          type="error"
          showIcon
          title="运行数据暂时无法读取"
          description={error}
          action={<Button onClick={refresh}>重新读取</Button>}
        />
      )}
      {empty
        ? !error && (
            <div className="operations-empty">
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={emptyText || "暂无符合条件的数据"}
              />
            </div>
          )
        : children}
    </>
  );
}

const statusLabels: Record<string, string> = {
  UP: "可用",
  DOWN: "不可用",
  UNKNOWN: "未检测",
  HEALTHY: "正常",
  DEGRADED: "部分异常",
  RUNNING: "执行中",
  COMPLETED: "已完成",
  SUCCESS: "成功",
  FAILED: "失败",
  ERROR: "异常",
  CANCELLED: "已停止",
  STOPPED: "已停止",
  PENDING: "待处理",
  PROCESSING: "处理中",
  SENT: "已发送",
  RETRY: "等待重试",
  RETRYING: "等待重试",
  DEAD: "已终止",
  SKIPPED: "已跳过",
};
export function OperationsStatus({
  status,
}: {
  status: string | null | undefined;
}) {
  const value = (status || "UNKNOWN").toUpperCase();
  const tone = ["UP", "HEALTHY", "COMPLETED", "SUCCESS", "SENT"].includes(value)
    ? "success"
    : ["DOWN", "FAILED", "ERROR", "DEAD"].includes(value)
      ? "error"
      : ["RUNNING", "PROCESSING", "PENDING", "RETRY", "RETRYING"].includes(
            value,
          )
        ? "processing"
        : "default";
  return (
    <Tag color={tone} className="operations-status">
      {statusLabels[value] || value}
    </Tag>
  );
}

export function OperationsFlow({
  steps,
  label,
}: {
  label: string;
  steps: {
    id: string;
    label: string;
    description?: string;
    value?: ReactNode;
    status?: string;
  }[];
}) {
  return (
    <ol className="operations-flow" aria-label={label}>
      {steps.map((step, index) => (
        <li key={step.id}>
          <span className="operations-flow-number">
            {String(index + 1).padStart(2, "0")}
          </span>
          <div>
            <strong>{step.label}</strong>
            {step.description && <p>{step.description}</p>}
            {step.value !== undefined && (
              <span className="operations-flow-value">{step.value}</span>
            )}
            {step.status && <OperationsStatus status={step.status} />}
          </div>
          {index < steps.length - 1 && (
            <ArrowRight
              className="operations-flow-arrow"
              size={18}
              aria-hidden
            />
          )}
        </li>
      ))}
    </ol>
  );
}

export const operationsDuration = (milliseconds?: number | null) =>
  milliseconds == null
    ? "—"
    : milliseconds < 1000
      ? `${Math.round(milliseconds)} ms`
      : `${(milliseconds / 1000).toFixed(1)} s`;
export const operationsTime = (value?: string | null) =>
  value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "—";
