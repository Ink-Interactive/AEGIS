package atlanteshellsing.aegis.fileplayground.gui;

import atlanteshellsing.aegis.annotations.ExcludeAsGenerated;
import atlanteshellsing.aegis.fileplayground.model.TemporaryFilePlaygroundSession;
import atlanteshellsing.aegis.logging.AEGISLogger;
import atlanteshellsing.aegis.threading.AEGISThreadManager;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * UI panel for browsing a Temporary File Playground workspace.
 */
@ExcludeAsGenerated
public class TemporaryFilePlaygroundPanel extends BorderPane {

    private final TemporaryFilePlaygroundSession session;
    private final Runnable onCloseRequested;
    private final TreeView<String> treeView;
    private final Label emptyStateLabel;
    private final AtomicLong refreshRequestId;

    /**
     * Create a panel bound to an existing Temporary File Playground session.
     *
     * @param session          existing session to display
     * @param onCloseRequested callback for close action button
     */
    public TemporaryFilePlaygroundPanel(TemporaryFilePlaygroundSession session, Runnable onCloseRequested) {
        this.session = Objects.requireNonNull(session, "session cannot be null");
        this.onCloseRequested = Objects.requireNonNull(onCloseRequested, "onCloseRequested cannot be null");
        this.treeView = new TreeView<>();
        this.emptyStateLabel = new Label("This temporary workspace is empty. Create a file or folder to get started.");
        this.refreshRequestId = new AtomicLong(0);

        setPadding(new Insets(16));

        setTop(buildHeader());
        setCenter(buildWorkspaceView());
        setBottom(buildActionsControls());

        refreshContents();
    }

    private VBox buildHeader() {
        Label title = new Label("Temporary File Playground");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Label temporaryNotice = new Label("Temporary Workspace: Files in this session are not permanent.");
        temporaryNotice.setStyle("-fx-font-weight: bold; -fx-text-fill: #D08A00;");

        Label workspacePath = new Label("Workspace Path: " + session.workspacePath());
        workspacePath.setWrapText(true);

        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(action -> refreshContents());

        HBox controls = new HBox(8, refreshButton);

        VBox header = new VBox(8, title, temporaryNotice, workspacePath, controls);
        header.setPadding(new Insets(0, 0, 12, 0));
        return header;
    }

    private VBox buildWorkspaceView() {
        treeView.setShowRoot(false);
        treeView.setCellFactory(view -> new TreeCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if(empty || item == null) {
                    setText(null);
                    return;
                }
                setText(item);
            }
        });

        emptyStateLabel.setStyle("-fx-font-style: italic; -fx-opacity: 0.75;");

        VBox container = new VBox(8, treeView, emptyStateLabel);
        VBox.setVgrow(treeView, Priority.ALWAYS);
        return container;
    }

    private HBox buildActionsControls() {
        Button openInExplorer = new Button("Open In Explorer");
        Button newFile =  new Button("New File");
        Button newFolder =  new Button("New Folder");
        Button deleteSelected =  new Button("Delete Selected");
        Button closePlayground = new Button("Close Playground");

        openInExplorer.setDisable(true);
        newFile.setDisable(true);
        newFolder.setDisable(true);
        deleteSelected.setDisable(true);

        closePlayground.setOnAction(action -> onCloseRequested.run());

        HBox actions = new HBox(8, openInExplorer, newFile, newFolder, deleteSelected, closePlayground);
        actions.setPadding(new Insets(12, 0, 0, 0));
        return actions;
    }

    private void refreshContents() {
        long currentRefreshId = refreshRequestId.incrementAndGet();
        emptyStateLabel.setText("Refreshing Workspace...");
        emptyStateLabel.setVisible(true);
        emptyStateLabel.setManaged(true);

        try {
            AEGISThreadManager.submitAsyncTask(
                    "temporary-playground-refresh-" + session.id(),
                    () -> {
                        try {
                            WorkspaceNode workspaceModel = buildWorkspaceModel(session.workspacePath());

                            Platform.runLater(() -> {
                               if(refreshRequestId.get() != currentRefreshId) return;

                               TreeItem<String> root = buildTree(workspaceModel);
                               treeView.setRoot(root);

                               boolean hasEntries = !root.getChildren().isEmpty();
                               emptyStateLabel.setText("This temporary workspace is empty. Create a file or folder to get started.");
                               emptyStateLabel.setVisible(!hasEntries);
                               emptyStateLabel.setManaged(!hasEntries);
                            });
                        } catch (RuntimeException e) {
                            Platform.runLater(() -> {
                               if(refreshRequestId.get() != currentRefreshId) return;
                               emptyStateLabel.setText("Unable to refresh workspace right now.");
                               emptyStateLabel.setVisible(true);
                               emptyStateLabel.setManaged(true);
                            });

                            AEGISLogger.log(
                                    AEGISLogger.AEGISLogKey.AEGIS_TOOL,
                                    AEGISLogger.AEGISLogLevel.WARNING,
                                    "Failed to refresh Temporary File Playground for session " + session.id(),
                                    e
                            );
                        }
                    },
                    TemporaryFilePlaygroundPanel.this.getClass().getSimpleName(),
                    AEGISThreadManager.PoolType.IO_BOUND
            );
        } catch (RejectedExecutionException e) {
            emptyStateLabel.setText("Unable to refresh workspace right now.");
            AEGISLogger.log(
                    AEGISLogger.AEGISLogKey.AEGIS_TOOL,
                    AEGISLogger.AEGISLogLevel.WARNING,
                    "Failed to queue Temporary File Playground refresh for session " + session.id(),
                    e
            );
        }
    }

    private TreeItem<String> buildTree(WorkspaceNode workspaceNode) {
        TreeItem<String> root = new TreeItem<>(workspaceNode.label());
        for(WorkspaceNode child : workspaceNode.children()) {
            root.getChildren().add(buildTree(child));
        }
        root.setExpanded(true);
        return root;
    }

    private WorkspaceNode buildWorkspaceModel(Path rootPath) {
        return new WorkspaceNode(rootPath.toString(), readWorkspaceChildren(rootPath));
    }

    private List<WorkspaceNode> readWorkspaceChildren(Path path) {
        if(!Files.isDirectory(path)) return List.of();

        List<Path> children;
        try (Stream<Path> stream = Files.list(path)) {
            children = stream
                    .sorted(Comparator
                            .comparing((Path child) -> !Files.isDirectory(child))
                            .thenComparing(child -> child.getFileName().toString().toLowerCase()))
                    .toList();
        } catch (IOException e) {
            AEGISLogger.log(
                    AEGISLogger.AEGISLogKey.AEGIS_TOOL,
                    AEGISLogger.AEGISLogLevel.WARNING,
                    "Failed to read Temporary File Playground path: " + path,
                    e
            );
            return List.of();
        }

        return children.stream()
                .map(child -> {
                    boolean isDirectory = Files.isDirectory(child);
                    String displayName = child.getFileName() == null
                            ? child.toString()
                            : child.getFileName().toString();
                    String label = (isDirectory ? "📁 " : "📄 ") + displayName;
                    List<WorkspaceNode> nestedChildren = isDirectory
                            ? readWorkspaceChildren(child)
                            : List.of();

                    return new WorkspaceNode(label, nestedChildren);
                })
                .toList();
    }

    private record WorkspaceNode(String label, List<WorkspaceNode> children) { }
}
