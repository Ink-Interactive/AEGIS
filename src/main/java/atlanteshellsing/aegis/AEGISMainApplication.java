package atlanteshellsing.aegis;

import atlanteshellsing.aegis.gui.AEGISMainGui;
import atlanteshellsing.aegis.structure.AEGISConfigurationManager;
import atlanteshellsing.aegis.threading.AEGISThreadManager;
import javafx.application.Application;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.net.URL;
import java.time.Duration;

public class AEGISMainApplication extends Application {

    private AEGISMainGui mainGUI;
    private Image logo;

    @Override
    public void init() {

        logo = loadImage("/images/AEGIS.png");

        AEGISThreadManager.configure(
                new AEGISThreadManager.AEGISThreadManagerConfig(
                        AEGISThreadManager.PoolConfig.fixedPool(
                                Runtime.getRuntime().availableProcessors(),
                                AEGISThreadManager.QueueType.ARRAY_BLOCKING,
                                128,
                                AEGISThreadManager.RejectionPolicy.CALLER_RUNS
                        ),
                        AEGISThreadManager.PoolConfig.scalingPool(
                                8,
                                32,
                                Duration.ofSeconds(60),
                                AEGISThreadManager.QueueType.LINKED_BLOCKING,
                                512,
                                AEGISThreadManager.RejectionPolicy.CALLER_RUNS,
                                true
                        ),
                        Duration.ofMinutes(5),
                        Duration.ofSeconds(30)
                )
        );
    }

    @Override
    public void start(Stage primaryStage) {
        AEGISConfigurationManager.initUserConfig();

        mainGUI = new AEGISMainGui();

        primaryStage.setScene(mainGUI.createScene(1280, 800));
        primaryStage.getIcons().setAll(logo);
        primaryStage.show();
    }

    private Image loadImage(String path) {
        URL url = getClass().getResource(path);
        if (url == null) {
            throw new IllegalStateException(
                    "Missing resource " + path + ". Expected at src/main/resources" + path
            );
        }
        return new Image(url.toExternalForm());
    }

    public static void main(String[] args) {
        launch(args);
    }
}
