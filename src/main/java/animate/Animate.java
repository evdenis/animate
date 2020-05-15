package animate;

import java.io.IOException;
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

	public void start(String model_path, int steps) throws Exception {
		System.out.println("ProB version: " + api.getVersion());
		System.out.println();
		System.out.println("Load Event-B Machine");

		StateSpace stateSpace = null;
		try {
			stateSpace = api.eventb_load(model_path);
		} catch (Exception e) {
			System.out.println("Error loading model: " + e.getMessage());
			System.exit(3);
		}

		System.out.println("Animate:");

		Trace t = new Trace(stateSpace);

		try {
			t = t.execute("$setup_constants");
			System.out.println(t.getCurrent().getTransition().evaluate().getPrettyRep());
			t = t.execute("$initialise_machine");
			System.out.println(t.getCurrent().getTransition().evaluate().getPrettyRep());

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

		options.addRequiredOption("m", "model", true, "path to model.bum file");
		options.addOption("d", "debug", false, "enable debug log (default: off)");
		options.addOption("s", "steps", true, "number of random steps (default: 5)");

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

		if (cmd.hasOption("steps")) {
			try {
				steps = Integer.parseInt(cmd.getOptionValue("steps"));
			} catch (Exception e) {
				System.out.println(e.getMessage());
				formatter.printHelp("animate", options);

				System.exit(1);
			}
		}

		Animate m = INJECTOR.getInstance(Animate.class);
		m.start(cmd.getOptionValue("model"), steps);

		System.exit(0);
	}
}
