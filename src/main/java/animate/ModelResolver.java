package animate;

import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

class ModelResolver {

  private static final ch.qos.logback.classic.Logger logger =
      (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ModelResolver.class);

  private Path tempDir;

  Path resolve(Path model) throws IOException {
    if (Files.isDirectory(model)) {
      return resolveDirectory(model);
    }
    if (!model.toString().endsWith(".zip")) {
      return model;
    }

    Path tempDirectory = Files.createTempDirectory("animate-");
    this.tempDir = tempDirectory;
    List<Path> bumFiles = new ArrayList<>();

    try (InputStream fis = Files.newInputStream(model);
        ZipInputStream zis = new ZipInputStream(fis)) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        Path entryPath = tempDirectory.resolve(entry.getName()).normalize();
        if (!entryPath.startsWith(tempDirectory)) {
          throw new IOException("Zip entry outside target directory: " + entry.getName());
        }
        if (entry.isDirectory()) {
          Files.createDirectories(entryPath);
        } else {
          Files.createDirectories(entryPath.getParent());
          Files.copy(zis, entryPath);
          if (entry.getName().endsWith(".bum")) {
            bumFiles.add(entryPath);
          }
        }
      }
    }

    if (bumFiles.isEmpty()) {
      throw new IOException("No .bum file found in zip archive: " + model);
    }
    if (bumFiles.size() > 1) {
      Path selected = findMostRefinedBum(bumFiles);
      logger.info(
          "Multiple .bum files found, auto-selected most refined: {}", selected.getFileName());
      return selected;
    }

    return bumFiles.get(0);
  }

  private Path resolveDirectory(Path dir) throws IOException {
    List<Path> bumFiles;
    try (var stream = Files.walk(dir)) {
      bumFiles = stream.filter(p -> p.toString().endsWith(".bum")).collect(Collectors.toList());
    }

    if (bumFiles.isEmpty()) {
      throw new IOException("No .bum file found in directory: " + dir);
    }
    if (bumFiles.size() == 1) {
      return bumFiles.get(0);
    }

    Path selected = findMostRefinedBum(bumFiles);
    logger.info(
        "Multiple .bum files found, auto-selected most refined: {}", selected.getFileName());
    return selected;
  }

  private Path findMostRefinedBum(List<Path> bumFiles) throws IOException {
    Map<String, String> refinesTarget = new HashMap<>();
    Map<String, Path> pathByName = new HashMap<>();

    for (Path bumFile : bumFiles) {
      String fileName = bumFile.getFileName().toString();
      String machineName = fileName.substring(0, fileName.length() - ".bum".length());
      pathByName.put(machineName, bumFile);

      try {
        Document doc =
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(bumFile.toFile());
        NodeList refines = doc.getElementsByTagName("org.eventb.core.refinesMachine");
        if (refines.getLength() > 0) {
          Element refEl = (Element) refines.item(0);
          String target = refEl.getAttribute("org.eventb.core.target");
          if (!target.isEmpty()) {
            refinesTarget.put(machineName, target);
          }
        }
      } catch (ParserConfigurationException | SAXException e) {
        throw new IOException("Failed to parse .bum file: " + bumFile, e);
      }
    }

    Set<String> refinedByOthers = new HashSet<>(refinesTarget.values());
    List<String> leaves =
        pathByName.keySet().stream()
            .filter(name -> !refinedByOthers.contains(name))
            .collect(Collectors.toList());

    if (leaves.isEmpty()) {
      throw new IOException("Circular refinement detected among .bum files in zip archive");
    }
    if (leaves.size() > 1) {
      throw new IOException(
          "Multiple independent refinement chains found in zip archive, "
              + "cannot auto-select. Leaf machines: "
              + leaves.stream().sorted().collect(Collectors.joining(", ")));
    }

    return pathByName.get(leaves.get(0));
  }

  void cleanupTempDir() {
    if (tempDir != null) {
      try {
        MoreFiles.deleteRecursively(tempDir, RecursiveDeleteOption.ALLOW_INSECURE);
      } catch (IOException e) {
        logger.warn("Failed to clean up temp directory: " + tempDir, e);
      }
      tempDir = null;
    }
  }
}
