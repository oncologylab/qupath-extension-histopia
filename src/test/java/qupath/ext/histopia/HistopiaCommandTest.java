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
        assertEquals("7", command.get(command.size() - 1));
        assertEquals(Path.of("registration run").toAbsolutePath().toString(), command.get(4));
    }

    @Test
    void rejectsBlankPythonExecutable() {
        assertThrows(
                IllegalArgumentException.class,
                () -> HistopiaCommand.exportBundle(
                        " ", Path.of("registration"), Path.of("output"), null, null));
    }
}
