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
import javafx.scene.control.Tooltip;
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
import qupath.lib.projects.Project;

import java.awt.image.BufferedImage;
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

final class HistopiaPanel {

    private static final Logger logger = LoggerFactory.getLogger(HistopiaPanel.class);
    private static final int REVIEW_WORKERS = 4;
    private final QuPathGUI qupath;
    private final Stage stage = new Stage();
    private TabPane tabs;
    private Project<BufferedImage> loadedProject;
    private final TextField python = new TextField("python");
    private final ListView<HistopiaWorkflow.ProjectSlide> projectSlides = new ListView<>();
    private final ComboBox<HistopiaWorkflow.ProjectSlide> projectReference = new ComboBox<>();
    private final ComboBox<String> projectOrder = new ComboBox<>();
    private final ComboBox<String> semanticDevice = new ComboBox<>();
    private final TextField workspace = new TextField();
    private final TextField modelCache = new TextField();
    private final TextField processedDimension = new TextField("1200");
    private final TextField registrationWorkers = new TextField(
            Integer.toString(HistopiaWorkflow.defaultRegistrationWorkers(
                    Runtime.getRuntime().availableProcessors())));
    private final TextField qcWorkers = new TextField(
            Integer.toString(HistopiaWorkflow.defaultRegistrationWorkers(
                    Runtime.getRuntime().availableProcessors())));
    private final TextField clusterMin = new TextField("5");
    private final TextField clusterMax = new TextField("15");
    private final TextField semanticBatchSize = new TextField("64");
    private final TextField patchWorkers = new TextField("1");
    private final TextField vipsThreads = new TextField();
    private final TextField fitThreads = new TextField("4");
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
    private final Button checkEnvironment = new Button("Check environment");
    private final Button runProjectRegistration = new Button("Run registration");
    private final Button runProjectSemantic = new Button("Run semantic atlas");
    private final Button openProjectReview = new Button("Open registration QC");
    private final Button openProjectSemanticReview = new Button("Open semantic QC");
    private final Button approveProjectMasks = new Button("Approve masks");
    private final Button approveProjectOrder = new Button("Approve order");
    private final Button approveProjectRegistration = new Button("Seal reviewed run");
    private final Button approveProjectSemantic = new Button("Approve semantic");
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
    private volatile boolean jobRunning;

    HistopiaPanel(QuPathGUI qupath) {
        this.qupath = qupath;
        stage.initOwner(qupath.getStage());
        stage.setTitle("Histopia");
        projectSlides.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        projectSlides.getSelectionModel().getSelectedItems().addListener(
                (ListChangeListener<HistopiaWorkflow.ProjectSlide>) change ->
                        refreshReferenceChoices());
        projectSlides.setMinHeight(80);
        projectSlides.setPrefHeight(110);
        projectOrder.getItems().setAll(
                "anchored_similarity", "similarity", "natural");
        projectOrder.setValue("anchored_similarity");
        semanticDevice.getItems().setAll("auto", "cpu", "cuda", "cuda:0", "mps");
        semanticDevice.setEditable(true);
        semanticDevice.setValue("auto");
        semanticDevice.setTooltip(new Tooltip(
                "UNI2-h feature-extraction backend; the global atlas fit remains on CPU"));
        vipsThreads.setPromptText("adaptive");
        vipsThreads.setTooltip(new Tooltip(
                "Optional native libvips worker cap for registration and semantic "
                        + "WSI reads; leave blank for adaptive"));
        fitThreads.setTooltip(new Tooltip(
                "Native CPU BLAS/OpenMP threads used for global atlas fitting"));
        registrationWorkers.setTooltip(new Tooltip(
                "Worker cap for thumbnail, tissue-mask, and section-order preparation"));
        qcWorkers.setTooltip(new Tooltip(
                "Separate worker cap for memory-heavier registration QC rendering"));
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
        log.setMinHeight(50);
        log.setPrefHeight(80);
        log.setMaxHeight(120);
        stage.setScene(new Scene(createContent(), 980, 760));
        stage.setMinWidth(900);
        stage.setMinHeight(620);
    }

    void show() {
        refreshProjectSlides();
        stage.show();
        stage.toFront();
    }

    private BorderPane createContent() {
        var root = new BorderPane();
        root.setPadding(new Insets(16));

        var pythonRow = new HBox(8, new Label("Python executable"), python);
        HBox.setHgrow(python, Priority.ALWAYS);
        root.setTop(pythonRow);

        tabs = new TabPane(
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
        paths.add(new HBox(8, automaticReference, projectReference), 1, 2);
        paths.add(new Label("Section order"), 0, 3);
        paths.add(projectOrder, 1, 3);

        var settings = new FlowPane(
                labeledField("Processed px", processedDimension),
                labeledField("Registration workers", registrationWorkers),
                labeledField("QC workers", qcWorkers),
                labeledField("Device", semanticDevice),
                labeledField("K min", clusterMin),
                labeledField("K max", clusterMax),
                labeledField("Batch", semanticBatchSize),
                labeledField("Patch workers", patchWorkers),
                labeledField("VIPS threads", vipsThreads),
                labeledField("Fit threads", fitThreads));
        settings.setHgap(8);
        settings.setVgap(6);
        settings.setPrefWrapLength(820);
        runProjectRegistration.setOnAction(event -> startProjectRegistration());
        runProjectSemantic.setOnAction(event -> startProjectSemantic());
        checkEnvironment.setOnAction(event -> inspectEnvironment());
        approveProjectMasks.setOnAction(event -> approveProjectMasks());
        approveProjectOrder.setOnAction(event -> approveProjectOrder());
        approveProjectRegistration.setOnAction(event -> approveProjectRegistration());
        openProjectReview.setOnAction(event -> openRegistrationReview());
        openProjectSemanticReview.setOnAction(event -> openSemanticReview());
        approveProjectSemantic.setOnAction(event -> approveProjectSemantic());
        var review = new FlowPane(
                labeledField("Reviewer", reviewer),
                labeledField("Review notes", reviewNotes));
        review.setHgap(8);
        review.setVgap(6);
        var actions = new FlowPane(
                8, 6,
                runProjectRegistration,
                openProjectReview,
                approveProjectMasks,
                approveProjectOrder,
                approveProjectRegistration,
                runProjectSemantic,
                openProjectSemanticReview,
                approveProjectSemantic,
                checkEnvironment,
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
        grid.setPadding(new Insets(10));
        grid.setHgap(8);
        grid.setVgap(8);
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
            startCheckedJob("Registration", "registration", command, null);
        } catch (IllegalArgumentException error) {
            Dialogs.showErrorMessage("Histopia registration", error.getMessage());
        }
    }

    private void refreshProjectSlides() {
        var project = qupath.getProject();
        var preserveSelection = project != null && project == loadedProject;
        var selectedPaths = projectSlides.getSelectionModel().getSelectedItems().stream()
                .map(HistopiaWorkflow.ProjectSlide::path)
                .collect(java.util.stream.Collectors.toSet());
        var previousReference = projectReference.getValue();
        var selection = HistopiaWorkflow.discover(project);
        projectSlides.getItems().setAll(selection.slides());
        if (preserveSelection) {
            for (var slide : selection.slides()) {
                if (selectedPaths.contains(slide.path()))
                    projectSlides.getSelectionModel().select(slide);
            }
        } else {
            projectSlides.getSelectionModel().selectAll();
        }
        loadedProject = project;
        refreshReferenceChoices();
        if (previousReference != null
                && projectReference.getItems().contains(previousReference))
            projectReference.setValue(previousReference);
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
        projectReference.setValue(
                HistopiaWorkflow.preferredReference(selected, previous));
    }

    private void startProjectRegistration() {
        try {
            var files = prepareProjectRegistrationWorkflow();
            startCheckedJob(
                    "Registration",
                    "registration",
                    HistopiaCommand.runRegistration(
                            python.getText(), files.registrationConfig()),
                    () -> status.setText(
                            HistopiaWorkflow.registrationStatus(files.registrationRun())));
        } catch (IOException | IllegalArgumentException error) {
            Dialogs.showErrorMessage("Histopia registration", error.getMessage());
        }
    }

    private void startProjectSemantic() {
        try {
            var workspacePath = requiredPath(workspace, "Analysis workspace");
            var selected = List.copyOf(
                    projectSlides.getSelectionModel().getSelectedItems());
            var files = HistopiaWorkflow.workflowFiles(workspacePath);
            if (!HistopiaWorkflow.selectionManifestMatches(
                    files.selectionManifest(), selected))
                throw new IllegalArgumentException(
                        "Selected project slides do not match the prepared registration; "
                                + "select the original cohort or rerun registration");
            if (!HistopiaWorkflow.registrationSealValid(files.registrationRun()))
                throw new IllegalArgumentException(
                        "Registration seal is missing or stale; review and seal "
                                + "the exact current result before semantic analysis");
            if (!HistopiaWorkflow.registrationMatchesSelection(
                    files.registrationRun(), selected))
                throw new IllegalArgumentException(
                        "Selected project slides do not match the sealed registration; "
                                + "rerun registration for this selection before semantic analysis");
            files = HistopiaWorkflow.writeSemanticConfig(
                    workspacePath,
                    requiredPath(modelCache, "UNI2-h model cache"),
                    selectedSemanticDevice(),
                    positiveInteger(clusterMin, "K min"),
                    positiveInteger(clusterMax, "K max"),
                    positiveInteger(semanticBatchSize, "Semantic batch size"),
                    positiveInteger(patchWorkers, "Patch workers"),
                    optionalPositiveInteger(vipsThreads, "VIPS threads"),
                    positiveInteger(fitThreads, "Semantic fit threads"));
            semanticConfig.setText(files.semanticConfig().toString());
            semantic.setText(files.semanticRun().toString());
            startCheckedJob(
                    "Semantic atlas",
                    "semantic",
                    HistopiaCommand.runSemantic(
                            python.getText(),
                            files.semanticConfig(),
                            projectAllowModelDownload.isSelected()),
                    () -> status.setText(
                            "Semantic atlas completed; scientific review required"));
        } catch (IOException | IllegalArgumentException error) {
            Dialogs.showErrorMessage("Histopia semantic atlas", error.getMessage());
        }
    }

    private void openRegistrationReview() {
        try {
            var workspacePath = requiredPath(workspace, "Analysis workspace");
            var run = workspacePath.resolve("registration");
            if (!Files.isRegularFile(run.resolve("mask_review.json")))
                throw new IllegalArgumentException(
                        "Run registration once to prepare tissue masks");
            var output = workspacePath.resolve(".histopia").resolve("registration-review");
            var index = output.resolve("index.html");
            startJob(
                    "Registration QC",
                    HistopiaCommand.buildRegistrationReview(
                            python.getText(),
                            run,
                            output,
                            REVIEW_WORKERS),
                    () -> {
                        if (!GuiTools.browseURI(index.toUri()))
                            throw new IllegalStateException(
                                    "Could not open the registration review");
                    });
        } catch (IllegalArgumentException error) {
            Dialogs.showErrorMessage("Histopia registration QC", error.getMessage());
        }
    }

    private void approveProjectMasks() {
        approveProjectStage(
                "Mask approval",
                "mask_review.json",
                HistopiaCommand::approveMasks);
    }

    private void approveProjectOrder() {
        approveProjectStage(
                "Order approval",
                "section_order_review.json",
                HistopiaCommand::approveOrder);
    }

    private void approveProjectRegistration() {
        approveProjectStage(
                "Registration approval",
                "registration_result.json",
                HistopiaCommand::approveRegistration);
    }

    private void openSemanticReview() {
        try {
            var workspacePath = requiredPath(workspace, "Analysis workspace");
            var registrationRun = workspacePath.resolve("registration");
            var semanticRun = workspacePath.resolve("semantic");
            if (!Files.isRegularFile(semanticRun.resolve("semantic_result.json")))
                throw new IllegalArgumentException(
                        "Run the semantic atlas before opening semantic QC");
            requireCurrentProjectSelection(workspacePath, registrationRun);
            var output = workspacePath.resolve(".histopia").resolve("semantic-review");
            startJob(
                    "Semantic QC",
                    HistopiaCommand.buildSemanticReview(
                            python.getText(),
                            registrationRun,
                            semanticRun,
                            output,
                            REVIEW_WORKERS),
                    () -> {
                        final java.net.URI reviewUri;
                        try {
                            reviewUri = HistopiaLocalServer.serve(output)
                                    .resolve("histopia/index.html");
                        } catch (IOException error) {
                            throw new IllegalStateException(
                                    "Could not start the local semantic review server",
                                    error);
                        }
                        if (!GuiTools.browseURI(reviewUri))
                            throw new IllegalStateException(
                                    "Could not open the semantic review");
                    });
        } catch (IllegalArgumentException error) {
            Dialogs.showErrorMessage("Histopia semantic QC", error.getMessage());
        }
    }

    private void approveProjectSemantic() {
        try {
            var workspacePath = requiredPath(workspace, "Analysis workspace");
            var registrationRun = workspacePath.resolve("registration");
            var semanticRun = workspacePath.resolve("semantic");
            if (!Files.isRegularFile(semanticRun.resolve("semantic_result.json")))
                throw new IllegalArgumentException(
                        "Run the semantic atlas before approving it");
            requireCurrentProjectSelection(workspacePath, registrationRun);
            startJob(
                    "Semantic approval",
                    HistopiaCommand.approveSemantic(
                            python.getText(),
                            semanticRun,
                            requiredText(reviewer, "Reviewer"),
                            requiredText(reviewNotes, "Review notes")));
        } catch (IllegalArgumentException error) {
            Dialogs.showErrorMessage("Histopia semantic approval", error.getMessage());
        }
    }

    private void requireCurrentProjectSelection(
            Path workspacePath,
            Path registrationRun) {
        var selected = List.copyOf(
                projectSlides.getSelectionModel().getSelectedItems());
        if (!HistopiaWorkflow.selectionManifestMatches(
                workspacePath.resolve(".histopia").resolve("qupath-selection.json"),
                selected)
                || !HistopiaWorkflow.registrationMatchesSelection(
                        registrationRun, selected))
            throw new IllegalArgumentException(
                    "Selected project slides do not match this workflow result");
    }

    private void approveProjectStage(
            String name,
            String requiredArtifact,
            ApprovalCommand command) {
        try {
            var workspacePath = requiredPath(workspace, "Analysis workspace");
            var run = workspacePath.resolve("registration");
            if (!Files.isRegularFile(run.resolve(requiredArtifact)))
                throw new IllegalArgumentException(
                        "Run registration to prepare " + requiredArtifact);
            if (!HistopiaWorkflow.selectionManifestMatches(
                    workspacePath.resolve(".histopia").resolve("qupath-selection.json"),
                    List.copyOf(projectSlides.getSelectionModel().getSelectedItems())))
                throw new IllegalArgumentException(
                        "Selected project slides do not match the prepared registration; "
                                + "select the original cohort or rerun registration");
            var reviewerName = requiredText(reviewer, "Reviewer");
            var notes = requiredText(reviewNotes, "Review notes");
            startJob(
                    name,
                    command.build(
                            python.getText(), run, reviewerName, notes));
        } catch (IllegalArgumentException error) {
            Dialogs.showErrorMessage(name, error.getMessage());
        }
    }

    @FunctionalInterface
    private interface ApprovalCommand {
        List<String> build(String python, Path run, String reviewer, String notes);
    }

    private HistopiaWorkflow.WorkflowFiles prepareProjectRegistrationWorkflow()
            throws IOException {
        var selected = List.copyOf(
                projectSlides.getSelectionModel().getSelectedItems());
        var files = HistopiaWorkflow.writeConfigs(
                requiredPath(workspace, "Analysis workspace"),
                selected,
                automaticReference.isSelected() ? null : projectReference.getValue(),
                projectOrder.getValue(),
                positiveInteger(processedDimension, "Processed image dimension"),
                positiveInteger(registrationWorkers, "Registration workers"),
                positiveInteger(qcWorkers, "QC workers"),
                optionalPath(modelCache),
                selectedSemanticDevice(),
                positiveInteger(clusterMin, "K min"),
                positiveInteger(clusterMax, "K max"),
                positiveInteger(semanticBatchSize, "Semantic batch size"),
                positiveInteger(patchWorkers, "Patch workers"),
                optionalPositiveInteger(vipsThreads, "VIPS threads"),
                positiveInteger(fitThreads, "Semantic fit threads"));
        registrationConfig.setText(files.registrationConfig().toString());
        semanticConfig.setText(files.semanticConfig().toString());
        registration.setText(files.registrationRun().toString());
        semantic.setText(files.semanticRun().toString());
        return files;
    }

    private void inspectEnvironment() {
        try {
            startJob(
                    "Environment check",
                    HistopiaCommand.inspectEnvironment(
                            python.getText(), selectedSemanticDevice(), "full"));
        } catch (IllegalArgumentException error) {
            Dialogs.showErrorMessage("Histopia environment", error.getMessage());
        }
    }

    private String selectedSemanticDevice() {
        var editorValue = semanticDevice.getEditor().getText();
        return HistopiaWorkflow.normalizeDevice(
                editorValue == null || editorValue.isBlank()
                        ? semanticDevice.getValue()
                        : editorValue);
    }

    private void startSemantic() {
        try {
            var command = HistopiaCommand.runSemantic(
                    python.getText(),
                    requiredFile(semanticConfig, "Semantic config"),
                    allowModelDownload.isSelected());
            startCheckedJob("Semantic atlas", "semantic", command, null);
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
            startCheckedJob("QuPath export", "interchange", command, null);
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
        startJob(name, command, null);
    }

    private void startCheckedJob(
            String name,
            String workflow,
            List<String> command,
            Runnable onSuccess) {
        var preflight = HistopiaCommand.inspectEnvironment(
                python.getText(), selectedSemanticDevice(), workflow);
        startJobs(name, List.of(preflight, command), onSuccess);
    }

    private void startJob(
            String name,
            List<String> command,
            Runnable onSuccess) {
        startJobs(name, List.of(command), onSuccess);
    }

    private void startJobs(
            String name,
            List<List<String>> commands,
            Runnable onSuccess) {
        if (jobRunning) {
            Dialogs.showErrorMessage(
                    "Histopia", "Another Histopia process is already running.");
            return;
        }
        if (commands.isEmpty())
            throw new IllegalArgumentException("Histopia job must contain a command");
        var immutableCommands = commands.stream().map(List::copyOf).toList();
        jobRunning = true;
        setJobRunning(true);
        cancellationRequested = false;
        status.setText(name + " running...");
        log.clear();
        CompletableFuture
                .supplyAsync(() -> runAll(immutableCommands), executor)
                .whenComplete((ignored, error) -> Platform.runLater(() -> {
                    jobRunning = false;
                    setJobRunning(false);
                    if (error == null) {
                        try {
                            status.setText(name + " completed");
                            if (onSuccess != null)
                                onSuccess.run();
                            Dialogs.showInfoNotification("Histopia", status.getText());
                        } catch (RuntimeException callbackError) {
                            status.setText(name + " failed");
                            Dialogs.showErrorMessage(name, callbackError.getMessage());
                        }
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

    private String runAll(List<List<String>> commands) {
        String result = "";
        for (var command : commands) {
            if (cancellationRequested)
                throw new CancellationException("Histopia job cancelled");
            appendLog("$ " + HistopiaCommand.display(command));
            result = run(command);
        }
        return result;
    }

    private String run(List<String> command) {
        try {
            var process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            activeProcess = process;
            if (cancellationRequested)
                HistopiaProcess.cancelTree(process);
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
                throw new IllegalStateException(
                        "Histopia exited with code " + exitCode
                                + (lastLine.isBlank() ? "" : ": " + lastLine));
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
        tabs.setDisable(running);
        python.setDisable(running);
        runRegistration.setDisable(running);
        runSemantic.setDisable(running);
        runProjectRegistration.setDisable(running);
        runProjectSemantic.setDisable(running);
        checkEnvironment.setDisable(running);
        openProjectReview.setDisable(running);
        openProjectSemanticReview.setDisable(running);
        approveProjectMasks.setDisable(running);
        approveProjectOrder.setDisable(running);
        approveProjectRegistration.setDisable(running);
        approveProjectSemantic.setDisable(running);
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
        HistopiaProcess.cancelTree(process);
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

    private static Integer optionalPositiveInteger(TextField field, String name) {
        if (field.getText() == null || field.getText().isBlank())
            return null;
        return positiveInteger(field, name);
    }

    private static String requiredText(TextField field, String name) {
        if (field.getText() == null || field.getText().isBlank())
            throw new IllegalArgumentException(name + " is required");
        return field.getText().strip();
    }
}
