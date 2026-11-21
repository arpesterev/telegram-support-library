# Concurrency and update processing

The library now offers **per-chat serialization in a single bot instance** and safe registration map publication. The application is still responsible for durable state reads/writes, handler thread safety and coordination across multiple bot processes.

## Processing an update with fresh state

Use the overload that accepts the chat ID and a loader:

```java
bot.onUpdateReceived(update, chatId, () -> stateRepository.load(chatId));
```

The loader is invoked **after acquiring the chat lock**; it must load the current persisted state. The method holds the same lock through handling and `persistState(copy)`, preventing two updates for the same chat from reading one stale state in the same bot instance.

An earlier overload, `onUpdateReceived(update, applicationState)`, remains for compatibility. It serializes processing, but **cannot guarantee freshness** if `applicationState` was fetched before another update. Prefer the loader overload for concurrent applications.

Implementation: [`TelegramBot`](../subprojects/handling/src/main/java/ru/council/telegram/support/TelegramBot.java). A fixed number of lock stripes avoids an unbounded chat-to-lock map. Different chats that hash to the same stripe may be serialized unnecessarily.

## Dispatcher registration

`registerDispatcherAndItsMethods` is synchronized, and the dispatcher registry uses `ConcurrentHashMap` with atomic duplicate detection. Registering before updates start is still the recommended pattern. `DispatcherWrapper` invokes methods on the **same dispatcher object** supplied by the application, so handlers should be stateless or implement their own synchronization for shared fields.

## Remaining limitations

- `ApplicationState.copy()` makes a new parameter map but does not deep-copy values within the map. Treat nested values as immutable or copy them explicitly.
- Local chat locks cannot protect multiple JVMs or application instances. Use optimistic concurrency/version checks or transactions in the state store.
- The abstract `persistState` method must not return before a state write is durably completed if the caller expects serialization.
- The new overload does not protect callers that independently write to the state store outside the same processing protocol.
- `ApplicationState` now implements `equals(Object)` and `hashCode()`, so unchanged copies are no longer unconditionally marked as modified.

## Regression tests

[`ConcurrentUpdateTest`](../subprojects/handling/src/test/java/ru/council/telegram/support/ConcurrentUpdateTest.java) schedules 120 concurrent updates for one chat and checks the final count. It also checks value equality and rejects mismatched loaded state. See CI for execution status.
