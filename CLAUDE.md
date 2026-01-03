# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build the project
./gradlew build

# Run with arguments
./gradlew run --args="path/to/model.bum"

# Build fat JAR (includes all dependencies)
./gradlew jar
# Output: build/libs/animate.jar
```

## Project Overview

Animate is a CLI tool for animating Event-B models using the ProB model checker. It provides random animation, invariant checking, coverage analysis, and trace replay capabilities.

## Architecture

**Entry Point:** `animate.Animate` - Main class using picocli for CLI parsing with three commands:
- Default command: Random animation of Event-B models
- `replay`: Replay a saved JSON trace
- `info`: Export model visualizations (dot/svg) and dump prolog model

**Dependency Injection:** Uses Google Guice with `animate.Config` installing ProB's `MainModule` for API access.

**Key Dependencies:**
- `de.prob2.kernel` (ProB 2.0) - Core model checking and animation engine
- `picocli` - Command-line argument parsing
- `logback` - Logging

**ProB Integration:** The `Api` class from ProB is injected to load Event-B models. Models are loaded via `api.eventb_load()` with configured preferences (memoization, symbolic mode, symmetry, etc.). Animation uses ProB's `Trace` and `StateSpace` classes.
