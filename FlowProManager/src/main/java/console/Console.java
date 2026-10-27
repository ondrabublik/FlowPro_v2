package console;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;

public class Console extends JPanel implements KeyListener, CaretListener {

    private static final String PROMPT = ">>";

    private final JScrollPane scrollPane;
    private final JTextArea consoleTextPane;

    private int startIndex;

    private final Commands com;

    public Console() {
        super();

        // Create a text area
        consoleTextPane = new JTextArea();
        consoleTextPane.setText(PROMPT);
        consoleTextPane.setBorder(null);
        // Wraps the text if it goes longer than a line, but NOT on word boundary
        // like a normal console
        consoleTextPane.setLineWrap(true);
        consoleTextPane.setWrapStyleWord(false);

        // Set the initial caret position
        startIndex = consoleTextPane.getText().length();
        consoleTextPane.setCaretPosition(startIndex);

        // Add the caret and key listeners
        consoleTextPane.addCaretListener(this);
        consoleTextPane.addKeyListener(this);

        // Scrollbar, always show the vertical one
        scrollPane = new JScrollPane(consoleTextPane);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        scrollPane.setBorder(null);

        JPanel panelCenter = new JPanel(new BorderLayout());
        panelCenter.setPreferredSize(new Dimension(400, 200));
        panelCenter.add(scrollPane, BorderLayout.CENTER);

        add(panelCenter, BorderLayout.CENTER);

        // commands
        com = new Commands(consoleTextPane);
    }

    public void start() {
        JFrame frame = new JFrame("FlowPro console");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setResizable(false);
        frame.add(new Console());
        frame.pack();
        frame.setVisible(true);
    }

    @Override
    public void keyTyped(KeyEvent e) {
        // All processing in keyPressed
    }

    @Override
    public void keyReleased(KeyEvent e) {
        // All processing in keyPressed
    }

    @Override
    public void keyPressed(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_ENTER:
                // ENTER key was pressed
                // Get the "Command"
                String input = consoleTextPane.getText().substring(startIndex);
                String[] tokens = input.split(" ");
                int nTok = tokens.length;
                String[] args = null;
                if (nTok > 1) {
                    args = new String[nTok - 1];
                    for(int i = 0; i < nTok-1; i++){
                        args[i] = tokens[i+1];
                    }
                }
                if (!input.isEmpty()) {
                    try {
                        com.command(tokens[0],args);
                    } catch (Exception ex) {
                        System.out.println(ex);
                    }
                    
                }

                // Update the start index and append a new prompt
                consoleTextPane.append(System.lineSeparator() + PROMPT);
                startIndex = consoleTextPane.getText().length();

                // Consume the ENTER key event so further processing is not
                // performed
                e.consume();
                break;
            case KeyEvent.VK_BACK_SPACE:
                // Make sure this is a valid delete
                if (consoleTextPane.getCaretPosition() <= startIndex) {
                    e.consume();
                    Toolkit.getDefaultToolkit().beep();
                }

                break;
            // TODO: add key presses here as desired
            default:
                //System.out.println("Unhandled: " + e.getKeyCode());
                break;
        }
    }

    @Override
    public void caretUpdate(CaretEvent e) {
        // Ensure that the caret position can only be a valid location
        if (e.getDot() < startIndex) {
            consoleTextPane.setCaretPosition(startIndex);
            Toolkit.getDefaultToolkit().beep();
        }
    }
}
