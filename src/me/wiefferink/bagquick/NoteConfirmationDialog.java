package me.wiefferink.bagquick;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.gui.util.WindowGeometry;
import org.openstreetmap.josm.gui.widgets.HtmlPanel;
import org.openstreetmap.josm.tools.ImageProvider;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.LinkedList;
import java.util.Map;
import java.util.List;

import static org.openstreetmap.josm.tools.I18n.tr;

/**
 * Dialogs that shows notes that are set on a Way, confirming if the update should continue
 */
public class NoteConfirmationDialog extends JDialog {

    /** Note tags to show in the UI */
    Map<String, String> noteTags;

    private final JButton continueButton = new JButton(new ContinueAction());
    /** True when canceled, false when confirmed */
    private boolean canceled;

    public NoteConfirmationDialog(Map<String, String> noteTags) {
        super(
                GuiHelper.getFrameForComponent(MainApplication.getMainFrame()),
                tr("There are notes on the building, continue anyway?"),
                ModalityType.DOCUMENT_MODAL
        );
        this.noteTags = noteTags;
        build();
    }

    private void build() {
        getContentPane().setLayout(new BorderLayout());

        HtmlPanel htmlPanel = new HtmlPanel();

        List<String> noteLines = new LinkedList<>();
        for (Map.Entry<String, String> noteTag : noteTags.entrySet()) {
            noteLines.add("<b>" + noteTag.getKey() + "</b>: " + noteTag.getValue());
        }
        String content = String.join("<br /><br />", noteLines);
        htmlPanel.getEditorPane().setText("<html>" + content + "</html>");
        getContentPane().add(htmlPanel, BorderLayout.NORTH);

        getContentPane().add(buildButtonRow(), BorderLayout.SOUTH);
        addWindowListener(new WindowEventHandler());
    }
    private JPanel buildButtonRow() {
        JPanel pnl = new JPanel(new FlowLayout());
        pnl.add(continueButton);
        continueButton.setFocusable(true);
        pnl.add(new JButton(new CancelAction()));
        return pnl;
    }

    public void setCanceled(boolean canceled) {
        this.canceled = canceled;
    }
    public boolean isCanceled() {
        return canceled;
    }

    @Override
    public void setVisible(boolean visible) {
        if (visible) {
            new WindowGeometry(
                    getClass().getName() + ".geometry",
                    WindowGeometry.centerInWindow(
                            MainApplication.getMainFrame(),
                            new Dimension(400, 200)
                    )
            ).applySafe(this);
            setCanceled(false);
        } else {
            if (isShowing()) {
                // Remember the location of this dialog (let users position it somewhere they prefer)
                new WindowGeometry(this).remember(getClass().getName() + ".geometry");
            }
        }
        super.setVisible(visible);
    }

    class ContinueAction extends AbstractAction {
        ContinueAction() {
            putValue(NAME, tr("Continue"));
            // TODO: better icon?
            new ImageProvider("ok").getResource().attachImageIcon(this);
            putValue(SHORT_DESCRIPTION, tr("Click to continue with the BAG building update, ignoring the notes"));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            setCanceled(false);
            setVisible(false);
        }
    }

    class CancelAction extends AbstractAction {
        CancelAction() {
            putValue(NAME, tr("Cancel"));
            new ImageProvider("cancel").getResource().attachImageIcon(this);
            putValue(SHORT_DESCRIPTION, tr("Click to cancel the BAG building update and close the dialog"));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            setCanceled(true);
            setVisible(false);
        }
    }

    class WindowEventHandler extends WindowAdapter {

        @Override
        public void windowClosing(WindowEvent e) {
            setCanceled(true);
        }

        @Override
        public void windowOpened(WindowEvent e) {
            continueButton.requestFocusInWindow();
        }
    }
}
