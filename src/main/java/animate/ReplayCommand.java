package animate;

import de.prob.check.tracereplay.ReplayedTrace;
import de.prob.check.tracereplay.TraceReplay;
import de.prob.statespace.StateSpace;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(name = "replay", description = "Replay json trace")
class ReplayCommand implements Callable<Integer> {

  @ParentCommand Animate parent;

  @Option(
      names = {"-t", "--trace"},
      required = true,
      paramLabel = "trace.json",
      description = "Path to a json trace")
  Path jsonTrace;

  @Override
  public Integer call() {
    StateSpace stateSpace = parent.initAndLoadModel();
    if (stateSpace == null) return 1;

    try {
      System.out.println("Starting trace replay. Use --debug to view steps.");
      ReplayedTrace trace = TraceReplay.replayTraceFile(stateSpace, jsonTrace);
      System.out.println("Trace replay status: " + trace.getReplayStatus());
      return 0;
    } finally {
      stateSpace.kill();
      parent.modelResolver.cleanupTempDir();
    }
  }
}
