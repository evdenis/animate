package animate;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Test the Animate CLI tool with various Event-B models.
 */
@RunWith(Parameterized.class)
public class AnimateCliTest {

    private final File modelFile;
    private final String modelName;

    public AnimateCliTest(String modelName, File modelFile) {
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

        // Only test a subset to keep tests fast
        // Select the main model from each project
        List<Object[]> filteredModels = new ArrayList<>();
        for (Object[] model : models) {
            String name = (String) model[0];
            // Get the highest numbered model from each directory (most refined)
            if (name.contains("base-model") && name.contains("M1.bum") ||
                name.contains("binary-search") && name.contains("M3.bum") ||
                name.contains("cars-on-bridge") && name.contains("M3.bum") ||
                name.contains("file-system") && name.contains("M0.bum") ||
                name.contains("traffic-light") && name.contains("M2.bum")) {
                filteredModels.add(model);
            }
        }

        return filteredModels.isEmpty() ? models : filteredModels;
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
                models.add(new Object[]{modelName, file});
            }
        }
    }

    @Test(timeout = 30000) // 30 second timeout per test
    public void testAnimateWithSteps() throws Exception {
        System.out.println("Testing CLI animation for: " + modelName);

        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try {
            System.setOut(new PrintStream(outContent));

            String[] args = {
                "--steps", "5",
                modelFile.getAbsolutePath()
            };

            int exitCode = Animate.execute(args);

            String output = outContent.toString();
            assertTrue("Output should contain animation information",
                output.length() > 0);
            assertEquals("Exit code should be 0", 0, exitCode);

            System.setOut(originalOut);
            System.out.println("  ✓ CLI animation completed");
        } catch (Exception e) {
            System.setOut(originalOut);
            System.err.println("  ✗ Animation failed: " + e.getMessage());
            throw e;
        }
    }

    @Test(timeout = 30000)
    public void testAnimateWithInvariants() throws Exception {
        System.out.println("Testing CLI with invariant checking for: " + modelName);

        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try {
            System.setOut(new PrintStream(outContent));

            String[] args = {
                "--steps", "5",
                "--invariants",
                modelFile.getAbsolutePath()
            };

            int exitCode = Animate.execute(args);

            String output = outContent.toString();
            assertTrue("Output should contain animation information",
                output.length() > 0);
            assertEquals("Exit code should be 0", 0, exitCode);

            System.setOut(originalOut);
            System.out.println("  ✓ Invariant checking completed");
        } catch (Exception e) {
            System.setOut(originalOut);
            System.err.println("  ✗ Invariant checking failed: " + e.getMessage());
            throw e;
        }
    }
}
