package animate;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
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

@Command(name = "animate", version = "animate 1.0", mixinStandardHelpOptions = true)
public class Animate implements Runnable {

    private static Injector INJECTOR = Guice.createInjector(Stage.PRODUCTION, new Config());
    private Api api;

    private TraceManager trace_manager;

    private static final Logger logger = (Logger) LoggerFactory.getLogger(Animate.class);

    @Parameters(description = "path to model.bum file")
    File model;
    @Option(names = { "-s", "--steps" }, defaultValue = "5", description = "number of random steps (default: ${DEFAULT-VALUE})")
    int steps;
    @Option(names = { "-z", "--size" }, defaultValue = "4", description = "default size for ProB sets (default: ${DEFAULT-VALUE})")
    int size;
    @Option(names = "--debug", description = "enable debug log (default: ${DEFAULT-VALUE})")
    boolean debug;
    @Option(names = "--perf", description = "print ProB performance info (default: ${DEFAULT-VALUE})")
    boolean perf;
    @Option(names = {"-i", "--invariants"}, description = "check invariants (default: ${DEFAULT-VALUE})")
    boolean checkInv;
    @Option(names = "--eventb", paramLabel="model.eventb", description = "dump prolog model to .eventb file and exit")
    Path eventb;
    @Option(names = "--save", paramLabel = "trace.json", description = "save animation trace in json to a file")
    Path jsonTrace;
    @Option(names = "--graph", paramLabel = "machine.dot", description = "save machine hierarchy graph in dot or svg and exit")
    Path machineHierarchy;

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

    @Override
    public void run() {

        if (!debug) {
            Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            root.setLevel(Level.WARN);
            logger.setLevel(Level.INFO);
        }

        StateSpace stateSpace = null;
        try {
            stateSpace = load_model();
        } catch (IOException e) {
            System.err.println("Error loading model: " + e.getMessage());
            System.exit(1);
        }

        if (machineHierarchy != null) {
            // machine_hierarchy, event_hierarchy, properties, invariant
            DotVisualizationCommand machine = DotVisualizationCommand.getByName("machine_hierarchy", stateSpace.getRoot());
            String extension = MoreFiles.getFileExtension(machineHierarchy);
            if (extension.equals("dot")) {
                machine.visualizeAsDotToFile(machineHierarchy, new ArrayList<>());
            } else if (extension.equals("svg")) {
                machine.visualizeAsSvgToFile(machineHierarchy, new ArrayList<>());
            } else {
                System.err.println("Unknown extension " + extension);
                System.exit(1);
            }
            return;
        }

        if (eventb != null) {
            try {
                eventb_save(stateSpace, eventb.toString(), true);
            } catch (IOException e) {
                System.err.println("Error saving model: " + e.getMessage());
                System.exit(1);
            }
            System.out.println("Saving model state to " + eventb);
            return;
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
                System.exit(1);
            }
        }

        stateSpace.kill();
    }

    public static void main(String[] args) throws Exception {
        Animate m = INJECTOR.getInstance(Animate.class);
        int exitCode = new CommandLine(m).execute(args);
        System.exit(exitCode);
    }
}
