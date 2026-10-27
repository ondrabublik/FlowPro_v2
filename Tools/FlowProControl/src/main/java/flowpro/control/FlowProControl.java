package flowpro.control;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

/**
 * Graphical control of FlowPro computations: selection of the simulation,
 * editing of parameters.txt, running (FlowPro is started as a separate
 * process), monitoring of the convergence, graceful stop and a preview of the
 * mesh and of the results.
 *
 * @author obublik
 */
public class FlowProControl extends JFrame {

    private static final String STOP_FILE_NAME = "STOP";
    private final Preferences prefs = Preferences.userNodeForPackage(FlowProControl.class);

    private File flowProDir;

    // simulations
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("simulations");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
    private final JTree tree = new JTree(treeModel);
    private String geometry, simulation;

    // parameters
    private ParameterFile parameterFile;
    private final ParameterTableModel parameterModel = new ParameterTableModel();
    private final JTable parameterTable = new JTable(parameterModel);
    private final Map<String, String> descriptions = new LinkedHashMap<>();
    private final JLabel parameterInfo = new JLabel(" ");

    // run
    private final JSpinner memorySpinner = new JSpinner(new SpinnerNumberModel(8, 1, 1024, 1));
    private final JSpinner domainsSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 4096, 1));
    private final JTextField overridesField = new JTextField(30);
    private final JTextField postprocessingField = new JTextField(20);
    private final JCheckBox continueBox = new JCheckBox("continue computation");
    private final JTextArea console = new JTextArea();
    private final JLabel runStatus = new JLabel("idle");
    private final JButton runButton = new JButton("Run");
    private final JButton stopButton = new JButton("Stop (save and finish)");
    private final JButton killButton = new JButton("Kill");
    private Process process;
    private String processSimulationPath;

    // monitoring
    private final ResiduumChart chart = new ResiduumChart();
    private final ResultView resultView = new ResultView();
    private final JComboBox<String> componentCombo = new JComboBox<>();
    private final JLabel resultInfo = new JLabel(" ");
    private final JTabbedPane tabs = new JTabbedPane();

    public FlowProControl(File flowProDir) {
        super("FlowPro Control");
        this.flowProDir = flowProDir;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exit();
            }
        });

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, createSimulationPanel(), createTabs());
        split.setDividerLocation(270);
        add(split, BorderLayout.CENTER);

        setSize(1350, 860);
        setLocationRelativeTo(null);

        refreshTree();
        loadParameterDescriptions();

        Timer timer = new Timer(1000, e -> monitor());
        timer.start();
    }

    // =================================================================== simulations
    private JPanel createSimulationPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JLabel dirLabel = new JLabel();
        updateDirLabel(dirLabel);
        panel.add(dirLabel, BorderLayout.NORTH);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.addTreeSelectionListener(e -> onTreeSelection());
        panel.add(new JScrollPane(tree), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(2, 0, 2, 0);
        c.gridx = 0;
        c.gridy = 0;
        buttons.add(button("Refresh", this::refreshTree), c);
        c.gridy++;
        buttons.add(button("New simulation (copy)...", this::newSimulation), c);
        c.gridy++;
        buttons.add(button("Open simulation folder", this::openFolder), c);
        c.gridy++;
        buttons.add(button("2D mesh generator", this::startMeshGenerator), c);
        c.gridy++;
        buttons.add(button("Change FlowPro directory...", () -> {
            File dir = chooseFlowProDir(this, flowProDir);
            if (dir != null) {
                flowProDir = dir;
                prefs.put("flowProDir", dir.getAbsolutePath());
                updateDirLabel(dirLabel);
                refreshTree();
                loadParameterDescriptions();
            }
        }), c);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private void updateDirLabel(JLabel label) {
        label.setText("<html><b>FlowPro:</b> " + flowProDir.getAbsolutePath() + "</html>");
    }

    private static JButton button(String text, Runnable action) {
        JButton b = new JButton(text);
        b.addActionListener(e -> action.run());
        return b;
    }

    private static final class SimNode {

        final String geometry, simulation;

        SimNode(String geometry, String simulation) {
            this.geometry = geometry;
            this.simulation = simulation;
        }

        @Override
        public String toString() {
            return simulation == null ? geometry : simulation;
        }
    }

    private void refreshTree() {
        root.removeAllChildren();
        List<String> geometries = new ArrayList<>();
        collectGeometries(new File(flowProDir, "simulations"), "", geometries, 0);
        for (String geo : geometries) {
            DefaultMutableTreeNode geoNode = new DefaultMutableTreeNode(new SimNode(geo, null));
            File[] dirs = new File(new File(flowProDir, "simulations"), geo).listFiles(File::isDirectory);
            if (dirs != null) {
                Arrays.sort(dirs);
                for (File dir : dirs) {
                    if (new File(dir, "parameters.txt").isFile()) {
                        geoNode.add(new DefaultMutableTreeNode(new SimNode(geo, dir.getName())));
                    }
                }
            }
            root.add(geoNode);
        }
        treeModel.reload();
        // restore the selection
        if (geometry != null) {
            for (int i = 0; i < root.getChildCount(); i++) {
                DefaultMutableTreeNode g = (DefaultMutableTreeNode) root.getChildAt(i);
                for (int j = 0; j < g.getChildCount(); j++) {
                    DefaultMutableTreeNode s = (DefaultMutableTreeNode) g.getChildAt(j);
                    SimNode n = (SimNode) s.getUserObject();
                    if (n.geometry.equals(geometry) && n.simulation.equals(simulation)) {
                        tree.setSelectionPath(new TreePath(s.getPath()));
                        tree.scrollPathToVisible(new TreePath(s.getPath()));
                    }
                }
            }
        }
    }

    private static void collectGeometries(File dir, String prefix, List<String> geometries, int depth) {
        File[] dirs = dir.listFiles(File::isDirectory);
        if (dirs == null) {
            return;
        }
        Arrays.sort(dirs);
        for (File sub : dirs) {
            String name = prefix.isEmpty() ? sub.getName() : prefix + "/" + sub.getName();
            if (new File(sub, "mesh").isDirectory()) {
                geometries.add(name);
            } else if (depth < 3) {
                collectGeometries(sub, name, geometries, depth + 1);
            }
        }
    }

    private void onTreeSelection() {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
        if (node == null || !(node.getUserObject() instanceof SimNode)) {
            return;
        }
        SimNode sim = (SimNode) node.getUserObject();
        if (sim.simulation == null) {
            return;
        }
        if (parameterModel.dirty && !sim.simulation.equals(simulation)
                && JOptionPane.showConfirmDialog(this, "Save the changed parameters of " + simulationLabel() + "?",
                        "Unsaved parameters", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            saveParameters();
        }
        geometry = sim.geometry;
        simulation = sim.simulation;
        setTitle("FlowPro Control – " + simulationLabel());
        loadParameters();
        chart.setFile(new File(simulationDir(), "residuum.txt"));
        loadResults();
    }

    private String simulationLabel() {
        return geometry + " / " + simulation;
    }

    private File simulationDir() {
        return new File(new File(new File(flowProDir, "simulations"), geometry), simulation);
    }

    private File meshDir() {
        return new File(new File(new File(flowProDir, "simulations"), geometry), "mesh");
    }

    private boolean requireSimulation() {
        if (simulation == null) {
            JOptionPane.showMessageDialog(this, "Select a simulation in the tree first.");
            return false;
        }
        return true;
    }

    private void newSimulation() {
        if (!requireSimulation()) {
            return;
        }
        String name = JOptionPane.showInputDialog(this, "Name of the new simulation of geometry " + geometry
                + "\n(parameters.txt is copied from " + simulation + "):", simulation + "_2");
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        File dir = new File(simulationDir().getParentFile(), name.trim());
        if (dir.exists()) {
            JOptionPane.showMessageDialog(this, "Simulation " + name + " already exists.");
            return;
        }
        try {
            Files.createDirectories(dir.toPath());
            Files.copy(new File(simulationDir(), "parameters.txt").toPath(), new File(dir, "parameters.txt").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            for (String optional : new String[]{"initScript.js", "order.txt"}) {
                File f = new File(simulationDir(), optional);
                if (f.isFile()) {
                    Files.copy(f.toPath(), new File(dir, optional).toPath());
                }
            }
            simulation = name.trim();
            refreshTree();
        } catch (IOException ex) {
            error("The simulation cannot be created: " + ex.getMessage());
        }
    }

    private void openFolder() {
        if (!requireSimulation()) {
            return;
        }
        try {
            Desktop.getDesktop().open(simulationDir());
        } catch (IOException | UnsupportedOperationException ex) {
            error("The folder cannot be opened: " + ex.getMessage());
        }
    }

    private void startMeshGenerator() {
        File jar = new File(flowProDir, "MeshGenerator2D.jar");
        if (!jar.isFile()) {
            error("File " + jar + " does not exist, build the project (gradlew build).");
            return;
        }
        try {
            new ProcessBuilder(javaExecutable(), "-jar", jar.getName()).directory(flowProDir).inheritIO().start();
        } catch (IOException ex) {
            error("The mesh generator cannot be started: " + ex.getMessage());
        }
    }

    // =================================================================== tabs
    private JTabbedPane createTabs() {
        tabs.addTab("Parameters", createParametersTab());
        tabs.addTab("Run", createRunTab());
        JPanel chartPanel = new JPanel(new BorderLayout());
        chartPanel.add(chart, BorderLayout.CENTER);
        JCheckBox timeAxis = new JCheckBox("physical time on x axis");
        timeAxis.addActionListener(e -> {
            chart.timeOnXAxis = timeAxis.isSelected();
            chart.repaint();
        });
        JPanel chartControls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        chartControls.add(timeAxis);
        chartPanel.add(chartControls, BorderLayout.SOUTH);
        tabs.addTab("Convergence", chartPanel);
        tabs.addTab("Mesh & results", createResultTab());
        return tabs;
    }

    // ------------------------------------------------------------------- parameters
    private final class ParameterTableModel extends AbstractTableModel {

        private final String[] columns = {"on", "parameter", "value", "comment", "description"};
        List<ParameterFile.Line> rows = new ArrayList<>();
        boolean dirty = false;

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int c) {
            return columns[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == 0 || c == 2;
        }

        @Override
        public Object getValueAt(int r, int c) {
            ParameterFile.Line line = rows.get(r);
            switch (c) {
                case 0:
                    return !line.disabled;
                case 1:
                    return line.key;
                case 2:
                    return line.value;
                case 3:
                    return line.comment;
                default:
                    return descriptions.getOrDefault(line.key, "");
            }
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            ParameterFile.Line line = rows.get(r);
            if (c == 0) {
                line.disabled = !(Boolean) v;
            } else if (c == 2) {
                line.value = v.toString().trim();
            }
            line.modified = true;
            dirty = true;
            parameterInfo.setText("unsaved changes");
            fireTableRowsUpdated(r, r);
        }
    }

    private JPanel createParametersTab() {
        JPanel panel = new JPanel(new BorderLayout());
        parameterTable.setRowHeight(22);
        parameterTable.getColumnModel().getColumn(0).setMaxWidth(40);
        parameterTable.getColumnModel().getColumn(1).setPreferredWidth(170);
        parameterTable.getColumnModel().getColumn(2).setPreferredWidth(160);
        parameterTable.getColumnModel().getColumn(3).setPreferredWidth(220);
        parameterTable.getColumnModel().getColumn(4).setPreferredWidth(380);
        panel.add(new JScrollPane(parameterTable), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(button("Save", this::saveParameters));
        buttons.add(button("Reload", this::loadParameters));
        buttons.add(button("Add parameter...", this::addParameter));
        buttons.add(button("Check (validate)", () -> runFlowPro("check")));
        buttons.add(parameterInfo);
        panel.add(buttons, BorderLayout.SOUTH);
        JLabel hint = new JLabel("  Uncheck 'on' to comment a parameter out (%). Parameters of the equations "
                + "(e.g. kappa, pOut) have no description, they are read by the modules.");
        hint.setForeground(Color.GRAY);
        panel.add(hint, BorderLayout.NORTH);
        return panel;
    }

    private void loadParameters() {
        if (simulation == null) {
            return;
        }
        try {
            parameterFile = new ParameterFile(new File(simulationDir(), "parameters.txt"));
            parameterModel.rows = parameterFile.parameters();
            parameterModel.dirty = false;
            parameterModel.fireTableDataChanged();
            parameterInfo.setText(parameterFile.file.getPath());
            continueBox.setSelected(false);
        } catch (IOException ex) {
            error("parameters.txt cannot be read: " + ex.getMessage());
        }
    }

    private boolean saveParameters() {
        if (parameterFile == null) {
            return true;
        }
        if (parameterTable.isEditing()) {
            parameterTable.getCellEditor().stopCellEditing();
        }
        try {
            parameterFile.save();
            parameterModel.dirty = false;
            parameterInfo.setText("saved " + parameterFile.file.getPath());
            return true;
        } catch (IOException ex) {
            error("parameters.txt cannot be saved: " + ex.getMessage());
            return false;
        }
    }

    private void addParameter() {
        if (parameterFile == null) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (String name : descriptions.keySet()) {
            if (parameterFile.find(name) == null) {
                names.add(name);
            }
        }
        JComboBox<String> nameBox = new JComboBox<>(names.toArray(new String[0]));
        nameBox.setEditable(true);
        JTextField valueField = new JTextField(15);
        JLabel description = new JLabel(" ");
        nameBox.addActionListener(e -> description.setText("<html><i>"
                + descriptions.getOrDefault((String) nameBox.getSelectedItem(), "") + "</i></html>"));
        Object[] form = {"parameter", nameBox, description, "value", valueField};
        if (JOptionPane.showConfirmDialog(this, form, "Add parameter", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            String name = ((String) nameBox.getSelectedItem()).trim();
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                error("Wrong parameter name.");
                return;
            }
            parameterFile.set(name, valueField.getText().trim());
            parameterModel.rows = parameterFile.parameters();
            parameterModel.dirty = true;
            parameterModel.fireTableDataChanged();
            parameterInfo.setText("unsaved changes");
        }
    }

    /**
     * Reads the descriptions of the parameters from "java -jar FlowPro.jar
     * params" (the format is defined by ParameterRegistry.printHelp).
     */
    private void loadParameterDescriptions() {
        File jar = new File(flowProDir, "FlowPro.jar");
        if (!jar.isFile()) {
            return;
        }
        new Thread(() -> {
            Map<String, String> found = new LinkedHashMap<>();
            try {
                Process p = new ProcessBuilder(javaExecutable(), "-jar", "FlowPro.jar", "params")
                        .directory(flowProDir).redirectErrorStream(true).start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    String last = null;
                    while ((line = r.readLine()) != null) {
                        if (line.length() > 55 && line.startsWith("  ")) {
                            String name = line.substring(2, 28).trim();
                            String rest = line.substring(Math.min(line.length(), 55)).trim();
                            String def = line.substring(38, 55).trim();
                            if (!name.isEmpty()) {
                                found.put(name, rest + (def.isEmpty() ? "" : " [" + def + "]"));
                                last = name;
                            } else if (last != null && rest.startsWith("values:")) {
                                found.put(last, found.get(last) + ", " + rest);
                            }
                        }
                    }
                }
                p.waitFor();
            } catch (IOException | InterruptedException | StringIndexOutOfBoundsException ex) {
                return;
            }
            SwingUtilities.invokeLater(() -> {
                descriptions.clear();
                descriptions.putAll(found);
                parameterModel.fireTableDataChanged();
            });
        }, "parameter-descriptions").start();
    }

    // ------------------------------------------------------------------- run
    private JPanel createRunTab() {
        JPanel panel = new JPanel(new BorderLayout());
        JPanel options = new JPanel(new GridBagLayout());
        options.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0;
        c.gridy = 0;
        options.add(new JLabel("JVM memory [GB]"), c);
        c.gridx = 1;
        memorySpinner.setValue(prefs.getInt("memory", 8));
        options.add(memorySpinner, c);
        c.gridx = 2;
        options.add(new JLabel("domains (0 = local)"), c);
        c.gridx = 3;
        options.add(domainsSpinner, c);
        c.gridx = 4;
        options.add(continueBox, c);

        c.gridx = 0;
        c.gridy = 1;
        options.add(new JLabel("override parameters"), c);
        c.gridx = 1;
        c.gridwidth = 4;
        c.fill = GridBagConstraints.HORIZONTAL;
        overridesField.setToolTipText("e.g. CFL=10, steps=500 - the file parameters.txt is not changed");
        options.add(overridesField, c);
        c.gridwidth = 1;
        c.fill = GridBagConstraints.NONE;

        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 5;
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        runButton.addActionListener(e -> runFlowPro("run"));
        stopButton.addActionListener(e -> requestStop());
        killButton.addActionListener(e -> kill());
        buttons.add(runButton);
        buttons.add(stopButton);
        buttons.add(killButton);
        buttons.add(button("Check", () -> runFlowPro("check")));
        buttons.add(new JLabel("   postprocessing arguments"));
        buttons.add(postprocessingField);
        buttons.add(button("Postprocessing", () -> runFlowPro("postprocessing")));
        options.add(buttons, c);

        c.gridy = 3;
        runStatus.setFont(runStatus.getFont().deriveFont(Font.BOLD));
        options.add(runStatus, c);
        panel.add(options, BorderLayout.NORTH);

        console.setEditable(false);
        console.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        panel.add(new JScrollPane(console), BorderLayout.CENTER);
        updateRunButtons();
        return panel;
    }

    private static String javaExecutable() {
        String exe = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        File java = new File(new File(System.getProperty("java.home"), "bin"), exe);
        return java.isFile() ? java.getAbsolutePath() : "java";
    }

    private boolean isRunning() {
        return process != null && process.isAlive();
    }

    private void updateRunButtons() {
        boolean running = isRunning();
        runButton.setEnabled(!running);
        stopButton.setEnabled(running);
        killButton.setEnabled(running);
    }

    /**
     * Starts FlowPro: java -Xmx..g -jar FlowPro.jar COMMAND -g GEOMETRY -s
     * SIMULATION [-d N] [-p key=value]...
     */
    private void runFlowPro(String command) {
        if (!requireSimulation()) {
            return;
        }
        if (isRunning()) {
            JOptionPane.showMessageDialog(this, "A computation is already running.");
            return;
        }
        if (parameterModel.dirty) {
            int answer = JOptionPane.showConfirmDialog(this, "Save the changed parameters before running?",
                    "Unsaved parameters", JOptionPane.YES_NO_CANCEL_OPTION);
            if (answer == JOptionPane.CANCEL_OPTION || (answer == JOptionPane.YES_OPTION && !saveParameters())) {
                return;
            }
        }
        if (!new File(flowProDir, "FlowPro.jar").isFile()) {
            error("File FlowPro.jar was not found in " + flowProDir);
            return;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExecutable());
        int memory = (Integer) memorySpinner.getValue();
        prefs.putInt("memory", memory);
        cmd.add("-Xmx" + memory + "g");
        cmd.add("-Xss10m");
        cmd.add("-jar");
        cmd.add("FlowPro.jar");
        cmd.add(command);
        if (command.equals("postprocessing")) {
            for (String arg : postprocessingField.getText().trim().split("\\s+")) {
                if (!arg.isEmpty()) {
                    cmd.add(arg);
                }
            }
        }
        cmd.add("--geometry");
        cmd.add(geometry);
        cmd.add("--simulation");
        cmd.add(simulation);
        if (!command.equals("postprocessing")) {
            int domains = (Integer) domainsSpinner.getValue();
            if (domains > 0) {
                cmd.add("--domains");
                cmd.add(Integer.toString(domains));
            }
            if (command.equals("run")) {
                cmd.add("--set");
                cmd.add("continueComputation=" + continueBox.isSelected());
            }
            for (String kv : overridesField.getText().split("[,;]")) {
                kv = kv.trim();
                if (!kv.isEmpty()) {
                    if (!kv.contains("=")) {
                        error("Override '" + kv + "' must have the form key=value.");
                        return;
                    }
                    cmd.add("--set");
                    cmd.add(kv);
                }
            }
        }

        if (command.equals("run")) {
            // a stop request left from a previous run must not stop the new one
            new File(simulationDir(), STOP_FILE_NAME).delete();
            if (!continueBox.isSelected()) {
                chart.setFile(new File(simulationDir(), "residuum.txt"));
            }
            tabs.setSelectedIndex(1);
        }

        console.setText(String.join(" ", cmd) + "\n\n");
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(flowProDir).redirectErrorStream(true);
            process = pb.start();
            processSimulationPath = simulationDir().getAbsolutePath();
            runStatus.setForeground(new Color(0, 110, 0));
            runStatus.setText(command + " running: " + simulationLabel());
            Process started = process;
            Thread reader = new Thread(() -> readOutput(started, command), "flowpro-output");
            reader.setDaemon(true);
            reader.start();
        } catch (IOException ex) {
            error("FlowPro cannot be started: " + ex.getMessage());
        }
        updateRunButtons();
    }

    private void readOutput(Process p, String command) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder buffer = new StringBuilder();
            long lastFlush = System.currentTimeMillis();
            String line;
            while ((line = r.readLine()) != null) {
                buffer.append(line).append('\n');
                if (System.currentTimeMillis() - lastFlush > 200) {
                    appendConsole(buffer.toString());
                    buffer.setLength(0);
                    lastFlush = System.currentTimeMillis();
                }
            }
            appendConsole(buffer.toString());
            int exit = p.waitFor();
            SwingUtilities.invokeLater(() -> processFinished(command, exit));
        } catch (IOException | InterruptedException ex) {
            appendConsole("\n" + ex + "\n");
        }
    }

    private void appendConsole(String text) {
        if (text.isEmpty()) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            console.append(text);
            // keep the console reasonably small
            int max = 400_000;
            if (console.getDocument().getLength() > max) {
                console.replaceRange("", 0, console.getDocument().getLength() - max / 2);
            }
            console.setCaretPosition(console.getDocument().getLength());
        });
    }

    private void processFinished(String command, int exit) {
        runStatus.setForeground(exit == 0 ? new Color(0, 70, 160) : Color.RED);
        String what = command.equals("check") ? (exit == 0 ? "check passed" : "check found problems")
                : command + (exit == 0 ? " finished" : " failed (exit code " + exit + ")");
        runStatus.setText(what + ": " + simulationLabel());
        updateRunButtons();
        chart.reload();
        if (command.equals("run")) {
            loadResults();
        }
    }

    private void requestStop() {
        if (!isRunning()) {
            return;
        }
        try {
            Files.write(new File(processSimulationPath, STOP_FILE_NAME).toPath(), new byte[0]);
            runStatus.setText("stop requested - the results will be saved after the current time step");
        } catch (IOException ex) {
            error("The stop request cannot be written: " + ex.getMessage());
        }
    }

    private void kill() {
        if (!isRunning()) {
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "Kill the computation? The results since the last save will be lost.",
                "Kill", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    /**
     * called every second
     */
    private void monitor() {
        if (simulation != null) {
            chart.reload();
        }
        if (isRunning()) {
            double[] last = chart.last();
            if (last != null && processSimulationPath != null && processSimulationPath.equals(simulationDir().getAbsolutePath())) {
                runStatus.setText(String.format(Locale.ROOT, "running %s: %d steps, residuum %.3e, t = %.5g",
                        simulationLabel(), chart.numberOfSteps(), last[0], last[1]));
            }
        }
        updateRunButtons();
    }

    // ------------------------------------------------------------------- results
    private JPanel createResultTab() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(resultView, BorderLayout.CENTER);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(button("Load / refresh", this::loadResults));
        controls.add(new JLabel("show"));
        componentCombo.addActionListener(e -> {
            resultView.component = componentCombo.getSelectedIndex() - 1;
            resultView.repaint();
        });
        controls.add(componentCombo);
        JCheckBox edges = new JCheckBox("element edges", true);
        edges.addActionListener(e -> {
            resultView.showEdges = edges.isSelected();
            resultView.repaint();
        });
        controls.add(edges);
        controls.add(button("Zoom to fit", resultView::zoomToFit));
        controls.add(resultInfo);
        panel.add(controls, BorderLayout.SOUTH);
        return panel;
    }

    private void loadResults() {
        if (simulation == null) {
            return;
        }
        int previous = componentCombo.getSelectedIndex();
        int n = resultView.load(meshDir(), simulationDir());
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        model.addElement("mesh only");
        for (int i = 0; i < n; i++) {
            model.addElement("W[" + i + "]" + (i == 0 ? " (density)" : ""));
        }
        componentCombo.setModel(model);
        componentCombo.setSelectedIndex(previous > 0 && previous <= n ? previous : (n > 0 ? 1 : 0));
        resultInfo.setText(resultView.getMessage());
    }

    // =================================================================== misc
    private void error(String message) {
        JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE);
    }

    private void exit() {
        if (parameterModel.dirty) {
            int answer = JOptionPane.showConfirmDialog(this, "Save the changed parameters?", "Exit",
                    JOptionPane.YES_NO_CANCEL_OPTION);
            if (answer == JOptionPane.CANCEL_OPTION || (answer == JOptionPane.YES_OPTION && !saveParameters())) {
                return;
            }
        }
        if (isRunning()) {
            Object[] options = {"Stop and save", "Leave it running", "Cancel"};
            int answer = JOptionPane.showOptionDialog(this, "A computation is running. What should happen with it?",
                    "Exit", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
            if (answer == 2 || answer < 0) {
                return;
            }
            if (answer == 0) {
                requestStop();
            }
        }
        dispose();
        System.exit(0);
    }

    static File chooseFlowProDir(JFrame parent, File start) {
        JFileChooser fc = new JFileChooser(start);
        fc.setDialogTitle("Select the FlowPro directory (with FlowPro.jar and directory 'simulations')");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showDialog(parent, "Select") == JFileChooser.APPROVE_OPTION) {
            return fc.getSelectedFile();
        }
        return null;
    }

    private static boolean isFlowProDir(File dir) {
        return dir != null && new File(dir, "FlowPro.jar").isFile() && new File(dir, "simulations").isDirectory();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ex) {
                // default look and feel
            }
            Preferences prefs = Preferences.userNodeForPackage(FlowProControl.class);
            File dir = args.length > 0 ? new File(args[0]) : new File(System.getProperty("user.dir"));
            if (!isFlowProDir(dir) && isFlowProDir(new File(dir, "FlowPro"))) {
                dir = new File(dir, "FlowPro");
            }
            if (!isFlowProDir(dir)) {
                File remembered = new File(prefs.get("flowProDir", ""));
                dir = isFlowProDir(remembered) ? remembered : chooseFlowProDir(null, dir);
                if (dir == null) {
                    System.exit(0);
                }
            }
            prefs.put("flowProDir", dir.getAbsolutePath());
            new FlowProControl(dir).setVisible(true);
        });
    }
}
