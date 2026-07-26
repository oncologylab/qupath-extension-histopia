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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.net.URI;

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
                2,
                "full",
                tempDir.resolve("models"),
                " CUDA:1 ",
                5,
                12,
                32,
                2,
                4,
                6);

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
        assertTrue(registration.get("alignment_cache").getAsBoolean());
        assertEquals(4, registration.get("thumbnail_workers").getAsInt());
        assertEquals(4, registration.get("mask_workers").getAsInt());
        assertEquals(4, registration.get("ordering_workers").getAsInt());
        assertEquals(2, registration.get("qc_workers").getAsInt());
        assertEquals("full", registration.get("alignment_qc_mode").getAsString());
        assertEquals(4, registration.get("vips_threads").getAsInt());
        assertTrue(registration.get("require_approved_masks").getAsBoolean());
        assertTrue(registration.get("require_approved_order").getAsBoolean());

        var semantic = JsonParser.parseString(
                java.nio.file.Files.readString(files.semanticConfig()))
                .getAsJsonObject();
        assertEquals("cuda:1", semantic.get("device").getAsString());
        assertEquals(12, semantic.get("cluster_max").getAsInt());
        assertEquals(4, semantic.get("vips_threads").getAsInt());
        assertEquals(6, semantic.get("fit_threads").getAsInt());

        var selection = JsonParser.parseString(
                java.nio.file.Files.readString(files.selectionManifest()))
                .getAsJsonObject();
        assertEquals("id-2", selection.getAsJsonArray("slides")
                .get(0).getAsJsonObject().get("project_image_id").getAsString());
        assertTrue(selection.getAsJsonArray("slides")
                .get(1).getAsJsonObject().get("reference").getAsBoolean());
        assertEquals(1, selection.getAsJsonArray("slides")
                .get(1).getAsJsonObject().get("order").getAsInt());
        assertTrue(HistopiaWorkflow.selectionManifestMatches(
                files.selectionManifest(), List.of(first, second)));
        assertFalse(HistopiaWorkflow.selectionManifestMatches(
                files.selectionManifest(), List.of(first)));
        assertEquals(
                tempDir.resolve("analysis/.histopia/workflow-audit.json")
                        .toAbsolutePath()
                        .normalize(),
                files.workflowAudit());
    }

    @Test
    void semanticRefreshPreservesRegistrationProvenanceFiles() throws Exception {
        var one = new HistopiaWorkflow.ProjectSlide(
                "one", "One", tempDir.resolve("one.ndpi"));
        var two = new HistopiaWorkflow.ProjectSlide(
                "two", "Two", tempDir.resolve("two.scn"));
        var workspace = tempDir.resolve("analysis");
        var files = HistopiaWorkflow.writeConfigs(
                workspace,
                List.of(one, two),
                one,
                "anchored_similarity",
                1200,
                2,
                1,
                "review",
                tempDir.resolve("models-a"),
                "cpu",
                5,
                10,
                32,
                1,
                null,
                2);
        var registrationBefore = Files.readAllBytes(files.registrationConfig());
        var selectionBefore = Files.readAllBytes(files.selectionManifest());
        var registrationModifiedBefore =
                Files.getLastModifiedTime(files.registrationConfig());
        var selectionModifiedBefore =
                Files.getLastModifiedTime(files.selectionManifest());

        var refreshed = HistopiaWorkflow.writeSemanticConfig(
                workspace,
                tempDir.resolve("models-b"),
                "cuda:1",
                6,
                12,
                64,
                2,
                4,
                6);

        assertEquals(files, refreshed);
        assertTrue(java.util.Arrays.equals(
                registrationBefore, Files.readAllBytes(files.registrationConfig())));
        assertTrue(java.util.Arrays.equals(
                selectionBefore, Files.readAllBytes(files.selectionManifest())));
        assertEquals(
                registrationModifiedBefore,
                Files.getLastModifiedTime(files.registrationConfig()));
        assertEquals(
                selectionModifiedBefore,
                Files.getLastModifiedTime(files.selectionManifest()));
        var semantic = JsonParser.parseString(
                Files.readString(files.semanticConfig())).getAsJsonObject();
        assertEquals("cuda:1", semantic.get("device").getAsString());
        assertEquals(6, semantic.get("cluster_min").getAsInt());
        assertEquals(4, semantic.get("vips_threads").getAsInt());
    }

    @Test
    void recognizesSupportedLocalUrisWithoutThrowingOnMalformedSources() {
        var slide = tempDir.resolve("encoded slide.ome.tiff").toAbsolutePath();

        assertEquals(slide, HistopiaWorkflow.localWsiPath(slide.toUri()));
        assertEquals(
                null,
                HistopiaWorkflow.localWsiPath(
                        URI.create("https://example.org/slide.ndpi")));
        assertEquals(
                null,
                HistopiaWorkflow.localWsiPath(
                        URI.create("file://remote-host/slide.ndpi")));
        assertEquals(
                null,
                HistopiaWorkflow.localWsiPath(
                        tempDir.resolve("notes.txt").toUri()));
    }

    @Test
    void rejectsUnknownSectionOrderStrategy() {
        var one = new HistopiaWorkflow.ProjectSlide(
                "one", "One", tempDir.resolve("one.ndpi"));
        var two = new HistopiaWorkflow.ProjectSlide(
                "two", "Two", tempDir.resolve("two.scn"));

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> HistopiaWorkflow.writeConfigs(
                        tempDir.resolve("analysis"),
                        List.of(one, two),
                        null,
                        "manual",
                        1200,
                        1,
                        1,
                        "review",
                        null,
                        "auto",
                        5,
                        10,
                        32,
                        1,
                        null,
                        4));
        assertTrue(error.getMessage().startsWith("Section order must be"));
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
                        1,
                        "review",
                        null,
                        "auto",
                        5,
                        10,
                        32,
                        1,
                        null,
                        4));
    }

    @Test
    void rejectsNonpositiveVipsThreads() {
        var one = new HistopiaWorkflow.ProjectSlide(
                "one", "One", tempDir.resolve("one.ndpi"));
        var two = new HistopiaWorkflow.ProjectSlide(
                "two", "Two", tempDir.resolve("two.ndpi"));

        assertThrows(
                IllegalArgumentException.class,
                () -> HistopiaWorkflow.writeConfigs(
                        tempDir.resolve("analysis"),
                        List.of(one, two),
                        null,
                        "natural",
                        1200,
                        1,
                        1,
                        "review",
                        null,
                        "auto",
                        5,
                        10,
                        32,
                        1,
                        0,
                        4));
    }

    @Test
    void rejectsNonpositiveQcWorkers() {
        var one = new HistopiaWorkflow.ProjectSlide(
                "one", "One", tempDir.resolve("one.ndpi"));
        var two = new HistopiaWorkflow.ProjectSlide(
                "two", "Two", tempDir.resolve("two.ndpi"));

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> HistopiaWorkflow.writeConfigs(
                        tempDir.resolve("analysis"),
                        List.of(one, two),
                        null,
                        "natural",
                        1200,
                        4,
                        0,
                        "review",
                        null,
                        "auto",
                        5,
                        10,
                        32,
                        1,
                        null,
                        4));
        assertEquals("QC workers must be positive", error.getMessage());
    }

    @Test
    void omitsAdaptiveVipsThreadSetting() throws Exception {
        var one = new HistopiaWorkflow.ProjectSlide(
                "one", "One", tempDir.resolve("one.ndpi"));
        var two = new HistopiaWorkflow.ProjectSlide(
                "two", "Two", tempDir.resolve("two.ndpi"));

        var files = HistopiaWorkflow.writeConfigs(
                tempDir.resolve("analysis"),
                List.of(one, two),
                null,
                "natural",
                1200,
                1,
                1,
                "none",
                null,
                "auto",
                5,
                10,
                32,
                1,
                null,
                4);

        var registration = JsonParser.parseString(
                Files.readString(files.registrationConfig())).getAsJsonObject();
        var semantic = JsonParser.parseString(Files.readString(files.semanticConfig()))
                .getAsJsonObject();
        assertFalse(registration.has("vips_threads"));
        assertEquals("none", registration.get("alignment_qc_mode").getAsString());
        assertFalse(semantic.has("vips_threads"));
        assertEquals(4, semantic.get("fit_threads").getAsInt());
    }

    @Test
    void rejectsNonpositiveFitThreads() {
        var one = new HistopiaWorkflow.ProjectSlide(
                "one", "One", tempDir.resolve("one.ndpi"));
        var two = new HistopiaWorkflow.ProjectSlide(
                "two", "Two", tempDir.resolve("two.ndpi"));

        assertThrows(
                IllegalArgumentException.class,
                () -> HistopiaWorkflow.writeConfigs(
                        tempDir.resolve("analysis"),
                        List.of(one, two),
                        null,
                        "natural",
                        1200,
                        1,
                        1,
                        "review",
                        null,
                        "auto",
                        5,
                        10,
                        32,
                        1,
                        null,
                        0));
    }

    @Test
    void validatesSemanticDeviceSyntax() {
        assertEquals("auto", HistopiaWorkflow.normalizeDevice(" AUTO "));
        assertEquals("cuda:12", HistopiaWorkflow.normalizeDevice(" CUDA:12 "));
        assertThrows(
                IllegalArgumentException.class,
                () -> HistopiaWorkflow.normalizeDevice("gpu"));
        assertThrows(
                IllegalArgumentException.class,
                () -> HistopiaWorkflow.normalizeDevice("cuda:-1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> HistopiaWorkflow.normalizeDevice("cuda:"));
        assertThrows(
                IllegalArgumentException.class,
                () -> HistopiaWorkflow.normalizeDevice("cuda:١"));
    }

    @Test
    void validatesAlignmentQcModeSyntax() {
        assertEquals("review", HistopiaWorkflow.normalizeAlignmentQcMode(" REVIEW "));
        assertEquals("none", HistopiaWorkflow.normalizeAlignmentQcMode("none"));
        assertEquals("full", HistopiaWorkflow.normalizeAlignmentQcMode("full"));
        assertThrows(
                IllegalArgumentException.class,
                () -> HistopiaWorkflow.normalizeAlignmentQcMode("debug"));
        assertThrows(
                IllegalArgumentException.class,
                () -> HistopiaWorkflow.normalizeAlignmentQcMode(" "));
    }

    @Test
    void boundsAutomaticRegistrationWorkers() {
        assertEquals(1, HistopiaWorkflow.defaultRegistrationWorkers(1));
        assertEquals(1, HistopiaWorkflow.defaultRegistrationWorkers(2));
        assertEquals(2, HistopiaWorkflow.defaultRegistrationWorkers(4));
        assertEquals(4, HistopiaWorkflow.defaultRegistrationWorkers(8));
        assertEquals(4, HistopiaWorkflow.defaultRegistrationWorkers(32));
        assertEquals(4, HistopiaWorkflow.defaultRegistrationWorkers(Integer.MAX_VALUE));
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
    void currentExecutionStatusOverridesStaleRegistrationArtifacts() throws Exception {
        var run = tempDir.resolve("registration");
        Files.createDirectories(run);
        Files.writeString(run.resolve("mask_review.json"), "{}");
        Files.writeString(run.resolve("section_order_review.json"), "{}");
        Files.writeString(run.resolve("registration_result.json"), "{}");
        Files.writeString(run.resolve("registration_approval.json"), "{}");
        var performance = new JsonObject();
        performance.addProperty("schema_version", 1);
        performance.addProperty("workflow", "registration");
        performance.addProperty("observational_only", true);
        performance.addProperty("status", "review_required");
        performance.addProperty("review_stage", "masks");
        performance.add("stages", new JsonObject());
        Files.writeString(
                run.resolve("registration_performance.json"),
                performance.toString());

        assertEquals(
                "Tissue mask review required",
                HistopiaWorkflow.registrationStatus(run));
        performance.addProperty("review_stage", "order");
        Files.writeString(
                run.resolve("registration_performance.json"),
                performance.toString());
        assertEquals(
                "Section order review required",
                HistopiaWorkflow.registrationStatus(run));
        performance.addProperty("status", "completed");
        Files.writeString(
                run.resolve("registration_performance.json"),
                performance.toString());
        assertEquals(
                "Registration seal stale; final review required",
                HistopiaWorkflow.registrationStatus(run));
    }

    @Test
    void bindsRegistrationStageArtifactsToExactProjectCohort() throws Exception {
        var run = tempDir.resolve("registration");
        Files.createDirectories(run);
        var first = new HistopiaWorkflow.ProjectSlide(
                "first", "First", tempDir.resolve("slides/first.ndpi"));
        var second = new HistopiaWorkflow.ProjectSlide(
                "second", "Second", tempDir.resolve("slides/second.scn"));
        var third = new HistopiaWorkflow.ProjectSlide(
                "third", "Third", tempDir.resolve("slides/third.ndpi"));
        var selected = List.of(first, second);
        writeSlideArtifact(
                run.resolve("mask_review.json"),
                2,
                "slide",
                List.of(second.filename(), first.filename()));
        writeSlideArtifact(
                run.resolve("section_order_review.json"),
                3,
                "slide",
                List.of(first.filename(), second.filename()));
        writeSlideArtifact(
                run.resolve("registration_result.json"),
                null,
                "path",
                List.of(second.path().toString(), first.path().toString()));

        assertTrue(HistopiaWorkflow.registrationStageMatchesSelection(
                run, "mask_review.json", selected));
        assertTrue(HistopiaWorkflow.registrationStageMatchesSelection(
                run, "section_order_review.json", selected));
        assertTrue(HistopiaWorkflow.registrationStageMatchesSelection(
                run, "registration_result.json", selected));

        var performance = new JsonObject();
        performance.addProperty("schema_version", 1);
        performance.addProperty("workflow", "registration");
        performance.addProperty("observational_only", true);
        performance.addProperty("status", "review_required");
        performance.addProperty("review_stage", "masks");
        performance.add("stages", new JsonObject());
        Files.writeString(
                run.resolve("registration_performance.json"),
                performance.toString());
        assertTrue(HistopiaWorkflow.registrationStageMatchesSelection(
                run, "mask_review.json", selected));
        assertFalse(HistopiaWorkflow.registrationStageMatchesSelection(
                run, "section_order_review.json", selected));
        assertFalse(HistopiaWorkflow.registrationStageMatchesSelection(
                run, "registration_result.json", selected));

        performance.addProperty("review_stage", "order");
        Files.writeString(
                run.resolve("registration_performance.json"),
                performance.toString());
        assertTrue(HistopiaWorkflow.registrationStageMatchesSelection(
                run, "section_order_review.json", selected));
        assertFalse(HistopiaWorkflow.registrationStageMatchesSelection(
                run, "registration_result.json", selected));

        performance.addProperty("status", "failed");
        Files.writeString(
                run.resolve("registration_performance.json"),
                performance.toString());
        assertFalse(HistopiaWorkflow.registrationStageMatchesSelection(
                run, "mask_review.json", selected));

        performance.addProperty("status", "completed");
        Files.writeString(
                run.resolve("registration_performance.json"),
                performance.toString());
        assertTrue(HistopiaWorkflow.registrationStageMatchesSelection(
                run, "registration_result.json", selected));
        assertFalse(HistopiaWorkflow.registrationStageMatchesSelection(
                run, "registration_result.json", List.of(first, third)));
        assertFalse(HistopiaWorkflow.registrationStageMatchesSelection(
                run, "unknown.json", selected));

        writeSlideArtifact(
                run.resolve("mask_review.json"),
                2,
                "slide",
                List.of(first.filename(), third.filename()));
        assertFalse(HistopiaWorkflow.registrationStageMatchesSelection(
                run, "mask_review.json", selected));
        assertFalse(HistopiaWorkflow.registrationStageMatchesSelection(
                run, "section_order_review.json", selected));
        assertFalse(HistopiaWorkflow.registrationStageMatchesSelection(
                run, "registration_result.json", selected));

        writeSlideArtifact(
                run.resolve("mask_review.json"),
                2,
                "slide",
                List.of(first.filename(), second.filename()));
        writeSlideArtifact(
                run.resolve("section_order_review.json"),
                3,
                "slide",
                List.of(first.filename(), first.filename()));
        assertFalse(HistopiaWorkflow.registrationStageMatchesSelection(
                run, "section_order_review.json", selected));
        assertFalse(HistopiaWorkflow.registrationStageMatchesSelection(
                run, "registration_result.json", selected));

        Files.writeString(
                run.resolve("mask_review.json"),
                """
                {"schema_version":"2","slides":[]}
                """);
        assertFalse(HistopiaWorkflow.registrationStageMatchesSelection(
                run, "mask_review.json", selected));
    }

    @Test
    void matchesSealedRegistrationToCurrentProjectSelection() throws Exception {
        var run = tempDir.resolve("registration");
        Files.createDirectories(run);
        var first = new HistopiaWorkflow.ProjectSlide(
                "first", "First", tempDir.resolve("slides/first.ndpi"));
        var second = new HistopiaWorkflow.ProjectSlide(
                "second", "Second", tempDir.resolve("slides/second.ndpi"));
        var result = new JsonObject();
        var slides = new JsonArray();
        for (var slide : List.of(second, first)) {
            var row = new JsonObject();
            row.addProperty("path", slide.path().toString());
            slides.add(row);
        }
        result.add("slides", slides);
        Files.writeString(
                run.resolve("registration_result.json"),
                new GsonBuilder().create().toJson(result));

        assertTrue(HistopiaWorkflow.registrationMatchesSelection(
                run, List.of(first, second)));
        assertFalse(HistopiaWorkflow.registrationMatchesSelection(
                run, List.of(first)));
        assertFalse(HistopiaWorkflow.registrationMatchesSelection(
                run,
                List.of(
                        first,
                        new HistopiaWorkflow.ProjectSlide(
                                "third", "Third", tempDir.resolve("slides/third.ndpi")))));
        assertFalse(HistopiaWorkflow.registrationMatchesSelection(
                run, List.of(first, first)));

        slides.get(0).getAsJsonObject().addProperty("path", first.path().toString());
        Files.writeString(
                run.resolve("registration_result.json"),
                new GsonBuilder().create().toJson(result));
        assertFalse(HistopiaWorkflow.registrationMatchesSelection(
                run, List.of(first, second)));
    }

    @Test
    void rejectsChangedOrMalformedPreparedSelection() throws Exception {
        var first = new HistopiaWorkflow.ProjectSlide(
                "first", "First", tempDir.resolve("slides/first.ndpi"));
        var second = new HistopiaWorkflow.ProjectSlide(
                "second", "Second", tempDir.resolve("slides/second.ndpi"));
        var manifest = tempDir.resolve("selection.json");
        Files.writeString(
                manifest,
                """
                {
                  "format": "histopia-qupath-selection",
                  "schema_version": 1,
                  "slides": [
                    {"source_path": "%s"},
                    {"source_path": "%s"}
                  ]
                }
                """.formatted(first.path(), second.path()));

        assertTrue(HistopiaWorkflow.selectionManifestMatches(
                manifest, List.of(second, first)));
        assertFalse(HistopiaWorkflow.selectionManifestMatches(
                manifest,
                List.of(
                        first,
                        new HistopiaWorkflow.ProjectSlide(
                                "third", "Third", tempDir.resolve("slides/third.ndpi")))));

        Files.writeString(
                manifest,
                """
                {"format":"histopia-qupath-selection","schema_version":"1","slides":[]}
                """);
        assertFalse(HistopiaWorkflow.selectionManifestMatches(
                manifest, List.of(first, second)));
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
    void parsesOnlyConsistentWorkflowAuditSummaries() throws Exception {
        var audit = tempDir.resolve("workflow-audit.json");
        Files.writeString(
                audit,
                """
                {
                  "schema_version": 1,
                  "status": "review_required",
                  "summary": {
                    "cohort_count": 1,
                    "approved": 0,
                    "review_required": 1,
                    "incomplete": 0,
                    "invalid": 0,
                    "viewer_unmapped_count": 0
                  },
                  "cohorts": [{"id": "qupath"}],
                  "viewer_unmapped_ids": []
                }
                """);

        var summary = HistopiaWorkflow.readWorkflowAudit(audit);

        assertEquals("review_required", summary.status());
        assertEquals(1, summary.cohortCount());
        assertEquals(1, summary.reviewRequired());
        assertEquals(
                "Workflow integrity valid; scientific review required",
                summary.displayStatus());

        Files.writeString(
                audit,
                Files.readString(audit).replace(
                        "\"status\": \"review_required\"",
                        "\"status\": \"approved\""));
        assertThrows(
                IllegalArgumentException.class,
                () -> HistopiaWorkflow.readWorkflowAudit(audit));

        Files.writeString(
                audit,
                """
                {
                  "schema_version": 1,
                  "status": "approved",
                  "summary": {
                    "cohort_count": "1",
                    "approved": 1,
                    "review_required": 0,
                    "incomplete": 0,
                    "invalid": 0
                  },
                  "cohorts": [{"id": "qupath"}]
                }
                """);
        assertThrows(
                IllegalArgumentException.class,
                () -> HistopiaWorkflow.readWorkflowAudit(audit));
    }

    @Test
    void validatesExternalRegistrationSealWhenConfigured() throws Exception {
        var configured = System.getenv("HISTOPIA_VALIDATED_REGISTRATION_RUN");
        assumeTrue(configured != null && !configured.isBlank());

        var run = Path.of(configured);
        var result = JsonParser.parseString(
                Files.readString(run.resolve("registration_result.json")))
                .getAsJsonObject();
        var selected = new ArrayList<HistopiaWorkflow.ProjectSlide>();
        var slides = result.getAsJsonArray("slides");
        for (int index = slides.size() - 1; index >= 0; index--) {
            var path = Path.of(slides.get(index).getAsJsonObject().get("path").getAsString());
            selected.add(new HistopiaWorkflow.ProjectSlide(
                    Integer.toString(index), path.getFileName().toString(), path));
        }

        assertTrue(HistopiaWorkflow.registrationSealValid(run));
        assertEquals("Registration sealed", HistopiaWorkflow.registrationStatus(run));
        assertTrue(HistopiaWorkflow.registrationMatchesSelection(run, selected));
        assertFalse(HistopiaWorkflow.registrationMatchesSelection(
                run, selected.subList(1, selected.size())));
    }

    private static String sha256(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path));
        return HexFormat.of().formatHex(digest);
    }

    private static void writeSlideArtifact(
            Path path,
            Integer schemaVersion,
            String field,
            List<String> values) throws Exception {
        var payload = new JsonObject();
        if (schemaVersion != null)
            payload.addProperty("schema_version", schemaVersion);
        var slides = new JsonArray();
        for (var value : values) {
            var row = new JsonObject();
            row.addProperty(field, value);
            slides.add(row);
        }
        payload.add("slides", slides);
        Files.writeString(path, payload.toString());
    }
}
