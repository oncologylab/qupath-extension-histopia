package qupath.ext.histopia;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
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
        stage.setMinWidth(760);
        stage.setMinHeight(620);
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

    private static Tab fixedTab(String title, GridPane content) {
        var tab = new Tab(title, content);
        tab.setClosable(false);
        return tab;
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
}
