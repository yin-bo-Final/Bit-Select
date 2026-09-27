"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import {
  Alert,
  App,
  Button,
  Collapse,
  Drawer,
  Empty,
  Input,
  Popconfirm,
  Spin,
  Switch,
  Tag,
} from "antd";
import {
  ArrowRight,
  Brain,
  ChatCircleDots,
  Check,
  ClockCounterClockwise,
  Plus,
  Stop,
  Trash,
} from "@phosphor-icons/react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { api, date, errorText } from "@/lib/api";
import { readEvents } from "@/lib/sse";
import type { ChatMessage, Conversation, Memory, Source } from "@/lib/types";
import { LoginGate } from "@/components/common";
type ConversationStats = {
  contextCapacity: number;
  summaryThrough: number;
  summaryPresent: boolean;
  tokenizer: string;
};

export default function AssistantPage() {
  return (
    <LoginGate>
      <Assistant />
    </LoginGate>
  );
}
function Assistant() {
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [error, setError] = useState("");
  const [memoryOpen, setMemoryOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [memories, setMemories] = useState<Memory[]>([]);
  const [memoryLoading, setMemoryLoading] = useState(false);
  const [memoryError, setMemoryError] = useState("");
  const [memoryEnabled, setMemoryEnabled] = useState(true);
  const [memorySaving, setMemorySaving] = useState(false);
  const [phase, setPhase] = useState("正在查阅商品与选购信息…");
  const [stats, setStats] = useState<ConversationStats | null>(null);
  const abort = useRef<AbortController | null>(null);
  const end = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<React.ComponentRef<typeof Input.TextArea>>(null);
  const { message: toast } = App.useApp();
  const loadConversations = useCallback(async () => {
    try {
      const result = await api<{ items: Conversation[] }>("/ai/conversations");
      setConversations(result.items);
    } catch (e) {
      setError(errorText(e));
    }
  }, []);
  useEffect(() => {
    void loadConversations();
    const params = new URLSearchParams(window.location.search);
    if (params.get("name"))
      setInput(`请介绍一下${params.get("name")}，它适合哪些使用场景？`);
    return () => abort.current?.abort();
  }, [loadConversations]);
  useEffect(() => {
    end.current?.scrollIntoView({ behavior: "instant", block: "end" });
  }, [messages]);
  const loadConversation = async (id: string) => {
    if (busy) return;
    setHistoryLoading(true);
    setError("");
    setHistoryOpen(false);
    try {
      const result = await api<{
        id: string;
        messages: ChatMessage[];
        stats?: ConversationStats;
      }>(`/ai/conversations/${id}`);
      setMessages(result.messages);
      setConversationId(result.id);
      setStats(result.stats || null);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setHistoryLoading(false);
    }
  };
  const newConversation = () => {
    if (busy) return;
    setMessages([]);
    setConversationId(null);
    setStats(null);
    setError("");
    setHistoryOpen(false);
    inputRef.current?.focus();
  };
  const loadMemories = async () => {
    setMemoryOpen(true);
    setMemoryLoading(true);
    setMemoryError("");
    try {
      const result = await api<{ items: Memory[]; enabled?: boolean }>(
        "/ai/memories",
      );
      setMemories(result.items);
      setMemoryEnabled(result.enabled !== false);
    } catch (e) {
      setMemoryError(errorText(e));
    } finally {
      setMemoryLoading(false);
    }
  };
  const forget = async (id: string) => {
    try {
      await api(`/ai/memories/${id}`, { method: "DELETE" });
      setMemories((list) => list.filter((item) => item.id !== id));
      toast.success("已删除这条记忆");
    } catch (e) {
      toast.error(errorText(e));
    }
  };
  const changeMemoryPreference = async (enabled: boolean) => {
    setMemorySaving(true);
    try {
      await api("/ai/memory-preference", {
        method: "PUT",
        body: JSON.stringify({ enabled }),
      });
      setMemoryEnabled(enabled);
      toast.success(enabled ? "已开启个人记忆" : "已关闭个人记忆");
    } catch (e) {
      toast.error(errorText(e));
    } finally {
      setMemorySaving(false);
    }
  };
  const send = async (text = input) => {
    const value = text.trim();
    if (!value || busy || historyLoading) return;
    setInput("");
    setBusy(true);
    setPhase("正在理解你的问题…");
    setError("");
    const controller = new AbortController();
    abort.current = controller;
    setMessages((items) => [
      ...items,
      { role: "user", content: value },
      { role: "assistant", content: "" },
    ]);
    let finished = false;
    let activeConversationId = conversationId;
    try {
      const response = await fetch("/api/ai/chat", {
        method: "POST",
        credentials: "include",
        headers: {
          "Content-Type": "application/json",
          Accept: "text/event-stream",
        },
        body: JSON.stringify({ conversationId, message: value }),
        signal: controller.signal,
      });
      if (!response.ok) {
        const body = await response.json().catch(() => null);
        throw new Error(body?.message || "导购暂时无法回答，请稍后重试。");
      }
      if (!response.body) throw new Error("回答连接未建立，请重新发送。");
      for await (const event of readEvents(response.body)) {
        if (event.data === "[DONE]") {
          finished = true;
          continue;
        }
        const body = JSON.parse(event.data);
        if (event.event === "meta" && body.conversationId) {
          setConversationId(body.conversationId);
          activeConversationId = body.conversationId;
        } else if (event.event === "phase")
          setPhase(body.label || "正在整理回答…");
        else if (event.event === "delta")
          setMessages((items) =>
            items.map((item, index) =>
              index === items.length - 1
                ? { ...item, content: item.content + (body.content || "") }
                : item,
            ),
          );
        else if (event.event === "sources")
          setMessages((items) =>
            items.map((item, index) =>
              index === items.length - 1
                ? { ...item, sources: body.items || [] }
                : item,
            ),
          );
        else if (event.event === "error")
          throw new Error(body.message || "回答中断，请重试。");
        else if (event.event === "done") finished = true;
      }
      if (!finished) setError("连接已结束，回答可能不完整。你可以继续追问。");
      void loadConversations();
      if (activeConversationId)
        void api<{ stats?: ConversationStats }>(
          `/ai/conversations/${activeConversationId}`,
        )
          .then((result) => setStats(result.stats || null))
          .catch(() => {});
    } catch (e) {
      if (e instanceof Error && e.name === "AbortError")
        toast.info("已停止本次回答");
      else {
        setError(errorText(e));
        setInput(value);
      }
    } finally {
      setBusy(false);
      abort.current = null;
    }
  };
  const history = (
    <>
      <Button
        block
        icon={<Plus size={18} />}
        onClick={newConversation}
        disabled={busy}
      >
        开启新对话
      </Button>
      <h2>最近对话</h2>
      <div className="conversation-list">
        {!conversations.length ? (
          <p className="muted">你的选购故事会保存在这里。</p>
        ) : (
          conversations.map((item) => (
            <button
              key={item.id}
              disabled={busy}
              className={conversationId === item.id ? "active" : ""}
              onClick={() => loadConversation(item.id)}
            >
              <ChatCircleDots size={18} />
              <span>
                {item.title || "新对话"}
                <small>{date(item.updatedAt)}</small>
              </span>
            </button>
          ))
        )}
      </div>
      <button className="memory-shortcut" onClick={() => void loadMemories()}>
        <Brain size={22} />
        <span>
          关于你的记忆<small>随时查看、随时删除</small>
        </span>
        <ArrowRight size={17} />
      </button>
    </>
  );
  return (
    <div className="assistant-layout">
      <aside className="conversation-sidebar">{history}</aside>
      <section className="chat-workspace">
        <header className="chat-header">
          <div className="chat-title">
            <span className="assistant-avatar">
              <ChatCircleDots size={22} />
            </span>
            <div>
              <h1>比特导购</h1>
              <p>选购建议，有据可依。</p>
            </div>
          </div>
          <div className="chat-header-actions">
            <Button
              className="mobile-history"
              type="text"
              aria-label="查看历史对话"
              icon={<ClockCounterClockwise size={22} />}
              onClick={() => setHistoryOpen(true)}
            />
            <Button
              type="text"
              aria-label="查看个人记忆"
              icon={<Brain size={20} />}
              onClick={() => void loadMemories()}
            >
              <span className="hide-mobile">我的记忆</span>
            </Button>
          </div>
        </header>
        <div
          className="chat-transcript"
          aria-live="polite"
          aria-busy={busy || historyLoading}
        >
          {historyLoading ? (
            <div className="chat-loading">
              <Spin />
              <p>正在找回对话</p>
            </div>
          ) : !messages.length ? (
            <div className="chat-welcome">
              <span className="welcome-mark">
                <ChatCircleDots size={44} weight="duotone" />
              </span>
              <h2>今天，想选点什么？</h2>
              <p>
                说说你的预算、使用场景，
                <br />
                或者直接问一件商品。
              </p>
              <div className="suggestion-grid">
                {[
                  "预算 300 元，帮我挑一副通勤耳机",
                  "想让出租屋更舒适，有哪些实用好物？",
                  "每天久坐办公，怎么搭配桌面装备？",
                  "帮我选适合一个人做饭的厨具",
                ].map((question) => (
                  <button key={question} onClick={() => void send(question)}>
                    {question}
                    <ArrowRight size={18} />
                  </button>
                ))}
              </div>
              <p className="assistant-intro">
                我会参考商品说明书与实际在售商品，帮你比较功能、价格和适用场景。
              </p>
            </div>
          ) : (
            <div className="message-list">
              {messages.map((item, index) => (
                <article
                  key={index}
                  className={`chat-message ${item.role === "user" ? "user-message" : "assistant-message"}`}
                >
                  <div className="message-author">
                    {item.role === "user" ? (
                      "你"
                    ) : (
                      <>
                        <ChatCircleDots size={18} />
                        比特导购
                      </>
                    )}
                  </div>
                  <div className="message-content">
                    {!item.content && busy && index === messages.length - 1 ? (
                      <span className="thinking">
                        <Spin size="small" />
                        {phase}
                      </span>
                    ) : (
                      <ReactMarkdown
                        remarkPlugins={[remarkGfm]}
                        components={{
                          a: (props) => (
                            <a {...props} target="_blank" rel="noreferrer" />
                          ),
                          img: () => null,
                        }}
                      >
                        {item.content || "本次回答未完成。"}
                      </ReactMarkdown>
                    )}
                  </div>
                  {!!item.sources?.length && <Sources sources={item.sources} />}
                </article>
              ))}
              <div ref={end} />
            </div>
          )}
        </div>
        <div className="chat-composer-area">
          {stats && (
            <Collapse
              className="conversation-stats"
              ghost
              size="small"
              items={[
                {
                  key: "context",
                  label: "会话状态",
                  children: (
                    <div>
                      <p>
                        模型上下文容量：{stats.contextCapacity.toLocaleString()}{" "}
                        token；计数方式：{stats.tokenizer}。
                      </p>
                      <p>
                        历史摘要：
                        {stats.summaryPresent
                          ? "已压缩较早的对话"
                          : "尚未触发摘要"}
                        。
                      </p>
                      <p>
                        达到容量的 60% 时触发摘要，保留最近约 20%
                        的完整对话轮次。
                      </p>
                    </div>
                  ),
                },
              ]}
            />
          )}
          {error && (
            <Alert
              className="chat-error"
              title={error}
              type="warning"
              closable
              onClose={() => setError("")}
            />
          )}
          <form
            className="chat-composer"
            onSubmit={(event) => {
              event.preventDefault();
              void send();
            }}
          >
            <Input.TextArea
              ref={inputRef}
              aria-label="发送给导购的问题"
              value={input}
              onChange={(event) => setInput(event.target.value)}
              placeholder="告诉我你想选什么，或说说你的使用习惯…"
              autoSize={{ minRows: 1, maxRows: 5 }}
              maxLength={12000}
              onKeyDown={(event) => {
                if (
                  event.key === "Enter" &&
                  !event.shiftKey &&
                  !event.nativeEvent.isComposing
                ) {
                  event.preventDefault();
                  void send();
                }
              }}
            />
            {busy ? (
              <Button
                type="primary"
                aria-label="停止生成"
                icon={<Stop size={19} weight="fill" />}
                onClick={() => abort.current?.abort()}
              />
            ) : (
              <Button
                type="primary"
                htmlType="submit"
                aria-label="发送问题"
                disabled={!input.trim() || historyLoading}
                icon={<ArrowRight size={21} />}
              />
            )}
          </form>
          <p className="composer-note">
            AI 建议仅供选购参考，价格和库存以商品页面为准。Shift + Enter 换行。
          </p>
        </div>
      </section>
      <Drawer
        title="对话记录"
        placement="left"
        open={historyOpen}
        onClose={() => setHistoryOpen(false)}
      >
        {history}
      </Drawer>
      <Drawer
        title="关于你的记忆"
        open={memoryOpen}
        onClose={() => setMemoryOpen(false)}
        size={460}
      >
        <p className="memory-description">
          导购会记住你提到的偏好和使用习惯，只用于你自己的推荐。新记忆异步整理，可能稍后出现；有冲突时优先参考你最近的表述。
        </p>
        <div className="memory-preference">
          <div>
            <strong>使用个人记忆</strong>
            <p>关闭后，不再提取或使用个人偏好记忆。</p>
          </div>
          <Switch
            aria-label="使用个人记忆"
            checked={memoryEnabled}
            loading={memorySaving}
            disabled={memoryLoading}
            onChange={changeMemoryPreference}
          />
        </div>
        {memoryError && (
          <Alert
            type="error"
            title={memoryError}
            action={<Button onClick={loadMemories}>重试</Button>}
          />
        )}
        <Button
          icon={<ClockCounterClockwise size={18} />}
          onClick={loadMemories}
          loading={memoryLoading}
        >
          刷新记忆
        </Button>
        {memoryLoading ? (
          <div className="chat-loading">
            <Spin />
          </div>
        ) : !memories.length ? (
          <Empty
            className="memory-empty"
            description="还没有记忆。聊聊你的喜好，导购会逐渐了解你。"
          />
        ) : (
          <div className="memory-list">
            {memories.map((item) => (
              <article key={item.id}>
                <div>
                  <Brain size={19} />
                  <Tag>个人偏好</Tag>
                </div>
                <p>{item.content}</p>
                <footer>
                  <span>{date(item.updatedAt)}</span>
                  <Popconfirm
                    title="删除这条记忆？"
                    description="后续推荐将不再使用这条记忆。"
                    okText="删除"
                    cancelText="保留"
                    onConfirm={() => forget(item.id)}
                  >
                    <Button
                      type="text"
                      aria-label={`删除记忆：${item.content}`}
                      icon={<Trash size={18} />}
                    />
                  </Popconfirm>
                </footer>
              </article>
            ))}
          </div>
        )}
      </Drawer>
    </div>
  );
}
function Sources({ sources }: { sources: Source[] }) {
  return (
    <div className="answer-sources">
      <Collapse
        ghost
        size="small"
        items={[
          {
            key: "sources",
            label: (
              <span className="source-label">
                <Check size={15} />
                参考了 {sources.length} 条商品资料
              </span>
            ),
            children: (
              <div className="source-list">
                {sources.map((source, index) => (
                  <div
                    key={`${source.productId}-${index}`}
                    className="source-item"
                  >
                    <strong>{source.title}</strong>
                    <p>{source.excerpt}</p>
                    <details className="source-full">
                      <summary>展开完整摘录</summary>
                      <ReactMarkdown remarkPlugins={[remarkGfm]}>
                        {source.excerpt}
                      </ReactMarkdown>
                    </details>
                    {source.productId && (
                      <Link href={`/products/${source.productId}`}>
                        查看商品 <ArrowRight size={15} />
                      </Link>
                    )}
                  </div>
                ))}
              </div>
            ),
          },
        ]}
      />
    </div>
  );
}
