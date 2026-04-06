package atlanteshellsing.aegis.fileplayground.gui;

import atlanteshellsing.aegis.annotations.ExcludeAsGenerated;
import atlanteshellsing.aegis.fileplayground.model.TemporaryFilePlaygroundSession;
import atlanteshellsing.aegis.logging.AEGISLogger;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * UI panel for browsing a Temporary File Playground workspace.
 */
@ExcludeAsGenerated
public class TemporaryFilePlaygroundPanel extends BorderPane {

    private final TemporaryFilePlaygroundSession session;
    private final Runnable onCloseRequested;
    private final TreeView<String> treeView;
    private final Label emptyStateLevel;

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
        this.emptyStateLevel = new Label("This temporary workspace is empty. Create a file or folder to get started.");

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

        emptyStateLevel.setStyle("fx-font-style: italic; -fx-opacity: 0.75;");

        VBox container = new VBox(8, treeView, emptyStateLevel);
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
        TreeItem<String> root = buildTree(session.workspacePath());
        treeView.setRoot(root);

        boolean hasEntries = !root.getChildren().isEmpty();
        emptyStateLevel.setVisible(!hasEntries);
        emptyStateLevel.setManaged(!hasEntries);
    }

    private TreeItem<String> buildTree(Path path) {
        TreeItem<String> root = new TreeItem<>(path.toString());
        populateChildren(root, path);
        root.setExpanded(true);
        return root;
    }

    private void populateChildren(TreeItem<String> parent, Path path) {
        if(!Files.isDirectory(path)) return;

        List<Path> children;
        try(Stream<Path> stream = Files.list(path)) {
            children = stream.sorted(Comparator
                            .comparing((Path child) -> !Files.isDirectory(child))
                            .thenComparing(child -> child.getFileName().toString().toLowerCase()))
                    .toList();
        } catch (Exception e) {
            AEGISLogger.log(AEGISLogger.AEGISLogKey.AEGIS_TOOL,
                    AEGISLogger.AEGISLogLevel.WARNING,
                    "Could not populate Temporary File Playground contents for session "
                            + session.id() + " at path: " + path,
                    e);
            return;
        }

        for(Path child : children) {
            boolean isDirectory = Files.isDirectory(child);
            String displayName = child.getFileName() == null
                    ? child.toString()
                    : child.getFileName().toString();
            TreeItem<String> childItem = new TreeItem<>((isDirectory ? "📁 " : "📄 ") + displayName);
            parent.getChildren().add(childItem);

            if(isDirectory) populateChildren(childItem, child);
        }
    }
}
