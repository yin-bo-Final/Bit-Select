package com.bitselect.contracts;

/** Read-only commerce context. Callers must derive userId from an authenticated session. */
public interface CommerceReadRpc {
  String userContext(long userId, String intent);
}
