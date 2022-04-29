package me.wiefferink.bagquick;

import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.tools.Logging;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

import static org.openstreetmap.josm.tools.I18n.tr;

public class BugReportDialog extends ExtendedDialog {

    public static final String GITHUB_ISSUES_URL = "https://github.com/NLthijs48/JOSM-bag-quick/issues/new";

    public BugReportDialog(Throwable throwable) {
        super(
            MainApplication.getMainFrame(),
            tr("Error Report"),
            new String[] {tr("OK")},
            true
        );

        String stacktraceAsString = getStacktraceAsString(throwable);
        String issueBody = "<describe your issue here>\n\n\n## Stacktrace (keep this this block of text):\n```\n" + stacktraceAsString + "\n```";
        String issueBodyEncoded;
        try {
            issueBodyEncoded = URLEncoder.encode(issueBody, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        String urlWithDetails = GITHUB_ISSUES_URL + "?body=" + issueBodyEncoded;

        Logging.info("url with details: "+urlWithDetails);

        setButtonIcons("ok");
        JPanel panel = new JPanel(new BorderLayout());

        StringBuilder html = new StringBuilder();
        html.append("<html>");
        html.append("<h1>").append(tr("Something went wrong!")).append("</h1>");
        html.append("<p>").append(tr("To get this solved please create an issue on Github to report it to the author: ")).append("<a href=\"").append(urlWithDetails).append("\">").append(GITHUB_ISSUES_URL).append("</a>.<br><br></p>");
        html.append("<p>").append(tr("In the report indicate what you did, indicate the location you were trying to update, or ideally use File > Save session as to save the current data and attach that.")).append("<br><br></p>");
        html.append("</html>");
        JLabel content = new JLabel(html.toString());

        content.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                try {
                    Desktop.getDesktop().browse(new URI(urlWithDetails));
                } catch (IOException | URISyntaxException exception) {
                    Logging.log(Level.INFO, "Could not open url: ", exception);
                }
            }
        });

        panel.add(content, BorderLayout.NORTH);
        setContent(panel);

        this.showDialog();
    }

    public static String getStacktraceAsString(Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter pw = new PrintWriter(stringWriter);
        throwable.printStackTrace(pw);
        return stringWriter.toString();
    }

}
