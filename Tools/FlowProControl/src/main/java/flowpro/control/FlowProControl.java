package flowpro.control;

import flowpro.mesher.Geometry;
import flowpro.mesher.MeshEditorPanel;
import flowpro.mesher.TriMesh;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
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
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

/**
 * Graphical environment of FlowPro: tasks (simulations/GUI/NAME, one mesh and
 * any number of computations), generation of the 2D mesh of a task, editing
 * of parameters.txt of the computations, running (FlowPro is started as a
 * separate process), monitoring of the convergence, graceful stop and a
 * preview of the mesh and of the results.
 *
 * @author obublik
 */
public class FlowProControl extends JFrame {

    private static final String TITLE = "FlowPro";
    private static final String STOP_FILE_NAME = "STOP";
    private final Preferences prefs = Preferences.userNodeForPackage(FlowProControl.class);

    private File flowProDir;

    // tasks
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("tasks");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
    private final JTree tree = new JTree(treeModel);
    private boolean ignoreSelection = false;
    private String task, computation;

    // mesh
    private final MeshEditorPanel meshEditor = new MeshEditorPanel();
    private final JLabel meshHeader = new JLabel(" ");

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
    private String processComputationPath;
    private String processLabel;

    // monitoring
    private final ResiduumChart chart = new ResiduumChart();
    private final ResultView resultView = new ResultView();
    private final JComboBox<String> componentCombo = new JComboBox<>();
    private final JLabel resultInfo = new JLabel(" ");
    private final JTabbedPane tabs = new JTabbedPane();
    private JPanel meshTab, parametersTab, runTab, resultTab;

    public FlowProControl(File flowProDir) {
        super(TITLE);
        this.flowProDir = flowProDir;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exit();
            }
        });

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, createTaskPanel(), createTabs());
        split.setDividerLocation(260);
        add(split, BorderLayout.CENTER);

        setSize(1450, 900);
        setLocationRelativeTo(null);

        refreshTree();
        loadParameterDescriptions();
        updateMeshHeader();

        Timer timer = new Timer(1000, e -> monitor());
        timer.start();
    }

    // =================================================================== tasks
    private JPanel createTaskPanel() {
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
        buttons.add(button("New task...", this::newTask), c);
        c.gridy++;
        buttons.add(button("New computation...", this::newComputation), c);
        c.gridy++;
        buttons.add(button("Rename...", this::rename), c);
        c.gridy++;
        buttons.add(button("Delete...", this::delete), c);
        c.gridy++;
        buttons.add(button("Open folder", this::openFolder), c);
        c.gridy++;
        buttons.add(button("Refresh", this::refreshTree), c);
        c.gridy++;
        buttons.add(button("Change FlowPro directory...", () -> {
            if (!confirmLeaveTask() || !confirmLeaveComputation()) {
                return;
            }
            File dir = chooseFlowProDir(this, flowProDir);
            if (dir != null) {
                flowProDir = dir;
                prefs.put("flowProDir", dir.getAbsolutePath());
                updateDirLabel(dirLabel);
                select(null, null);
                refreshTree();
                loadParameterDescriptions();
            }
        }), c);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private void updateDirLabel(JLabel label) {
        label.setText("<html><b>Tasks</b> (simulations/" + Tasks.DIR + ")</html>");
        label.setToolTipText(Tasks.tasksDir(flowProDir).getAbsolutePath());
    }

    private static JButton button(String text, Runnable action) {
        JButton b = new JButton(text);
        b.addActionListener(e -> action.run());
        return b;
    }

    /**
     * node of the tree: a task (computation == null) or its computation
     */
    private static final class TaskNode {

        final String task, computation;
        final String label;

        TaskNode(String task, String computation, String label) {
            this.task = task;
            this.computation = computation;
            this.label = label;
        }

        boolean is(String t, String c) {
            return task.equals(t) && (computation == null ? c == null : computation.equals(c));
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private void refreshTree() {
        root.removeAllChildren();
        for (String name : Tasks.list(flowProDir)) {
            File dir = new File(Tasks.tasksDir(flowProDir), name);
            DefaultMutableTreeNode taskNode = new DefaultMutableTreeNode(
                    new TaskNode(name, null, Tasks.hasMesh(dir) ? name : name + "  (no mesh)"));
            for (String comp : Tasks.computations(dir)) {
                String label = isRunningIn(new File(dir, comp)) ? comp + "  (running)" : comp;
                taskNode.add(new DefaultMutableTreeNode(new TaskNode(name, comp, label)));
            }
            root.add(taskNode);
        }
        treeModel.reload();
        for (int i = 0; i < root.getChildCount(); i++) {
            tree.expandPath(new TreePath(((DefaultMutableTreeNode) root.getChildAt(i)).getPath()));
        }
        selectInTree(task, computation);
    }

    private void selectInTree(String t, String c) {
        ignoreSelection = true;
        try {
            tree.clearSelection();
            if (t == null) {
                return;
            }
            for (int i = 0; i < root.getChildCount(); i++) {
                DefaultMutableTreeNode taskNode = (DefaultMutableTreeNode) root.getChildAt(i);
                if (((TaskNode) taskNode.getUserObject()).is(t, c)) {
                    tree.setSelectionPath(new TreePath(taskNode.getPath()));
                }
                for (int j = 0; j < taskNode.getChildCount(); j++) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) taskNode.getChildAt(j);
                    if (((TaskNode) node.getUserObject()).is(t, c)) {
                        TreePath path = new TreePath(node.getPath());
                        tree.setSelectionPath(path);
                        tree.scrollPathToVisible(path);
                    }
                }
            }
        } finally {
            ignoreSelection = false;
        }
    }

    private void onTreeSelection() {
        if (ignoreSelection) {
            return;
        }
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
        if (node == null || !(node.getUserObject() instanceof TaskNode)) {
            return;
        }
        TaskNode n = (TaskNode) node.getUserObject();
        if (n.is(task, computation)) {
            return;
        }
        boolean taskChanged = !n.task.equals(task);
        if ((taskChanged && !confirmLeaveTask()) || !confirmLeaveComputation()) {
            selectInTree(task, computation); // stay where we are
            return;
        }
        select(n.task, n.computation);
        // saving of the mesh in confirmLeaveTask may have rebuilt the tree
        selectInTree(task, computation);
        if (n.computation == null) {
            tabs.setSelectedComponent(meshTab);
        } else if (tabs.getSelectedComponent() == meshTab) {
            tabs.setSelectedComponent(parametersTab);
        }
    }

    /**
     * Makes the task and the computation current (null = none) and loads
     * them into the tabs.
     */
    private void select(String newTask, String newComputation) {
        boolean taskChanged = newTask == null ? task != null : !newTask.equals(task);
        task = newTask;
        computation = newComputation;
        setTitle(task == null ? TITLE : TITLE + " – " + selectionLabel());
        if (taskChanged) {
            loadTaskMesh();
        }
        loadParameters();
        chart.setFile(computation == null ? null : new File(computationDir(), "residuum.txt"));
        loadResults();
    }

    private String selectionLabel() {
        return computation == null ? task : task + " / " + computation;
    }

    /**
     * name of the geometry for FlowPro (directory in simulations)
     */
    private String geometryName() {
        return Tasks.DIR + "/" + task;
    }

    private File taskDir() {
        return new File(Tasks.tasksDir(flowProDir), task);
    }

    private File computationDir() {
        return new File(taskDir(), computation);
    }

    private File meshDir() {
        return new File(taskDir(), Tasks.MESH);
    }

    private boolean requireTask() {
        if (task == null) {
            JOptionPane.showMessageDialog(this, "Select a task in the list first (or create a new one).");
            return false;
        }
        return true;
    }

    private boolean requireComputation() {
        if (computation == null) {
            JOptionPane.showMessageDialog(this, "Select a computation of the task in the list first.");
            return false;
        }
        return true;
    }

    private boolean isRunningIn(File dir) {
        if (!isRunning() || processComputationPath == null) {
            return false;
        }
        String path = dir.getAbsolutePath();
        return processComputationPath.equals(path) || processComputationPath.startsWith(path + File.separator);
    }

    private void newTask() {
        if (!confirmLeaveTask() || !confirmLeaveComputation()) {
            return;
        }
        JTextField nameField = new JTextField("task1", 20);
        JRadioButton empty = new JRadioButton("empty - draw the geometry and generate the mesh", true);
        JRadioButton copy = new JRadioButton("copy the mesh of an existing task or FlowPro geometry:");
        ButtonGroup group = new ButtonGroup();
        group.add(empty);
        group.add(copy);
        List<String> sources = Tasks.geometries(flowProDir);
        JComboBox<String> sourceBox = new JComboBox<>(sources.toArray(new String[0]));
        if (task != null) {
            sourceBox.setSelectedItem(geometryName());
        }
        JCheckBox copyComputations = new JCheckBox("copy also its computations (parameters only, without results)", true);
        sourceBox.setEnabled(false);
        copyComputations.setEnabled(false);
        copy.setEnabled(!sources.isEmpty());
        copy.addChangeListener(e -> {
            sourceBox.setEnabled(copy.isSelected());
            copyComputations.setEnabled(copy.isSelected());
        });
        JComboBox<Tasks.ModelTemplate> modelBox = new JComboBox<>(Tasks.ModelTemplate.values());
        Object[] form = {"name of the task (directory simulations/" + Tasks.DIR + "/NAME)", nameField, " ",
            empty, copy, sourceBox, copyComputations, " ",
            "model of the first computation (unless the computations are copied)", modelBox};
        if (JOptionPane.showConfirmDialog(this, form, "New task", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        String name = nameField.getText().trim();
        String problem = Tasks.checkName(name);
        File dir = new File(Tasks.tasksDir(flowProDir), name);
        if (problem == null && dir.exists()) {
            problem = "Task " + name + " already exists.";
        }
        if (problem != null) {
            error(problem);
            return;
        }
        String firstComputation = "default";
        try {
            Files.createDirectories(dir.toPath());
            boolean computationsCopied = false;
            if (copy.isSelected() && sourceBox.getSelectedItem() != null) {
                File source = new File(new File(flowProDir, "simulations"), (String) sourceBox.getSelectedItem());
                Tasks.copyDirectory(new File(source, Tasks.MESH).toPath(), new File(dir, Tasks.MESH).toPath());
                File geo = new File(source, Tasks.GEOMETRY_FILE);
                if (geo.isFile()) {
                    Files.copy(geo.toPath(), new File(dir, Tasks.GEOMETRY_FILE).toPath());
                }
                if (copyComputations.isSelected()) {
                    for (String comp : Tasks.computations(source)) {
                        Tasks.copyComputationSetup(new File(source, comp), new File(dir, comp));
                        if (!computationsCopied) {
                            firstComputation = comp;
                        }
                        computationsCopied = true;
                    }
                }
            }
            if (!computationsCopied) {
                Tasks.createComputation(new File(dir, firstComputation), (Tasks.ModelTemplate) modelBox.getSelectedItem());
            }
        } catch (IOException ex) {
            error("The task cannot be created: " + ex.getMessage());
            if (!dir.isDirectory()) {
                return;
            }
        }
        select(name, null);
        refreshTree();
        tabs.setSelectedComponent(meshTab);
    }

    private void newComputation() {
        if (!requireTask() || !confirmLeaveComputation()) {
            return;
        }
        List<String> existing = Tasks.computations(taskDir());
        JTextField nameField = new JTextField(computation != null ? computation + "_2" : "default", 20);
        Tasks.ModelTemplate[] templates = Tasks.ModelTemplate.values();
        List<String> sources = new ArrayList<>();
        for (Tasks.ModelTemplate template : templates) {
            sources.add("new: " + template.label);
        }
        for (String comp : existing) {
            sources.add("copy of " + comp);
        }
        JComboBox<String> sourceBox = new JComboBox<>(sources.toArray(new String[0]));
        if (computation != null) {
            sourceBox.setSelectedItem("copy of " + computation);
        }
        Object[] form = {"name of the computation of task " + task, nameField, " ",
            "parameters - a model template (check the boundary conditions!) or a copy", sourceBox,
            "(the results are never copied)"};
        if (JOptionPane.showConfirmDialog(this, form, "New computation", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        String name = nameField.getText().trim();
        String problem = Tasks.checkName(name);
        File dir = new File(taskDir(), name);
        if (problem == null && dir.exists()) {
            problem = "Computation " + name + " already exists.";
        }
        if (problem != null) {
            error(problem);
            return;
        }
        try {
            int source = Math.max(0, sourceBox.getSelectedIndex());
            if (source < templates.length) {
                Tasks.createComputation(dir, templates[source]);
            } else {
                Tasks.copyComputationSetup(new File(taskDir(), existing.get(source - templates.length)), dir);
            }
        } catch (IOException ex) {
            error("The computation cannot be created: " + ex.getMessage());
            return;
        }
        select(task, name);
        refreshTree();
        tabs.setSelectedComponent(parametersTab);
    }

    private void rename() {
        if (!requireTask()) {
            return;
        }
        boolean isTask = computation == null;
        File dir = isTask ? taskDir() : computationDir();
        if (isRunningIn(dir)) {
            error("A computation of " + selectionLabel() + " is running.");
            return;
        }
        if (!isTask && !confirmLeaveComputation()) {
            return;
        }
        String old = isTask ? task : computation;
        String name = JOptionPane.showInputDialog(this, "New name of the " + (isTask ? "task " : "computation ") + old + ":", old);
        if (name == null || name.trim().equals(old)) {
            return;
        }
        name = name.trim();
        File target = new File(dir.getParentFile(), name);
        String problem = Tasks.checkName(name);
        if (problem == null && target.exists()) {
            problem = name + " already exists.";
        }
        if (problem != null) {
            error(problem);
            return;
        }
        try {
            Files.move(dir.toPath(), target.toPath());
        } catch (IOException ex) {
            error("Renaming failed (is a file of it open in another program?): " + ex.getMessage());
            return;
        }
        if (isTask) {
            task = name; // the mesh editor keeps its (possibly unsaved) state
            updateMeshHeader();
            select(task, null);
        } else {
            select(task, name);
        }
        refreshTree();
    }

    private void delete() {
        if (!requireTask()) {
            return;
        }
        boolean isTask = computation == null;
        File dir = isTask ? taskDir() : computationDir();
        if (isRunningIn(dir)) {
            error("A computation of " + selectionLabel() + " is running.");
            return;
        }
        String what = isTask ? "task " + task + " with its mesh and all its computations and results"
                : "computation " + selectionLabel() + " with its results";
        if (JOptionPane.showConfirmDialog(this, "Delete " + what + "?\nThe files are deleted permanently.",
                "Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            Tasks.deleteDirectory(dir.toPath());
        } catch (IOException ex) {
            error("Deleting failed (is a file of it open in another program?): " + ex.getMessage());
        }
        parameterModel.dirty = false;
        if (isTask) {
            meshEditor.setModified(false);
            select(null, null);
        } else {
            select(task, null);
        }
        refreshTree();
    }

    private void openFolder() {
        if (!requireTask()) {
            return;
        }
        try {
            Desktop.getDesktop().open(computation == null ? taskDir() : computationDir());
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException ex) {
            error("The folder cannot be opened: " + ex.getMessage());
        }
    }

    // =================================================================== tabs
    private JTabbedPane createTabs() {
        meshTab = createMeshTab();
        parametersTab = createParametersTab();
        runTab = createRunTab();
        resultTab = createResultTab();
        tabs.addTab("Mesh", meshTab);
        tabs.addTab("Parameters", parametersTab);
        tabs.addTab("Run", runTab);
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
        tabs.addTab("Results", resultTab);
        return tabs;
    }

    // ------------------------------------------------------------------- mesh
    private JPanel createMeshTab() {
        JPanel panel = new JPanel(new BorderLayout());
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        header.add(meshHeader);
        header.add(button("Import points...", () -> {
            if (requireTask()) {
                meshEditor.importPoints(taskDir());
            }
        }));
        header.add(button("Import geometry (.geo2d)...", this::importGeometry));
        panel.add(header, BorderLayout.NORTH);
        meshEditor.addMeshAction("Save mesh to task", this::saveMeshToTask);
        meshEditor.addMeshAction("Save geometry only", this::saveGeometryOnly);
        meshEditor.addMeshAction("Revert to saved", () -> {
            if (requireTask() && (!meshEditor.isModified()
                    || confirm("Discard the changes of the geometry and the mesh of task " + task + "?"))) {
                loadTaskMesh();
            }
        });
        meshEditor.setChangeListener(this::updateMeshHeader);
        panel.add(meshEditor, BorderLayout.CENTER);
        return panel;
    }

    private void updateMeshHeader() {
        if (task == null) {
            meshHeader.setText("<html><b>No task selected</b> - select a task or create a new one</html>");
            return;
        }
        String state = meshEditor.isModified() ? "<font color='#c00000'>unsaved changes</font>"
                : (Tasks.hasMesh(taskDir()) ? "saved" : "<font color='#c00000'>no mesh yet</font>");
        meshHeader.setText("<html><b>Task " + task + "</b> - " + state + "</html>");
    }

    private File taskGeometryFile() {
        return new File(taskDir(), Tasks.GEOMETRY_FILE);
    }

    /**
     * Loads the geometry of the current task and its mesh, if it corresponds
     * to the geometry, into the mesh editor.
     */
    private void loadTaskMesh() {
        if (meshEditor.isMeshing()) {
            meshEditor.generateOrCancel(); // cancels the meshing of the previous task
        }
        if (task == null) {
            meshEditor.setGeometry(new Geometry());
            meshEditor.setInfo(MeshEditorPanel.helpText());
            updateMeshHeader();
            return;
        }
        File geoFile = taskGeometryFile();
        File meshGeoFile = new File(meshDir(), Tasks.GEOMETRY_FILE);
        String message;
        try {
            File source = geoFile.isFile() ? geoFile : meshGeoFile;
            meshEditor.setGeometry(source.isFile() ? Geometry.load(source) : new Geometry());
            boolean sameGeometry = !geoFile.isFile() || (meshGeoFile.isFile()
                    && Files.mismatch(geoFile.toPath(), meshGeoFile.toPath()) == -1);
            if (!Tasks.hasMesh(taskDir())) {
                message = "The task has no mesh yet.\n\n" + MeshEditorPanel.helpText()
                        + "\n\nGenerate the mesh and save it to the task.";
            } else if (!sameGeometry) {
                message = "The geometry was changed after the mesh had been saved, the task still uses the old mesh "
                        + "(see tab Results). Generate the mesh and save it to the task.";
            } else {
                TriMesh mesh = TriMesh.loadFlowPro(meshDir());
                if (mesh == null) {
                    message = "The mesh of the task was not created by the generator (it does not consist "
                            + "of triangles only), see it on tab Results. A mesh generated and saved here replaces it.";
                } else {
                    meshEditor.setMesh(mesh);
                    message = "Mesh of the task:\n" + mesh.statistics().replace(", ", "\n");
                }
            }
        } catch (IOException ex) {
            meshEditor.setGeometry(new Geometry());
            message = "The geometry or the mesh of the task cannot be read: " + ex.getMessage();
        }
        meshEditor.setInfo(message);
        meshEditor.zoomToFit();
        meshEditor.setModified(false);
        updateMeshHeader();
    }

    private void importGeometry() {
        if (!requireTask()) {
            return;
        }
        if (meshEditor.isModified() && !confirm("Replace the changed geometry of task " + task + "?")) {
            return;
        }
        JFileChooser fc = new JFileChooser(new File(flowProDir, "simulations"));
        fc.setFileFilter(new FileNameExtensionFilter("FlowPro 2D geometry (*.geo2d)", "geo2d"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                meshEditor.setGeometry(Geometry.load(fc.getSelectedFile()));
                meshEditor.setModified(true);
                meshEditor.setInfo("Geometry imported from " + fc.getSelectedFile()
                        + ".\nGenerate the mesh and save it to the task.");
                updateMeshHeader();
            } catch (IOException ex) {
                error(ex.getMessage());
            }
        }
    }

    private boolean saveGeometryOnly() {
        if (!requireTask() || !meshEditor.readParameters()) {
            return false;
        }
        try {
            meshEditor.getGeometry().save(taskGeometryFile());
            meshEditor.setModified(false);
            meshEditor.setInfo("The geometry was saved into " + taskGeometryFile()
                    + (meshEditor.getMesh() == null ? ".\nThe task still uses its previous mesh." : "."));
            updateMeshHeader();
            return true;
        } catch (IOException ex) {
            error("The geometry cannot be saved: " + ex.getMessage());
            return false;
        }
    }

    private boolean saveMeshToTask() {
        if (!requireTask()) {
            return false;
        }
        TriMesh mesh = meshEditor.getMesh();
        if (mesh == null) {
            error("Generate the mesh first (or use 'Save geometry only').");
            return false;
        }
        if (isRunningIn(taskDir())) {
            error("A computation of task " + task + " is running, the mesh cannot be replaced now.");
            return false;
        }
        if (!meshEditor.readParameters()) {
            return false;
        }
        List<String> withResults = new ArrayList<>();
        for (String comp : Tasks.computations(taskDir())) {
            if (Tasks.hasResults(new File(taskDir(), comp))) {
                withResults.add(comp);
            }
        }
        if (Tasks.hasMesh(taskDir()) && !withResults.isEmpty() && !confirm("The results of computations " + withResults
                + "\nwere computed on the previous mesh and will not correspond to the new one\n"
                + "(run them again without 'continue computation'). Replace the mesh of task " + task + "?")) {
            return false;
        }
        try {
            List<String> deleted = mesh.exportFlowPro(meshDir());
            meshEditor.getGeometry().save(new File(meshDir(), Tasks.GEOMETRY_FILE));
            meshEditor.getGeometry().save(taskGeometryFile());
            meshEditor.setModified(false);
            meshEditor.setInfo("The mesh was saved into " + meshDir() + ":\n" + mesh.statistics().replace(", ", "\n")
                    + (deleted.isEmpty() ? "" : "\n\nFiles derived from the previous mesh were deleted: " + deleted));
        } catch (IOException ex) {
            error("The mesh cannot be saved: " + ex.getMessage());
            return false;
        }
        updateMeshHeader();
        refreshTree();
        loadResults();
        return true;
    }

    /**
     * Asks what to do with the unsaved geometry / mesh before another task is
     * selected.
     *
     * @return false if the user cancelled it
     */
    private boolean confirmLeaveTask() {
        if (task == null) {
            return true;
        }
        if (meshEditor.isMeshing()) {
            JOptionPane.showMessageDialog(this, "The mesh of task " + task + " is being generated, wait or cancel it.");
            return false;
        }
        if (!meshEditor.isModified()) {
            return true;
        }
        boolean hasMesh = meshEditor.getMesh() != null;
        Object[] options = {hasMesh ? "Save mesh" : "Save geometry", "Discard", "Cancel"};
        int answer = JOptionPane.showOptionDialog(this, "The geometry" + (hasMesh ? " and the mesh" : "") + " of task "
                + task + " were changed." + (hasMesh ? "" : "\n(The mesh was not generated, the task keeps its old mesh.)"),
                "Unsaved mesh", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (answer == 0) {
            return hasMesh ? saveMeshToTask() : saveGeometryOnly();
        }
        if (answer == 1) {
            meshEditor.setModified(false);
            return true;
        }
        return false;
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
        parameterFile = null;
        parameterModel.rows = new ArrayList<>();
        parameterModel.dirty = false;
        parameterInfo.setText(task == null ? " " : "select a computation of task " + task);
        if (computation != null) {
            try {
                parameterFile = new ParameterFile(new File(computationDir(), "parameters.txt"));
                parameterModel.rows = parameterFile.parameters();
                parameterInfo.setText(parameterFile.file.getPath());
            } catch (IOException ex) {
                error("parameters.txt cannot be read: " + ex.getMessage());
            }
        }
        parameterModel.fireTableDataChanged();
        continueBox.setSelected(false);
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

    /**
     * Asks what to do with the changed parameters before another computation
     * is selected.
     *
     * @return false if the user cancelled it
     */
    private boolean confirmLeaveComputation() {
        if (parameterTable.isEditing()) {
            parameterTable.getCellEditor().stopCellEditing();
        }
        if (!parameterModel.dirty) {
            return true;
        }
        int answer = JOptionPane.showConfirmDialog(this, "Save the changed parameters of " + selectionLabel() + "?",
                "Unsaved parameters", JOptionPane.YES_NO_CANCEL_OPTION);
        if (answer == JOptionPane.YES_OPTION) {
            return saveParameters();
        }
        if (answer == JOptionPane.NO_OPTION) {
            parameterModel.dirty = false;
            return true;
        }
        return false;
    }

    private void addParameter() {
        if (parameterFile == null) {
            requireComputation();
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
     * Starts FlowPro: java -Xmx..g -jar FlowPro.jar COMMAND -g GUI/TASK -s
     * COMPUTATION [-d N] [-p key=value]...
     */
    private void runFlowPro(String command) {
        if (!requireTask() || !requireComputation()) {
            return;
        }
        if (isRunning()) {
            JOptionPane.showMessageDialog(this, "A computation is already running (" + processLabel + ").");
            return;
        }
        if (!Tasks.hasMesh(taskDir())) {
            error("Task " + task + " has no mesh yet. Generate it on tab Mesh and save it to the task.");
            tabs.setSelectedComponent(meshTab);
            return;
        }
        if (meshEditor.isModified() && !command.equals("postprocessing")
                && !confirm("The geometry / mesh of task " + task + " has unsaved changes,\n"
                        + "the computation uses the mesh saved in the task. Continue?")) {
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
        cmd.add(geometryName());
        cmd.add("--simulation");
        cmd.add(computation);
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
            new File(computationDir(), STOP_FILE_NAME).delete();
            if (!continueBox.isSelected()) {
                chart.setFile(new File(computationDir(), "residuum.txt"));
            }
        }
        tabs.setSelectedComponent(runTab);

        console.setText(String.join(" ", cmd) + "\n\n");
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(flowProDir).redirectErrorStream(true);
            process = pb.start();
            processComputationPath = computationDir().getAbsolutePath();
            processLabel = selectionLabel();
            runStatus.setForeground(new Color(0, 110, 0));
            runStatus.setText(command + " running: " + processLabel);
            Process started = process;
            Thread reader = new Thread(() -> readOutput(started, command), "flowpro-output");
            reader.setDaemon(true);
            reader.start();
        } catch (IOException ex) {
            error("FlowPro cannot be started: " + ex.getMessage());
        }
        updateRunButtons();
        if (command.equals("run")) {
            refreshTree();
        }
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

    private boolean isProcessOfSelection() {
        return processComputationPath != null && computation != null
                && processComputationPath.equals(computationDir().getAbsolutePath());
    }

    private void processFinished(String command, int exit) {
        runStatus.setForeground(exit == 0 ? new Color(0, 70, 160) : Color.RED);
        String what = command.equals("check") ? (exit == 0 ? "check passed" : "check found problems")
                : command + (exit == 0 ? " finished" : " failed (exit code " + exit + ")");
        runStatus.setText(what + ": " + processLabel);
        updateRunButtons();
        chart.reload();
        if (command.equals("run")) {
            if (isProcessOfSelection()) {
                loadResults();
            }
            refreshTree();
        }
    }

    private void requestStop() {
        if (!isRunning()) {
            return;
        }
        try {
            Files.write(new File(processComputationPath, STOP_FILE_NAME).toPath(), new byte[0]);
            runStatus.setText("stop requested - the results of " + processLabel
                    + " will be saved after the current time step");
        } catch (IOException ex) {
            error("The stop request cannot be written: " + ex.getMessage());
        }
    }

    private void kill() {
        if (!isRunning()) {
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "Kill the computation " + processLabel
                + "? The results since the last save will be lost.", "Kill", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    /**
     * called every second
     */
    private void monitor() {
        if (computation != null) {
            chart.reload();
        }
        if (isRunning() && isProcessOfSelection()) {
            double[] last = chart.last();
            if (last != null) {
                runStatus.setText(String.format(Locale.ROOT, "running %s: %d steps, residuum %.3e, t = %.5g",
                        processLabel, chart.numberOfSteps(), last[0], last[1]));
            }
        }
        updateRunButtons();
    }

    // ------------------------------------------------------------------- results
    /**
     * Quantity offered for plotting: a component of the solution (W0, W1, ...)
     * or a quantity of parameter results evaluated by the model. Vector
     * quantities are shown as the magnitude or as one component.
     */
    private static final class Quantity {

        final String label;
        final String name; // name for FlowPro postprocessing, null = mesh only
        final int column; // -1 = scalar or magnitude of a vector, otherwise the component
        final int wComponent; // column of W.txt for the element averages, -1 if not a component of W

        Quantity(String label, String name, int column, int wComponent) {
            this.label = label;
            this.name = name;
            this.column = column;
            this.wComponent = wComponent;
        }
    }

    private static final String[] DISPLAY_MODES = {"element averages", "inside elements (smooth)", "inside elements (flat)"};
    private final JComboBox<String> displayCombo = new JComboBox<>(DISPLAY_MODES);
    private final JSpinner subdivisionSpinner = new JSpinner(new SpinnerNumberModel(4, 1, 12, 1));
    private final List<Quantity> quantities = new ArrayList<>();
    private boolean updatingQuantities;
    // last output of the postprocessing: key (computation, parts, time stamps), points, polygons, values
    private String exportKey;
    private double[][] exportPoints;
    private int[][] exportPolygons;
    private final Map<String, double[][]> exportValues = new LinkedHashMap<>();
    private String exportRunningKey;

    private JPanel createResultTab() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(resultView, BorderLayout.CENTER);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(button("Load / refresh", this::loadResults));
        controls.add(new JLabel("show"));
        componentCombo.addActionListener(e -> {
            if (!updatingQuantities) {
                showSelectedQuantity();
            }
        });
        controls.add(componentCombo);
        displayCombo.setToolTipText("element averages: one colour per element; inside elements: the polynomial "
                + "solution evaluated in the elements divided into parts (FlowPro postprocessing)");
        displayCombo.addActionListener(e -> {
            subdivisionSpinner.setEnabled(displayCombo.getSelectedIndex() > 0);
            showSelectedQuantity();
        });
        controls.add(displayCombo);
        controls.add(new JLabel("parts per edge"));
        subdivisionSpinner.setToolTipText("each element is divided into N parts per edge (higher orders need more)");
        subdivisionSpinner.setEnabled(false);
        subdivisionSpinner.addChangeListener(e -> showSelectedQuantity());
        controls.add(subdivisionSpinner);
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
        int index = componentCombo.getSelectedIndex();
        String previous = index >= 0 && index < quantities.size() ? quantities.get(index).label : null;
        quantities.clear();
        quantities.add(new Quantity("mesh only", null, -1, -1));
        int n = 0;
        if (task != null) {
            // a task without a computation: its mesh only (there is no W.txt in the task directory)
            n = resultView.load(meshDir(), computation == null ? taskDir() : computationDir());
            for (int i = 0; i < n; i++) {
                quantities.add(new Quantity("W[" + i + "]", "W" + i, -1, i));
            }
            if (n > 0) {
                for (String name : resultNames()) {
                    quantities.add(new Quantity(name, name, -1, -1));
                }
            }
        } else {
            resultView.load(new File(flowProDir, "-"), new File(flowProDir, "-"));
        }
        exportKey = null;
        exportValues.clear();
        setQuantityItems(previous, n > 0 ? 1 : 0);
        resultInfo.setText(task == null ? " " : resultView.getMessage());
        showSelectedQuantity();
    }

    // names of parameter results of the selected computation (e.g. mach, pressure, velocity)
    private List<String> resultNames() {
        List<String> names = new ArrayList<>();
        if (computation == null) {
            return names;
        }
        File file = new File(computationDir(), "parameters.txt");
        if (!file.isFile()) {
            return names;
        }
        try {
            String value = new ParameterFile(file).get("results");
            if (value != null) {
                for (String name : value.split(",")) {
                    name = name.trim();
                    if (!name.isEmpty() && !names.contains(name)) {
                        names.add(name);
                    }
                }
            }
        } catch (IOException ex) {
            // no results
        }
        return names;
    }

    private void setQuantityItems(String selectLabel, int defaultIndex) {
        updatingQuantities = true;
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        int select = defaultIndex;
        for (int i = 0; i < quantities.size(); i++) {
            model.addElement(quantities.get(i).label);
            if (quantities.get(i).label.equals(selectLabel)) {
                select = i;
            }
        }
        componentCombo.setModel(model);
        componentCombo.setSelectedIndex(Math.min(select, quantities.size() - 1));
        updatingQuantities = false;
    }

    private void showSelectedQuantity() {
        int index = componentCombo.getSelectedIndex();
        Quantity q = index >= 0 && index < quantities.size() ? quantities.get(index) : null;
        int mode = displayCombo.getSelectedIndex();
        if (q == null || q.name == null || !resultView.hasMesh()) {
            resultView.component = -1;
            resultView.clearField();
            return;
        }
        if (mode == 0 && q.wComponent >= 0) { // element averages of W directly from W.txt
            resultView.component = q.wComponent;
            resultView.clearField();
            return;
        }
        // element averages of the other quantities: the mean of the values in the vertices
        int parts = mode == 0 ? 1 : (Integer) subdivisionSpinner.getValue();
        boolean smooth = mode == 1;
        ensureExport(parts, () -> {
            double[][] v = exportValues.get(q.name);
            if (v == null) {
                resultInfo.setText("quantity " + q.name + " was not computed (see the console on tab Run)");
                resultView.clearField();
                return;
            }
            double[] scalar = new double[v.length];
            for (int i = 0; i < v.length; i++) {
                if (q.column >= 0) {
                    scalar[i] = q.column < v[i].length ? v[i][q.column] : Double.NaN;
                } else if (v[i].length == 1) {
                    scalar[i] = v[i][0];
                } else {
                    double sum = 0;
                    for (double c : v[i]) {
                        sum += c * c;
                    }
                    scalar[i] = Math.sqrt(sum);
                }
            }
            String label = q.label + (mode == 0 ? " (element averages)" : " (" + parts + " parts per edge)");
            resultView.setField(exportPoints, exportPolygons, scalar, smooth, label);
            resultInfo.setText(resultView.getMessage());
        });
    }

    /**
     * Runs FlowPro postprocessing (java -jar FlowPro.jar postprocessing -ftxt -dN
     * -oDIR W0 ... results ...) for the selected computation unless its output
     * is up to date, then calls whenReady in the event thread.
     */
    private void ensureExport(int parts, Runnable whenReady) {
        File compDir = computationDir();
        File we = new File(compDir, "We.txt");
        if (!we.isFile()) {
            resultInfo.setText("no polynomial solution (We.txt) yet");
            resultView.clearField();
            return;
        }
        String dirName = "gui_d" + parts;
        String key = compDir.getAbsolutePath() + "|" + parts + "|" + we.lastModified()
                + "|" + new File(compDir, "parameters.txt").lastModified();
        if (key.equals(exportKey)) {
            whenReady.run();
            return;
        }
        if (key.equals(exportRunningKey)) {
            return; // the same postprocessing is running
        }
        List<String> names = new ArrayList<>();
        for (Quantity q : quantities) {
            if (q.name != null && !names.contains(q.name)) {
                names.add(q.name);
            }
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExecutable());
        cmd.add("-Xmx" + memorySpinner.getValue() + "g");
        cmd.add("-Xss10m");
        cmd.add("-jar");
        cmd.add("FlowPro.jar");
        cmd.add("postprocessing");
        cmd.add("-ftxt");
        cmd.add("-d" + parts);
        cmd.add("-o" + dirName);
        cmd.addAll(names);
        cmd.add("--geometry");
        cmd.add(geometryName());
        cmd.add("--simulation");
        cmd.add(computation);
        exportRunningKey = key;
        resultInfo.setText("evaluating the solution in the elements (FlowPro postprocessing) ...");
        File outDir = new File(compDir, dirName);
        Thread worker = new Thread(() -> {
            StringBuilder output = new StringBuilder();
            int exit;
            try {
                Process p = new ProcessBuilder(cmd).directory(flowProDir).redirectErrorStream(true).start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        output.append(line).append('\n');
                    }
                }
                exit = p.waitFor();
            } catch (IOException | InterruptedException ex) {
                output.append(ex).append('\n');
                exit = -1;
            }
            double[][] pts = null;
            int[][] polys = null;
            Map<String, double[][]> vals = new LinkedHashMap<>();
            String problem = null;
            if (exit == 0) {
                try {
                    pts = ResultView.readDoubles(new File(outDir, "vertices.txt"));
                    polys = ResultView.readInts(new File(outDir, "elements.txt"));
                    for (String name : names) {
                        File f = new File(outDir, name + ".txt");
                        if (f.isFile()) {
                            vals.put(name, ResultView.readDoubles(f));
                        }
                    }
                } catch (IOException | RuntimeException ex) {
                    problem = "the output of the postprocessing cannot be read: " + ex.getMessage();
                }
            } else {
                problem = "postprocessing failed (exit code " + exit + ")";
            }
            double[][] fpts = pts;
            int[][] fpolys = polys;
            String fproblem = problem;
            String log = output.toString();
            SwingUtilities.invokeLater(() -> exportFinished(key, fpts, fpolys, vals, fproblem, log, cmd));
        }, "flowpro-postprocessing");
        worker.setDaemon(true);
        worker.start();
    }

    private void exportFinished(String key, double[][] pts, int[][] polys, Map<String, double[][]> vals,
            String problem, String log, List<String> cmd) {
        if (key.equals(exportRunningKey)) {
            exportRunningKey = null;
        }
        if (problem != null) {
            appendConsole(String.join(" ", cmd) + "\n" + log);
            resultInfo.setText(problem + " - see the console on tab Run");
            return;
        }
        if (computation == null || !key.startsWith(computationDir().getAbsolutePath() + "|")) {
            return; // another computation has been selected meanwhile
        }
        exportKey = key;
        exportPoints = pts;
        exportPolygons = polys;
        exportValues.clear();
        exportValues.putAll(vals);
        addVectorComponents();
        // the selection may have changed while the postprocessing was running
        showSelectedQuantity();
    }

    // vector quantities (several columns) get also the entries of their components
    private void addVectorComponents() {
        int index = componentCombo.getSelectedIndex();
        String selected = index >= 0 && index < quantities.size() ? quantities.get(index).label : null;
        boolean changed = false;
        String[] axes = {"x", "y", "z"};
        for (int i = 0; i < quantities.size(); i++) {
            Quantity q = quantities.get(i);
            double[][] v = q.name == null || q.column >= 0 || q.label.endsWith("(magnitude)") ? null : exportValues.get(q.name);
            if (v == null || v.length == 0 || v[0].length < 2) {
                continue;
            }
            // 2D: the third column of the exported vectors is zero
            int nComponents = Math.min(v[0].length, 2);
            quantities.set(i, new Quantity(q.name + " (magnitude)", q.name, -1, -1));
            for (int c = 0; c < nComponents; c++) {
                quantities.add(i + 1 + c, new Quantity(q.name + " (" + axes[c] + ")", q.name, c, -1));
            }
            if (q.label.equals(selected)) {
                selected = q.name + " (magnitude)";
            }
            changed = true;
        }
        if (changed) {
            setQuantityItems(selected, 0);
        }
    }

    // =================================================================== misc
    private boolean confirm(String message) {
        return JOptionPane.showConfirmDialog(this, message, "Confirm", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE);
    }

    private void exit() {
        if (!confirmLeaveTask() || !confirmLeaveComputation()) {
            return;
        }
        if (isRunning()) {
            Object[] options = {"Stop and save", "Leave it running", "Cancel"};
            int answer = JOptionPane.showOptionDialog(this, "Computation " + processLabel
                    + " is running. What should happen with it?",
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
