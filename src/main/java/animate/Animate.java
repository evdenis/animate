package animate;

import java.util.*;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Stage;

import de.prob.scripting.Api;
import de.prob.statespace.StateSpace;
import de.prob.statespace.Trace;

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

	public void start(final String model_path, final int steps, final int size, final boolean perf, final String dump_file) throws Exception {
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

		if (!dump_file.isEmpty()) {
			try {
				api.eventb_save(stateSpace, dump_file);
			} catch (Exception e) {
				System.out.println("Error saving model: " + e.getMessage());
			}
		}

		System.out.println("Animate:");

		Trace t = new Trace(stateSpace);

		try {
			for (int i = 0; i < steps; i++) {
				t = t.anyEvent(null);
				System.out.println(t.getCurrent().getTransition().evaluate().getPrettyRep());
			}
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
		}

		System.out.println(t.getCurrentState().getStateRep());
	}

	public static void main(String[] args) throws Exception {
		Options options = new Options();
		int steps = 5;
		int size = 4;

		options.addRequiredOption("m", "model", true, "path to model.bum file");
		options.addOption("d", "dump", true, "dump prolog model to .eventb file");
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
		m.start(cmd.getOptionValue("model"), steps, size,
				cmd.hasOption("perf"),
				cmd.getOptionValue("dump", ""));

		System.exit(0);
	}
}
