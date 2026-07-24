package qupath.ext.histopia;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class HistopiaCommand {

    private HistopiaCommand() {
    }

    static List<String> exportBundle(
            String python,
            Path registrationRun,
            Path output,
            Path semanticRun,
            Integer clusters) {
        if (python == null || python.isBlank())
            throw new IllegalArgumentException("Python executable must not be blank");
        var command = new ArrayList<>(List.of(
                python,
                "-m",
                "histopia.qupath._cli",
                "--registration-run",
                registrationRun.toAbsolutePath().toString(),
                "--output",
                output.toAbsolutePath().toString()));
        if (semanticRun != null) {
            command.add("--semantic-run");
            command.add(semanticRun.toAbsolutePath().toString());
            if (clusters != null) {
                command.add("--clusters");
                command.add(clusters.toString());
            }
        }
        return List.copyOf(command);
    }
}
