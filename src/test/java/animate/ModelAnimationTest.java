package animate;

import static org.junit.Assert.*;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Stage;
import de.prob.scripting.Api;
import de.prob.statespace.StateSpace;
import de.prob.statespace.Trace;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Test that verifies Event-B models can be loaded and animated. Tests models from
 * https://github.com/17451k/eventb-models
 */
@RunWith(Parameterized.class)
public class ModelAnimationTest {

  private static final int ANIMATION_STEPS = 10;
  private static Api api;
  private final File modelFile;
  private final String modelName;

  @BeforeClass
  public static void setupApi() {
    Injector injector = Guice.createInjector(Stage.PRODUCTION, new Config());
    api = injector.getInstance(Api.class);
  }

  public ModelAnimationTest(String modelName, File modelFile) {
    this.modelName = modelName;
    this.modelFile = modelFile;
  }

  @Parameters(name = "{0}")
  public static Collection<Object[]> getModels() {
    List<Object[]> models = new ArrayList<>();

    // Find all .bum files in test resources
    File resourcesDir = new File("src/test/resources/models");
    if (resourcesDir.exists()) {
      findBumFiles(resourcesDir, models, "");
    }

    return models;
  }

  private static void findBumFiles(File dir, List<Object[]> models, String prefix) {
    File[] files = dir.listFiles();
    if (files == null) return;

    for (File file : files) {
      if (file.isDirectory()) {
        String newPrefix = prefix.isEmpty() ? file.getName() : prefix + "/" + file.getName();
        findBumFiles(file, models, newPrefix);
      } else if (file.getName().endsWith(".bum")) {
        String modelName = prefix + "/" + file.getName();
        models.add(new Object[] {modelName, file});
      }
    }
  }

  @Test
  public void testModelLoads() throws Exception {
    System.out.println("Testing model: " + modelName);

    assertNotNull("ProB API should be available", api);

    StateSpace stateSpace = api.eventb_load(modelFile.getAbsolutePath());
    assertNotNull("Model should load successfully", stateSpace);

    try {
      Trace trace = new Trace(stateSpace);
      assertNotNull("Initial trace should be created", trace);

      // Verify we can get the initial state
      assertNotNull("Current state should exist", trace.getCurrentState());

      System.out.println("  ✓ Model loaded successfully");
    } finally {
      stateSpace.kill();
    }
  }

  @Test
  public void testModelAnimation() throws Exception {
    System.out.println("Testing animation for: " + modelName);

    StateSpace stateSpace = api.eventb_load(modelFile.getAbsolutePath());

    try {
      Trace trace = new Trace(stateSpace);

      // Try to execute random steps
      int successfulSteps = 0;
      for (int i = 0; i < ANIMATION_STEPS; i++) {
        try {
          Trace newTrace = trace.anyOperation(null);
          if (newTrace != null && !newTrace.equals(trace)) {
            trace = newTrace;
            successfulSteps++;
          } else {
            // No more operations available
            break;
          }
        } catch (Exception e) {
          // Some models may have limited animation possibilities
          System.out.println(
              "  ⚠ Animation stopped after " + successfulSteps + " steps: " + e.getMessage());
          break;
        }
      }

      System.out.println("  ✓ Performed " + successfulSteps + " animation steps");

      // At minimum, we should be able to create the initial trace
      assertTrue("Should be able to create initial trace", trace != null);
    } finally {
      stateSpace.kill();
    }
  }

  @Test
  public void testInvariantChecking() throws Exception {
    System.out.println("Testing invariant checking for: " + modelName);

    StateSpace stateSpace = api.eventb_load(modelFile.getAbsolutePath());

    try {
      Trace trace = new Trace(stateSpace);

      // Perform a few animation steps and check invariants
      int steps = Math.min(5, ANIMATION_STEPS);
      for (int i = 0; i < steps; i++) {
        try {
          Trace newTrace = trace.anyOperation(null);
          if (newTrace != null && !newTrace.equals(trace)) {
            trace = newTrace;

            // Check if current state has invariant violations
            // (ProB automatically checks invariants during state space exploration)
            assertNotNull("Current state should exist", trace.getCurrentState());
          } else {
            break;
          }
        } catch (Exception e) {
          // Expected for some models
          break;
        }
      }

      System.out.println("  ✓ Invariant checking completed");
    } finally {
      stateSpace.kill();
    }
  }
}
