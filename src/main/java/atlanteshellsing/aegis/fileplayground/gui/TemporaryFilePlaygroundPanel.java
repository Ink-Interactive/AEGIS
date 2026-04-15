package atlanteshellsing.aegis.fileplayground.gui;

import atlanteshellsing.aegis.annotations.ExcludeAsGenerated;
import atlanteshellsing.aegis.fileplayground.model.TemporaryFilePlaygroundSession;
import atlanteshellsing.aegis.fileplayground.service.AEGISTemporaryFilePlaygroundManager;
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
import java.nio.file.LinkOption;
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

    private final AEGISTemporaryFilePlaygroundManager manager;
    private final TemporaryFilePlaygroundSession session;
    private final Runnable onCloseRequested;
    private final TreeView<WorkspaceTreeEntry> treeView;
    private final Label emptyStateLabel;
    private final AtomicLong refreshRequestId;

    /**
     * Create a panel bound to an existing Temporary File Playground session.
     *
     * @param manager          manager used for file/folder creation actions
     * @param session          existing session to display
     * @param onCloseRequested callback for close action button
     */
    public TemporaryFilePlaygroundPanel(AEGISTemporaryFilePlaygroundManager manager, TemporaryFilePlaygroundSession session, Runnable onCloseRequested) {
        this.manager = Objects.requireNonNull(manager, "manager cannot be null");
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
            protected void updateItem(WorkspaceTreeEntry item, boolean empty) {
                super.updateItem(item, empty);
                if(empty || item == null) {
                    setText(null);
                    return;
                }
                setText(item.label());
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
        deleteSelected.setDisable(true);

        newFile.setOnAction(action -> onCreateFileRequested());
        newFolder.setOnAction(action -> onCreateFolderRequested());
        closePlayground.setOnAction(action -> onCloseRequested.run());

        HBox actions = new HBox(8, openInExplorer, newFile, newFolder, deleteSelected, closePlayground);
        actions.setPadding(new Insets(12, 0, 0, 0));
        return actions;
    }

    private void onCreateFileRequested() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Temporary File Playground");
        dialog.setHeaderText("Create New File");
        dialog.setContentText("File Name:");

        dialog.showAndWait().ifPresent(name -> {
            try {
                manager.createFile(session.id(), getSelectedTargetDirectory(), name);
                refreshContents();
            } catch (IllegalStateException | IllegalArgumentException e) {
                AEGISLogger.log(
                        AEGISLogger.AEGISLogKey.AEGIS_TOOL,
                        AEGISLogger.AEGISLogLevel.WARNING,
                        "Failed to create file in Temporary File Playground for session " + session.id(),
                        e
                );
                showErrorAlert("Cannot Create File", e.getMessage());
            }
        });
    }

    private void onCreateFolderRequested() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Temporary File Playground");
        dialog.setHeaderText("Create New Folder");
        dialog.setContentText("Folder name:");

        dialog.showAndWait().ifPresent(name -> {
           try {
                manager.createFolder(session.id(), getSelectedTargetDirectory(), name);
                refreshContents();
              } catch (IllegalStateException | IllegalArgumentException e) {
                AEGISLogger.log(
                          AEGISLogger.AEGISLogKey.AEGIS_TOOL,
                          AEGISLogger.AEGISLogLevel.WARNING,
                          "Failed to create folder in Temporary File Playground for session " + session.id(),
                          e
                );
                showErrorAlert("Cannot Create Folder", e.getMessage());
           }
        });
    }

    private void showErrorAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Temporary File Playground");
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private Path getSelectedTargetDirectory() {
        TreeItem<WorkspaceTreeEntry> selected = treeView.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getValue() == null) return session.workspacePath();

        WorkspaceTreeEntry entry = selected.getValue();
        if(entry.isDirectory()) return entry.path();

        Path parent = entry.path().getParent();
        return parent == null ? session.workspacePath() : parent;
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

                               TreeItem<WorkspaceTreeEntry> root = buildTree(workspaceModel);
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
        } catch (RuntimeException e) {
            emptyStateLabel.setText("Unable to refresh workspace right now.");
            emptyStateLabel.setVisible(true);
            emptyStateLabel.setManaged(true);
            AEGISLogger.log(
                    AEGISLogger.AEGISLogKey.AEGIS_TOOL,
                    AEGISLogger.AEGISLogLevel.WARNING,
                    "Failed to queue Temporary File Playground refresh for session " + session.id(),
                    e
            );
        }
    }

    private TreeItem<WorkspaceTreeEntry> buildTree(WorkspaceNode workspaceNode) {
        TreeItem<WorkspaceTreeEntry> root = new TreeItem<>(new WorkspaceTreeEntry(
                workspaceNode.label(),
                workspaceNode.path(),
                workspaceNode.directory()
        ));
        for(WorkspaceNode child : workspaceNode.children()) {
            root.getChildren().add(buildTree(child));
        }
        root.setExpanded(true);
        return root;
    }

    private WorkspaceNode buildWorkspaceModel(Path rootPath) {
        return new WorkspaceNode(rootPath.toString(), rootPath, true, readWorkspaceChildren(rootPath));
    }

    private List<WorkspaceNode> readWorkspaceChildren(Path path) {
        if(!Files.isDirectory(path)) return List.of();

        List<Path> children;
        try (Stream<Path> stream = Files.list(path)) {
            children = stream
                    .sorted(Comparator
                            .comparing((Path child) -> !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS))
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
                    boolean isDirectory = Files.isDirectory(child,  LinkOption.NOFOLLOW_LINKS);
                    boolean isSymlink = Files.isSymbolicLink(child);

                    String displayName = child.getFileName() == null
                            ? child.toString()
                            : child.getFileName().toString();
                    String label = (isDirectory ? "📁 " : "📄 ") + displayName;
                    List<WorkspaceNode> nestedChildren = (isDirectory && !isSymlink)
                            ? readWorkspaceChildren(child)
                            : List.of();

                    return new WorkspaceNode(label, child, isDirectory, nestedChildren);
                })
                .toList();
    }

    private record WorkspaceNode(String label, Path path, boolean directory, List<WorkspaceNode> children) { }
    private record WorkspaceTreeEntry(String label, Path path, boolean isDirectory) { }
}
