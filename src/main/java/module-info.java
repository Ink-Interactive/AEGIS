module atlanteshellsing.aegis {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.logging;
	requires java.compiler;
    requires java.xml;
    requires java.desktop;

    opens atlanteshellsing.aegis.gui to javafx.fxml;
    opens atlanteshellsing.aegis.components.gui to javafx.fxml;
    exports atlanteshellsing.aegis;
    exports  atlanteshellsing.aegis.gui;
    exports atlanteshellsing.aegis.components.gui;
    exports atlanteshellsing.aegis.theme;
}