package atlanteshellsing.aegis.gui;

import atlanteshellsing.aegis.annotations.ExcludeAsGenerated;
import atlanteshellsing.aegis.components.gui.AEGISTabPane;
import atlanteshellsing.aegis.fileplayground.gui.TemporaryFilePlaygroundPanel;
import atlanteshellsing.aegis.fileplayground.model.TemporaryFilePlaygroundSession;
import atlanteshellsing.aegis.fileplayground.service.AEGISTemporaryFilePlaygroundManager;
import atlanteshellsing.aegis.logging.AEGISLogger;
import atlanteshellsing.aegis.structure.AEGISConfigurationManager;
import atlanteshellsing.aegis.theme.AEGISThemeManager;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import java.nio.file.Path;

@ExcludeAsGenerated
public class AEGISMainGui {

    private final BorderPane pane;
    private final MenuBar menuBar;
    private final AEGISTabPane tabPane;
    private final AEGISTemporaryFilePlaygroundManager playgroundManager;

    public AEGISMainGui() {
        pane = new BorderPane();
        menuBar = new MenuBar();
        tabPane = new AEGISTabPane();
        playgroundManager = new AEGISTemporaryFilePlaygroundManager(getPlaygroundRootPath());

        initMenuBar();

        pane.setCenter(tabPane);

        tabPane.addTab("home", "home", new Label("Welcome to Aegis"));
    }

    private void initMenuBar() {
        Menu fileMenu = new Menu("File");
        fileMenu.getItems().addAll(
                new MenuItem("New"),
                new MenuItem("Open"),
                new MenuItem("Exit")
        );

        Menu toolsMenu = new Menu("Tools");
        MenuItem openTemporaryPlayground =  new MenuItem("Open Temporary File Playground");
        openTemporaryPlayground.setOnAction(action -> openTemporaryPlayground());
        toolsMenu.getItems().addAll(openTemporaryPlayground);

        Menu viewMenu = new Menu("View");
        MenuItem toggleTheme = new MenuItem("Toggle Theme");
        toggleTheme.setOnAction(action -> AEGISThemeManager.toggleTheme(pane.getScene()));
        viewMenu.getItems().add(toggleTheme);

        Menu helpMenu = new Menu("Help");
        helpMenu.getItems().add(new MenuItem("About"));

        menuBar.getMenus().addAll(fileMenu, toolsMenu, helpMenu, viewMenu);

        initHeader();
    }

    private void initHeader() {
        Label titleLabel = new Label("AEGIS - Alpha");
        titleLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        titleLabel.setAlignment(Pos.CENTER);

        VBox headerBox = new VBox();
        headerBox.setAlignment(Pos.CENTER);
        headerBox.getChildren().addAll(titleLabel, menuBar);

        pane.setTop(headerBox);
    }

    /**
     * Create a JavaFX Scene rooted at the main application pane and apply the current theme.
     *
     * @param width  the scene width in pixels
     * @param height the scene height in pixels
     * @return the created Scene containing the main application layout
     */
    public Scene createScene(double width, double height) {
        Scene scene = new Scene(pane, width, height);
        AEGISThemeManager.loadTheme(scene);
        return scene;
    }

    private void openTemporaryPlayground() {
        try {
            TemporaryFilePlaygroundSession session = playgroundManager.createSession();
            String tabKey = "playground-" + session.id();
            String title = "Playground " + session.id().toString().substring(0, 8);

            TemporaryFilePlaygroundPanel panel = new TemporaryFilePlaygroundPanel(
                    playgroundManager,
                    session,
                    () -> closeTemporaryPlayground(session, tabKey)
            );

            tabPane.addTab(tabKey, title, panel, false);
            tabPane.selectTab(tabKey);
        } catch (IllegalStateException e) {
            AEGISLogger.log(AEGISLogger.AEGISLogKey.AEGIS_TOOL, AEGISLogger.AEGISLogLevel.SEVERE, "Failed to open temporary playground", e);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Temporary File Playground");
            alert.setHeaderText("Unable to open Temporary File Playground");
            alert.setContentText(e.getMessage());
            alert.showAndWait();
        }
    }

    private void closeTemporaryPlayground(TemporaryFilePlaygroundSession session, String tabKey) {
        try {
            playgroundManager.closeSession(session.id());
            tabPane.removeTab(tabKey);
        } catch (IllegalArgumentException | IllegalStateException e) {
            AEGISLogger.log(
                    AEGISLogger.AEGISLogKey.AEGIS_TOOL,
                    AEGISLogger.AEGISLogLevel.WARNING,
                    "Failed to close temporary playground session " + session.id(),
                    e
            );
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Temporary File Playground");
            alert.setHeaderText("Playground cleanup is pending");
            alert.setContentText(
                    e.getMessage() + "\n\n" +
                            "AEGIS could not delete the workspace right now. " +
                            "The session has been marked for cleanup and is no longer treated as open. " +
                            "Files may remain on disk until cleanup is retried or performed manually."
            );

            alert.showAndWait();
            tabPane.removeTab(tabKey);
        }
    }

    private static Path getPlaygroundRootPath() {
        return AEGISConfigurationManager.userAppDataDir
                .resolve("temp")
                .resolve("file-playground");
    }

    public AEGISTabPane getMainTabPane() { return tabPane; }
}