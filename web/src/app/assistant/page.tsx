"use client";
import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
} from "react";
import {
  Alert,
  App,
  Button,
  Collapse,
  Drawer,
  Empty,
  Input,
  Popconfirm,
  Skeleton,
  Spin,
  Switch,
  Tag,
} from "antd";
import {
  ArrowRight,
  ArrowDown,
  ArrowUp,
  Brain,
  ChatCircleDots,
  ClockCounterClockwise,
  BookOpenText,
  CookingPot,
  Desktop,
  Headphones,
  House,
  Plus,
  Stop,
  Trash,
} from "@phosphor-icons/react";
import { api, date, errorText } from "@/lib/api";
import { readEvents } from "@/lib/sse";
import {
  cancelChatRequest,
  openChatStream,
  type ActiveChatRequest,
} from "@/lib/chat-request";
import {
  createChatScrollFollow,
  chatScrollKeyDirection,
} from "@/lib/chat-scroll-follow";
import type { ChatMessage, Conversation, Memory, Source } from "@/lib/types";
import { LoginGate } from "@/components/common";
import {
  AssistantStreamMessage,
  updatePublicPhases,
  type StreamChatMessage,
  type PublicPhase,
} from "@/components/assistant-stream-message";
import "./assistant-stream.css";
type ConversationStats = {
  contextCapacity: number;
  summaryThrough: number;
  summaryPresent: boolean;
  tokenizer: string;
};
const scrollMetrics = (element: HTMLDivElement) => ({
  scrollTop: element.scrollTop,
  scrollHeight: element.scrollHeight,
  clientHeight: element.clientHeight,
});

export default function AssistantPage() {
  return (
    <LoginGate>
      <Assistant />
    </LoginGate>
  );
}
function Assistant() {
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [conversationsLoading, setConversationsLoading] = useState(true);
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [messages, setMessages] = useState<StreamChatMessage[]>([]);
  const hasMessages = messages.length > 0;
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [stopping, setStopping] = useState(false);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [error, setError] = useState("");
  const [memoryOpen, setMemoryOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [memories, setMemories] = useState<Memory[]>([]);
  const [memoryLoading, setMemoryLoading] = useState(false);
  const [memoryError, setMemoryError] = useState("");
  const [memoryEnabled, setMemoryEnabled] = useState(true);
  const [memorySaving, setMemorySaving] = useState(false);
  const [announcement, setAnnouncement] = useState("");
  const [showLatest, setShowLatest] = useState(false);
  const [stats, setStats] = useState<ConversationStats | null>(null);
  const activeRequest = useRef<ActiveChatRequest | null>(null);
  const historyRequest = useRef<AbortController | null>(null);
  const memoryRequest = useRef<AbortController | null>(null);
  const memoryRevision = useRef(0);
  const mounted = useRef(true);
  const viewRevision = useRef(0);
  const historyRevision = useRef(0);
  const activeConversation = useRef<string | null>(null);
  const draft = useRef("");
  const transcript = useRef<HTMLDivElement | null>(null);
  const transcriptContent = useRef<HTMLDivElement | null>(null);
  const scrollFollow = useRef(createChatScrollFollow());
  const touchY = useRef<number | null>(null);
  const inputRef = useRef<React.ComponentRef<typeof Input.TextArea>>(null);
  const { message: toast } = App.useApp();
  const updateDraft = useCallback((value: string) => {
    draft.current = value;
    setInput(value);
  }, []);
  const loadConversations = useCallback(async () => {
    const revision = ++historyRevision.current;
    try {
      const result = await api<{ items: Conversation[] }>("/ai/conversations");
      if (mounted.current && revision === historyRevision.current)
        setConversations(result.items);
    } catch (e) {
      if (mounted.current && revision === historyRevision.current)
        setError(errorText(e));
    } finally {
      if (mounted.current && revision === historyRevision.current)
        setConversationsLoading(false);
    }
  }, []);
  useEffect(() => {
    mounted.current = true;
    void loadConversations();
    const params = new URLSearchParams(window.location.search);
    if (params.get("name"))
      updateDraft(
        `请介绍一下${params.get("name")}，它适合哪些使用场景？`.slice(0, 8000),
      );
    return () => {
      mounted.current = false;
      activeRequest.current?.controller.abort();
      historyRequest.current?.abort();
      memoryRequest.current?.abort();
    };
  }, [loadConversations, updateDraft]);
  const keepLatestVisible = useCallback(() => {
    const element = transcript.current;
    if (!element) return;
    if (!hasMessages) {
      element.scrollTop = 0;
      scrollFollow.current.resume();
      scrollFollow.current.didProgrammaticScroll(scrollMetrics(element));
      setShowLatest(false);
      return;
    }
    scrollFollow.current.observeLayout(scrollMetrics(element));
    if (scrollFollow.current.isFollowing()) {
      element.scrollTop = element.scrollHeight;
      scrollFollow.current.didProgrammaticScroll(scrollMetrics(element));
    }
    setShowLatest(
      element.scrollHeight - element.clientHeight - element.scrollTop >
        (scrollFollow.current.isFollowing() ? 96 : 2),
    );
  }, [hasMessages]);
  useLayoutEffect(keepLatestVisible, [
    messages,
    historyLoading,
    keepLatestVisible,
  ]);
  useEffect(() => {
    const observer = new ResizeObserver(keepLatestVisible);
    if (transcript.current) observer.observe(transcript.current);
    if (transcriptContent.current) observer.observe(transcriptContent.current);
    return () => observer.disconnect();
  }, [keepLatestVisible]);
  useEffect(() => {
    const finishDrag = () => scrollFollow.current.endScrollbarDrag();
    window.addEventListener("pointerup", finishDrag);
    window.addEventListener("pointercancel", finishDrag);
    window.addEventListener("blur", finishDrag);
    return () => {
      window.removeEventListener("pointerup", finishDrag);
      window.removeEventListener("pointercancel", finishDrag);
      window.removeEventListener("blur", finishDrag);
    };
  }, []);
  const jumpToLatest = () => {
    scrollFollow.current.resume();
    // A deliberate jump must replace the previous reading-position baseline.
    if (transcript.current)
      scrollFollow.current.didProgrammaticScroll(
        scrollMetrics(transcript.current),
      );
    keepLatestVisible();
  };
  const loadConversation = async (id: string) => {
    if (activeRequest.current || historyRequest.current) return;
    const controller = new AbortController();
    historyRequest.current = controller;
    const revision = ++viewRevision.current;
    setHistoryLoading(true);
    setError("");
    setHistoryOpen(false);
    try {
      const result = await api<{
        id: string;
        messages: ChatMessage[];
        stats?: ConversationStats;
      }>(`/ai/conversations/${id}`, { signal: controller.signal });
      if (!mounted.current || revision !== viewRevision.current) return;
      scrollFollow.current.resume();
      if (transcript.current)
        scrollFollow.current.didProgrammaticScroll(
          scrollMetrics(transcript.current),
        );
      setMessages(
        result.messages.map((item, index) => ({
          ...item,
          id: `${id}-${index}`,
          status: "complete",
        })),
      );
      activeConversation.current = result.id;
      setConversationId(result.id);
      setStats(result.stats || null);
    } catch (e) {
      if (
        mounted.current &&
        !controller.signal.aborted &&
        revision === viewRevision.current
      )
        setError(errorText(e));
    } finally {
      if (historyRequest.current === controller) {
        historyRequest.current = null;
        if (mounted.current) setHistoryLoading(false);
      }
    }
  };
  const newConversation = () => {
    if (activeRequest.current || historyRequest.current) return;
    ++viewRevision.current;
    activeConversation.current = null;
    scrollFollow.current.resume();
    setMessages([]);
    setConversationId(null);
    setStats(null);
    setError("");
    setAnnouncement("已开启新对话");
    setShowLatest(false);
    setHistoryOpen(false);
    inputRef.current?.focus();
  };
  const loadMemories = async () => {
    const revision = ++memoryRevision.current;
    memoryRequest.current?.abort();
    const controller = new AbortController();
    memoryRequest.current = controller;
    setMemoryOpen(true);
    setMemoryLoading(true);
    setMemoryError("");
    try {
      const result = await api<{ items: Memory[]; enabled?: boolean }>(
        "/ai/memories",
        { signal: controller.signal },
      );
      if (!mounted.current || revision !== memoryRevision.current) return;
      setMemories(result.items);
      setMemoryEnabled(result.enabled !== false);
    } catch (e) {
      if (mounted.current && revision === memoryRevision.current)
        setMemoryError(errorText(e));
    } finally {
      if (mounted.current && revision === memoryRevision.current) {
        memoryRequest.current = null;
        setMemoryLoading(false);
      }
    }
  };
  const invalidateMemoryRead = () => {
    ++memoryRevision.current;
    memoryRequest.current?.abort();
    memoryRequest.current = null;
    setMemoryLoading(false);
  };
  const forget = async (id: string) => {
    invalidateMemoryRead();
    try {
      await api(`/ai/memories/${id}`, { method: "DELETE" });
      invalidateMemoryRead();
      setMemories((list) => list.filter((item) => item.id !== id));
      toast.success("已删除这条记忆");
    } catch (e) {
      toast.error(errorText(e));
    }
  };
  const changeMemoryPreference = async (enabled: boolean) => {
    if (memorySaving) return;
    invalidateMemoryRead();
    setMemorySaving(true);
    try {
      await api("/ai/memory-preference", {
        method: "PUT",
        body: JSON.stringify({ enabled }),
      });
      invalidateMemoryRead();
      setMemoryEnabled(enabled);
      toast.success(enabled ? "已开启个人记忆" : "已关闭个人记忆");
    } catch (e) {
      toast.error(errorText(e));
    } finally {
      setMemorySaving(false);
    }
  };
  const send = useCallback(
    async (text?: string) => {
      const value = (text ?? draft.current).trim();
      if (!value || activeRequest.current || historyRequest.current) return;
      if (value.length > 8000) {
        setError("问题最多支持 8000 个字符，请缩短后再发送。");
        return;
      }
      const id = crypto.randomUUID();
      const controller = new AbortController();
      const request: ActiveChatRequest = { id, controller };
      activeRequest.current = request;
      const revision = ++viewRevision.current;
      let currentConversationId = activeConversation.current;
      const ownsRequest = () =>
        mounted.current && activeRequest.current === request;
      const updateReply = (
        update: (item: StreamChatMessage) => StreamChatMessage,
      ) => {
        if (ownsRequest())
          setMessages((items) =>
            items.map((item) => (item.id === id ? update(item) : item)),
          );
      };
      updateDraft("");
      setBusy(true);
      setAnnouncement("问题已发送，正在连接导购");
      setError("");
      scrollFollow.current.resume();
      if (transcript.current)
        scrollFollow.current.didProgrammaticScroll(
          scrollMetrics(transcript.current),
        );
      setMessages((items) => [
        ...items,
        { id: `${id}-user`, role: "user", content: value },
        {
          id,
          role: "assistant",
          content: "",
          status: "waiting",
          request: value,
          phases: [],
        },
      ]);
      let finished = false;
      let retryable = true;
      try {
        const response = await openChatStream(
          currentConversationId,
          value,
          controller.signal,
          () => setAnnouncement("正在等待上一轮回答结束，随后重新回答"),
        );
        if (
          !response.headers
            .get("Content-Type")
            ?.toLowerCase()
            .includes("text/event-stream")
        ) {
          throw new Error("回答连接格式异常，请稍后重新发送。");
        }
        if (!response.body) throw new Error("回答连接未建立，请重新发送。");
        for await (const event of readEvents(
          response.body,
          controller.signal,
        )) {
          if (!ownsRequest() || controller.signal.aborted) break;
          if (
            !["meta", "phase", "delta", "sources", "error", "done"].includes(
              event.event,
            )
          )
            continue;
          let body;
          try {
            body = JSON.parse(event.data);
          } catch {
            throw new Error("回答数据格式异常，请重新发送。");
          }
          if (!body || typeof body !== "object" || Array.isArray(body))
            throw new Error("回答数据格式异常，请重新发送。");
          if (
            event.event === "meta" &&
            typeof body.conversationId === "string"
          ) {
            if (typeof body.requestId === "string")
              request.serverRequestId = body.requestId;
            setConversationId(body.conversationId);
            activeConversation.current = body.conversationId;
            currentConversationId = body.conversationId;
          } else if (
            event.event === "phase" &&
            typeof body.label === "string"
          ) {
            const next: PublicPhase = {
              code: typeof body.code === "string" ? body.code : body.label,
              label: body.label,
              status: body.status === "completed" ? "completed" : "running",
            };
            updateReply((item) => ({
              ...item,
              phases: updatePublicPhases(item.phases || [], next),
            }));
            setAnnouncement(body.label);
          } else if (
            event.event === "delta" &&
            typeof body.content === "string"
          ) {
            updateReply((item) => ({
              ...item,
              status: "streaming",
              content: item.content + body.content,
            }));
          } else if (event.event === "sources" && Array.isArray(body.items)) {
            const sources: Source[] = body.items.filter(
              (source: Source) =>
                source &&
                typeof source.title === "string" &&
                typeof source.excerpt === "string",
            );
            updateReply((item) => ({ ...item, sources }));
          } else if (event.event === "error") {
            retryable = body.retryable !== false;
            throw new Error(
              typeof body.message === "string"
                ? body.message
                : "回答中断，请重试。",
            );
          } else if (event.event === "done") {
            finished = true;
            break;
          }
        }
        if (!ownsRequest()) return;
        if (controller.signal.aborted) {
          updateReply((item) => ({ ...item, status: "stopped" }));
          setAnnouncement("本次回答已停止");
        } else {
          updateReply((item) => ({
            ...item,
            status: finished ? "complete" : "interrupted",
            phases: finished
              ? item.phases?.map((phase) => ({ ...phase, status: "completed" }))
              : item.phases,
          }));
          setAnnouncement(
            finished
              ? "回答已完成，可查看正文和参考资料"
              : "回答连接中断，已保留收到的文字",
          );
        }
      } catch (e) {
        if (!ownsRequest()) return;
        const stopped = controller.signal.aborted;
        updateReply((item) => ({
          ...item,
          status: stopped ? "stopped" : "error",
          issue: stopped ? undefined : errorText(e),
          retryable,
        }));
        setAnnouncement(
          stopped
            ? "本次回答已停止"
            : retryable
              ? "本次回答未完成，可在该回复下重试"
              : "本次回答未完成，请查看回复下方的提示",
        );
      } finally {
        if (request.cancellation) {
          await request.cancellation;
        }
        if (activeRequest.current === request) {
          activeRequest.current = null;
          if (mounted.current) {
            setBusy(false);
            setStopping(false);
            void loadConversations();
            if (finished && currentConversationId) {
              const completedId = currentConversationId;
              void api<{ stats?: ConversationStats }>(
                `/ai/conversations/${completedId}`,
              )
                .then((result) => {
                  if (
                    mounted.current &&
                    viewRevision.current === revision &&
                    activeConversation.current === completedId
                  )
                    setStats(result.stats || null);
                })
                .catch(() => {});
            }
          }
        }
      }
    },
    [loadConversations, updateDraft],
  );
  const stopResponse = () => {
    const request = activeRequest.current;
    if (!request || request.controller.signal.aborted) return;
    setStopping(true);
    setAnnouncement("正在停止本轮回答，等待后台完成清理");
    if (request.serverRequestId)
      request.cancellation = cancelChatRequest(request.serverRequestId).catch(
        (failure) => {
          if (mounted.current && activeRequest.current === request)
            setError(errorText(failure));
        },
      );
    request.controller.abort();
    setMessages((items) =>
      items.map((item) =>
        item.id === request.id ? { ...item, status: "stopped" } : item,
      ),
    );
  };
  const history = (
    <>
      <div className="workspace-sidebar-title">
        <ChatCircleDots size={22} weight="duotone" />
        <span>导购工作区</span>
      </div>
      <Button
        className="new-conversation-button"
        block
        type="primary"
        icon={<Plus size={18} />}
        onClick={newConversation}
        disabled={busy || historyLoading}
      >
        开启新对话
      </Button>
      <div className="history-section-heading">
        <h2>最近对话</h2>
        {!conversationsLoading && <span>{conversations.length}</span>}
      </div>
      <div className="conversation-list">
        {conversationsLoading ? (
          <Skeleton active title={false} paragraph={{ rows: 4 }} />
        ) : !conversations.length ? (
          <div className="conversation-empty">
            <ClockCounterClockwise size={26} />
            <p>从一个问题开始</p>
            <span>已保存的对话会出现在这里，随时接着聊。</span>
          </div>
        ) : (
          conversations.map((item) => (
            <button
              key={item.id}
              disabled={busy || historyLoading}
              className={conversationId === item.id ? "active" : ""}
              aria-current={conversationId === item.id ? "true" : undefined}
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
    <div className="assistant-layout precision-assistant stream-assistant">
      <aside className="conversation-sidebar">{history}</aside>
      <section className="chat-workspace">
        <header className="chat-header">
          <div className="chat-title">
            <span className="assistant-avatar">
              <ChatCircleDots size={22} />
            </span>
            <div>
              <h1>比特导购</h1>
              <p>
                {busy
                  ? stopping
                    ? "正在停止上一轮回答…"
                    : "正在为你整理选购信息…"
                  : "把预算、场景与商品资料放在一起考虑。"}
              </p>
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
          className="stream-announcement"
          role="status"
          aria-live="polite"
          aria-atomic="true"
        >
          {announcement}
        </div>
        <div className="stream-scroll-region">
          <div
            ref={transcript}
            className="chat-transcript"
            tabIndex={0}
            aria-label="导购对话"
            aria-live="off"
            onWheelCapture={(event) => {
              if (!hasMessages || event.deltaY === 0) return;
              scrollFollow.current.userScroll(
                event.deltaY < 0 ? "up" : "down",
                scrollMetrics(event.currentTarget),
              );
            }}
            onKeyDownCapture={(event) => {
              if (
                !hasMessages ||
                event.ctrlKey ||
                event.metaKey ||
                event.altKey
              )
                return;
              const target = event.target as HTMLElement;
              if (
                target.closest(
                  'input, textarea, select, [contenteditable]:not([contenteditable="false"])',
                )
              )
                return;
              if (
                (event.key === " " || event.key === "Spacebar") &&
                target.closest("button, a, summary")
              )
                return;
              const direction = chatScrollKeyDirection(
                event.key,
                event.shiftKey,
              );
              if (direction)
                scrollFollow.current.userScroll(
                  direction,
                  scrollMetrics(event.currentTarget),
                );
            }}
            onTouchStart={(event) => {
              touchY.current = event.touches[0]?.clientY ?? null;
            }}
            onTouchMove={(event) => {
              const nextY = event.touches[0]?.clientY;
              if (
                hasMessages &&
                touchY.current !== null &&
                nextY !== undefined &&
                nextY !== touchY.current
              ) {
                scrollFollow.current.userScroll(
                  nextY > touchY.current ? "up" : "down",
                  scrollMetrics(event.currentTarget),
                );
              }
              touchY.current = nextY ?? null;
            }}
            onTouchEnd={() => {
              touchY.current = null;
            }}
            onTouchCancel={() => {
              touchY.current = null;
              scrollFollow.current.endUserScroll();
            }}
            onPointerDownCapture={(event) => {
              const element = event.currentTarget;
              if (
                !hasMessages ||
                event.pointerType !== "mouse" ||
                event.button !== 0 ||
                event.target !== element ||
                element.scrollHeight <= element.clientHeight
              )
                return;
              const right = element.getBoundingClientRect().right;
              const gutter = Math.max(
                element.offsetWidth - element.clientWidth,
                16,
              );
              if (event.clientX >= right - gutter && event.clientX <= right)
                scrollFollow.current.beginScrollbarDrag(scrollMetrics(element));
            }}
            onScrollEnd={() => scrollFollow.current.endUserScroll()}
            onScroll={(event) => {
              if (!hasMessages) {
                setShowLatest(false);
                return;
              }
              const element = event.currentTarget;
              scrollFollow.current.scroll(scrollMetrics(element));
              const distanceFromBottom =
                element.scrollHeight - element.clientHeight - element.scrollTop;
              setShowLatest(
                distanceFromBottom >
                  (scrollFollow.current.isFollowing() ? 96 : 2),
              );
            }}
          >
            <div ref={transcriptContent}>
              {historyLoading ? (
                <div className="chat-loading">
                  <Skeleton active paragraph={{ rows: 4 }} />
                  <p>正在找回对话</p>
                </div>
              ) : !messages.length ? (
                <div className="chat-welcome">
                  <span
                    className="assistant-welcome-orbit"
                    aria-hidden="true"
                  />
                  <div className="welcome-intro">
                    <span className="welcome-mark">
                      <ChatCircleDots size={36} weight="duotone" />
                    </span>
                    <span className="welcome-label">你的专属选购空间</span>
                  </div>
                  <h2>
                    想选得明白，
                    <br />
                    <span>我们一起看看。</span>
                  </h2>
                  <p>
                    从预算、使用习惯或一件心仪商品开始。
                    我会查阅资料，帮你找到适合自己的选择。
                  </p>
                  <div className="suggestion-grid">
                    {[
                      {
                        title: "通勤耳机",
                        detail: "预算 300 元，听听怎么选",
                        question: "预算 300 元，帮我挑一副通勤耳机",
                        icon: <Headphones size={23} />,
                      },
                      {
                        title: "舒适生活",
                        detail: "让出租屋多一点舒服",
                        question: "想让出租屋更舒适，有哪些实用好物？",
                        icon: <House size={23} />,
                      },
                      {
                        title: "办公桌面",
                        detail: "久坐办公，也能得心应手",
                        question: "每天久坐办公，怎么搭配桌面装备？",
                        icon: <Desktop size={23} />,
                      },
                      {
                        title: "一人食厨房",
                        detail: "简单做饭，轻松收拾",
                        question: "帮我选适合一个人做饭的厨具",
                        icon: <CookingPot size={23} />,
                      },
                    ].map(({ title, detail, question, icon }) => (
                      <button
                        key={title}
                        onClick={() => void send(question)}
                        disabled={busy || historyLoading}
                      >
                        <span className="suggestion-icon">{icon}</span>
                        <span className="suggestion-copy">
                          <strong>{title}</strong>
                          <small>{detail}</small>
                        </span>
                        <ArrowRight size={18} />
                      </button>
                    ))}
                  </div>
                  <p className="assistant-intro">
                    <BookOpenText size={17} />
                    参考说明书与实际在售商品，答案附可查看的资料。
                  </p>
                </div>
              ) : (
                <div className="message-list">
                  {messages.map((item) => (
                    <AssistantStreamMessage
                      key={item.id}
                      item={item}
                      onRetry={send}
                      retryDisabled={busy || historyLoading}
                    />
                  ))}
                </div>
              )}
            </div>
          </div>
          {showLatest && messages.length > 0 && (
            <Button
              className="latest-reply-button"
              icon={<ArrowDown size={16} />}
              onClick={jumpToLatest}
            >
              回到最新
            </Button>
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
                  label: (
                    <span className="context-summary-label">
                      <ClockCounterClockwise size={15} />
                      {stats.summaryPresent
                        ? "较早对话已整理为摘要"
                        : "当前对话保留完整上下文"}
                      <span>查看会话状态</span>
                    </span>
                  ),
                  children: (
                    <div>
                      <p>
                        当前模型一次可参考的文字容量为{" "}
                        {stats.contextCapacity.toLocaleString()}{" "}
                        token（文字计量单位）。
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
            <label className="composer-label" htmlFor="bit-assistant-input">
              说说你想找什么
            </label>
            <Input.TextArea
              id="bit-assistant-input"
              ref={inputRef}
              aria-label="发送给导购的问题"
              aria-describedby="stream-input-hint"
              value={input}
              onChange={(event) => updateDraft(event.target.value)}
              placeholder="告诉我你想选什么，或说说你的使用习惯…"
              autoSize={{ minRows: 2, maxRows: 5 }}
              maxLength={8000}
              onKeyDown={(event) => {
                if (
                  event.key === "Enter" &&
                  !event.shiftKey &&
                  !event.nativeEvent.isComposing &&
                  !busy &&
                  window.matchMedia("(hover: hover) and (pointer: fine)")
                    .matches
                ) {
                  event.preventDefault();
                  void send();
                }
              }}
            />
            <div className="composer-toolbar">
              <span id="stream-input-hint" className="composer-context">
                <BookOpenText size={15} />
                {busy
                  ? stopping
                    ? "正在停止，完成后可以重新回答"
                    : "回答中，也可以准备下一条问题"
                  : "预算、场景、偏好，都可以聊"}
              </span>
              <div className="composer-send-actions">
                {input.length >= 7600 && (
                  <span
                    className="composer-character-count"
                    aria-label={`已输入 ${input.length} 个字符，最多 8000 个字符`}
                  >
                    {input.length.toLocaleString()} / 8,000
                  </span>
                )}
                {busy ? (
                  <Button
                    type="primary"
                    aria-label={stopping ? "正在停止生成" : "停止生成"}
                    loading={stopping}
                    disabled={stopping}
                    icon={<Stop size={19} weight="fill" />}
                    onClick={stopResponse}
                  />
                ) : (
                  <Button
                    type="primary"
                    htmlType="submit"
                    aria-label="发送问题"
                    disabled={!input.trim() || historyLoading}
                    icon={<ArrowUp size={21} />}
                  />
                )}
              </div>
            </div>
          </form>
          <p className="composer-note">
            <span>AI 建议供选购参考，价格与库存以商品页为准。</span>
            <span className="composer-shortcut">
              <kbd>Shift</kbd> + <kbd>Enter</kbd> 换行
            </span>
          </p>
        </div>
      </section>
      <Drawer
        rootClassName="precision-drawer precision-history-drawer"
        title="对话记录"
        placement="left"
        open={historyOpen}
        onClose={() => setHistoryOpen(false)}
      >
        {history}
      </Drawer>
      <Drawer
        rootClassName="precision-drawer precision-memory-drawer"
        title="关于你的记忆"
        open={memoryOpen}
        onClose={() => setMemoryOpen(false)}
        size={460}
      >
        <div className="memory-drawer-intro">
          <Brain size={28} weight="duotone" />
          <h2>越了解，越合适。</h2>
        </div>
        <p className="memory-description">
          导购会记住你提到的偏好和使用习惯，只用于你自己的推荐。新记忆需要一点时间整理；有冲突时优先参考你最近的表述。
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
            disabled={memoryLoading || !!memoryError}
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
        <div className="memory-list-heading">
          <h3>已保存的偏好</h3>
          <Button
            icon={<ClockCounterClockwise size={18} />}
            onClick={loadMemories}
            loading={memoryLoading}
          >
            刷新记忆
          </Button>
        </div>
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
