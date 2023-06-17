package animate;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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

@Command(name = "animate", sortOptions = false, version = "animate 1.0",  subcommands = {CommandLine.HelpCommand.class})
public class Animate implements Callable<Integer> {

    private static Injector INJECTOR = Guice.createInjector(Stage.PRODUCTION, new Config());
    private Api api;

    private TraceManager trace_manager;

    private static final Logger logger = (Logger) LoggerFactory.getLogger(Animate.class);

    @Parameters(description = "path to model.bum file", scope = ScopeType.INHERIT)
    File model;
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
    @Option(names = "--debug", description = "enable debug log (default: ${DEFAULT-VALUE})")
    boolean debug;

    @Inject
    public Animate(Api api) {
        this.api = api;
        this.trace_manager = INJECTOR.getInstance(TraceManager.class);
    }

    public void printCoverage(StateSpace stateSpace) {
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

    class InvariantsViolation extends Exception {
        public InvariantsViolation() {
        }

        public InvariantsViolation(String message) {
            super(message);
        }
    }

    class DeadlockedState extends Exception {
        public DeadlockedState() {
        }

        public DeadlockedState(String message) {
            super(message);
        }
    }

    public List<String> findViolatedInvariants(StateSpace stateSpace, State state) {
        List<IEvalElement> invariants = ((EventBMachine) stateSpace.getMainComponent())
                .getAllInvariants()
                .stream()
                .map(i -> i.getPredicate())
                .collect(Collectors.toList());
        List<AbstractEvalResult> results = state.eval(invariants);

        List<String> violated_invariants = IntStream
                .range(0, results.size())
                .filter(i -> results.get(i) != EvalResult.TRUE)
                .mapToObj(i -> invariants.get(i).toString())
                .collect(Collectors.toList());

        return violated_invariants;
    }

    // Same as api.eventb_save, but pretty-printed
    public void eventb_save(final StateSpace s, final String path, final boolean use_indentation) throws IOException {
        final EventBModelTranslator translator = new EventBModelTranslator((EventBModel) s.getModel(), s.getMainComponent());

        try (final FileOutputStream fos = new FileOutputStream(path)) {
            final PrologTermOutput pto = new PrologTermOutput(fos, use_indentation);
            pto.openTerm("package");
            translator.printProlog(pto);
            pto.closeTerm();
            pto.fullstop();
            pto.flush();
        }
    }

    public StateSpace load_model() throws IOException {
        logger.info("Load Event-B Machine");

        StateSpace stateSpace = null;

        Map<String, String> prefs = new HashMap<String, String>() {{
            put("MEMOIZE_FUNCTIONS", "true");
            put("SYMBOLIC", "true");
            put("TRACE_INFO", "true");
            put("TRY_FIND_ABORT", "true");
            put("SYMMETRY_MODE", "hash");
            put("DEFAULT_SETSIZE", String.valueOf(size));
            put("COMPRESSION", "true");
            put("CLPFD", "true");
            put("PROOF_INFO", "true");
            put("OPERATION_REUSE", "true");
            if (perf) {
                put("PERFORMANCE_INFO", "true");
            }
        }};

        stateSpace = api.eventb_load(model.getPath(), prefs);

        GetVersionCommand version = new GetVersionCommand();
        stateSpace.execute(version);
        logger.info("ProB Version: " + version.getVersionString() + "\n");

        return stateSpace;
    }

    public void initLogging() {
        if (!debug) {
            Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            root.setLevel(Level.WARN);
            logger.setLevel(Level.INFO);
        }
    }

    public Trace start(final StateSpace stateSpace) {

        stateSpace.startTransaction();
        Trace trace = new Trace(stateSpace);

        System.out.println("Animation steps:");
        try {
            for (int i = 0; i < steps; i++) {
                Trace new_trace = trace.anyEvent(null);
                if (new_trace == trace)
                    throw new DeadlockedState("Can't find an event to execute from this state (deadlock)");
                trace = new_trace;

                Transition transition = trace.getCurrent().getTransition().evaluate(FormulaExpand.EXPAND);
                System.out.println(transition.getPrettyRep());
                if (checkInv && !trace.getCurrentState().isInvariantOk()) {
                    throw new InvariantsViolation();
                }
            }
        } catch (InvariantsViolation e) {
            List<String> inv = findViolatedInvariants(stateSpace, trace.getCurrentState());
            System.err.println("Error: violated invariants:\n\t - " + String.join("\n\t - ", inv));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
        System.out.println();

        System.out.println("Current state:\n" + trace.getCurrentState().getStateRep());
        System.out.println();
        printCoverage(stateSpace);

        stateSpace.endTransaction();

        return trace;
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
            stateSpace = load_model();
        } catch (Exception e) {
            System.err.println("Error loading model: " + e.getMessage());
            return 1;
        }

        Map<String, Path> visualizationCommand = new HashMap<String, Path>() {{
            put("machine_hierarchy", machine);
            put("event_hierarchy", events);
            put("properties", properties);
            put("invariant", invariant);
        }};

        logger.info("Initializing model");
        stateSpace.startTransaction();
        Trace trace = new Trace(stateSpace);
        trace = trace.execute("$setup_constants");
        trace = trace.execute("$initialise_machine");
        stateSpace.endTransaction();

        boolean anyCmd = false;
        for (Map.Entry<String, Path> el : visualizationCommand.entrySet()) {
            Path path = el.getValue();
            if (path != null) {
                anyCmd = true;
                logger.info("Saving {} to {}", el.getKey(), path);
                // machine_hierarchy, event_hierarchy, properties, invariant
                DotVisualizationCommand cmd = DotVisualizationCommand.getByName(el.getKey(), trace.getCurrentState());
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

        if (eventb != null) {
            anyCmd = true;
            logger.info("Saving B model to {}", eventb);
            try {
                eventb_save(stateSpace, eventb.toString(), true);
            } catch (IOException e) {
                System.err.println("Error saving model: " + e.getMessage());
                err = 1;
            }
        }

        if (!anyCmd) {
            EventBModel model = (EventBModel) stateSpace.getModel();
            System.out.print(model.calculateDependencies().getGraph());
        }

        return err;
    }

    @Override
    public Integer call() {
        initLogging();

        StateSpace stateSpace;
        try {
            stateSpace = load_model();
        } catch (Exception e) {
            System.err.println("Error loading model: " + e.getMessage());
            return 1;
        }

        Trace trace = start(stateSpace);

        if (jsonTrace != null) {
            JsonMetadata metadata = new JsonMetadataBuilder("Trace", 5)
                    .withSavedNow()
                    .withCreator("animate")
                    .withProBCliVersion("version")
                    .withModelName(stateSpace.getMainComponent().toString())
                    .build();
            TraceJsonFile abstractJsonFile = new TraceJsonFile(trace, metadata);
            logger.info("Saving animation trace to {}", jsonTrace);

            try {
                trace_manager.save(jsonTrace, abstractJsonFile);
            } catch (IOException e) {
                System.err.println("Error saving trace: " + e.getMessage());
                return 1;
            }
        }

        stateSpace.kill();

        return 0;
    }

    public static void main(String[] args) throws Exception {
        Animate m = INJECTOR.getInstance(Animate.class);
        int exitCode = new CommandLine(m).execute(args);
        System.exit(exitCode);
    }
}
