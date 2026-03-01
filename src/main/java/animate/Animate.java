package animate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Stage;
import de.prob.animator.command.ComputeCoverageCommand;
import de.prob.animator.command.ComputeCoverageCommand.ComputeCoverageResult;
import de.prob.animator.command.GetVersionCommand;
import de.prob.animator.domainobjects.*;
import de.prob.check.tracereplay.json.TraceManager;
import de.prob.check.tracereplay.json.storage.TraceJsonFile;
import de.prob.json.JsonMetadata;
import de.prob.json.JsonMetadataBuilder;
import de.prob.model.eventb.EventBMachine;
import de.prob.scripting.Api;
import de.prob.statespace.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ScopeType;

@Command(
    name = "animate",
    sortOptions = false,
    version = "animate @VERSION@",
    subcommands = {CommandLine.HelpCommand.class, ReplayCommand.class, InfoCommand.class})
public class Animate implements Callable<Integer> {

  private static final Injector INJECTOR = Guice.createInjector(Stage.PRODUCTION, new Config());

  private final Api api;
  private final TraceManager traceManager;
  final ModelResolver modelResolver = new ModelResolver();
  private String probVersionString;

  private static final Logger logger = (Logger) LoggerFactory.getLogger(Animate.class);

  @Parameters(description = "path to model.bum or .zip file", scope = ScopeType.INHERIT)
  Path model;

  @Option(
      names = {"-s", "--steps"},
      defaultValue = "5",
      description = "number of random steps (default: ${DEFAULT-VALUE})")
  int steps;

  @Option(
      names = {"-z", "--size"},
      defaultValue = "4",
      description = "default size for ProB sets (default: ${DEFAULT-VALUE})")
  int size;

  @Option(
      names = {"-i", "--invariants"},
      description = "check invariants (default: ${DEFAULT-VALUE})")
  boolean checkInv;

  @Option(names = "--perf", description = "print ProB performance info (default: ${DEFAULT-VALUE})")
  boolean perf;

  @Option(
      names = "--save",
      paramLabel = "trace.json",
      description = "save animation trace in json to a file")
  Path jsonTrace;

  @Option(
      names = "--debug",
      description = "enable debug log (default: ${DEFAULT-VALUE})",
      scope = ScopeType.INHERIT)
  boolean debug;

  @Inject
  public Animate(Api api, TraceManager traceManager) {
    this.api = api;
    this.traceManager = traceManager;
  }

  private void printCoverage(StateSpace stateSpace) {
    ComputeCoverageCommand cmd = new ComputeCoverageCommand();
    stateSpace.execute(cmd);
    ComputeCoverageResult coverage = cmd.getResult();
    List<String> ops = coverage.getOps();
    List<String> uncovered = coverage.getUncovered();

    System.out.println("Coverage properties:\n\t - " + String.join("\n\t - ", coverage.getNodes()));
    if (!ops.isEmpty()) {
      System.out.println("Covered operations:\n\t - " + String.join("\n\t - ", ops));
    }
    if (!uncovered.isEmpty()) {
      System.out.println("Uncovered operations:\n\t - " + String.join("\n\t - ", uncovered));
    }
  }

  private List<String> findViolatedInvariants(StateSpace stateSpace, State state) {
    Object mainComponent = stateSpace.getMainComponent();
    if (mainComponent == null) {
      logger.warn("Main component is null, cannot check invariants");
      return Collections.emptyList();
    }
    if (!(mainComponent instanceof EventBMachine)) {
      logger.warn(
          "Main component is not an EventBMachine: {}, cannot check invariants",
          mainComponent.getClass().getName());
      return Collections.emptyList();
    }

    List<IEvalElement> invariants =
        ((EventBMachine) mainComponent)
            .getAllInvariants().stream().map(i -> i.getPredicate()).collect(Collectors.toList());
    List<AbstractEvalResult> results = state.eval(invariants);

    List<String> violatedInvariants =
        IntStream.range(0, results.size())
            .filter(i -> results.get(i) != EvalResult.TRUE)
            .mapToObj(i -> invariants.get(i).toString())
            .collect(Collectors.toList());

    return violatedInvariants;
  }

  private void validateInput() throws IllegalArgumentException {
    if (model == null) {
      throw new IllegalArgumentException("Model file is required");
    }
    if (!Files.exists(model)) {
      throw new IllegalArgumentException("Model file does not exist: " + model);
    }
    if (!Files.isRegularFile(model) && !Files.isDirectory(model)) {
      throw new IllegalArgumentException("Model path is not a file or directory: " + model);
    }
    if (!Files.isReadable(model)) {
      throw new IllegalArgumentException("Model path is not readable: " + model);
    }
    if (steps <= 0) {
      throw new IllegalArgumentException("Number of steps must be positive, got: " + steps);
    }
    if (size <= 0) {
      throw new IllegalArgumentException("Default set size must be positive, got: " + size);
    }
  }

  private StateSpace loadModel() throws IOException {
    validateInput();

    logger.info("Load Event-B Machine");

    Map<String, String> prefs = new HashMap<>();
    prefs.put("MEMOIZE_FUNCTIONS", "true");
    prefs.put("SYMBOLIC", "true");
    prefs.put("TRACE_INFO", "true");
    prefs.put("TRY_FIND_ABORT", "true");
    prefs.put("SYMMETRY_MODE", "hash");
    prefs.put("DEFAULT_SETSIZE", String.valueOf(size));
    prefs.put("COMPRESSION", "true");
    prefs.put("CLPFD", "true");
    prefs.put("PROOF_INFO", "true");
    prefs.put("OPERATION_REUSE", "true");
    if (perf) {
      prefs.put("PERFORMANCE_INFO", "true");
    }

    Path resolvedModel = modelResolver.resolve(model);
    String machineName = resolvedModel.getFileName().toString().replaceFirst("\\.bum$", "");
    System.out.println("Machine: " + machineName);
    StateSpace stateSpace = api.eventb_load(resolvedModel.toString(), prefs);

    GetVersionCommand version = new GetVersionCommand();
    stateSpace.execute(version);
    probVersionString = version.getVersionString();
    logger.info("ProB Version: " + probVersionString + "\n");

    return stateSpace;
  }

  private void initLogging() {
    if (!debug) {
      Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
      root.setLevel(Level.WARN);
      logger.setLevel(Level.INFO);
    }
  }

  StateSpace initAndLoadModel() {
    initLogging();
    try {
      return loadModel();
    } catch (Exception e) {
      modelResolver.cleanupTempDir();
      logger.error("Error loading model", e);
      System.err.println("Error loading model: " + e.getMessage());
      return null;
    }
  }

  public Trace start(final StateSpace stateSpace) {
    stateSpace.startTransaction();
    Trace trace = new Trace(stateSpace);

    try {
      System.out.println("Animation steps:");
      for (int i = 0; i < steps; i++) {
        Trace newTrace = trace.anyEvent(null);
        if (newTrace == trace) {
          System.err.println("Error: Can't find an event to execute from this state (deadlock)");
          break;
        }
        trace = newTrace;

        Transition transition = trace.getCurrent().getTransition().evaluate(FormulaExpand.EXPAND);
        System.out.println(transition.getPrettyRep());
        if (checkInv && !trace.getCurrentState().isInvariantOk()) {
          List<String> inv = findViolatedInvariants(stateSpace, trace.getCurrentState());
          System.err.println("Error: violated invariants:\n\t - " + String.join("\n\t - ", inv));
          break;
        }
      }
      System.out.println();

      System.out.println("Current state:\n" + trace.getCurrentState().getStateRep());
      System.out.println();
      printCoverage(stateSpace);
    } finally {
      stateSpace.endTransaction();
    }

    return trace;
  }

  @Override
  public Integer call() {
    StateSpace stateSpace = initAndLoadModel();
    if (stateSpace == null) return 1;

    try {
      Trace trace = start(stateSpace);

      if (jsonTrace != null) {
        JsonMetadata metadata =
            new JsonMetadataBuilder("Trace", 6)
                .withSavedNow()
                .withCreator("animate")
                .withProBCliVersion(probVersionString)
                .withModelName(stateSpace.getMainComponent().toString())
                .build();
        TraceJsonFile abstractJsonFile = new TraceJsonFile(trace, metadata);
        logger.info("Saving animation trace to {}", jsonTrace);

        try {
          traceManager.save(jsonTrace, abstractJsonFile);
        } catch (IOException e) {
          logger.error("Error saving trace", e);
          System.err.println("Error saving trace: " + e.getMessage());
          return 1;
        }
      }

      return 0;
    } finally {
      stateSpace.kill();
      modelResolver.cleanupTempDir();
    }
  }

  public static int execute(String[] args) {
    Animate m = INJECTOR.getInstance(Animate.class);
    return new CommandLine(m).execute(args);
  }

  public static void main(String[] args) {
    System.exit(execute(args));
  }
}
