package io.plainround.seatwave.concurrency.bench;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.concurrency.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

/**
 * Command-line entry point of the benchmark. It runs the same open-model load once per thread
 * model, prints the table, the thread census and a text chart, and writes the CSV and two SVG
 * charts. Exit status 2 means bad usage or settings; 1 means a run failed.
 */
public final class BenchmarkCli {

  private static final int EXIT_RUN_FAILED = 1;
  // Exit status 2 is the shell convention for "the command was used incorrectly".
  private static final int EXIT_BAD_USAGE = 2;
  private static final String PROPERTIES_SUFFIX = ".properties";

  private BenchmarkCli() {}

  /**
   * Runs the benchmark.
   *
   * @param arguments the settings file, then the folder for the CSV and SVG files
   */
  public static void main(String[] arguments) {
    if (arguments.length != 2) {
      System.err.println("usage: benchmark <settings.properties> <output folder>");
      System.exit(EXIT_BAD_USAGE);
    }
    Path settingsFile = Path.of(arguments[0]);
    try {
      Result<BenchmarkSettings, List<String>> read =
          BenchmarkSettings.from(new SettingsReader(SettingsReader.load(settingsFile)));
      switch (read) {
        case Result.Success<BenchmarkSettings, List<String>>(BenchmarkSettings settings) ->
            run(settings, runName(settingsFile), Path.of(arguments[1]));
        case Result.Error<BenchmarkSettings, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid setting: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
    } catch (IOException | SQLException failed) {
      System.err.println("the benchmark failed: " + failed);
      System.exit(EXIT_RUN_FAILED);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      System.err.println("the benchmark was interrupted");
      System.exit(EXIT_RUN_FAILED);
    }
  }

  private static void run(BenchmarkSettings settings, String name, Path folder)
      throws IOException, SQLException, InterruptedException {
    System.out.print(BenchmarkRun.header(settings));
    List<StepResult> all = BenchmarkRun.runEveryMode(settings);
    System.out.println();
    System.out.print(BenchmarkRun.summary(settings, all));
    List<Path> written = BenchmarkRun.writeFiles(folder, name, all, settings);
    System.out.printf(
        Locale.ROOT,
        "%nWrote %s%n",
        String.join(", ", written.stream().map(Path::toString).toList()));
  }

  private static String runName(Path settingsFile) {
    String fileName = settingsFile.getFileName().toString();
    return fileName.endsWith(PROPERTIES_SUFFIX)
        ? fileName.substring(0, fileName.length() - PROPERTIES_SUFFIX.length())
        : fileName;
  }
}
