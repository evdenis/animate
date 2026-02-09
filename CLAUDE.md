# CLAUDE.md

## Project Overview

A CLI tool for animating Event-B models using the ProB 2.0 model checker.

## Build & Run

```bash
./gradlew build
./gradlew run --args="path/to/model.bum"
./gradlew run --args="replay -t trace.json path/to/model.bum"
./gradlew run --args="info path/to/model.bum"
```

## Architecture

Single-package (`animate`) Java 11 project with two classes:

- **`Animate`** — CLI application using picocli. Subcommands: `replay`, `info`. Dependency-injected via Guice.
- **`Config`** — Guice module installing ProB 2.0's `MainModule`.

Key dependencies: **de.prob2.kernel** (ProB 2.0), **picocli**, **Guice**, **logback**.

Gradle via wrapper. Fat JAR build. CI via GitHub Actions.
