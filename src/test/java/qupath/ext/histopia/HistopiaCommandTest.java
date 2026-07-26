package qupath.ext.histopia;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertPythonModule(command, "histopia.qupath._cli");
        assertEquals("7", command.get(command.indexOf("--clusters") + 1));
        assertEquals("regions", command.get(command.indexOf("--semantic-geometry") + 1));
        assertEquals(Path.of("registration run").toAbsolutePath().toString(), command.get(5));
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

        assertPythonModule(registration, "histopia.registration._cli");
        assertEquals("--config", registration.get(4));
        assertEquals("--staged", registration.get(registration.size() - 1));
        assertPythonModule(semantic, "histopia.semantic._cli");
        assertEquals("run", semantic.get(4));
        assertEquals("--allow-model-download", semantic.get(semantic.size() - 1));
    }

    @Test
    void buildsEnvironmentInspectionForExplicitGpu() {
        var command = HistopiaCommand.inspectEnvironment(
                "python", " CUDA:2 ", "semantic");

        assertPythonModule(command, "histopia.qupath._cli");
        assertEquals("--doctor", command.get(4));
        assertEquals("semantic", command.get(command.indexOf("--workflow") + 1));
        assertEquals("cuda:2", command.get(command.indexOf("--device") + 1));
        assertEquals("1", command.get(command.indexOf("--require-api") + 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> HistopiaCommand.inspectEnvironment(
                        "python", "gpu", "semantic"));
        assertThrows(
                IllegalArgumentException.class,
                () -> HistopiaCommand.inspectEnvironment(
                        "python", "cpu", "unknown"));

        var registration = HistopiaCommand.inspectEnvironment(
                "python", "not-used", "registration");
        assertEquals(
                "auto",
                registration.get(registration.indexOf("--device") + 1));
    }

    @Test
    void buildsRegistrationApprovalWithoutShellInterpolation() {
        var command = HistopiaCommand.approveRegistration(
                "python",
                Path.of("registration run"),
                "Reviewer Name",
                "Masks and physical order reviewed");

        assertPythonModule(command, "histopia.registration._cli");
        assertEquals("--approve-run", command.get(4));
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

        assertPythonModule(masks, "histopia.registration._cli");
        assertPythonModule(order, "histopia.registration._cli");
        assertEquals("--approve-masks", masks.get(4));
        assertEquals("--approve-order", order.get(4));
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

        assertPythonModule(command, "histopia.visualization._cli");
        assertEquals("registration-review", command.get(4));
        assertEquals("4", command.get(command.indexOf("--workers") + 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> HistopiaCommand.buildRegistrationReview(
                        "python", Path.of("registration"), Path.of("review"), 0));
    }

    @Test
    void buildsSemanticReviewAndApprovalCommands() {
        var review = HistopiaCommand.buildSemanticReview(
                "python",
                Path.of("registration run"),
                Path.of("semantic run"),
                Path.of("review output"),
                3);
        var approval = HistopiaCommand.approveSemantic(
                "python",
                Path.of("registration run"),
                Path.of("semantic run"),
                "Reviewer Name",
                "Reviewed K sensitivity and topology");

        assertPythonModule(review, "histopia.visualization._cli");
        assertEquals("build", review.get(4));
        assertEquals(
                "qupath=" + Path.of("semantic run").toAbsolutePath(),
                review.get(review.indexOf("--semantic-run") + 1));
        assertEquals("3", review.get(review.indexOf("--workers") + 1));
        assertPythonModule(approval, "histopia.semantic._cli");
        assertEquals("approve", approval.get(4));
        assertEquals(
                Path.of("registration run").toAbsolutePath().toString(),
                approval.get(approval.indexOf("--registration-run") + 1));
        assertEquals(
                "Reviewer Name",
                approval.get(approval.indexOf("--reviewer") + 1));
    }

    @Test
    void redactsReviewNotesFromDisplayedCommand() {
        var command = HistopiaCommand.approveSemantic(
                "python",
                Path.of("registration run"),
                Path.of("semantic run"),
                "Reviewer Name",
                "private review notes");

        var displayed = HistopiaCommand.display(command);

        assertTrue(displayed.contains("\"Reviewer Name\""));
        assertTrue(displayed.contains("--review-notes <redacted>"));
        assertFalse(displayed.contains("private review notes"));
        assertTrue(displayed.contains("semantic run"));
    }

    private static void assertPythonModule(List<String> command, String module) {
        assertEquals("-u", command.get(1));
        assertEquals("-m", command.get(2));
        assertEquals(module, command.get(3));
    }
}
