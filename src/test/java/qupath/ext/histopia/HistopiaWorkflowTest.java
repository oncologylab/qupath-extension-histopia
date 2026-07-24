package qupath.ext.histopia;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistopiaWorkflowTest {

    @TempDir
    Path tempDir;

    @Test
    void writesExactProjectSelectionAndRunnableConfigs() throws Exception {
        var second = new HistopiaWorkflow.ProjectSlide(
                "id-2", "Second", tempDir.resolve("two.scn"));
        var first = new HistopiaWorkflow.ProjectSlide(
                "id-1", "First", tempDir.resolve("one.ndpi"));

        var files = HistopiaWorkflow.writeConfigs(
                tempDir.resolve("analysis"),
                List.of(second, first),
                first,
                "anchored_similarity",
                1200,
                4,
                tempDir.resolve("models"),
                "cuda",
                5,
                12,
                32,
                2);

        var registration = JsonParser.parseString(
                java.nio.file.Files.readString(files.registrationConfig()))
                .getAsJsonObject();
        assertEquals(
                second.path().toString(),
                registration.getAsJsonArray("input_slides").get(0).getAsString());
        assertEquals("one.ndpi", registration.get("reference_slide").getAsString());
        assertEquals("anchored_similarity",
                registration.get("section_order_strategy").getAsString());
        assertEquals(
                files.selectionManifest().toString(),
                registration.get("section_order_path").getAsString());
        assertTrue(registration.get("preprocessing_cache").getAsBoolean());

        var semantic = JsonParser.parseString(
                java.nio.file.Files.readString(files.semanticConfig()))
                .getAsJsonObject();
        assertEquals("cuda", semantic.get("device").getAsString());
        assertEquals(12, semantic.get("cluster_max").getAsInt());

        var selection = JsonParser.parseString(
                java.nio.file.Files.readString(files.selectionManifest()))
                .getAsJsonObject();
        assertEquals("id-2", selection.getAsJsonArray("slides")
                .get(0).getAsJsonObject().get("project_image_id").getAsString());
        assertTrue(selection.getAsJsonArray("slides")
                .get(1).getAsJsonObject().get("reference").getAsBoolean());
        assertEquals(1, selection.getAsJsonArray("slides")
                .get(1).getAsJsonObject().get("order").getAsInt());
    }

    @Test
    void rejectsAmbiguousDuplicateFilenames() {
        var one = new HistopiaWorkflow.ProjectSlide(
                "one", "One", tempDir.resolve("a/slide.ndpi"));
        var two = new HistopiaWorkflow.ProjectSlide(
                "two", "Two", tempDir.resolve("b/slide.ndpi"));

        assertThrows(
                IllegalArgumentException.class,
                () -> HistopiaWorkflow.writeConfigs(
                        tempDir.resolve("analysis"),
                        List.of(one, two),
                        null,
                        "natural",
                        1200,
                        1,
                        null,
                        "auto",
                        5,
                        10,
                        32,
                        1));
    }
}
