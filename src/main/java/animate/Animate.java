package animate;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
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

import org.apache.commons.cli.*;


public class Animate {

    private static Injector INJECTOR = Guice.createInjector(Stage.PRODUCTION, new Config());
    private Api api;

    private TraceManager trace_manager;

    private static final Logger logger = (Logger) LoggerFactory.getLogger(Animate.class);

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

    public StateSpace load_model(final String model_path,
                                 final int size,
                                 final boolean perf) throws IOException {
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
        try {
            stateSpace = api.eventb_load(model_path, prefs);
        } catch (IOException e) {
            System.err.println("Error loading model: " + e.getMessage());
            throw e;
        }

        GetVersionCommand version = new GetVersionCommand();
        stateSpace.execute(version);
        logger.info("ProB Version: " + version.getVersionString() + "\n");

        return stateSpace;
    }

    public Trace start(final StateSpace stateSpace,
                      final int steps,
                      final boolean checkInv) {

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

    public static void main(String[] args) throws Exception {
        Options options = new Options();
        int steps = 5;
        int size = 4;

        options.addRequiredOption("m", "model", true, "path to model.bum file");
        options.addOption(null,"eventb", true, "dump prolog model to .eventb file and exit");
        options.addOption(null, "graph", true, "print model dependency graph and exit");
        options.addOption("i", "invariants", false, "check invariants");
        options.addOption("d", "debug", false, "enable debug log (default: off)");
        options.addOption("s", "steps", true, "number of random steps (default: 5)");
        options.addOption(null, "save", true, "save animation trace in json to a file");
        options.addOption("z", "size", true, "default size for ProB sets (default: 4)");
        options.addOption(null, "perf", false, "print ProB performance info (default: off)");

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd = null;

        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.err.println(e.getMessage());
            formatter.printHelp("animate", options);

            System.exit(1);
        }

        if (!cmd.hasOption("debug")) {
            Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            root.setLevel(Level.WARN);
            logger.setLevel(Level.INFO);
        }

        try {
            if (cmd.hasOption("steps")) {
                steps = Integer.parseInt(cmd.getOptionValue("steps"));
            }
            if (cmd.hasOption("size")) {
                size = Integer.parseInt(cmd.getOptionValue("size"));
            }
        } catch (NumberFormatException e) {
            System.err.println(e.getMessage());
            formatter.printHelp("animate", options);

            System.exit(1);
        }

        Animate m = INJECTOR.getInstance(Animate.class);

        StateSpace stateSpace = m.load_model(cmd.getOptionValue("model"), size, cmd.hasOption("perf"));

        if (cmd.hasOption("graph")) {
            // machine_hierarchy, event_hierarchy, properties, invariant
            DotVisualizationCommand machine = DotVisualizationCommand.getByName("machine_hierarchy", stateSpace.getRoot());
            Path file = Paths.get(cmd.getOptionValue("graph"));
            String extension = MoreFiles.getFileExtension(file);
            if (extension.equals("dot")) {
                machine.visualizeAsDotToFile(file, new ArrayList<>());
            } else if (extension.equals("svg")) {
                machine.visualizeAsSvgToFile(file, new ArrayList<>());
            } else {
                System.err.println("Unknown extension " + extension);
                System.exit(1);
            }
            System.exit(0);
        }

        if (cmd.hasOption("eventb")) {
            String dumpFile = cmd.getOptionValue("eventb");
            try {
                m.eventb_save(stateSpace, dumpFile, true);
            } catch (IOException e) {
                System.err.println("Error saving model: " + e.getMessage());
                System.exit(1);
            }
            System.out.println("Saving model state to " + dumpFile);
            System.exit(0);
        }

        Trace trace = m.start(stateSpace, steps, cmd.hasOption("invariants"));

        if (cmd.hasOption("save")) {
            JsonMetadata metadata = new JsonMetadataBuilder("Trace", 5)
                    .withSavedNow()
                    .withUserCreator()
                    .withProBCliVersion("version")
                    .withModelName(stateSpace.getMainComponent().toString())
                    .build();
            TraceJsonFile abstractJsonFile = new TraceJsonFile(trace, metadata);
            logger.info("Saving animation trace to {}", cmd.getOptionValue("save"));

            try {
                m.trace_manager.save(new File(cmd.getOptionValue("save")).toPath(), abstractJsonFile);
            } catch (IOException e) {
                System.err.println("Error saving trace: " + e.getMessage());
            }
        }

        stateSpace.kill();

        System.exit(0);
    }
}
