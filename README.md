# Animate

A command-line tool for animating Event-B models using the ProB model checker.

## Features

- Random animation of Event-B models
- Invariant checking during animation
- Coverage analysis
- Trace saving and replay in JSON format
- Model visualization export (machine hierarchy, events, properties, invariants)

## Requirements

- Java 8 or later
- Gradle

## Building

```bash
./gradlew build
```

## Usage

### Basic Animation

```bash
./gradlew run --args="path/to/model.bum"
```

### Options

- `-s, --steps <n>` - Number of random animation steps (default: 5)
- `-z, --size <n>` - Default size for ProB sets (default: 4)
- `-i, --invariants` - Check invariants during animation
- `--perf` - Print ProB performance information
- `--save <file.json>` - Save animation trace to JSON file
- `--debug` - Enable debug logging

### Commands

#### Replay a Trace

```bash
./gradlew run --args="replay -t path/to/trace.json path/to/model.bum"
```

#### Model Information

```bash
./gradlew run --args="info path/to/model.bum"
```

Export options:
- `-m, --machine <file>` - Save machine hierarchy graph (.dot or .svg)
- `-e, --events <file>` - Save events hierarchy graph (.dot or .svg)
- `-p, --properties <file>` - Save properties graph (.dot or .svg)
- `-i, --invariant <file>` - Save invariant graph (.dot or .svg)
- `-b, --bmodel <file>` - Dump prolog model to .eventb file

## License

See LICENSE file for details.
