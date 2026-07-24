package qupath.ext.histopia;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HistopiaCommandTest {

    @Test
    void buildsSemanticExportWithoutShellInterpolation() {
        var command = HistopiaCommand.exportBundle(
                "python",
                Path.of("registration run"),
                Path.of("output"),
                Path.of("semantic"),
                7);

        assertEquals("python", command.get(0));
        assertEquals("histopia.qupath._cli", command.get(2));
        assertEquals("7", command.get(command.indexOf("--clusters") + 1));
        assertEquals("regions", command.get(command.indexOf("--semantic-geometry") + 1));
        assertEquals(Path.of("registration run").toAbsolutePath().toString(), command.get(4));
    }

    @Test
    void rejectsBlankPythonExecutable() {
        assertThrows(
                IllegalArgumentException.class,
                () -> HistopiaCommand.exportBundle(
                        " ", Path.of("registration"), Path.of("output"), null, null));
    }

    @Test
    void buildsRegistrationAndSemanticAnalysisCommands() {
        var registration = HistopiaCommand.runRegistration(
                "python", Path.of("registration.toml"));
        var semantic = HistopiaCommand.runSemantic(
                "python", Path.of("semantic.toml"), true);

        assertEquals("histopia.registration._cli", registration.get(2));
        assertEquals("--config", registration.get(3));
        assertEquals("histopia.semantic._cli", semantic.get(2));
        assertEquals("run", semantic.get(3));
        assertEquals("--allow-model-download", semantic.get(semantic.size() - 1));
    }
}
