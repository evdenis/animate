# Changelog

All notable changes to this project will be documented in this file.

## [4.1] - 2026-03-20

### Features

- Add SpotBugs static analysis and pre-commit/pre-push hooks
- Create GitHub release with auto-generated notes instead of just uploading JAR

### Bug Fixes

- Return non-zero exit code on invariant violations

### Testing

- Update invariant test to accept non-zero exit code

### Dependencies

- Bump actions/upload-artifact from 6 to 7
- Bump com.diffplug.spotless:spotless-plugin-gradle
- Bump gradle-wrapper from 9.3.1 to 9.4.0
- Bump com.github.spotbugs.snom:spotbugs-gradle-plugin

### Build

- Use Java toolchain instead of source/target compatibility

### CI/CD

- Reuse build artifact in release workflow instead of rebuilding
- Add workflow_dispatch trigger to build workflow

## [4.0] - 2026-03-01

### Features

- Replace single args input with structured inputs in action.yml
- Replace single ANIMATE_ARGS with structured variables in GitLab CI template

## [3.1] - 2026-03-01

### Features

- Support .zip files
- Auto-select most-refined .bum from multi-machine dirs and zips

### Refactoring

- Extract ModelResolver from Animate
- Extract ReplayCommand and InfoCommand from Animate

## [3.0] - 2026-02-25

### Bug Fixes

- Replace exception-based flow control with break in start()
- Inject TraceManager via constructor parameter
- Ensure stateSpace.kill() runs even when trace save fails in call()
- Use actual ProB version in trace metadata instead of literal "version"

### Refactoring

- Rename snake_case identifiers to camelCase
- Reduce visibility of internal methods to private
- Change model field from File to Path
- Remove unused useIndentation parameter from eventbSave()
- Extract initAndLoadModel() helper to deduplicate init+load+error-handling
- Extract saveVisualization() helper in info()
- Minor cleanups in loadModel()

### Dependencies

- Bump actions/setup-java from 4 to 5
- Bump de.hhu.stups:de.prob2.kernel from 4.15.0 to 4.15.1
- Bump ch.qos.logback:logback-classic from 1.5.23 to 1.5.32
- Bump org.hamcrest:hamcrest from 2.2 to 3.0

### CI/CD

- Add actions

### Miscellaneous

- Simplify CLAUDE.md and fix deprecated Gradle APIs
- Update gradle and bump minimal java version
- Add spotlesscheck
- Add GitLab template

## [2.0] - 2026-01-03

### Features

- Directly use stateSpace variable for coverage printing
- Don't output empty coverage stats
- Add invariants checking
- Check for deadlocks
- Exit after saving *.eventb model state
- Pretty-print eventb model
- Output model graph
- Don't use deprecated api.getVersion()
- Kill stateSpace at the end of the execution
- Use new trace.anyEvent() API
- Add start/end transaction calls
- Add logging
- Remove short options for eventb/graph/perf
- Allow to save animation traces
- Save dot and svg files with --graph
- Use stderr to print errors
- Switch to picocli
- Exit with error in case trace saving fails
- Use animate creator in saved traces
- Make model parameter
- Use default values in commands descriptions
- Implement Callable to correctly return exit codes
- Add help and info subcommands
- Don't sort cli options
- Provide additional information with info command
- Add replay command
- Update trace JSON format version from 5 to 6
- Make setup_constants step optional
- Handle models without constants in info command
- Add input validation for model file and parameters
- Improve exception handling with proper logging
- Add null and type check for EventBMachine cast

### Bug Fixes

- Fix arguments option
- Fix typo
- Update EventBModelTranslator constructor call
- Update DotVisualizationCommand to non-deprecated API
- Use Guice dependency injection for ProB API in tests
- Fix resource leak in replay subcommand

### Other

- Create dependabot.yml
- Add README.md with project documentation
- Update dependabot.yml
- CLAUDE.md
- Update to Java 21
- Update README.md
- Add comprehensive testing infrastructure with Event-B models
- Add LICENSE

### Refactoring

- Refactor coverage print
- Rename trace variable
- Simplify invariants computation in findViolatedInvariants()
- Refactor functions
- Reformat code
- Extract execute() method to enable CLI testing
- Refactor code

### Testing

- Add base-model Event-B test case
- Base-model: Mark InductionAxiom as theorem for ProB.

### Dependencies

- Bump actions/upload-artifact from 3 to 5
- Bump actions/checkout from 3 to 5
- Bump gradle/gradle-build-action from 2 to 3
- Bump info.picocli:picocli from 4.7.4 to 4.7.7
- Bump ch.qos.logback:logback-classic from 1.3.7 to 1.5.21
- Bump de.hhu.stups:de.prob2.kernel from 4.12.2 to 4.15.0
- Bump actions/checkout from 5 to 6
- Bump actions/upload-artifact from 5 to 6
- Bump ch.qos.logback:logback-classic from 1.5.21 to 1.5.23

### Build

- Bump compatability to 1.8
- Update gradlew wrapper
- Update build.gradle to new gradle version
- Add commons-cli build dependency
- Add ch.qos.logback dependency
- Upgrade ProB library version to 4.12
- Add -Xlint:deprecation
- Upgrade ProB library version to 4.12.1
- Upgrade ProB library version to 4.12.2
- Upgrade minimum Java version to 11
- Add project version

### CI/CD

- Add Github Actions CI build
- Publish CI build results
- Upgrade GitHub Actions
- Use actions/setup-gradle
- Remove separate test step
- Use path mask for artifact upload

## [1.0] - 2020-07-02

### Features

- Simple ProB tool for random model animation
- Add ProB prefs and more options
- Dump internal model representation
- Print coverage

### Other

- Bump ProB library version to 3.11.0

### Build

- Update wrapper properties


