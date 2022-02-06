package me.wiefferink.bagquick;

import org.openstreetmap.josm.gui.Notification;

import javax.swing.*;
import java.util.LinkedList;
import java.util.List;

import static org.openstreetmap.josm.tools.I18n.tr;

/**
 * Collects information about an operation, and send a notification
 * - Tracks notes about executed actions
 * - Tracks warnings about potential issues
 */
public class ResultSummary {

	private boolean hasFailed = false;
	/** Notes about the result, indicates what has changed */
	private List<String> notes = new LinkedList<>();
	/** Warnings about rough edges in the result */
	private List<String> warnings = new LinkedList<>();

	public void addNote(String message) {
		this.notes.add(message);
	}

	public void addWarning(String message) {
		this.notes.add(message);
	}

	public void failed(String message) {
		this.hasFailed = true;
		notification(message, JOptionPane.ERROR_MESSAGE);
	}

	public void sendNotification() {
		if (hasFailed) {
			// Already failed and done
			return;
		}

		if (this.notes.isEmpty() && this.warnings.isEmpty()) {
			// TODO: better message than this?
			notification(tr("Action completed without notes/warnings"), JOptionPane.INFORMATION_MESSAGE);
			return;
		}

		List<String> result = new LinkedList<>();

		if (!warnings.isEmpty()) {
			if (warnings.size() == 1) {
				result.add(tr("Warning: {0}", warnings.get(0)));
			} else {
				result.add(tr("Warnings:"));
				StringBuilder list = new StringBuilder("<ul>");
				for (String warning : warnings) {
					list.append("<li>");
					list.append(warning);
					list.append("</li>");
				}
				list.append("</ul>");
				result.add(list.toString());
			}
		}

		if (!notes.isEmpty()) {
			if (notes.size() == 1) {
				result.add(tr("Note: {0}", notes.get(0)));
			} else {
				result.add(tr("Notes:"));
				StringBuilder list = new StringBuilder("<ul>");
				for (String notes : notes) {
					list.append("<li>");
					list.append(notes);
					list.append("</li>");
				}
				list.append("</ul>");
				result.add(list.toString());
			}
		}

		int messageType = this.warnings.isEmpty() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE;
		String joinedMessage = String.join("<br />", result);
		notification(joinedMessage, messageType);
	}

	private void notification(String message, int messageType) {
		BagQuickPlugin.debug("notification: "+message.replace("<br />", "\n"));
		new Notification("<strong>" + tr("Bag Quick") + "</strong><br />" + message)
				.setIcon(messageType)
				.setDuration(Notification.TIME_LONG)
				.show();
	}

}
