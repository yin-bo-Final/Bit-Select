"use client";

import { memo, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { App, Button, Tooltip } from "antd";
import {
  ArrowClockwise,
  ArrowUpRight,
  BookOpenText,
  CaretDown,
  ChatCircleDots,
  Check,
  CircleNotch,
  Copy,
  Minus,
  WarningCircle,
} from "@phosphor-icons/react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import type { ChatMessage, Source } from "@/lib/types";

export type ReplyStatus =
  "waiting" | "streaming" | "complete" | "stopped" | "interrupted" | "error";
export type PublicPhase = {
  code: string;
  label: string;
  status: "running" | "completed";
};
export type StreamChatMessage = ChatMessage & {
  id: string;
  status?: ReplyStatus;
  phases?: PublicPhase[];
  issue?: string;
  request?: string;
  retryable?: boolean;
};

// These are public workflow labels, never model reasoning or hidden chain-of-thought.
export function updatePublicPhases(
  phases: PublicPhase[],
  incoming: PublicPhase,
): PublicPhase[] {
  const existing = phases.findIndex((phase) => phase.code === incoming.code);
  if (existing >= 0)
    return phases.map((phase, index) =>
      index === existing ? incoming : phase,
    );
  return [
    ...phases.map((phase) =>
      phase.status === "running"
        ? { ...phase, status: "completed" as const }
        : phase,
    ),
    incoming,
  ];
}

const markdownComponents = {
  a: (props: React.ComponentProps<"a">) => (
    <a href={props.href} title={props.title} target="_blank" rel="noreferrer">
      {props.children}
    </a>
  ),
  img: () => null,
};

function ProcessingProgress({
  item,
  active,
}: {
  item: StreamChatMessage;
  active: boolean;
}) {
  const phases = item.phases || [];
  const current = [...phases]
    .reverse()
    .find((phase) => phase.status === "running");
  const label = active
    ? current?.label || (item.content ? "正在接收回答" : "正在连接导购…")
    : item.status === "complete"
      ? "处理完成"
      : "处理已结束";
  if (!active && !phases.length) return null;
  return (
    <details className="response-progress" data-active={active}>
      <summary>
        {active ? (
          <CircleNotch className="response-spinner" size={16} />
        ) : item.status === "complete" ? (
          <Check size={16} />
        ) : (
          <Minus size={16} />
        )}
        <span>{label}</span>
        {phases.length > 0 && (
          <span className="response-phase-count">{phases.length} 个步骤</span>
        )}
        <CaretDown className="response-disclosure" size={13} />
      </summary>
      {phases.length ? (
        <ol className="response-phase-list">
          {phases.map((phase) => (
            <li
              key={phase.code}
              data-current={active && phase.status === "running"}
            >
              {phase.status === "completed" ? (
                <Check size={14} />
              ) : active ? (
                <CircleNotch className="response-spinner" size={14} />
              ) : (
                <Minus size={14} />
              )}
              <span>{phase.label}</span>
              <small>
                {phase.status === "completed"
                  ? "完成"
                  : active
                    ? "进行中"
                    : "未完成"}
              </small>
            </li>
          ))}
        </ol>
      ) : (
        <p className="response-connecting">正在建立回答连接。</p>
      )}
    </details>
  );
}

function Sources({ sources }: { sources: Source[] }) {
  return (
    <details className="stream-sources">
      <summary>
        <BookOpenText size={16} />
        <span>参考商品资料</span>
        <span className="stream-source-count">{sources.length}</span>
        <CaretDown size={13} />
      </summary>
      <ol>
        {sources.map((source, index) => (
          <li key={`${source.productId}-${source.title}-${index}`}>
            <details className="stream-source-detail">
              <summary>
                <span className="stream-source-index">{index + 1}</span>
                <span>{source.title}</span>
                <CaretDown size={13} />
              </summary>
              <div className="stream-source-excerpt">
                <ReactMarkdown
                  remarkPlugins={[remarkGfm]}
                  components={markdownComponents}
                >
                  {source.excerpt}
                </ReactMarkdown>
                {source.productId && (
                  <Link href={`/products/${source.productId}`}>
                    查看商品 <ArrowUpRight size={15} />
                  </Link>
                )}
              </div>
            </details>
          </li>
        ))}
      </ol>
    </details>
  );
}

export const AssistantStreamMessage = memo(function AssistantStreamMessage({
  item,
  onRetry,
  retryDisabled,
}: {
  item: StreamChatMessage;
  onRetry: (text: string) => void;
  retryDisabled: boolean;
}) {
  const assistant = item.role === "assistant";
  const active = item.status === "waiting" || item.status === "streaming";
  const incomplete =
    item.status === "stopped" ||
    item.status === "interrupted" ||
    item.status === "error";
  const [copied, setCopied] = useState(false);
  const copyTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const { message } = App.useApp();
  useEffect(
    () => () => {
      if (copyTimer.current) clearTimeout(copyTimer.current);
    },
    [],
  );
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(item.content);
      setCopied(true);
      if (copyTimer.current) clearTimeout(copyTimer.current);
      copyTimer.current = setTimeout(() => setCopied(false), 1800);
    } catch {
      message.error("复制未成功，请选择回答文字后复制。");
    }
  };
  return (
    <article
      className={`chat-message ${assistant ? "assistant-message" : "user-message"}`}
      data-message-id={item.id}
      data-status={item.status || "complete"}
    >
      <div className="message-author">
        {assistant ? (
          <>
            <ChatCircleDots size={18} />
            <span>比特导购</span>
          </>
        ) : (
          "你"
        )}
      </div>
      {assistant && <ProcessingProgress item={item} active={active} />}
      <div
        className={`message-content ${active && item.content ? "is-streaming" : ""}`}
        aria-busy={active}
      >
        {item.content ? (
          assistant ? (
            <ReactMarkdown
              remarkPlugins={[remarkGfm]}
              components={markdownComponents}
            >
              {item.content}
            </ReactMarkdown>
          ) : (
            <p className="user-plain-text">{item.content}</p>
          )
        ) : !active ? (
          <p className="empty-reply">
            {item.status === "stopped"
              ? "本次回答已停止。"
              : "暂时没有收到回答。"}
          </p>
        ) : null}
      </div>
      {!!item.sources?.length && <Sources sources={item.sources} />}
      {incomplete && (
        <div className="reply-notice" data-state={item.status}>
          <WarningCircle size={16} />
          <span>
            {item.status === "stopped"
              ? "已停止生成"
              : item.status === "interrupted"
                ? "连接中断，回答可能不完整"
                : "本次回答未完成"}
            {item.content ? "，已收到的文字保留在本页。" : "。"}
            {item.issue && <small>{item.issue}</small>}
          </span>
        </div>
      )}
      {(item.content || (incomplete && item.request)) && (
        <div className="message-actions">
          {!!item.content && (
            <Tooltip title={copied ? "已复制" : "复制文字"}>
              <Button
                type="text"
                size="small"
                aria-label={
                  copied
                    ? `已复制${assistant ? "回答" : "问题"}`
                    : `复制${assistant ? "回答" : "问题"}`
                }
                icon={copied ? <Check size={16} /> : <Copy size={16} />}
                onClick={() => void copy()}
              >
                {copied ? "已复制" : "复制"}
              </Button>
            </Tooltip>
          )}
          {incomplete && item.request && item.retryable !== false && (
            <Button
              type="text"
              size="small"
              disabled={retryDisabled}
              icon={<ArrowClockwise size={16} />}
              onClick={() => onRetry(item.request!)}
            >
              重新回答
            </Button>
          )}
        </div>
      )}
    </article>
  );
});
