package qupath.ext.histopia;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        assertTrue(registration.get("require_approved_masks").getAsBoolean());
        assertTrue(registration.get("require_approved_order").getAsBoolean());

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

    @Test
    void choosesInitialReferenceWithoutComparingImmutableListToNull() {
        var first = new HistopiaWorkflow.ProjectSlide(
                "first", "First", tempDir.resolve("first.ndpi"));
        var second = new HistopiaWorkflow.ProjectSlide(
                "second", "Second", tempDir.resolve("second.ndpi"));
        var selected = List.of(first, second);

        assertEquals(
                first,
                HistopiaWorkflow.preferredReference(selected, null));
        assertEquals(
                second,
                HistopiaWorkflow.preferredReference(selected, second));
        assertEquals(
                null,
                HistopiaWorkflow.preferredReference(List.of(), first));
    }

    @Test
    void reportsEveryRegistrationReviewGate() throws Exception {
        var run = tempDir.resolve("registration");
        Files.createDirectories(run);

        assertEquals(
                "Registration completed without review artifacts",
                HistopiaWorkflow.registrationStatus(run));
        Files.writeString(run.resolve("mask_review.json"), "{}");
        assertEquals(
                "Tissue mask review required",
                HistopiaWorkflow.registrationStatus(run));
        Files.writeString(run.resolve("section_order_review.json"), "{}");
        assertEquals(
                "Section order review required",
                HistopiaWorkflow.registrationStatus(run));
        Files.writeString(run.resolve("registration_result.json"), "{}");
        assertEquals(
                "Registration completed; final review required",
                HistopiaWorkflow.registrationStatus(run));
        Files.writeString(run.resolve("registration_approval.json"), "{}");
        assertEquals(
                "Registration seal stale; final review required",
                HistopiaWorkflow.registrationStatus(run));
        assertFalse(HistopiaWorkflow.registrationSealValid(run));
    }

    @Test
    void acceptsOnlyExactFingerprintBoundRegistrationSeal() throws Exception {
        var run = tempDir.resolve("registration");
        Files.createDirectories(run);
        var result = new JsonObject();
        var slides = new JsonArray();
        var slide = new JsonObject();
        var maskReview = new JsonObject();
        maskReview.addProperty("status", "auto_pass");
        slide.add("mask_review", maskReview);
        slides.add(slide);
        result.add("slides", slides);
        var masks = new JsonObject();
        masks.add("slides", new JsonArray());
        var order = new JsonObject();
        order.addProperty("approved", true);
        order.addProperty("fingerprint", "order-fingerprint");
        var gson = new GsonBuilder().setPrettyPrinting().create();
        Files.writeString(run.resolve("registration_result.json"), gson.toJson(result));
        Files.writeString(run.resolve("mask_review.json"), gson.toJson(masks));
        Files.writeString(run.resolve("section_order_review.json"), gson.toJson(order));

        var artifacts = new JsonObject();
        for (var name : List.of(
                "registration_result.json",
                "mask_review.json",
                "section_order_review.json"))
            artifacts.addProperty(name, sha256(run.resolve(name)));
        var approval = new JsonObject();
        approval.addProperty("schema_version", 1);
        approval.addProperty("reviewer", "Reviewer");
        approval.addProperty("reviewed_at", "2026-07-24T20:00:00+00:00");
        approval.addProperty("slide_count", 1);
        approval.addProperty("order_fingerprint", "order-fingerprint");
        approval.add("artifacts", artifacts);
        approval.addProperty("slide_count", "1");
        Files.writeString(run.resolve("registration_approval.json"), gson.toJson(approval));
        assertFalse(HistopiaWorkflow.registrationSealValid(run));

        approval.addProperty("slide_count", 1);
        Files.writeString(run.resolve("registration_approval.json"), gson.toJson(approval));

        assertTrue(HistopiaWorkflow.registrationSealValid(run));
        assertEquals("Registration sealed", HistopiaWorkflow.registrationStatus(run));

        Files.writeString(
                run.resolve("registration_result.json"),
                Files.readString(run.resolve("registration_result.json")) + System.lineSeparator());
        assertFalse(HistopiaWorkflow.registrationSealValid(run));
        assertEquals(
                "Registration seal stale; final review required",
                HistopiaWorkflow.registrationStatus(run));
    }

    @Test
    void validatesExternalRegistrationSealWhenConfigured() {
        var configured = System.getenv("HISTOPIA_VALIDATED_REGISTRATION_RUN");
        assumeTrue(configured != null && !configured.isBlank());

        var run = Path.of(configured);

        assertTrue(HistopiaWorkflow.registrationSealValid(run));
        assertEquals("Registration sealed", HistopiaWorkflow.registrationStatus(run));
    }

    private static String sha256(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path));
        return HexFormat.of().formatHex(digest);
    }
}
