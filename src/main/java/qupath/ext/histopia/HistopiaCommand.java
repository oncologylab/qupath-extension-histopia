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
                List.of(
                        "--config",
                        config.toAbsolutePath().toString(),
                        "--staged"));
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

    static List<String> buildRegistrationReview(
            String python,
            Path registrationRun,
            Path output,
            int workers) {
        if (workers <= 0)
            throw new IllegalArgumentException("Review workers must be positive");
        return moduleCommand(
                python,
                "histopia.visualization._cli",
                List.of(
                        "registration-review",
                        registrationRun.toAbsolutePath().toString(),
                        output.toAbsolutePath().toString(),
                        "--workers",
                        Integer.toString(workers)));
    }

    static List<String> approveRegistration(
            String python,
            Path registrationRun,
            String reviewer,
            String notes) {
        return moduleCommand(
                python,
                "histopia.registration._cli",
                List.of(
                        "--approve-run",
                        registrationRun.toAbsolutePath().toString(),
                        "--reviewer",
                        reviewer,
                        "--review-notes",
                        notes));
    }

    static List<String> approveMasks(
            String python,
            Path registrationRun,
            String reviewer,
            String notes) {
        return approvalCommand(
                python, "--approve-masks", registrationRun, reviewer, notes);
    }

    static List<String> approveOrder(
            String python,
            Path registrationRun,
            String reviewer,
            String notes) {
        return approvalCommand(
                python, "--approve-order", registrationRun, reviewer, notes);
    }

    private static List<String> approvalCommand(
            String python,
            String action,
            Path registrationRun,
            String reviewer,
            String notes) {
        return moduleCommand(
                python,
                "histopia.registration._cli",
                List.of(
                        action,
                        registrationRun.toAbsolutePath().toString(),
                        "--reviewer",
                        reviewer,
                        "--review-notes",
                        notes));
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
