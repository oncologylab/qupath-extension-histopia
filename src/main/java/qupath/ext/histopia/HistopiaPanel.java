package qupath.ext.histopia;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.io.PathIO;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

final class HistopiaPanel {

    private static final Logger logger = LoggerFactory.getLogger(HistopiaPanel.class);
    private final QuPathGUI qupath;
    private final Stage stage = new Stage();
    private final TextField python = new TextField("python");
    private final ListView<HistopiaWorkflow.ProjectSlide> projectSlides = new ListView<>();
    private final ComboBox<HistopiaWorkflow.ProjectSlide> projectReference = new ComboBox<>();
    private final ComboBox<String> projectOrder = new ComboBox<>();
    private final ComboBox<String> semanticDevice = new ComboBox<>();
    private final TextField workspace = new TextField();
    private final TextField modelCache = new TextField();
    private final TextField processedDimension = new TextField("1200");
    private final TextField registrationWorkers = new TextField(
            Integer.toString(Math.max(1, Runtime.getRuntime().availableProcessors() / 2)));
    private final TextField clusterMin = new TextField("5");
    private final TextField clusterMax = new TextField("15");
    private final TextField semanticBatchSize = new TextField("64");
    private final TextField patchWorkers = new TextField("1");
    private final TextField reviewer = new TextField();
    private final TextField reviewNotes = new TextField();
    private final CheckBox automaticReference = new CheckBox("Choose reference automatically");
    private final CheckBox projectAllowModelDownload =
            new CheckBox("Allow authenticated model download");
    private final TextField registrationConfig = new TextField();
    private final TextField semanticConfig = new TextField();
    private final TextField registration = new TextField();
    private final TextField semantic = new TextField();
    private final TextField output = new TextField();
    private final CheckBox allowModelDownload =
            new CheckBox("Allow authenticated model download");
    private final CheckBox includeSemantic = new CheckBox("Include semantic atlas");
    private final CheckBox replaceSemantic =
            new CheckBox("Replace existing Histopia annotations");
    private final ComboBox<Integer> clusters = new ComboBox<>();
    private final Button runRegistration = new Button("Run registration");
    private final Button runSemantic = new Button("Run semantic atlas");
    private final Button runProjectRegistration = new Button("Run registration");
    private final Button runProjectSemantic = new Button("Run semantic atlas");
    private final Button approveProjectRegistration = new Button("Approve reviewed run");
    private final Button export = new Button("Export bundle");
    private final Button cancel = new Button("Cancel");
    private final Label status = new Label("Ready");
    private final TextArea log = new TextArea();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "histopia-process");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Process activeProcess;
    private volatile boolean cancellationRequested;

    HistopiaPanel(QuPathGUI qupath) {
        this.qupath = qupath;
        stage.initOwner(qupath.getStage());
        stage.setTitle("Histopia");
        stage.setScene(new Scene(createContent()));
        stage.setMinWidth(900);
        stage.setMinHeight(620);
        projectSlides.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        projectSlides.getSelectionModel().getSelectedItems().addListener(
                (ListChangeListener<HistopiaWorkflow.ProjectSlide>) change ->
                        refreshReferenceChoices());
        projectSlides.setPrefHeight(230);
        projectOrder.getItems().setAll(
                "anchored_similarity", "similarity", "natural");
        projectOrder.setValue("anchored_similarity");
        semanticDevice.getItems().setAll("auto", "cpu", "cuda", "mps");
        semanticDevice.setValue("auto");
        automaticReference.setSelected(true);
        projectReference.setDisable(true);
        automaticReference.selectedProperty().addListener(
                (observable, oldValue, selected) ->
                        projectReference.setDisable(selected));
        refreshProjectSlides();
        includeSemantic.selectedProperty().addListener(
                (observable, oldValue, selected) -> {
                    semantic.setDisable(!selected);
                    clusters.setDisable(!selected);
                    if (selected && !semantic.getText().isBlank())
                        refreshSemanticSummary();
                });
        semantic.setDisable(true);
        clusters.setDisable(true);
        replaceSemantic.setSelected(true);
        cancel.setDisable(true);
        log.setEditable(false);
        log.setWrapText(false);
        log.setPrefRowCount(10);
    }

    void show() {
        stage.show();
        stage.toFront();
    }

    private BorderPane createContent() {
        var root = new BorderPane();
        root.setPadding(new Insets(16));

        var pythonRow = new HBox(8, new Label("Python executable"), python);
        HBox.setHgrow(python, Priority.ALWAYS);
        root.setTop(pythonRow);

        var tabs = new TabPane(
                fixedTab("Project workflow", createProjectWorkflowPane()),
                fixedTab("Run analysis", createAnalysisPane()),
                fixedTab("Export and import", createExportPane()));
        BorderPane.setMargin(tabs, new Insets(12, 0, 12, 0));
        root.setCenter(tabs);

        var statusRow = new HBox(8, status, cancel);
        var footer = new VBox(8, statusRow, log);
        VBox.setVgrow(log, Priority.ALWAYS);
        root.setBottom(footer);
        return root;
    }

    private VBox createProjectWorkflowPane() {
        var refresh = new Button("Refresh project");
        var selectAll = new Button("Select all");
        var selectNone = new Button("Clear");
        refresh.setOnAction(event -> refreshProjectSlides());
        selectAll.setOnAction(event -> projectSlides.getSelectionModel().selectAll());
        selectNone.setOnAction(event -> projectSlides.getSelectionModel().clearSelection());

        var selectionHeader = new HBox(
                8,
                new Label("Project slides"),
                refresh,
                selectAll,
                selectNone);
        var paths = configuredGrid();
        addDirectoryRow(paths, 0, "Analysis workspace", workspace, null);
        addDirectoryRow(paths, 1, "UNI2-h model cache", modelCache, null);
        paths.add(new Label("Reference"), 0, 2);
        paths.add(new VBox(4, automaticReference, projectReference), 1, 2);
        paths.add(new Label("Section order"), 0, 3);
        paths.add(projectOrder, 1, 3);

        var settings = new FlowPane(
                labeledField("Processed px", processedDimension),
                labeledField("Registration workers", registrationWorkers),
                labeledField("Device", semanticDevice),
                labeledField("K min", clusterMin),
                labeledField("K max", clusterMax),
                labeledField("Batch", semanticBatchSize),
                labeledField("Patch workers", patchWorkers));
        settings.setHgap(8);
        settings.setVgap(6);
        settings.setPrefWrapLength(820);
        runProjectRegistration.setOnAction(event -> startProjectRegistration());
        runProjectSemantic.setOnAction(event -> startProjectSemantic());
        approveProjectRegistration.setOnAction(event -> approveProjectRegistration());
        var openReview = new Button("Open registration QC");
        openReview.setOnAction(event -> openRegistrationReview());
        var review = new FlowPane(
                labeledField("Reviewer", reviewer),
                labeledField("Review notes", reviewNotes));
        review.setHgap(8);
        review.setVgap(6);
        var actions = new HBox(
                8,
                runProjectRegistration,
                openReview,
                approveProjectRegistration,
                runProjectSemantic,
                projectAllowModelDownload);
        var pane = new VBox(
                8, selectionHeader, projectSlides, paths, settings, review, actions);
        pane.setPadding(new Insets(12));
        VBox.setVgrow(projectSlides, Priority.ALWAYS);
        return pane;
    }

    private GridPane createAnalysisPane() {
        var grid = configuredGrid();
        addFileRow(grid, 0, "Registration config", registrationConfig);
        addFileRow(grid, 1, "Semantic config", semanticConfig);
        grid.add(allowModelDownload, 1, 2);
        runRegistration.setOnAction(event -> startRegistration());
        runSemantic.setOnAction(event -> startSemantic());
        grid.add(new HBox(8, runRegistration, runSemantic), 1, 3);
        return grid;
    }

    private GridPane createExportPane() {
        var grid = configuredGrid();
        addDirectoryRow(grid, 0, "Registration run", registration, null);
        addDirectoryRow(
                grid,
                1,
                "Semantic run",
                semantic,
                this::refreshSemanticSummary);
        addDirectoryRow(grid, 2, "Output bundle", output, null);
        grid.add(includeSemantic, 1, 3);
        grid.add(new Label("Semantic K"), 0, 4);
        grid.add(clusters, 1, 4);
        grid.add(replaceSemantic, 1, 5);

        var importButton = new Button("Import current slide");
        export.setOnAction(event -> exportBundle());
        importButton.setOnAction(event -> importGeoJson());
        grid.add(new HBox(8, export, importButton), 1, 6);
        return grid;
    }

    private static GridPane configuredGrid() {
        var grid = new GridPane();
        grid.setPadding(new Insets(16));
        grid.setHgap(8);
        grid.setVgap(10);
        return grid;
    }

    private static Tab fixedTab(String title, Node content) {
        var tab = new Tab(title, content);
        tab.setClosable(false);
        return tab;
    }

    private static VBox labeledField(String label, Node field) {
        return new VBox(3, new Label(label), field);
    }

    private void addDirectoryRow(
            GridPane grid,
            int row,
            String label,
            TextField field,
            Runnable afterSelection) {
        var browse = new Button("Browse");
        browse.setOnAction(event -> {
            var chooser = new DirectoryChooser();
            chooser.setTitle(label);
            var selected = chooser.showDialog(stage);
            if (selected != null) {
                field.setText(selected.toPath().toString());
                if (afterSelection != null)
                    afterSelection.run();
            }
        });
        addPathRow(grid, row, label, field, browse);
    }

    private void addFileRow(GridPane grid, int row, String label, TextField field) {
        var browse = new Button("Browse");
        browse.setOnAction(event -> {
            var chooser = new FileChooser();
            chooser.setTitle(label);
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter(
                            "Histopia config", "*.toml", "*.tml", "*.json"));
            var selected = chooser.showOpenDialog(stage);
            if (selected != null)
                field.setText(selected.toPath().toString());
        });
        addPathRow(grid, row, label, field, browse);
    }

    private static void addPathRow(
            GridPane grid,
            int row,
            String label,
            TextField field,
            Button browse) {
        field.setPrefColumnCount(42);
        GridPane.setHgrow(field, Priority.ALWAYS);
        grid.add(new Label(label), 0, row);
        grid.add(field, 1, row);
        grid.add(browse, 2, row);
    }

    private void startRegistration() {
        try {
            var command = HistopiaCommand.runRegistration(
                    python.getText(),
                    requiredFile(registrationConfig, "Registration config"));
            startJob("Registration", command);
        } catch (IllegalArgumentException error) {
            Dialogs.showErrorMessage("Histopia registration", error.getMessage());
        }
    }

    private void refreshProjectSlides() {
        var selection = HistopiaWorkflow.discover(qupath.getProject());
        projectSlides.getItems().setAll(selection.slides());
        projectSlides.getSelectionModel().selectAll();
        refreshReferenceChoices();
        var message = selection.slides().size() + " local WSI project images available";
        if (!selection.warnings().isEmpty())
            message += "; " + selection.warnings().size() + " unsupported entries skipped";
        status.setText(message);
    }

    private void refreshReferenceChoices() {
        var previous = projectReference.getValue();
        var selected = List.copyOf(
                projectSlides.getSelectionModel().getSelectedItems());
        projectReference.getItems().setAll(selected);
        if (selected.contains(previous))
            projectReference.setValue(previous);
        else if (!selected.isEmpty())
            projectReference.setValue(selected.get(0));
    }

    private void startProjectRegistration() {
        try {
            var files = prepareProjectWorkflow();
            startJob(
                    "Registration",
                    HistopiaCommand.runRegistration(
                            python.getText(), files.registrationConfig()));
        } catch (IOException | IllegalArgumentException error) {
            Dialogs.showErrorMessage("Histopia registration", error.getMessage());
        }
    }

    private void startProjectSemantic() {
        try {
            requiredPath(modelCache, "UNI2-h model cache");
            var files = prepareProjectWorkflow();
            if (!Files.isDirectory(files.registrationRun()))
                throw new IllegalArgumentException(
                        "Run and review registration in this workspace first");
            startJob(
                    "Semantic atlas",
                    HistopiaCommand.runSemantic(
                            python.getText(),
                            files.semanticConfig(),
                            projectAllowModelDownload.isSelected()));
        } catch (IOException | IllegalArgumentException error) {
            Dialogs.showErrorMessage("Histopia semantic atlas", error.getMessage());
        }
    }

    private void openRegistrationReview() {
        try {
            var run = requiredPath(workspace, "Analysis workspace")
                    .resolve("registration");
            if (!Files.isDirectory(run))
                throw new IllegalArgumentException(
                        "Run registration in this workspace first");
            if (!GuiTools.browseDirectory(run.toFile()))
                throw new IllegalStateException("Could not open the registration directory");
        } catch (IllegalArgumentException | IllegalStateException error) {
            Dialogs.showErrorMessage("Histopia registration QC", error.getMessage());
        }
    }

    private void approveProjectRegistration() {
        try {
            var run = requiredPath(workspace, "Analysis workspace")
                    .resolve("registration");
            var reviewerName = requiredText(reviewer, "Reviewer");
            var notes = requiredText(reviewNotes, "Review notes");
            startJob(
                    "Registration approval",
                    HistopiaCommand.approveRegistration(
                            python.getText(), run, reviewerName, notes));
        } catch (IllegalArgumentException error) {
            Dialogs.showErrorMessage("Histopia registration approval", error.getMessage());
        }
    }

    private HistopiaWorkflow.WorkflowFiles prepareProjectWorkflow() throws IOException {
        var selected = List.copyOf(
                projectSlides.getSelectionModel().getSelectedItems());
        var files = HistopiaWorkflow.writeConfigs(
                requiredPath(workspace, "Analysis workspace"),
                selected,
                automaticReference.isSelected() ? null : projectReference.getValue(),
                projectOrder.getValue(),
                positiveInteger(processedDimension, "Processed image dimension"),
                positiveInteger(registrationWorkers, "Registration workers"),
                optionalPath(modelCache),
                semanticDevice.getValue(),
                positiveInteger(clusterMin, "K min"),
                positiveInteger(clusterMax, "K max"),
                positiveInteger(semanticBatchSize, "Semantic batch size"),
                positiveInteger(patchWorkers, "Patch workers"));
        registrationConfig.setText(files.registrationConfig().toString());
        semanticConfig.setText(files.semanticConfig().toString());
        registration.setText(files.registrationRun().toString());
        semantic.setText(files.semanticRun().toString());
        return files;
    }

    private void startSemantic() {
        try {
            var command = HistopiaCommand.runSemantic(
                    python.getText(),
                    requiredFile(semanticConfig, "Semantic config"),
                    allowModelDownload.isSelected());
            startJob("Semantic atlas", command);
        } catch (IllegalArgumentException error) {
            Dialogs.showErrorMessage("Histopia semantic atlas", error.getMessage());
        }
    }

    private void exportBundle() {
        try {
            var semanticPath = includeSemantic.isSelected()
                    ? requiredDirectory(semantic, "Semantic run")
                    : null;
            var selectedClusters = semanticPath == null ? null : clusters.getValue();
            if (semanticPath != null && selectedClusters == null)
                throw new IllegalArgumentException(
                        "Select a semantic K after loading the semantic run");
            var command = HistopiaCommand.exportBundle(
                    python.getText(),
                    requiredDirectory(registration, "Registration run"),
                    requiredPath(output, "Output bundle"),
                    semanticPath,
                    selectedClusters);
            startJob("QuPath export", command);
        } catch (IllegalArgumentException error) {
            Dialogs.showErrorMessage("Histopia export", error.getMessage());
        }
    }

    private void refreshSemanticSummary() {
        try {
            var summary = HistopiaBundle.readSemanticSummary(
                    requiredDirectory(semantic, "Semantic run"));
            clusters.getItems().setAll(summary.clusterCounts());
            clusters.setValue(summary.selectedClusters());
            status.setText(
                    "Loaded semantic K values " + summary.clusterCounts()
                            + "; selected K=" + summary.selectedClusters());
        } catch (IOException | IllegalArgumentException error) {
            clusters.getItems().clear();
            status.setText("Could not read semantic K values");
            Dialogs.showErrorMessage("Histopia semantic result", error.getMessage());
        }
    }

    private void startJob(String name, List<String> command) {
        if (activeProcess != null) {
            Dialogs.showErrorMessage(
                    "Histopia", "Another Histopia process is already running.");
            return;
        }
        setJobRunning(true);
        cancellationRequested = false;
        status.setText(name + " running...");
        log.clear();
        appendLog("$ " + command.stream().collect(Collectors.joining(" ")));
        CompletableFuture
                .supplyAsync(() -> run(command), executor)
                .whenComplete((message, error) -> Platform.runLater(() -> {
                    setJobRunning(false);
                    if (error == null) {
                        status.setText(name + " completed");
                        Dialogs.showInfoNotification("Histopia", message);
                    } else {
                        var cause = error.getCause() == null ? error : error.getCause();
                        if (cause instanceof CancellationException) {
                            status.setText(name + " cancelled");
                        } else {
                            status.setText(name + " failed");
                            Dialogs.showErrorMessage(name, cause.getMessage());
                        }
                    }
                }));
    }

    private String run(List<String> command) {
        try {
            var process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            activeProcess = process;
            if (cancellationRequested)
                process.destroy();
            String lastLine = "";
            try (var reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lastLine = line;
                    appendLog(line);
                }
            }
            var exitCode = process.waitFor();
            if (cancellationRequested)
                throw new CancellationException("Histopia job cancelled");
            if (exitCode != 0)
                throw new IllegalStateException("Histopia exited with code " + exitCode);
            return lastLine.isBlank() ? "Histopia job completed" : lastLine;
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Could not start Histopia. Check the Python executable and installation.",
                    error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Histopia job was interrupted", error);
        } finally {
            activeProcess = null;
        }
    }

    private void appendLog(String line) {
        Platform.runLater(() -> log.appendText(line + System.lineSeparator()));
    }

    private void setJobRunning(boolean running) {
        runRegistration.setDisable(running);
        runSemantic.setDisable(running);
        runProjectRegistration.setDisable(running);
        runProjectSemantic.setDisable(running);
        approveProjectRegistration.setDisable(running);
        export.setDisable(running);
        cancel.setDisable(!running);
        cancel.setOnAction(event -> cancelActiveJob());
    }

    private void cancelActiveJob() {
        cancellationRequested = true;
        var process = activeProcess;
        if (process == null) {
            status.setText("Cancellation requested...");
            return;
        }
        status.setText("Cancelling...");
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
    }

    private void importGeoJson() {
        var imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage("Histopia import", "Open the matching source slide first.");
            return;
        }
        var chooser = new FileChooser();
        chooser.setTitle("Select histopia-qupath.json");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Histopia bundle", "*.json"));
        var selected = chooser.showOpenDialog(stage);
        if (selected == null)
            return;
        try {
            var names = new HashSet<String>();
            names.add(imageData.getServer().getMetadata().getName());
            for (var uri : imageData.getServer().getURIs()) {
                var path = uri.getPath();
                if (path != null)
                    names.add(Path.of(path).getFileName().toString());
            }
            var artifact = HistopiaBundle.findSemanticAnnotations(
                    selected.toPath(), names);
            var objects = PathIO.readObjects(artifact.path().toFile());
            var hierarchy = imageData.getHierarchy();
            if (replaceSemantic.isSelected()) {
                var existing = hierarchy.getAnnotationObjects().stream()
                        .filter(object -> object.getClassification() != null)
                        .filter(object -> object.getClassification().startsWith("Histopia K"))
                        .toList();
                hierarchy.removeObjects(existing, true);
            }
            hierarchy.addObjects(objects);
            hierarchy.fireHierarchyChangedEvent(this);
            var details = artifact.regionCount() >= 0
                    ? String.format(
                            "%d classes, %,d regions, %,d source patches",
                            objects.size(),
                            artifact.regionCount(),
                            artifact.patchCount())
                    : objects.size() + " semantic region objects";
            Dialogs.showInfoNotification("Histopia", "Imported " + details + ".");
        } catch (IOException error) {
            logger.error("Could not import Histopia GeoJSON", error);
            Dialogs.showErrorMessage("Histopia import", error.getMessage());
        }
    }

    private static Path requiredFile(TextField field, String name) {
        var path = requiredPath(field, name);
        if (!Files.isRegularFile(path))
            throw new IllegalArgumentException(name + " does not exist: " + path);
        return path;
    }

    private static Path requiredDirectory(TextField field, String name) {
        var path = requiredPath(field, name);
        if (!Files.isDirectory(path))
            throw new IllegalArgumentException(name + " does not exist: " + path);
        return path;
    }

    private static Path requiredPath(TextField field, String name) {
        if (field.getText() == null || field.getText().isBlank())
            throw new IllegalArgumentException(name + " is required");
        return Path.of(field.getText()).toAbsolutePath().normalize();
    }

    private static Path optionalPath(TextField field) {
        if (field.getText() == null || field.getText().isBlank())
            return null;
        return Path.of(field.getText()).toAbsolutePath().normalize();
    }

    private static int positiveInteger(TextField field, String name) {
        try {
            var value = Integer.parseInt(field.getText());
            if (value <= 0)
                throw new NumberFormatException();
            return value;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " must be a positive integer");
        }
    }

    private static String requiredText(TextField field, String name) {
        if (field.getText() == null || field.getText().isBlank())
            throw new IllegalArgumentException(name + " is required");
        return field.getText().strip();
    }
}
