# Animate

A command-line tool for animating Event-B models using the ProB model checker.

## Features

- Random animation of Event-B models
- Invariant checking during animation
- Coverage analysis
- Trace saving and replay in JSON format
- Model visualization export (machine hierarchy, events, properties, invariants)

## Requirements

- Java 21 or later
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

## CI Integration

Use `animate` in your CI pipelines without building from source.

### GitHub Actions

```yaml
- uses: evdenis/animate@v4
  with:
    model-path: 'path/to/model.bum'
```

#### Inputs

| Input | Description | Required | Default |
|-------|-------------|----------|---------|
| `model-path` | Path to model `.bum`, `.zip`, or directory | Yes | — |
| `command` | Subcommand: `animate` or `replay` | No | `animate` |
| `steps` | Number of random animation steps (animate) | No | — |
| `size` | Default size for ProB sets (animate) | No | — |
| `invariants` | Check invariants during animation (animate) | No | `false` |
| `save` | Save animation trace to JSON file (animate) | No | — |
| `trace` | Path to JSON trace file (replay, required) | No | — |
| `args` | Extra args appended to the assembled command | No | — |
| `version` | Release version tag (e.g., `v3.0`) | No | `latest` |
| `java-version` | Java version to use (must be 21 or later) | No | `21` |

#### Examples

```yaml
# Check invariants with 20 steps
- uses: evdenis/animate@v4
  with:
    model-path: 'path/to/model.bum'
    steps: 20
    invariants: true

# Replay a trace
- uses: evdenis/animate@v4
  with:
    model-path: 'models/system.bum'
    command: 'replay'
    trace: 'tests/trace.json'

# Pin to a specific release
- uses: evdenis/animate@v4
  with:
    model-path: 'path/to/model.bum'
    version: 'v4.0'
```

### GitLab CI

Include the reusable template and extend the `.animate` hidden job:

```yaml
include:
  - remote: 'https://raw.githubusercontent.com/evdenis/animate/v3.0/.gitlab-ci-template.yml'

animate-model:
  extends: .animate
  variables:
    ANIMATE_ARGS: 'path/to/model.bum'
```

#### Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `ANIMATE_ARGS` | Arguments passed to the animate CLI | `''` |
| `ANIMATE_VERSION` | Release version tag (e.g., `v2.0`) | `latest` |

#### Examples

```yaml
include:
  - remote: 'https://raw.githubusercontent.com/evdenis/animate/v3.0/.gitlab-ci-template.yml'

# Check invariants with 20 steps
animate-check:
  extends: .animate
  variables:
    ANIMATE_ARGS: '--steps 20 --invariants path/to/model.bum'

# Replay a trace
animate-replay:
  extends: .animate
  variables:
    ANIMATE_ARGS: 'replay -t tests/trace.json models/system.bum'

# Pin to a specific release
animate-pinned:
  extends: .animate
  variables:
    ANIMATE_ARGS: 'path/to/model.bum'
    ANIMATE_VERSION: 'v1.0'
```

## License

See LICENSE file for details.
