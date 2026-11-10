package io.plainround.seatwave.concurrency.sizing;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.concurrency.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Command-line entry point of the sizing step: reads the node's numbers and prints the Little's Law
 * size of each pool. Exit status 2 means bad usage or settings.
 */
public final class SizingCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  private static final int EXIT_BAD_USAGE = 2;

  private SizingCli() {}

  /**
   * Prints the sizing.
   *
   * @param arguments the settings file
   */
  public static void main(String[] arguments) {
    if (arguments.length != 1) {
      System.err.println("usage: sizing <sizing.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
    try {
      switch (SizingInputs.from(new SettingsReader(SettingsReader.load(Path.of(arguments[0]))))) {
        case Result.Success<SizingInputs, List<String>>(SizingInputs inputs) ->
            System.out.print(SizingReport.render(inputs));
        case Result.Error<SizingInputs, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid setting: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
    } catch (IOException unreadable) {
      System.err.println("cannot read " + arguments[0] + ": " + unreadable.getMessage());
      System.exit(EXIT_BAD_USAGE);
    }
  }
}
