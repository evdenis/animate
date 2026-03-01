package animate;

import com.google.common.io.MoreFiles;
import de.prob.animator.domainobjects.DotVisualizationCommand;
import de.prob.model.eventb.EventBModel;
import de.prob.model.eventb.translate.EventBModelTranslator;
import de.prob.prolog.output.PrologTermOutput;
import de.prob.statespace.StateSpace;
import de.prob.statespace.Trace;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(name = "info", description = "Dump information about the model")
class InfoCommand implements Callable<Integer> {

  private static final ch.qos.logback.classic.Logger logger =
      (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(InfoCommand.class);

  private static final String SETUP_CONSTANTS_EVENT = "$setup_constants";
  private static final String INITIALISE_MACHINE_EVENT = "$initialise_machine";

  @ParentCommand Animate parent;

  @Option(
      names = {"-m", "--machine"},
      paramLabel = "machine.dot",
      description = "save machine hierarchy graph in dot or svg")
  Path machine;

  @Option(
      names = {"-e", "--events"},
      paramLabel = "events.dot",
      description = "save events hierarchy graph in dot or svg")
  Path events;

  @Option(
      names = {"-p", "--properties"},
      paramLabel = "properties.dot",
      description = "save properties graph in dot or svg")
  Path properties;

  @Option(
      names = {"-i", "--invariant"},
      paramLabel = "invariant.dot",
      description = "save invariant graph in dot or svg")
  Path invariant;

  @Option(
      names = {"-b", "--bmodel"},
      paramLabel = "model.eventb",
      description = "dump prolog model to .eventb file")
  Path eventb;

  @Override
  public Integer call() {
    int err = 0;

    StateSpace stateSpace = parent.initAndLoadModel();
    if (stateSpace == null) return 1;

    try {
      boolean hasVisualizationCmd =
          machine != null || events != null || properties != null || invariant != null;

      if (hasVisualizationCmd) {
        logger.info("Initializing model");
        stateSpace.startTransaction();
        Trace trace = new Trace(stateSpace);

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

        err |= saveVisualization("machine_hierarchy", machine, trace);
        err |= saveVisualization("event_hierarchy", events, trace);
        err |= saveVisualization("properties", properties, trace);
        err |= saveVisualization("invariant", invariant, trace);
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
      parent.modelResolver.cleanupTempDir();
    }

    return err;
  }

  private int saveVisualization(String name, Path path, Trace trace) {
    if (path == null) return 0;
    logger.info("Saving {} to {}", name, path);
    DotVisualizationCommand cmd = DotVisualizationCommand.getByName(name, trace);
    String extension = MoreFiles.getFileExtension(path);
    if (extension.equals("dot")) {
      cmd.visualizeAsDotToFile(path, new ArrayList<>());
    } else if (extension.equals("svg")) {
      cmd.visualizeAsSvgToFile(path, new ArrayList<>());
    } else {
      System.err.println("Unknown extension " + extension);
      return 1;
    }
    return 0;
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
}
