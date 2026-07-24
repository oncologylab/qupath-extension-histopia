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
        assertEquals("--staged", registration.get(registration.size() - 1));
        assertEquals("histopia.semantic._cli", semantic.get(2));
        assertEquals("run", semantic.get(3));
        assertEquals("--allow-model-download", semantic.get(semantic.size() - 1));
    }

    @Test
    void buildsRegistrationApprovalWithoutShellInterpolation() {
        var command = HistopiaCommand.approveRegistration(
                "python",
                Path.of("registration run"),
                "Reviewer Name",
                "Masks and physical order reviewed");

        assertEquals("histopia.registration._cli", command.get(2));
        assertEquals("--approve-run", command.get(3));
        assertEquals("Reviewer Name", command.get(command.indexOf("--reviewer") + 1));
        assertEquals(
                "Masks and physical order reviewed",
                command.get(command.indexOf("--review-notes") + 1));
    }

    @Test
    void buildsStageApprovalCommandsWithoutShellInterpolation() {
        var masks = HistopiaCommand.approveMasks(
                "python", Path.of("registration run"), "Reviewer", "Masks reviewed");
        var order = HistopiaCommand.approveOrder(
                "python", Path.of("registration run"), "Reviewer", "Order reviewed");

        assertEquals("--approve-masks", masks.get(3));
        assertEquals("--approve-order", order.get(3));
        assertEquals("Reviewer", masks.get(masks.indexOf("--reviewer") + 1));
        assertEquals("Order reviewed", order.get(order.indexOf("--review-notes") + 1));
    }

    @Test
    void buildsRegistrationReviewPortalCommand() {
        var command = HistopiaCommand.buildRegistrationReview(
                "python",
                Path.of("registration run"),
                Path.of("review output"),
                4);

        assertEquals("histopia.visualization._cli", command.get(2));
        assertEquals("registration-review", command.get(3));
        assertEquals("4", command.get(command.indexOf("--workers") + 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> HistopiaCommand.buildRegistrationReview(
                        "python", Path.of("registration"), Path.of("review"), 0));
    }
}
