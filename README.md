# Telegram Support Library

A Java library for organizing Telegram bot update handling and outgoing replies. It builds on `telegrambots-spring-boot-starter` and separates message dispatch from response models.

## Modules

| Module | Responsibility |
| --- | --- |
| `handling` | Bot base class, dispatcher registration, update routing and handler annotations |
| `reply` | Response types for text, photos, documents, audio, video and message edits |

The `handling` module defines an abstract `TelegramBot` and an `@Dispatcher` annotation. Dispatchers can handle message types and callback queries. `ApplicationState` carries the state used for routing updates.

## Reading the code

- [`TelegramBot.java`](subprojects/handling/src/main/java/ru/council/telegram/support/TelegramBot.java): dispatcher registration and update handling.
- [Handler annotations](subprojects/handling/src/main/java/ru/council/telegram/support/annotations/): supported handler types.
- [`ExampleAccountDispatcher.java`](subprojects/handling/src/test/java/ru/council/telegram/support/ExampleAccountDispatcher.java): a small dispatcher example.
- [Response models](subprojects/reply/src/main/java/ru/council/telegram/support/reply/models/): supported outgoing messages.

## Build

The repository uses a multi-project Gradle build. With Gradle installed, run from the root:

```sh
gradle :handling:build :reply:build
```

The root build also refers to a separate Maven package registry. Access may be needed to resolve non-public artifacts; configuration reads `LIBS_PACKAGES_READ_TOKEN` from the environment.

## Scope

This is a library, not a deployable bot. A consuming application must supply bot credentials, dispatcher implementations and its own application-state persistence. Do not commit Telegram tokens to the repository.

**Build status:** the commands are derived from Gradle files and have not been executed as part of this documentation update.
