# FXC

A multi-module Gradle project scaffolding four independent system components. Each module currently
contains only a stub `Main` class — no functionality is implemented yet.

## Modules

- **FxcPub** — a Mastodon server implementation based on [Vysper](https://github.com/apache/mina-vysper).
- **FxcInvestor** — an agent and UI client for FxcBroker and FxcPub.
- **FxcBroker** — a minimal OFX brokerage account implementation with an order management system
  (OMS). Connects to FxcPub and FxcExchange via FIX, and accepts OFX connections from FxcInvestor
  instances.
- **FxcExchange** — minimal market data, trade matching, and clearing.

## Requirements

- Java 21 (all modules target this via the Gradle toolchain).

## Build

```sh
./gradlew build
```

## Run a module

```sh
./gradlew :FxcPub:run
./gradlew :FxcInvestor:run
./gradlew :FxcBroker:run
./gradlew :FxcExchange:run
```
