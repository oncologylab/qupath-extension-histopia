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
import java.nio.file.Files;
import java.nio.file.Path;
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
    private static final Set<String> REGISTRATION_SEAL_ARTIFACTS = Set.of(
            "registration_result.json",
            "mask_review.json",
            "section_order_review.json");
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
            var uris = entry.getServerBuilder().getURIs();
            if (uris.size() != 1) {
                warnings.add(entry.getImageName() + ": expected one source URI");
                continue;
            }
            var uri = uris.iterator().next();
            var path = localWsiPath(uri);
            if (path == null) {
                warnings.add(entry.getImageName() + ": source is not a supported local WSI");
                continue;
            }
            slides.add(new ProjectSlide(entry.getID(), entry.getImageName(), path));
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
        requirePositive(batchSize, "Semantic batch size");
        requirePositive(patchWorkers, "Patch workers");
        if (vipsThreads != null)
            requirePositive(vipsThreads, "VIPS threads");
        requirePositive(fitThreads, "Semantic fit threads");
        if (clusterMin <= 1 || clusterMax < clusterMin)
            throw new IllegalArgumentException("Semantic K range is invalid");
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

        workspace = workspace.toAbsolutePath().normalize();
        var metadataDir = workspace.resolve(".histopia");
        var registrationRun = workspace.resolve("registration");
        var semanticRun = workspace.resolve("semantic");
        Files.createDirectories(metadataDir);
        var selectionPath = metadataDir.resolve("qupath-selection.json");

        var registration = new JsonObject();
        registration.addProperty("input_dir", slides.get(0).path().getParent().toString());
        registration.addProperty("output_dir", registrationRun.toString());
        var inputs = new JsonArray();
        slides.forEach(slide -> inputs.add(slide.path().toString()));
        registration.add("input_slides", inputs);
        registration.addProperty(
                "reference_policy", reference == null ? "best_connected" : "explicit");
        if (reference != null)
            registration.addProperty("reference_slide", reference.filename());
        registration.addProperty("section_order_strategy", orderStrategy);
        if (reference != null && "anchored_similarity".equals(orderStrategy))
            registration.addProperty("section_order_path", selectionPath.toString());
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

        var semantic = new JsonObject();
        semantic.addProperty("registration_run", registrationRun.toString());
        semantic.addProperty("output_dir", semanticRun.toString());
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
        var registrationPath = metadataDir.resolve("registration-config.json");
        var semanticPath = metadataDir.resolve("semantic-config.json");
        Files.writeString(
                registrationPath,
                gson.toJson(registration) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        Files.writeString(
                semanticPath,
                gson.toJson(semantic) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        Files.writeString(
                selectionPath,
                gson.toJson(selection) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        return new WorkflowFiles(
                registrationPath,
                semanticPath,
                selectionPath,
                registrationRun,
                semanticRun);
    }

    static String registrationStatus(Path registrationRun) {
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

    private static Path localWsiPath(URI uri) {
        if (!"file".equalsIgnoreCase(uri.getScheme()))
            return null;
        var path = Path.of(uri).toAbsolutePath().normalize();
        var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (WSI_EXTENSIONS.stream().noneMatch(name::endsWith))
            return null;
        return path;
    }

    private static void requirePositive(int value, String label) {
        if (value <= 0)
            throw new IllegalArgumentException(label + " must be positive");
    }
}
