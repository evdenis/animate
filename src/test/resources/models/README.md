# Event-B Test Models

This directory contains Event-B models used for testing the animate program.

## Source

All models are from the repository: https://github.com/17451k/eventb-models

## Models

### Binary Search (`binary-search/`)
- Implementation of the binary search algorithm
- Models: M0.bum, M1.bum, M2.bum, M3.bum (refinement chain)

### Cars on Bridge (`cars-on-bridge/`)
- A model for controlling cars on a bridge
- Models: M0.bum, M1.bum, M2.bum, M3.bum (refinement chain)

### File System (`file-system/`)
- A Unix file system model
- Models: M0.bum

### Traffic Light (`traffic-light/`)
- A traffic light controller system
- Models: M0.bum, M1.bum, M2.bum (refinement chain)

## License

These models are provided under the MIT License as specified in the source repository.

## Usage in Tests

The models are automatically discovered and tested by:
- `ModelAnimationTest.java` - Tests loading, animation, and invariant checking
- `AnimateCliTest.java` - Tests the CLI tool with various models
