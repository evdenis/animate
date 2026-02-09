package animate;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.google.common.io.MoreFiles;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Stage;

import de.prob.animator.command.ComputeCoverageCommand;
import de.prob.animator.command.ComputeCoverageCommand.ComputeCoverageResult;
import de.prob.animator.command.GetVersionCommand;
import de.prob.animator.domainobjects.*;
import de.prob.check.tracereplay.ReplayedTrace;
import de.prob.check.tracereplay.TraceReplay;
import de.prob.check.tracereplay.json.TraceManager;
import de.prob.check.tracereplay.json.storage.TraceJsonFile;
import de.prob.json.JsonMetadata;
import de.prob.json.JsonMetadataBuilder;
import de.prob.model.eventb.EventBMachine;
import de.prob.model.eventb.EventBModel;
import de.prob.model.eventb.translate.EventBModelTranslator;
import de.prob.prolog.output.PrologTermOutput;
import de.prob.scripting.Api;
import de.prob.statespace.*;

import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ScopeType;

@Command(name = "animate", sortOptions = false, version = "animate @VERSION@",  subcommands = {CommandLine.HelpCommand.class})
public class Animate implements Callable<Integer> {

    private static final Injector INJECTOR = Guice.createInjector(Stage.PRODUCTION, new Config());
    private static final String SETUP_CONSTANTS_EVENT = "$setup_constants";
    private static final String INITIALISE_MACHINE_EVENT = "$initialise_machine";

    private final Api api;
    private final TraceManager traceManager;

    private static final Logger logger = (Logger) LoggerFactory.getLogger(Animate.class);

    @Parameters(description = "path to model.bum file", scope = ScopeType.INHERIT)
    Path model;
    @Option(names = { "-s", "--steps" }, defaultValue = "5", description = "number of random steps (default: ${DEFAULT-VALUE})")
    int steps;
    @Option(names = { "-z", "--size" }, defaultValue = "4", description = "default size for ProB sets (default: ${DEFAULT-VALUE})")
    int size;
    @Option(names = {"-i", "--invariants"}, description = "check invariants (default: ${DEFAULT-VALUE})")
    boolean checkInv;
    @Option(names = "--perf", description = "print ProB performance info (default: ${DEFAULT-VALUE})")
    boolean perf;
    @Option(names = "--save", paramLabel = "trace.json", description = "save animation trace in json to a file")
    Path jsonTrace;
    @Option(names = "--debug", description = "enable debug log (default: ${DEFAULT-VALUE})", scope = ScopeType.INHERIT)
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
            logger.warn("Main component is not an EventBMachine: {}, cannot check invariants", mainComponent.getClass().getName());
            return Collections.emptyList();
        }

        List<IEvalElement> invariants = ((EventBMachine) mainComponent)
                .getAllInvariants()
                .stream()
                .map(i -> i.getPredicate())
                .collect(Collectors.toList());
        List<AbstractEvalResult> results = state.eval(invariants);

        List<String> violatedInvariants = IntStream
                .range(0, results.size())
                .filter(i -> results.get(i) != EvalResult.TRUE)
                .mapToObj(i -> invariants.get(i).toString())
                .collect(Collectors.toList());

        return violatedInvariants;
    }

    // Same as api.eventb_save, but pretty-printed
    private void eventbSave(final StateSpace s, final String path) throws IOException {
        final EventBModelTranslator translator = new EventBModelTranslator((EventBModel) s.getModel());

        try (final FileOutputStream fos = new FileOutputStream(path)) {
            final PrologTermOutput pto = new PrologTermOutput(fos, true);
            pto.openTerm("package");
            translator.printProlog(pto);
            pto.closeTerm();
            pto.fullstop();
            pto.flush();
        }
    }

    private void validateInput() throws IllegalArgumentException {
        if (model == null) {
            throw new IllegalArgumentException("Model file is required");
        }
        if (!Files.exists(model)) {
            throw new IllegalArgumentException("Model file does not exist: " + model);
        }
        if (!Files.isRegularFile(model)) {
            throw new IllegalArgumentException("Model path is not a file: " + model);
        }
        if (!Files.isReadable(model)) {
            throw new IllegalArgumentException("Model file is not readable: " + model);
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

        StateSpace stateSpace;

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

        stateSpace = api.eventb_load(model.toString(), prefs);

        GetVersionCommand version = new GetVersionCommand();
        stateSpace.execute(version);
        logger.info("ProB Version: " + version.getVersionString() + "\n");

        return stateSpace;
    }

    private void initLogging() {
        if (!debug) {
            Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            root.setLevel(Level.WARN);
            logger.setLevel(Level.INFO);
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

    @Command(description = "Replay json trace")
    public Integer replay(@Option(names = {"-t", "--trace"}, required = true, paramLabel = "trace.json", description = "Path to a json trace")
                          final Path jsonTrace){
        initLogging();

        StateSpace stateSpace;
        try {
            stateSpace = loadModel();
        } catch (Exception e) {
            logger.error("Error loading model", e);
            System.err.println("Error loading model: " + e.getMessage());
            return 1;
        }

        try {
            System.out.println("Starting trace replay. Use --debug to view steps.");
            ReplayedTrace trace = TraceReplay.replayTraceFile(stateSpace, jsonTrace);
            System.out.println("Trace replay status: " + trace.getReplayStatus());
            return 0;
        } finally {
            stateSpace.kill();
        }
    }

    @Command(description = "Dump information about the model")
    public Integer info(@Option(names = {"-m", "--machine"}, paramLabel = "machine.dot", description = "save machine hierarchy graph in dot or svg")
                        final Path machine,
                        @Option(names = {"-e", "--events"}, paramLabel = "events.dot", description = "save events hierarchy graph in dot or svg")
                        final Path events,
                        @Option(names = {"-p", "--properties"}, paramLabel = "properties.dot", description = "save properties graph in dot or svg")
                        final Path properties,
                        @Option(names = {"-i", "--invariant"}, paramLabel = "invariant.dot", description = "save invariant graph in dot or svg")
                        final Path invariant,
                        @Option(names = {"-b", "--bmodel"}, paramLabel = "model.eventb", description = "dump prolog model to .eventb file")
                        final Path eventb) {
        int err = 0;

        initLogging();

        StateSpace stateSpace;
        try {
            stateSpace = loadModel();
        } catch (Exception e) {
            logger.error("Error loading model", e);
            System.err.println("Error loading model: " + e.getMessage());
            return 1;
        }

        try {
            Map<String, Path> visualizationCommand = new HashMap<>();
            visualizationCommand.put("machine_hierarchy", machine);
            visualizationCommand.put("event_hierarchy", events);
            visualizationCommand.put("properties", properties);
            visualizationCommand.put("invariant", invariant);

            // Check if any visualization commands are specified
            boolean hasVisualizationCmd = machine != null || events != null || properties != null || invariant != null;

            Trace trace = null;
            if (hasVisualizationCmd) {
                logger.info("Initializing model");
                stateSpace.startTransaction();
                trace = new Trace(stateSpace);

                // Initialize model - some models don't have constants
                try {
                    trace = trace.execute(SETUP_CONSTANTS_EVENT);
                } catch (IllegalArgumentException e) {
                    // No constants to set up, continue
                    logger.debug("No setup_constants event available");
                }
                try {
                    trace = trace.execute(INITIALISE_MACHINE_EVENT);
                } catch (Exception e) {
                    System.err.println("Warning: Could not fully initialize model: " + e.getMessage());
                }
                stateSpace.endTransaction();

                for (Map.Entry<String, Path> el : visualizationCommand.entrySet()) {
                    Path path = el.getValue();
                    if (path != null) {
                        logger.info("Saving {} to {}", el.getKey(), path);
                        // machine_hierarchy, event_hierarchy, properties, invariant
                        DotVisualizationCommand cmd = DotVisualizationCommand.getByName(el.getKey(), trace);
                        String extension = MoreFiles.getFileExtension(path);
                        if (extension.equals("dot")) {
                            cmd.visualizeAsDotToFile(path, new ArrayList<>());
                        } else if (extension.equals("svg")) {
                            cmd.visualizeAsSvgToFile(path, new ArrayList<>());
                        } else {
                            System.err.println("Unknown extension " + extension);
                            err = 1;
                        }
                    }
                }
            }

            if (eventb != null) {
                logger.info("Saving B model to {}", eventb);
                try {
                    eventbSave(stateSpace, eventb.toString());
                } catch (IOException e) {
                    logger.error("Error saving model", e);
                    System.err.println("Error saving model: " + e.getMessage());
                    err = 1;
                }
            }

            if (!hasVisualizationCmd && eventb == null) {
                EventBModel model = (EventBModel) stateSpace.getModel();
                System.out.print(model.calculateDependencies().getGraph());
            }
        } finally {
            stateSpace.kill();
        }

        return err;
    }

    @Override
    public Integer call() {
        initLogging();

        StateSpace stateSpace;
        try {
            stateSpace = loadModel();
        } catch (Exception e) {
            logger.error("Error loading model", e);
            System.err.println("Error loading model: " + e.getMessage());
            return 1;
        }

        Trace trace = start(stateSpace);

        if (jsonTrace != null) {
            JsonMetadata metadata = new JsonMetadataBuilder("Trace", 6)
                    .withSavedNow()
                    .withCreator("animate")
                    .withProBCliVersion("version")
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

        stateSpace.kill();

        return 0;
    }

    public static int execute(String[] args) {
        Animate m = INJECTOR.getInstance(Animate.class);
        return new CommandLine(m).execute(args);
    }

    public static void main(String[] args) {
        System.exit(execute(args));
    }
}
