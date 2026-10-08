"use client";

import {
  Cloud,
  Database,
  Globe,
  Info,
  ShieldCheck,
} from "@phosphor-icons/react";
import { Tag } from "antd";
import type { OperationsService } from "@/lib/operations-types";
import {
  operationsDuration,
  operationsTime,
} from "@/components/operations-shared";

export function OperationsTopology({
  services,
}: {
  services: OperationsService[];
}) {
  const gateways = services.filter(
    (service) =>
      service.layer === "application" &&
      (/gateway/i.test(service.id) || service.name.includes("网关")),
  );
  const applications = services.filter(
    (service) => service.layer === "application" && !gateways.includes(service),
  );
  const middleware = services.filter(
    (service) => service.layer === "middleware",
  );
  return (
    <div className="operations-topology" aria-label="应用与中间件服务分层拓扑">
      <div className="operations-client-node">
        <Globe size={22} />
        <div>
          <strong>商城与导购客户端</strong>
          <span>经统一网关访问业务服务</span>
        </div>
      </div>
      {gateways.length > 0 && (
        <ServiceTier
          title="入口层"
          description="HTTP 请求入口"
          services={gateways}
        />
      )}
      {applications.length > 0 && (
        <ServiceTier
          title="业务层"
          description="服务间通过 Dubbo 通信"
          services={applications}
        />
      )}
      {middleware.length > 0 && (
        <ServiceTier
          title="基础设施"
          description="数据存储、注册与异步消息"
          services={middleware}
        />
      )}
      <p className="operations-topology-note">
        <Info size={16} />
        连线表示架构分层关系。HTTP 为健康端点探测；TCP
        仅表示端口可达，不代表组件内部业务完全正常。
      </p>
    </div>
  );
}

function ServiceTier({
  title,
  description,
  services,
}: {
  title: string;
  description: string;
  services: OperationsService[];
}) {
  return (
    <section className="operations-service-tier">
      <div className="operations-tier-label">
        <h3>{title}</h3>
        <p>{description}</p>
      </div>
      <div className="operations-service-nodes">
        {services.map((service) => (
          <ServiceNode key={service.id} service={service} />
        ))}
      </div>
    </section>
  );
}

function ServiceNode({ service }: { service: OperationsService }) {
  const Icon =
    service.layer === "middleware"
      ? Database
      : /gateway/i.test(service.id)
        ? ShieldCheck
        : Cloud;
  const label =
    service.status === "up"
      ? service.probe === "tcp"
        ? "端口可达"
        : "健康检查通过"
      : service.status === "down"
        ? "探测失败"
        : "未检测";
  return (
    <article className="operations-service-node" data-status={service.status}>
      <div className="operations-service-node-title">
        <Icon size={20} />
        <h4>{service.name}</h4>
      </div>
      <div className="operations-probe-line">
        <Tag
          color={
            service.status === "up"
              ? "success"
              : service.status === "down"
                ? "error"
                : "default"
          }
        >
          {label}
        </Tag>
        <span>{operationsDuration(service.latencyMs)}</span>
      </div>
      <details>
        <summary>探测详情</summary>
        <dl>
          <div>
            <dt>方式</dt>
            <dd>{service.probe.toUpperCase()}</dd>
          </div>
          <div>
            <dt>检查时间</dt>
            <dd>{operationsTime(service.checkedAt)}</dd>
          </div>
          <div>
            <dt>结果代码</dt>
            <dd>{service.detailCode || "—"}</dd>
          </div>
        </dl>
      </details>
    </article>
  );
}
