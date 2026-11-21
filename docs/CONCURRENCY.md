# Concurrency and update processing

**Status:** The library does **not** guarantee safe concurrent dispatcher registration, shared mutable dispatcher state or simultaneous updates to the same chat. The code does not provide per-chat serialization or atomic persistence.

## Specific risks

- [`TelegramBot`](../subprojects/handling/src/main/java/ru/council/telegram/support/TelegramBot.java) registers handlers into an ordinary `HashMap` (`executionMapping`) and a mutable `mainDispatcher`. `registerDispatcherAndItsMethods()` uses check-then-update logic without synchronization. Concurrent registration, or registration while handling updates, is unsafe.
- [`DispatcherWrapper`](../subprojects/handling/src/main/java/ru/council/telegram/support/DispatcherWrapper.java) stores the **same dispatcher instance** supplied at registration. Handler methods are invoked on that shared object via reflection. If an application handler changes instance fields, its concurrency safety depends on code outside this library.
- [`ApplicationState.copy()`](../subprojects/handling/src/main/java/ru/council/telegram/support/ApplicationState.java) copies the parameters map into a new `HashMap`, but **not the objects inside the map**. Nested mutable values remain shared.
- [`TelegramBot.onUpdateReceived()`](../subprojects/handling/src/main/java/ru/council/telegram/support/TelegramBot.java) copies the supplied chat state, invokes handlers and later calls `persistState(copy)`. Two concurrent updates for the same chat can independently read an old state and overwrite each other's changes if the caller/storage does not coordinate them. `persistState` is abstract; storage guarantees must come from the application.

## Recommended integration contract

1. **Register all dispatchers before receiving updates**; do not modify the registry at runtime without a properly synchronized design.
2. Make dispatcher implementations stateless or independently thread-safe. Keep per-chat mutable information in an explicitly managed chat state, not shared fields on a handler instance.
3. Process updates for the **same chat sequentially** from state loading through handler execution and durable state persistence. A per-chat queue/actor is one option. For multiple service instances, use storage-level version checks (optimistic locking) or transactions, not only an in-memory monitor.
4. Do not assume copying `ApplicationState` isolates nested objects; copy or reconstruct mutable nested values where necessary.
5. If processing different chats concurrently, guarantee correct visibility and thread safety for the application's dispatcher implementations and persistence layer.

## Tests to establish a guarantee

A concurrency suite should deliver simultaneous updates to one chat and assert that every expected state transition survives, while checking independent chats can be processed in parallel. Add concurrent-registration tests only if dynamic registration is meant to be supported.

This note is a source-level assessment; no concurrency stress test was run for this change.
