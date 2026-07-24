package qupath.ext.histopia;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import qupath.lib.projects.Project;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class HistopiaWorkflow {

    private static final Set<String> WSI_EXTENSIONS =
            Set.of(".ndpi", ".scn", ".svs", ".tif", ".tiff");

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

    static WorkflowFiles writeConfigs(
            Path workspace,
            List<ProjectSlide> slides,
            ProjectSlide reference,
            String orderStrategy,
            int maxProcessedDimension,
            int workers,
            Path modelCache,
            String device,
            int clusterMin,
            int clusterMax,
            int batchSize,
            int patchWorkers) throws IOException {
        if (slides.size() < 2)
            throw new IllegalArgumentException("Select at least two project slides");
        if (reference != null && !slides.contains(reference))
            throw new IllegalArgumentException("Reference slide must be selected");
        requirePositive(maxProcessedDimension, "Processed image dimension");
        requirePositive(workers, "Registration workers");
        requirePositive(batchSize, "Semantic batch size");
        requirePositive(patchWorkers, "Patch workers");
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
        registration.addProperty("preprocessing_cache", true);
        registration.addProperty("wsi_only", true);
        registration.addProperty("max_processed_image_dim_px", maxProcessedDimension);
        registration.addProperty("write_processed_images", true);

        var semantic = new JsonObject();
        semantic.addProperty("registration_run", registrationRun.toString());
        semantic.addProperty("output_dir", semanticRun.toString());
        if (modelCache != null)
            semantic.addProperty(
                    "model_cache_dir", modelCache.toAbsolutePath().normalize().toString());
        semantic.addProperty("device", device);
        semantic.addProperty("cluster_min", clusterMin);
        semantic.addProperty("cluster_max", clusterMax);
        semantic.addProperty("batch_size", batchSize);
        semantic.addProperty("patch_workers", patchWorkers);

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
