package animate;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Stage;

import de.prob.animator.command.ComputeCoverageCommand;
import de.prob.animator.command.ComputeCoverageCommand.ComputeCoverageResult;
import de.prob.animator.domainobjects.*;
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

	@Inject
	public Animate(Api api) {
		this.api = api;
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

	class InvariantsViolation extends Exception
	{
		public InvariantsViolation() {}

		public InvariantsViolation(String message)
		{
			super(message);
		}
	}

	class DeadlockedState extends Exception
	{
		public DeadlockedState() {}

		public DeadlockedState(String message)
		{
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

	public Trace anyEvent(Trace trace) throws DeadlockedState {
		List<Transition> ops = trace.getCurrentState().getOutTransitions(true, FormulaExpand.EXPAND);
		Collections.shuffle(ops);
		if (ops.isEmpty()) {
			throw new DeadlockedState("Can't find an event to execute from this state (deadlock)");
		}
		return trace.add(ops.get(0).getId());
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

	public void start(final String model_path,
					  final int steps, final int size,
					  final boolean checkInv,
					  final boolean perf, final String dumpFile) {
		System.out.println("ProB version: " + api.getVersion());
		System.out.println();
		System.out.println("Load Event-B Machine");

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
		} catch (Exception e) {
			System.out.println("Error loading model: " + e.getMessage());
			System.exit(3);
		}

		if (!dumpFile.isEmpty()) {
			try {
				this.eventb_save(stateSpace, dumpFile, true);
			} catch (Exception e) {
				System.out.println("Error saving model: " + e.getMessage());
			}
			System.out.println("Saving model state to " + dumpFile);
			return;
		}

		Trace trace = new Trace(stateSpace);

		System.out.println("Animate:");
		try {
			for (int i = 0; i < steps; i++) {
				trace = anyEvent(trace);
				System.out.println(trace.getCurrent().getTransition().getPrettyRep());
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

		System.out.println("Current state:\n" + trace.getCurrentState().getStateRep());
		System.out.println();
		printCoverage(stateSpace);
	}

	public static void main(String[] args) throws Exception {
		Options options = new Options();
		int steps = 5;
		int size = 4;

		options.addRequiredOption("m", "model", true, "path to model.bum file");
		options.addOption("e", "eventb", true, "dump prolog model to .eventb file");
		options.addOption("i", "invariants", false, "check invariants");
		options.addOption("d", "debug", false, "enable debug log (default: off)");
		options.addOption("s", "steps", true, "number of random steps (default: 5)");
		options.addOption("z", "size", true, "default size for ProB sets (default: 4)");
		options.addOption("p", "perf", false, "print ProB performance info (default: off)");

		CommandLineParser parser = new DefaultParser();
		HelpFormatter formatter = new HelpFormatter();
		CommandLine cmd = null;

		try {
			cmd = parser.parse(options, args);
		} catch (ParseException e) {
			System.out.println(e.getMessage());
			formatter.printHelp("animate", options);

			System.exit(1);
		}

		if (!cmd.hasOption("debug")) {
			Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
			root.setLevel(Level.WARN);
		}

		try {
			if (cmd.hasOption("steps")) {
				steps = Integer.parseInt(cmd.getOptionValue("steps"));
			}
			if (cmd.hasOption("size")) {
				size = Integer.parseInt(cmd.getOptionValue("size"));
			}
		} catch (Exception e) {
			System.out.println(e.getMessage());
			formatter.printHelp("animate", options);

			System.exit(1);
		}

		Animate m = INJECTOR.getInstance(Animate.class);
		m.start(cmd.getOptionValue("model"),
				steps, size, cmd.hasOption("invariants"),
				cmd.hasOption("perf"), cmd.getOptionValue("eventb", ""));

		System.exit(0);
	}
}
