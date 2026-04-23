package atlanteshellsing.aegis.components.gui;

import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import java.util.HashMap;
import java.util.Map;

public class AEGISTabPane extends TabPane {

    protected record TabInfo(String key, Tab tab, String title, Node content) {}

    private final Map<String, TabInfo> tabRegistry = new HashMap<>();

    /**
     * Adds a new tab to the pane and the registry.
     *
     * @param key     Unique key for this tab.
     * @param title   Display title of the tab.
     * @param content The UI content for the tab.
     */
    public void addTab(String key, String title, Node content) {
        addTab(key, title, content, true);
    }

    /**
     * Adds a new tab to the pane and the registry.
     *
     * @param key       Unique key for this tab.
     * @param title     Display title of the tab.
     * @param content   The UI content for the tab.
     * @param closable  whether this tab should be closable by the tab header close action
     */
    public void addTab(String key, String title, Node content, boolean closable) {
        if(containsTab(key)) {
            selectTab(key);
            return;
        }

        Tab tab = new Tab(title, content);
        tab.setClosable(closable);
        tab.setOnClosed(e -> removeTab(key));
        getTabs().add(tab);
        tabRegistry.put(key, new TabInfo(key, tab, title, content));
    }

    /**
     * Removes a tab by key.
     *
     * @param key The unique key of the tab.
     */
    public void removeTab(String key) {
        TabInfo info = tabRegistry.remove(key);
        if(info != null) {
            getTabs().remove(info.tab());
        }
    }

    /**
     * Selects a tab by key.
     *
     * @param key The unique key of the tab.
     */
    public void selectTab(String key) {
        TabInfo info = getTabInfo(key);
        if(info != null) {
            getSelectionModel().select(info.tab());
        }
    }

    /**
     * Checks if a tab exists by key.
     *
     * @param key Key to be searched for.
     * @return True if the tab exists, else false.
     */
    public boolean containsTab(String key) { return tabRegistry.containsKey(key); }

    /**
     * Gets the TabInfo for a key.
     *
     * @param key Key to be searched for.
     * @return The TabInfo object; Null if not found.
     */
    protected TabInfo getTabInfo(String key) { return tabRegistry.get(key); }
}
