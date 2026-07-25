package qupath.ext.histopia;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class HistopiaCommand {

    private static final int QUPATH_WORKFLOW_API_VERSION = 1;
    private static final Set<String> REDACTED_OPTIONS = Set.of("--review-notes");
    private static final Set<String> WORKFLOWS =
            Set.of("registration", "semantic", "interchange", "full");

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

    static List<String> inspectEnvironment(
            String python,
            String device,
            String workflow) {
        var normalizedWorkflow = workflow == null ? "" : workflow.strip().toLowerCase();
        if (!WORKFLOWS.contains(normalizedWorkflow))
            throw new IllegalArgumentException(
                    "Workflow must be registration, semantic, interchange, or full");
        var normalizedDevice = Set.of("semantic", "full").contains(normalizedWorkflow)
                ? HistopiaWorkflow.normalizeDevice(device)
                : "auto";
        return moduleCommand(
                python,
                "histopia.qupath._cli",
                List.of(
                        "--doctor",
                        "--workflow",
                        normalizedWorkflow,
                        "--device",
                        normalizedDevice,
                        "--require-api",
                        Integer.toString(QUPATH_WORKFLOW_API_VERSION)));
    }

    static String display(List<String> command) {
        var displayed = new ArrayList<String>(command.size());
        var redactNext = false;
        for (var argument : command) {
            if (redactNext) {
                displayed.add("<redacted>");
                redactNext = false;
            } else {
                displayed.add(quoteForDisplay(argument));
                redactNext = REDACTED_OPTIONS.contains(argument);
            }
        }
        return String.join(" ", displayed);
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

    static List<String> buildSemanticReview(
            String python,
            Path registrationRun,
            Path semanticRun,
            Path output,
            int workers) {
        if (workers <= 0)
            throw new IllegalArgumentException("Review workers must be positive");
        return moduleCommand(
                python,
                "histopia.visualization._cli",
                List.of(
                        "build",
                        output.toAbsolutePath().toString(),
                        "--run",
                        "qupath=" + registrationRun.toAbsolutePath(),
                        "--semantic-run",
                        "qupath=" + semanticRun.toAbsolutePath(),
                        "--workers",
                        Integer.toString(workers)));
    }

    static List<String> approveSemantic(
            String python,
            Path semanticRun,
            String reviewer,
            String notes) {
        return moduleCommand(
                python,
                "histopia.semantic._cli",
                List.of(
                        "approve",
                        "--run",
                        semanticRun.toAbsolutePath().toString(),
                        "--reviewer",
                        reviewer,
                        "--review-notes",
                        notes));
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

    private static String quoteForDisplay(String argument) {
        if (argument.matches("[A-Za-z0-9_./:=+,-]+"))
            return argument;
        return "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
