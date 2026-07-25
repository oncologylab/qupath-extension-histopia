package qupath.ext.histopia;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

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
    void buildsComputeInspectionForExplicitGpu() {
        var command = HistopiaCommand.inspectCompute("python", " CUDA:2 ");

        assertEquals("histopia.semantic._cli", command.get(2));
        assertEquals("doctor", command.get(3));
        assertEquals("cuda:2", command.get(command.indexOf("--device") + 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> HistopiaCommand.inspectCompute("python", "gpu"));
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
                Path.of("semantic run"),
                "Reviewer Name",
                "Reviewed K sensitivity and topology");

        assertEquals("histopia.visualization._cli", review.get(2));
        assertEquals("build", review.get(3));
        assertEquals(
                "qupath=" + Path.of("semantic run").toAbsolutePath(),
                review.get(review.indexOf("--semantic-run") + 1));
        assertEquals("3", review.get(review.indexOf("--workers") + 1));
        assertEquals("histopia.semantic._cli", approval.get(2));
        assertEquals("approve", approval.get(3));
        assertEquals(
                "Reviewer Name",
                approval.get(approval.indexOf("--reviewer") + 1));
    }

    @Test
    void redactsReviewNotesFromDisplayedCommand() {
        var command = HistopiaCommand.approveSemantic(
                "python",
                Path.of("semantic run"),
                "Reviewer Name",
                "private review notes");

        var displayed = HistopiaCommand.display(command);

        assertTrue(displayed.contains("\"Reviewer Name\""));
        assertTrue(displayed.contains("--review-notes <redacted>"));
        assertFalse(displayed.contains("private review notes"));
        assertTrue(displayed.contains("semantic run"));
    }
}
