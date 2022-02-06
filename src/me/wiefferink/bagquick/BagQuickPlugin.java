// TODO: should the josm plugin package be used?
package me.wiefferink.bagquick;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MainMenu;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.tools.Logging;

/**
 * This is the main class for the AreaSelector plugin.
 */
public class BagQuickPlugin extends Plugin {

	/** Enables debug logging */
	public static final boolean DEBUG = true;

	BagUpdateAction bagUpdateAction;

	public BagQuickPlugin(PluginInformation info) {
		super(info);

		Logging.info("Hello world from the BagQuick plugin");

		bagUpdateAction = new BagUpdateAction();
		MainMenu.add(MainApplication.getMenu().toolsMenu, bagUpdateAction);
	}

	/** Print debug logging, only when enabled */
	public static void debug(String pattern, Object... args) {
		if (DEBUG) {
			Logging.debug(pattern, args);
		}
	}

}
