package qupath.ext.histopia;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HistopiaPythonContractTest {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    @TempDir
    Path temporaryDirectory;

    @Test
    void generatedConfigsLoadInInstalledHistopia() throws Exception {
        var python = System.getenv("HISTOPIA_PYTHON");
        assumeTrue(
                python != null && !python.isBlank(),
                "HISTOPIA_PYTHON enables the installed-package contract test");
        var source = Files.createDirectories(temporaryDirectory.resolve("source"));
        var firstPath = Files.createFile(source.resolve("first.ndpi"));
        var secondPath = Files.createFile(source.resolve("second.scn"));
        var first = new HistopiaWorkflow.ProjectSlide("first-id", "First", firstPath);
        var second = new HistopiaWorkflow.ProjectSlide("second-id", "Second", secondPath);
        var modelCache = Files.createDirectories(temporaryDirectory.resolve("model"));
        var files = HistopiaWorkflow.writeConfigs(
                temporaryDirectory.resolve("workspace"),
                List.of(first, second),
                first,
                "anchored_similarity",
                1200,
                2,
                1,
                modelCache,
                "cpu",
                5,
                9,
                16,
                1,
                null,
                2);

        var script = """
                import json
                import sys
                from histopia.registration import load_registration_config
                from histopia.semantic import load_semantic_config

                registration = load_registration_config(sys.argv[1])
                semantic = load_semantic_config(sys.argv[2])
                print(json.dumps({
                    "registration": {
                        "input_slides": [str(path) for path in registration.input_slides],
                        "reference_slide": registration.reference_slide,
                        "section_order_strategy": registration.section_order_strategy,
                        "mask_workers": registration.mask_workers,
                        "qc_workers": registration.qc_workers,
                        "require_approved_masks": registration.require_approved_masks,
                        "require_approved_order": registration.require_approved_order,
                    },
                    "semantic": {
                        "device": semantic.device,
                        "cluster_min": semantic.cluster_min,
                        "cluster_max": semantic.cluster_max,
                        "fit_threads": semantic.fit_threads,
                        "model_cache_dir": str(semantic.model_cache_dir),
                    },
                }))
                """;
        var loaded = run(List.of(
                python,
                "-c",
                script,
                files.registrationConfig().toString(),
                files.semanticConfig().toString()));
        assertEquals(0, loaded.exitCode(), loaded.output());
        var payload = JsonParser.parseString(loaded.output()).getAsJsonObject();
        var registration = payload.getAsJsonObject("registration");
        var semantic = payload.getAsJsonObject("semantic");
        assertEquals(2, registration.getAsJsonArray("input_slides").size());
        assertEquals("first.ndpi", registration.get("reference_slide").getAsString());
        assertEquals(
                "anchored_similarity",
                registration.get("section_order_strategy").getAsString());
        assertEquals(2, registration.get("mask_workers").getAsInt());
        assertEquals(1, registration.get("qc_workers").getAsInt());
        assertTrue(registration.get("require_approved_masks").getAsBoolean());
        assertTrue(registration.get("require_approved_order").getAsBoolean());
        assertEquals("cpu", semantic.get("device").getAsString());
        assertEquals(5, semantic.get("cluster_min").getAsInt());
        assertEquals(9, semantic.get("cluster_max").getAsInt());
        assertEquals(2, semantic.get("fit_threads").getAsInt());
        assertEquals(modelCache.toString(), semantic.get("model_cache_dir").getAsString());

        var doctor = run(HistopiaCommand.inspectEnvironment(
                python, "cpu", "interchange"));
        assertEquals(0, doctor.exitCode(), doctor.output());
        var report = JsonParser.parseString(doctor.output()).getAsJsonObject();
        assertEquals("ok", report.get("status").getAsString());
        assertEquals(1, report.get("qupath_workflow_api_version").getAsInt());
        assertEquals(
                4,
                report.getAsJsonObject("capabilities")
                        .get("qupath_interchange_schema_version")
                        .getAsInt());

        var registrationHelp = run(List.of(
                python, "-m", "histopia.registration._cli", "--help"));
        assertEquals(0, registrationHelp.exitCode(), registrationHelp.output());
        assertTrue(registrationHelp.output().contains("--staged"));
        assertTrue(registrationHelp.output().contains("--approve-masks"));
        var semanticHelp = run(List.of(
                python, "-m", "histopia.semantic._cli", "run", "--help"));
        assertEquals(0, semanticHelp.exitCode(), semanticHelp.output());
        assertTrue(semanticHelp.output().contains("--allow-model-download"));
        assertTrue(semanticHelp.output().contains("--fit-threads"));
        var semanticApprovalHelp = run(List.of(
                python, "-m", "histopia.semantic._cli", "approve", "--help"));
        assertEquals(
                0, semanticApprovalHelp.exitCode(), semanticApprovalHelp.output());
        assertTrue(
                semanticApprovalHelp.output().contains("--registration-run"));
    }

    @Test
    void cancelledInstalledHistopiaCheckpointsInterruptedStage() throws Exception {
        var python = System.getenv("HISTOPIA_PYTHON");
        assumeTrue(
                python != null && !python.isBlank(),
                "HISTOPIA_PYTHON enables the installed-package contract test");
        var output = temporaryDirectory.resolve("cancelled-registration");
        var script = """
                import sys
                import time
                from histopia._signals import graceful_sigterm
                from histopia.registration._performance import RegistrationPerformance

                performance = RegistrationPerformance(sys.argv[1], {})
                with graceful_sigterm():
                    try:
                        performance.start_stage("slide_discovery")
                        print("READY", flush=True)
                        while True:
                            time.sleep(1)
                    except BaseException as error:
                        performance.fail(error)
                        raise
                """;
        var process = new ProcessBuilder(
                python,
                "-u",
                "-c",
                script,
                output.toString())
                .redirectErrorStream(true)
                .start();
        try (var reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals("READY", reader.readLine());
            HistopiaProcess.cancelTree(process);
            assertTrue(
                    process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "cancelled Histopia process did not exit");
            assertEquals(143, process.exitValue());
            assertNull(reader.readLine());
        } finally {
            if (process.isAlive())
                HistopiaProcess.cancelTree(process, Duration.ZERO);
        }

        var performance = JsonParser.parseString(
                Files.readString(output.resolve("registration_performance.json")))
                .getAsJsonObject();
        assertEquals("interrupted", performance.get("status").getAsString());
        assertEquals("SystemExit", performance.get("failure_type").getAsString());
        assertEquals(
                "interrupted",
                performance.getAsJsonObject("stages")
                        .getAsJsonObject("slide_discovery")
                        .get("status")
                        .getAsString());
    }

    private static CommandResult run(List<String> command)
            throws IOException, InterruptedException {
        var process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            HistopiaProcess.cancelTree(process, Duration.ZERO);
            throw new IllegalStateException(
                    "Timed out running Histopia contract command: "
                            + HistopiaCommand.display(command));
        }
        var output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).strip();
        return new CommandResult(process.exitValue(), output);
    }

    private record CommandResult(int exitCode, String output) {
    }
}
