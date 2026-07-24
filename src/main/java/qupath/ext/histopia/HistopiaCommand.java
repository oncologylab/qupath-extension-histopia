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
            command.add("--semantic-geometry");
            command.add("regions");
        }
        return List.copyOf(command);
    }

    static List<String> runRegistration(String python, Path config) {
        return moduleCommand(
                python,
                "histopia.registration._cli",
                List.of("--config", config.toAbsolutePath().toString()));
    }

    static List<String> runSemantic(
            String python,
            Path config,
            boolean allowModelDownload) {
        var arguments = new ArrayList<>(List.of(
                "run",
                "--config",
                config.toAbsolutePath().toString()));
        if (allowModelDownload)
            arguments.add("--allow-model-download");
        return moduleCommand(python, "histopia.semantic._cli", arguments);
    }

    private static List<String> moduleCommand(
            String python,
            String module,
            List<String> arguments) {
        if (python == null || python.isBlank())
            throw new IllegalArgumentException("Python executable must not be blank");
        var command = new ArrayList<>(List.of(python, "-m", module));
        command.addAll(arguments);
        return List.copyOf(command);
    }
}
