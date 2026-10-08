export type ChatScrollMetrics = {
  scrollTop: number;
  scrollHeight: number;
  clientHeight: number;
};

export type ChatScrollDirection = "up" | "down";

const atBottom = (metrics: ChatScrollMetrics) =>
  metrics.scrollHeight - metrics.clientHeight - metrics.scrollTop <= 2;

/** User intent owns following; a layout/scroll notification alone cannot resume it. */
export function createChatScrollFollow() {
  let following = true;
  let previous: ChatScrollMetrics | null = null;
  let downwardIntent = false;
  let programmaticTop: number | null = null;
  let draggingScrollbar = false;
  let dragReachedBottom = false;

  const pause = () => {
    following = false;
    downwardIntent = false;
    dragReachedBottom = false;
  };
  const resume = () => {
    following = true;
    downwardIntent = false;
    draggingScrollbar = false;
    dragReachedBottom = false;
  };
  const acceptDownwardMovement = (metrics: ChatScrollMetrics) => {
    const movedDown = previous && metrics.scrollTop > previous.scrollTop + 0.5;
    const programmatic =
      programmaticTop !== null &&
      Math.abs(metrics.scrollTop - programmaticTop) <= 0.5;
    if (!movedDown || programmatic || (!downwardIntent && !draggingScrollbar))
      return false;
    if (atBottom(metrics)) {
      if (draggingScrollbar) dragReachedBottom = true;
      else resume();
    }
    return true;
  };
  return {
    isFollowing: () => following,
    pause,
    resume,
    userScroll(direction: ChatScrollDirection, metrics: ChatScrollMetrics) {
      previous = { ...metrics };
      // A queued old notification has no movement from this fresh baseline.
      programmaticTop = null;
      if (direction === "up") pause();
      else if (atBottom(metrics)) resume();
      else downwardIntent = true;
    },
    observeLayout(metrics: ChatScrollMetrics) {
      // Browser movement can reach layout effects before its scroll event.
      // Process the actual displacement before replacing its reference point.
      const userMovedDown = acceptDownwardMovement(metrics);
      // Scrollbar movement can reach layout effects before the scroll event.
      if (
        previous &&
        metrics.scrollTop < previous.scrollTop - 0.5 &&
        !atBottom(metrics)
      )
        pause();
      const contentOnlyGrowth =
        previous &&
        metrics.scrollTop === previous.scrollTop &&
        metrics.clientHeight === previous.clientHeight &&
        metrics.scrollHeight > previous.scrollHeight;
      if (
        !userMovedDown &&
        !contentOnlyGrowth &&
        previous &&
        (previous.scrollTop !== metrics.scrollTop ||
          previous.scrollHeight !== metrics.scrollHeight ||
          previous.clientHeight !== metrics.clientHeight)
      ) {
        // Pure SSE growth may precede native movement; resize/clamp cannot
        // borrow a previous gesture, but growth alone does not cancel it.
        downwardIntent = false;
        dragReachedBottom = false;
      }
      previous = { ...metrics };
    },
    didProgrammaticScroll(metrics: ChatScrollMetrics) {
      previous = { ...metrics };
      programmaticTop = metrics.scrollTop;
      downwardIntent = false;
    },
    scroll(metrics: ChatScrollMetrics) {
      const delta = previous ? metrics.scrollTop - previous.scrollTop : 0;
      const programmatic =
        programmaticTop !== null &&
        Math.abs(metrics.scrollTop - programmaticTop) <= 0.5;
      if (!programmatic) {
        if (delta < -0.5) pause();
        else acceptDownwardMovement(metrics);
      }
      programmaticTop = null;
      previous = { ...metrics };
      return following;
    },
    beginScrollbarDrag(metrics: ChatScrollMetrics) {
      pause();
      previous = { ...metrics };
      draggingScrollbar = true;
    },
    endScrollbarDrag() {
      if (!draggingScrollbar) return;
      const shouldResume =
        draggingScrollbar &&
        dragReachedBottom &&
        previous &&
        atBottom(previous);
      draggingScrollbar = false;
      dragReachedBottom = false;
      downwardIntent = false;
      if (shouldResume) resume();
    },
    endUserScroll() {
      downwardIntent = false;
    },
  };
}

export function chatScrollKeyDirection(
  key: string,
  shiftKey = false,
): ChatScrollDirection | null {
  if (key === "ArrowUp" || key === "PageUp" || key === "Home") return "up";
  if (key === "ArrowDown" || key === "PageDown" || key === "End") return "down";
  if (key === " " || key === "Spacebar") return shiftKey ? "up" : "down";
  return null;
}
