package qupath.ext.histopia;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import qupath.lib.projects.Project;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class HistopiaWorkflow {

    private static final Set<String> WSI_EXTENSIONS =
            Set.of(".ndpi", ".scn", ".svs", ".tif", ".tiff");
    private static final Set<String> SECTION_ORDER_STRATEGIES =
            Set.of("anchored_similarity", "similarity", "natural");
    private static final Set<String> REGISTRATION_SEAL_ARTIFACTS = Set.of(
            "registration_result.json",
            "mask_review.json",
            "section_order_review.json");
    private static final String MASK_REVIEW_ARTIFACT = "mask_review.json";
    private static final String ORDER_REVIEW_ARTIFACT = "section_order_review.json";
    private static final String REGISTRATION_RESULT_ARTIFACT = "registration_result.json";
    private static final Set<String> REGISTRATION_PERFORMANCE_STATUSES =
            Set.of("running", "completed", "review_required", "failed", "interrupted");
    private static final Set<String> FINISHED_STAGE_STATUSES =
            Set.of("completed", "review_required");
    private static final Set<String> APPROVED_MASK_STATUSES =
            Set.of("auto_pass", "override_pass");
    private static final int MAX_AUTOMATIC_REGISTRATION_WORKERS = 4;

    private HistopiaWorkflow() {
    }

    record ProjectSlide(String projectId, String displayName, Path path) {

        ProjectSlide {
            path = path.toAbsolutePath().normalize();
        }

        String filename() {
            return path.getFileName().toString();
        }

        @Override
        public String toString() {
            return displayName + "  [" + filename() + "]";
        }
    }

    record ProjectSelection(List<ProjectSlide> slides, List<String> warnings) {

        ProjectSelection {
            slides = List.copyOf(slides);
            warnings = List.copyOf(warnings);
        }
    }

    record WorkflowFiles(
            Path registrationConfig,
            Path semanticConfig,
            Path selectionManifest,
            Path registrationRun,
            Path semanticRun) {
    }

    static ProjectSelection discover(Project<BufferedImage> project) {
        if (project == null)
            return new ProjectSelection(List.of(), List.of("Open a QuPath project first."));
        var slides = new ArrayList<ProjectSlide>();
        var warnings = new ArrayList<String>();
        for (var entry : project.getImageList()) {
            try {
                var builder = entry.getServerBuilder();
                var uris = builder == null ? null : builder.getURIs();
                if (uris == null || uris.size() != 1) {
                    warnings.add(entry.getImageName() + ": expected one source URI");
                    continue;
                }
                var uri = uris.iterator().next();
                var path = localWsiPath(uri);
                if (path == null) {
                    warnings.add(
                            entry.getImageName() + ": source is not a supported local WSI");
                    continue;
                }
                if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                    warnings.add(entry.getImageName() + ": local WSI is unavailable");
                    continue;
                }
                slides.add(new ProjectSlide(entry.getID(), entry.getImageName(), path));
            } catch (RuntimeException error) {
                warnings.add(entry.getImageName() + ": source URI could not be read");
            }
        }
        return new ProjectSelection(slides, warnings);
    }

    static ProjectSlide preferredReference(
            List<ProjectSlide> selected,
            ProjectSlide previous) {
        if (previous != null && selected.contains(previous))
            return previous;
        return selected.isEmpty() ? null : selected.get(0);
    }

    static String normalizeDevice(String device) {
        if (device == null)
            throw new IllegalArgumentException("Semantic device must not be blank");
        var normalized = device.strip().toLowerCase(Locale.ROOT);
        if (Set.of("auto", "cpu", "cuda", "mps").contains(normalized)
                || normalized.matches("cuda:[0-9]+"))
            return normalized;
        throw new IllegalArgumentException(
                "Semantic device must be auto, cpu, cuda, cuda:N, or mps");
    }

    static int defaultRegistrationWorkers(int availableProcessors) {
        return Math.max(
                1,
                Math.min(
                        MAX_AUTOMATIC_REGISTRATION_WORKERS,
                        availableProcessors / 2));
    }

    static WorkflowFiles workflowFiles(Path workspace) {
        workspace = workspace.toAbsolutePath().normalize();
        var metadataDir = workspace.resolve(".histopia");
        return new WorkflowFiles(
                metadataDir.resolve("registration-config.json"),
                metadataDir.resolve("semantic-config.json"),
                metadataDir.resolve("qupath-selection.json"),
                workspace.resolve("registration"),
                workspace.resolve("semantic"));
    }

    static WorkflowFiles writeConfigs(
            Path workspace,
            List<ProjectSlide> slides,
            ProjectSlide reference,
            String orderStrategy,
            int maxProcessedDimension,
            int workers,
            int qcWorkers,
            Path modelCache,
            String device,
            int clusterMin,
            int clusterMax,
            int batchSize,
            int patchWorkers,
            Integer vipsThreads,
            int fitThreads) throws IOException {
        if (slides.size() < 2)
            throw new IllegalArgumentException("Select at least two project slides");
        if (reference != null && !slides.contains(reference))
            throw new IllegalArgumentException("Reference slide must be selected");
        requirePositive(maxProcessedDimension, "Processed image dimension");
        requirePositive(workers, "Registration workers");
        requirePositive(qcWorkers, "QC workers");
        requireOrderStrategy(orderStrategy);
        var duplicate = slides.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        ProjectSlide::filename,
                        java.util.stream.Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(java.util.Map.Entry::getKey)
                .findFirst();
        if (duplicate.isPresent())
            throw new IllegalArgumentException(
                    "Selected slide filenames must be unique: " + duplicate.get());

        var files = workflowFiles(workspace);
        var metadataDir = files.registrationConfig().getParent();
        Files.createDirectories(metadataDir);

        var registration = new JsonObject();
        registration.addProperty("input_dir", slides.get(0).path().getParent().toString());
        registration.addProperty("output_dir", files.registrationRun().toString());
        var inputs = new JsonArray();
        slides.forEach(slide -> inputs.add(slide.path().toString()));
        registration.add("input_slides", inputs);
        registration.addProperty(
                "reference_policy", reference == null ? "best_connected" : "explicit");
        if (reference != null)
            registration.addProperty("reference_slide", reference.filename());
        registration.addProperty("section_order_strategy", orderStrategy);
        if (reference != null && "anchored_similarity".equals(orderStrategy))
            registration.addProperty(
                    "section_order_path", files.selectionManifest().toString());
        registration.addProperty("thumbnail_workers", workers);
        registration.addProperty("mask_workers", workers);
        registration.addProperty("ordering_workers", workers);
        registration.addProperty("qc_workers", qcWorkers);
        if (vipsThreads != null)
            registration.addProperty("vips_threads", vipsThreads);
        registration.addProperty("preprocessing_cache", true);
        registration.addProperty("alignment_cache", true);
        registration.addProperty("require_approved_masks", true);
        registration.addProperty("require_approved_order", true);
        registration.addProperty("wsi_only", true);
        registration.addProperty("max_processed_image_dim_px", maxProcessedDimension);
        registration.addProperty("write_processed_images", true);

        var semantic = semanticConfig(
                files,
                modelCache,
                device,
                clusterMin,
                clusterMax,
                batchSize,
                patchWorkers,
                vipsThreads,
                fitThreads);

        var selection = new JsonObject();
        selection.addProperty("format", "histopia-qupath-selection");
        selection.addProperty("schema_version", 1);
        var selectedSlides = new JsonArray();
        for (int index = 0; index < slides.size(); index++) {
            var slide = slides.get(index);
            var row = new JsonObject();
            row.addProperty("project_image_id", slide.projectId());
            row.addProperty("project_image_name", slide.displayName());
            row.addProperty("source_path", slide.path().toString());
            row.addProperty("project_order", index + 1);
            row.addProperty("reference", slide.equals(reference));
            row.addProperty("slide", slide.filename());
            row.addProperty(
                    "order",
                    "anchored_similarity".equals(orderStrategy) && slide.equals(reference)
                            ? 1
                            : 0);
            selectedSlides.add(row);
        }
        selection.add("slides", selectedSlides);

        var gson = new GsonBuilder().setPrettyPrinting().create();
        writeStringAtomic(
                files.selectionManifest(),
                gson.toJson(selection) + System.lineSeparator());
        writeStringAtomic(
                files.semanticConfig(),
                gson.toJson(semantic) + System.lineSeparator());
        writeStringAtomic(
                files.registrationConfig(),
                gson.toJson(registration) + System.lineSeparator());
        return files;
    }

    static WorkflowFiles writeSemanticConfig(
            Path workspace,
            Path modelCache,
            String device,
            int clusterMin,
            int clusterMax,
            int batchSize,
            int patchWorkers,
            Integer vipsThreads,
            int fitThreads) throws IOException {
        var files = workflowFiles(workspace);
        Files.createDirectories(files.semanticConfig().getParent());
        var semantic = semanticConfig(
                files,
                modelCache,
                device,
                clusterMin,
                clusterMax,
                batchSize,
                patchWorkers,
                vipsThreads,
                fitThreads);
        var gson = new GsonBuilder().setPrettyPrinting().create();
        writeStringAtomic(
                files.semanticConfig(),
                gson.toJson(semantic) + System.lineSeparator());
        return files;
    }

    static String registrationStatus(Path registrationRun) {
        var currentExecution = currentRegistrationExecutionStatus(registrationRun);
        if (currentExecution != null)
            return currentExecution;
        if (Files.isRegularFile(registrationRun.resolve("registration_approval.json")))
            return registrationSealValid(registrationRun)
                    ? "Registration sealed"
                    : "Registration seal stale; final review required";
        if (Files.isRegularFile(registrationRun.resolve("registration_result.json")))
            return "Registration completed; final review required";
        if (Files.isRegularFile(registrationRun.resolve("section_order_review.json")))
            return "Section order review required";
        if (Files.isRegularFile(registrationRun.resolve("mask_review.json")))
            return "Tissue mask review required";
        return "Registration completed without review artifacts";
    }

    static boolean registrationStageMatchesSelection(
            Path registrationRun,
            String artifactName,
            List<ProjectSlide> selectedSlides) {
        if (selectedSlides.size() < 2
                || !currentRegistrationStageArtifacts(registrationRun)
                        .contains(artifactName))
            return false;
        var expected = new HashSet<String>();
        for (var slide : selectedSlides) {
            if (!expected.add(slide.filename()))
                return false;
        }
        try {
            var masksMatch = artifactFilenamesMatch(
                    registrationRun.resolve(MASK_REVIEW_ARTIFACT),
                    2,
                    "slide",
                    expected);
            if (MASK_REVIEW_ARTIFACT.equals(artifactName))
                return masksMatch;
            if (!masksMatch)
                return false;
            var orderMatches = artifactFilenamesMatch(
                    registrationRun.resolve(ORDER_REVIEW_ARTIFACT),
                    3,
                    "slide",
                    expected);
            if (ORDER_REVIEW_ARTIFACT.equals(artifactName))
                return orderMatches;
            if (REGISTRATION_RESULT_ARTIFACT.equals(artifactName))
                return orderMatches
                        && registrationMatchesSelection(registrationRun, selectedSlides);
            return false;
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }

    static boolean registrationSealValid(Path registrationRun) {
        try {
            var approval = readObject(registrationRun.resolve("registration_approval.json"));
            if (!hasIntegerValue(approval, "schema_version", 1))
                return false;
            var artifacts = approval.getAsJsonObject("artifacts");
            if (artifacts == null || !artifacts.keySet().equals(REGISTRATION_SEAL_ARTIFACTS))
                return false;
            for (var name : REGISTRATION_SEAL_ARTIFACTS) {
                if (!hasNonblankString(artifacts, name))
                    return false;
                var artifact = registrationRun.resolve(name);
                if (!Files.isRegularFile(artifact)
                        || !sha256(artifact).equals(artifacts.get(name).getAsString()))
                    return false;
            }

            var order = readObject(registrationRun.resolve("section_order_review.json"));
            if (!hasBooleanValue(order, "approved", true)
                    || !hasNonblankString(order, "fingerprint")
                    || !hasNonblankString(approval, "order_fingerprint")
                    || !order.get("fingerprint").getAsString()
                            .equals(approval.get("order_fingerprint").getAsString()))
                return false;

            var result = readObject(registrationRun.resolve("registration_result.json"));
            var slides = result.getAsJsonArray("slides");
            if (slides == null
                    || slides.isEmpty()
                    || !hasIntegerValue(approval, "slide_count", slides.size()))
                return false;
            for (var element : slides) {
                if (!element.isJsonObject())
                    return false;
                var maskReview = element.getAsJsonObject().getAsJsonObject("mask_review");
                if (maskReview == null
                        || !hasNonblankString(maskReview, "status")
                        || !APPROVED_MASK_STATUSES.contains(
                                maskReview.get("status").getAsString()))
                    return false;
            }
            return hasNonblankString(approval, "reviewer")
                    && hasNonblankString(approval, "reviewed_at");
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }

    static boolean registrationMatchesSelection(
            Path registrationRun,
            List<ProjectSlide> selectedSlides) {
        if (selectedSlides.size() < 2)
            return false;
        var expected = new HashSet<Path>();
        for (var slide : selectedSlides) {
            if (!expected.add(slide.path()))
                return false;
        }
        try {
            var result = readObject(registrationRun.resolve("registration_result.json"));
            var slides = result.getAsJsonArray("slides");
            if (slides == null || slides.size() != expected.size())
                return false;
            var actual = new HashSet<Path>();
            for (var element : slides) {
                if (!element.isJsonObject())
                    return false;
                var row = element.getAsJsonObject();
                if (!hasNonblankString(row, "path"))
                    return false;
                final Path path;
                try {
                    path = Path.of(row.get("path").getAsString())
                            .toAbsolutePath()
                            .normalize();
                } catch (RuntimeException error) {
                    return false;
                }
                if (!actual.add(path))
                    return false;
            }
            return actual.equals(expected);
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }

    static boolean selectionManifestMatches(
            Path selectionManifest,
            List<ProjectSlide> selectedSlides) {
        if (selectedSlides.size() < 2)
            return false;
        var expected = new HashSet<Path>();
        for (var slide : selectedSlides) {
            if (!expected.add(slide.path()))
                return false;
        }
        try {
            var manifest = readObject(selectionManifest);
            if (!hasIntegerValue(manifest, "schema_version", 1)
                    || !hasNonblankString(manifest, "format")
                    || !"histopia-qupath-selection".equals(
                            manifest.get("format").getAsString()))
                return false;
            var slides = manifest.getAsJsonArray("slides");
            if (slides == null || slides.size() != expected.size())
                return false;
            var actual = new HashSet<Path>();
            for (var element : slides) {
                if (!element.isJsonObject())
                    return false;
                var row = element.getAsJsonObject();
                if (!hasNonblankString(row, "source_path"))
                    return false;
                final Path path;
                try {
                    path = Path.of(row.get("source_path").getAsString())
                            .toAbsolutePath()
                            .normalize();
                } catch (RuntimeException error) {
                    return false;
                }
                if (!actual.add(path))
                    return false;
            }
            return actual.equals(expected);
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }

    private static String currentRegistrationExecutionStatus(Path registrationRun) {
        var performance = validRegistrationPerformance(registrationRun);
        if (performance == null
                || !"review_required".equals(performance.get("status").getAsString())
                || !hasNonblankString(performance, "review_stage"))
            return null;
        return switch (performance.get("review_stage").getAsString()) {
            case "masks" -> "Tissue mask review required";
            case "order" -> "Section order review required";
            default -> null;
        };
    }

    private static Set<String> currentRegistrationStageArtifacts(
            Path registrationRun) {
        var performance = validRegistrationPerformance(registrationRun);
        if (performance == null)
            return REGISTRATION_SEAL_ARTIFACTS;
        var status = performance.get("status").getAsString();
        if ("completed".equals(status))
            return REGISTRATION_SEAL_ARTIFACTS;
        if ("review_required".equals(status)
                && hasNonblankString(performance, "review_stage")) {
            return switch (performance.get("review_stage").getAsString()) {
                case "masks" -> Set.of(MASK_REVIEW_ARTIFACT);
                case "order" -> Set.of(MASK_REVIEW_ARTIFACT, ORDER_REVIEW_ARTIFACT);
                default -> Set.of();
            };
        }
        var reached = new HashSet<String>();
        if (hasStageStatus(performance, "mask_review", FINISHED_STAGE_STATUSES))
            reached.add(MASK_REVIEW_ARTIFACT);
        if (hasStageStatus(
                performance, "section_ordering", FINISHED_STAGE_STATUSES)) {
            reached.add(MASK_REVIEW_ARTIFACT);
            reached.add(ORDER_REVIEW_ARTIFACT);
        }
        if (hasStageStatus(performance, "result_write", Set.of("completed")))
            reached.addAll(REGISTRATION_SEAL_ARTIFACTS);
        return Set.copyOf(reached);
    }

    private static JsonObject validRegistrationPerformance(Path registrationRun) {
        try {
            var performance = readObject(
                    registrationRun.resolve("registration_performance.json"));
            var stages = performance.get("stages");
            if (!hasIntegerValue(performance, "schema_version", 1)
                    || !hasNonblankString(performance, "workflow")
                    || !"registration".equals(performance.get("workflow").getAsString())
                    || !hasBooleanValue(performance, "observational_only", true)
                    || !hasNonblankString(performance, "status")
                    || !REGISTRATION_PERFORMANCE_STATUSES.contains(
                            performance.get("status").getAsString())
                    || stages == null
                    || !stages.isJsonObject())
                return null;
            return performance;
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    private static boolean hasStageStatus(
            JsonObject performance,
            String stageName,
            Set<String> statuses) {
        var stages = performance.getAsJsonObject("stages");
        var stage = stages.get(stageName);
        return stage != null
                && stage.isJsonObject()
                && hasNonblankString(stage.getAsJsonObject(), "status")
                && statuses.contains(
                        stage.getAsJsonObject().get("status").getAsString());
    }

    private static boolean artifactFilenamesMatch(
            Path artifact,
            int schemaVersion,
            String field,
            Set<String> expected) throws IOException {
        var payload = readObject(artifact);
        if (!hasIntegerValue(payload, "schema_version", schemaVersion))
            return false;
        var slides = payload.getAsJsonArray("slides");
        if (slides == null || slides.size() != expected.size())
            return false;
        var actual = new HashSet<String>();
        for (var element : slides) {
            if (!element.isJsonObject())
                return false;
            var row = element.getAsJsonObject();
            if (!hasNonblankString(row, field))
                return false;
            final String filename;
            try {
                filename = Path.of(row.get(field).getAsString())
                        .getFileName()
                        .toString();
            } catch (RuntimeException error) {
                return false;
            }
            if (!actual.add(filename))
                return false;
        }
        return actual.equals(expected);
    }

    private static JsonObject readObject(Path path) throws IOException {
        var element = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
        if (!element.isJsonObject())
            throw new IllegalArgumentException("JSON root must be an object: " + path);
        return element.getAsJsonObject();
    }

    private static boolean hasNonblankString(JsonObject object, String key) {
        var value = object.get(key);
        return value != null
                && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isString()
                && !value.getAsString().isBlank();
    }

    private static boolean hasIntegerValue(JsonObject object, String key, int expected) {
        var value = object.get(key);
        return value != null
                && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isNumber()
                && value.getAsBigDecimal().compareTo(java.math.BigDecimal.valueOf(expected)) == 0;
    }

    private static boolean hasBooleanValue(
            JsonObject object,
            String key,
            boolean expected) {
        var value = object.get(key);
        return value != null
                && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean() == expected;
    }

    private static String sha256(Path path) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
        try (InputStream input = Files.newInputStream(path)) {
            var buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0)
                    digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static Path localWsiPath(URI uri) {
        if (uri == null || !"file".equalsIgnoreCase(uri.getScheme()))
            return null;
        try {
            var path = Path.of(uri).toAbsolutePath().normalize();
            var filename = path.getFileName();
            if (filename == null)
                return null;
            var name = filename.toString().toLowerCase(Locale.ROOT);
            if (WSI_EXTENSIONS.stream().noneMatch(name::endsWith))
                return null;
            return path;
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static JsonObject semanticConfig(
            WorkflowFiles files,
            Path modelCache,
            String device,
            int clusterMin,
            int clusterMax,
            int batchSize,
            int patchWorkers,
            Integer vipsThreads,
            int fitThreads) {
        requirePositive(batchSize, "Semantic batch size");
        requirePositive(patchWorkers, "Patch workers");
        if (vipsThreads != null)
            requirePositive(vipsThreads, "VIPS threads");
        requirePositive(fitThreads, "Semantic fit threads");
        if (clusterMin <= 1 || clusterMax < clusterMin)
            throw new IllegalArgumentException("Semantic K range is invalid");

        var semantic = new JsonObject();
        semantic.addProperty("registration_run", files.registrationRun().toString());
        semantic.addProperty("output_dir", files.semanticRun().toString());
        if (modelCache != null)
            semantic.addProperty(
                    "model_cache_dir", modelCache.toAbsolutePath().normalize().toString());
        semantic.addProperty("device", normalizeDevice(device));
        semantic.addProperty("cluster_min", clusterMin);
        semantic.addProperty("cluster_max", clusterMax);
        semantic.addProperty("batch_size", batchSize);
        semantic.addProperty("patch_workers", patchWorkers);
        if (vipsThreads != null)
            semantic.addProperty("vips_threads", vipsThreads);
        semantic.addProperty("fit_threads", fitThreads);
        return semantic;
    }

    private static void writeStringAtomic(Path path, String text) throws IOException {
        if (Files.isRegularFile(path)
                && Files.readString(path, StandardCharsets.UTF_8).equals(text))
            return;
        var temporary = Files.createTempFile(
                path.getParent(),
                "." + path.getFileName() + ".",
                ".tmp");
        var moved = false;
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved)
                Files.deleteIfExists(temporary);
        }
    }

    private static void requireOrderStrategy(String value) {
        if (!SECTION_ORDER_STRATEGIES.contains(value))
            throw new IllegalArgumentException(
                    "Section order must be anchored_similarity, similarity, or natural");
    }

    private static void requirePositive(int value, String label) {
        if (value <= 0)
            throw new IllegalArgumentException(label + " must be positive");
    }
}
