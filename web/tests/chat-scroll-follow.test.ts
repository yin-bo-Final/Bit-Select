import test from "node:test";
import assert from "node:assert/strict";
import {
  chatScrollKeyDirection,
  createChatScrollFollow,
} from "../src/lib/chat-scroll-follow.ts";

const position = (
  scrollTop: number,
  scrollHeight = 1000,
  clientHeight = 300,
) => ({
  scrollTop,
  scrollHeight,
  clientHeight,
});

test("a small upward wheel pauses before the browser dispatches scroll", () => {
  const follow = createChatScrollFollow();
  follow.observeLayout(position(700));
  follow.userScroll("up", position(700));
  assert.equal(follow.isFollowing(), false);
  // The next SSE delta can arrive before the wheel's delayed scroll event.
  follow.observeLayout(position(700, 1040));
  assert.equal(follow.isFollowing(), false);
  follow.scroll(position(676, 1040));
  assert.equal(follow.isFollowing(), false);
});

test("content growth and old automatic scroll events cannot resume a paused view", () => {
  const follow = createChatScrollFollow();
  follow.observeLayout(position(700));
  follow.userScroll("up", position(700));
  follow.didProgrammaticScroll(position(700));
  follow.scroll(position(700));
  assert.equal(follow.isFollowing(), false);
  for (let height = 1040; height <= 1800; height += 40) {
    follow.observeLayout(position(676, height));
    follow.scroll(position(676, height));
    assert.equal(follow.isFollowing(), false);
  }
});

test("a position decrease pauses even when it remains within the former 96px threshold", () => {
  const follow = createChatScrollFollow();
  follow.observeLayout(position(700));
  follow.scroll(position(694));
  assert.equal(follow.isFollowing(), false);
});

test("layout observes upward scrollbar movement before its delayed scroll event", () => {
  const follow = createChatScrollFollow();
  follow.observeLayout(position(700));
  follow.didProgrammaticScroll(position(700));
  follow.observeLayout(position(694, 1020));
  assert.equal(follow.isFollowing(), false);
  follow.scroll(position(694, 1020));
  assert.equal(follow.isFollowing(), false);
});

test("downward user movement resumes only after reaching the actual bottom", () => {
  const follow = createChatScrollFollow();
  follow.observeLayout(position(600));
  follow.pause();
  follow.userScroll("down", position(600));
  follow.scroll(position(650));
  assert.equal(follow.isFollowing(), false);
  follow.userScroll("down", position(650));
  follow.scroll(position(699));
  assert.equal(follow.isFollowing(), true);
});

test("a real downward move reaches bottom before its scroll callback", () => {
  const follow = createChatScrollFollow();
  follow.observeLayout(position(600));
  follow.pause();
  follow.userScroll("down", position(600));
  // React layout/ResizeObserver can run between the native move and scroll event.
  follow.observeLayout(position(740, 1040));
  assert.equal(follow.isFollowing(), true);
  follow.scroll(position(740, 1040));
  assert.equal(follow.isFollowing(), true);
});

test("incoming text between a downward gesture and its native movement preserves the gesture", () => {
  const follow = createChatScrollFollow();
  follow.observeLayout(position(600));
  follow.pause();
  follow.userScroll("down", position(600));
  follow.observeLayout(position(600, 1040));
  assert.equal(follow.isFollowing(), false);
  follow.scroll(position(740, 1040));
  assert.equal(follow.isFollowing(), true);
});

test("downward intent without actual movement does not grant a delayed resume", () => {
  const follow = createChatScrollFollow();
  follow.observeLayout(position(700));
  follow.pause();
  follow.userScroll("down", position(700));
  follow.scroll(position(700));
  assert.equal(follow.isFollowing(), false);
  follow.endUserScroll();
  follow.scroll(position(700));
  assert.equal(follow.isFollowing(), false);
});

test("a new downward action can reach bottom after an earlier programmatic scroll", () => {
  const follow = createChatScrollFollow();
  follow.didProgrammaticScroll(position(700));
  follow.userScroll("up", position(700));
  follow.scroll(position(676));
  follow.userScroll("down", position(676));
  follow.scroll(position(699));
  assert.equal(follow.isFollowing(), true);
});

test("layout clamping cannot reuse an earlier downward action to resume", () => {
  const follow = createChatScrollFollow();
  follow.observeLayout(position(600));
  follow.pause();
  follow.userScroll("down", position(600));
  follow.observeLayout(position(600, 900));
  follow.scroll(position(600, 900));
  assert.equal(follow.isFollowing(), false);
});

test("scrollbar dragging pauses first and can resume after real downward movement", () => {
  const follow = createChatScrollFollow();
  follow.observeLayout(position(700));
  follow.beginScrollbarDrag(position(700));
  assert.equal(follow.isFollowing(), false);
  follow.scroll(position(676));
  follow.scroll(position(600));
  assert.equal(follow.isFollowing(), false);
  follow.scroll(position(700));
  follow.endScrollbarDrag();
  assert.equal(follow.isFollowing(), true);
});

test("upward reading cancels any prior downward permission; explicit latest restores follow", () => {
  const follow = createChatScrollFollow();
  follow.observeLayout(position(650));
  follow.pause();
  follow.userScroll("down", position(650));
  follow.userScroll("up", position(650));
  follow.scroll(position(700));
  assert.equal(follow.isFollowing(), false);
  follow.resume();
  follow.didProgrammaticScroll(position(700));
  follow.observeLayout(position(700, 1200));
  assert.equal(follow.isFollowing(), true);
});

test("reading keys distinguish up, down, and unrelated keyboard input", () => {
  for (const key of ["ArrowUp", "PageUp", "Home"])
    assert.equal(chatScrollKeyDirection(key), "up");
  assert.equal(chatScrollKeyDirection(" ", true), "up");
  for (const key of ["ArrowDown", "PageDown", "End", " "])
    assert.equal(chatScrollKeyDirection(key), "down");
  assert.equal(chatScrollKeyDirection("Enter"), null);
});
