package flowpro.mesher;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * GUI of the 2D mesh generator for FlowPro.
 *
 * @author obublik
 */
public class MeshGeneratorApp extends JFrame {

    private static final String[] TYPE_CHOICES = {
        "-1  wall", "-2  inlet", "-3  outlet", "-4  inviscid wall"
    };

    private final MeshCanvas canvas;
    private Geometry geo = new Geometry();
    private File geometryFile;
    private File flowProDir;

    private final JTextField meshSizeField = new JTextField(8);
    private final JTextField minAngleField = new JTextField(8);
    private final JTextField gradingField = new JTextField(8);
    private final JTextField smoothingField = new JTextField(8);
    private final JTextField gridField = new JTextField(8);
    private final JTextField circleField = new JTextField(8);
    private final JCheckBox snapBox = new JCheckBox("snap to grid");
    private final JCheckBox showMeshBox = new JCheckBox("show mesh", true);

    private final JLabel selectionLabel = new JLabel("nothing selected");
    private final JComboBox<String> typeCombo = new JComboBox<>(TYPE_CHOICES);
    private final JTextField localSizeField = new JTextField(8);
    private final JTextField aleField = new JTextField(8);
    private final JComboBox<String> defaultTypeCombo = new JComboBox<>(TYPE_CHOICES);

    private final JTextArea info = new JTextArea(8, 26);
    private final JLabel status = new JLabel(" ");
    private final JButton generateButton = new JButton("Generate mesh");
    private SwingWorker<TriMesh, String> worker;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public MeshGeneratorApp() {
        super("FlowPro – 2D mesh generator");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        flowProDir = guessFlowProDir();

        canvas = new MeshCanvas(geo);
        canvas.setStatusListener(s -> status.setText(s));
        canvas.setSelectionListener(this::updateSelectionPanel);
        canvas.setGeometryListener(() -> info.setText("geometry changed, generate the mesh again"));
        typeCombo.setEditable(true);
        defaultTypeCombo.setEditable(true);

        setJMenuBar(createMenu());
        add(createToolBar(), BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        add(new JScrollPane(createSidePanel()), BorderLayout.EAST);
        add(status, BorderLayout.SOUTH);
        status.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));

        loadParametersToFields();
        info.setEditable(false);
        info.setLineWrap(true);
        info.setWrapStyleWord(true);
        info.setText(helpText());

        setSize(1300, 850);
        setLocationRelativeTo(null);
    }

    private static String helpText() {
        return "Draw the outer boundary first (polygon, rectangle, circle or NACA profile), "
                + "then the bodies (holes).\n\n"
                + "Polygon: click the points, double click or Enter closes it, Backspace removes the last point.\n"
                + "Select: click a point (drag to move) or a segment (edit its boundary condition), "
                + "double click a segment to insert a point, Delete removes a point / loop.\n"
                + "Right or middle drag (or Ctrl + drag) pans, the wheel zooms.\n\n"
                + "Local mesh size of a segment refines the mesh around it, the mesh size grows "
                + "with the distance from the segment according to 'grading'.";
    }

    private static File guessFlowProDir() {
        File cwd = new File(System.getProperty("user.dir"));
        if (new File(cwd, "simulations").isDirectory()) {
            return cwd;
        }
        if (new File(cwd, "FlowPro/simulations").isDirectory()) {
            return new File(cwd, "FlowPro");
        }
        return cwd;
    }

    // ---------------------------------------------------------------- UI construction
    private JMenuBar createMenu() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        file.add(menuItem("New geometry", KeyEvent.VK_N, () -> {
            if (confirm("Discard the current geometry?")) {
                setGeometry(new Geometry(), null);
            }
        }));
        file.add(menuItem("Open geometry...", KeyEvent.VK_O, this::openGeometry));
        file.add(menuItem("Save geometry", KeyEvent.VK_S, () -> saveGeometry(false)));
        file.add(menuItem("Save geometry as...", 0, () -> saveGeometry(true)));
        file.addSeparator();
        file.add(menuItem("Import points (x y per line)...", 0, this::importPoints));
        file.add(menuItem("Export mesh to FlowPro...", KeyEvent.VK_E, this::exportMesh));
        file.addSeparator();
        file.add(menuItem("Exit", 0, () -> dispose()));
        bar.add(file);

        JMenu shapes = new JMenu("Shapes");
        shapes.add(menuItem("NACA 4-digit airfoil...", 0, this::addNaca));
        shapes.add(menuItem("Rectangular channel (inlet / outlet / walls)...", 0, this::addChannel));
        bar.add(shapes);

        JMenu view = new JMenu("View");
        view.add(menuItem("Zoom to fit", KeyEvent.VK_F, canvas::zoomToFit));
        bar.add(view);
        return bar;
    }

    private JMenuItem menuItem(String text, int key, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        if (key != 0) {
            item.setAccelerator(KeyStroke.getKeyStroke(key, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        }
        item.addActionListener(e -> action.run());
        return item;
    }

    private JToolBar createToolBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        ButtonGroup group = new ButtonGroup();
        for (MeshCanvas.Mode mode : MeshCanvas.Mode.values()) {
            JToggleButton b = new JToggleButton(mode.name().charAt(0) + mode.name().substring(1).toLowerCase());
            b.addActionListener(e -> canvas.setMode(mode));
            group.add(b);
            bar.add(b);
            if (mode == MeshCanvas.Mode.SELECT) {
                b.setSelected(true);
            }
        }
        bar.addSeparator();
        bar.add(new JLabel("type of new loops: "));
        defaultTypeCombo.setMaximumSize(new Dimension(150, 30));
        defaultTypeCombo.addActionListener(e -> {
            Integer t = parseType((String) defaultTypeCombo.getSelectedItem());
            if (t != null) {
                canvas.defaultType = t;
            }
        });
        bar.add(defaultTypeCombo);
        bar.addSeparator();
        JButton naca = new JButton("NACA...");
        naca.addActionListener(e -> addNaca());
        bar.add(naca);
        JButton fit = new JButton("Zoom to fit");
        fit.addActionListener(e -> canvas.zoomToFit());
        bar.add(fit);
        JButton delete = new JButton("Delete selected");
        delete.addActionListener(e -> canvas.deleteSelection());
        bar.add(delete);
        return bar;
    }

    private JPanel createSidePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 2, 2, 2);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.gridy = 0;

        JPanel mesh = section("Mesh");
        addRow(mesh, "mesh size h", meshSizeField, 0);
        addRow(mesh, "min angle [°]", minAngleField, 1);
        addRow(mesh, "grading", gradingField, 2);
        addRow(mesh, "smoothing iterations", smoothingField, 3);
        GridBagConstraints mc = new GridBagConstraints();
        mc.gridx = 0;
        mc.gridy = 4;
        mc.gridwidth = 2;
        mc.fill = GridBagConstraints.HORIZONTAL;
        mc.insets = new Insets(4, 2, 2, 2);
        generateButton.addActionListener(e -> generateOrCancel());
        mesh.add(generateButton, mc);
        mc.gridy = 5;
        showMeshBox.addActionListener(e -> {
            canvas.showMesh = showMeshBox.isSelected();
            canvas.repaint();
        });
        mesh.add(showMeshBox, mc);
        mc.gridy = 6;
        JButton export = new JButton("Export to FlowPro...");
        export.addActionListener(e -> exportMesh());
        mesh.add(export, mc);
        panel.add(mesh, c);

        c.gridy++;
        JPanel sel = section("Selected segment / loop");
        GridBagConstraints sc = new GridBagConstraints();
        sc.gridx = 0;
        sc.gridy = 0;
        sc.gridwidth = 2;
        sc.fill = GridBagConstraints.HORIZONTAL;
        sel.add(selectionLabel, sc);
        addRow(sel, "boundary type", typeCombo, 1);
        addRow(sel, "local size (empty = h)", localSizeField, 2);
        addRow(sel, "ALE body tag (0 = none)", aleField, 3);
        sc.gridy = 4;
        sc.gridwidth = 1;
        JButton applySeg = new JButton("Apply to segment");
        applySeg.addActionListener(e -> applyAttributes(false));
        sel.add(applySeg, sc);
        sc.gridx = 1;
        JButton applyLoop = new JButton("Apply to loop");
        applyLoop.addActionListener(e -> applyAttributes(true));
        sel.add(applyLoop, sc);
        panel.add(sel, c);

        c.gridy++;
        JPanel draw = section("Drawing");
        addRow(draw, "grid step", gridField, 0);
        addRow(draw, "points of circle", circleField, 1);
        GridBagConstraints dc = new GridBagConstraints();
        dc.gridx = 0;
        dc.gridy = 2;
        dc.gridwidth = 2;
        dc.anchor = GridBagConstraints.WEST;
        snapBox.addActionListener(e -> readDrawingFields());
        draw.add(snapBox, dc);
        gridField.addActionListener(e -> readDrawingFields());
        circleField.addActionListener(e -> readDrawingFields());
        panel.add(draw, c);

        c.gridy++;
        JPanel infoPanel = section("Info");
        infoPanel.setLayout(new BorderLayout());
        infoPanel.add(new JScrollPane(info), BorderLayout.CENTER);
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        panel.add(infoPanel, c);

        updateSelectionPanel();
        return panel;
    }

    private static JPanel section(String title) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder(title));
        return p;
    }

    private static void addRow(JPanel panel, String label, JComponent field, int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.gridx = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 2, 2, 6);
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        panel.add(field, c);
    }

    // ---------------------------------------------------------------- parameters
    private void loadParametersToFields() {
        meshSizeField.setText(fmt(geo.meshSize));
        minAngleField.setText(fmt(geo.minAngle));
        gradingField.setText(fmt(geo.grading));
        smoothingField.setText(Integer.toString(geo.smoothingIterations));
        gridField.setText(fmt(canvas.gridStep));
        circleField.setText(Integer.toString(canvas.circlePoints));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6g", v).replaceAll("\\.?0+(e|$)", "$1");
    }

    private boolean readParameters() {
        try {
            geo.meshSize = Double.parseDouble(meshSizeField.getText().trim());
            geo.minAngle = Double.parseDouble(minAngleField.getText().trim());
            geo.grading = Double.parseDouble(gradingField.getText().trim());
            geo.smoothingIterations = Integer.parseInt(smoothingField.getText().trim());
        } catch (NumberFormatException ex) {
            error("Wrong mesh parameter: " + ex.getMessage());
            return false;
        }
        if (geo.meshSize <= 0 || geo.grading < 0) {
            error("The mesh size must be positive and the grading non-negative.");
            return false;
        }
        if (geo.minAngle > 33) {
            info.setText("The minimal angle was limited to 33° (the refinement may not terminate for larger values).");
            geo.minAngle = 33;
            minAngleField.setText("33");
        }
        return true;
    }

    private void readDrawingFields() {
        try {
            canvas.gridStep = Double.parseDouble(gridField.getText().trim());
            canvas.circlePoints = Integer.parseInt(circleField.getText().trim());
        } catch (NumberFormatException ex) {
            error("Wrong number: " + ex.getMessage());
        }
        canvas.snapToGrid = snapBox.isSelected();
        canvas.repaint();
    }

    private static Integer parseType(String text) {
        if (text == null) {
            return null;
        }
        String[] t = text.trim().split("\\s+");
        try {
            return Integer.parseInt(t[0]);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void updateSelectionPanel() {
        int l = canvas.getSelectedLoop();
        if (l < 0 || l >= geo.loops.size()) {
            selectionLabel.setText("nothing selected");
            return;
        }
        Geometry.Loop loop = geo.loops.get(l);
        int s = canvas.getSelectedSegment();
        int p = canvas.getSelectedPoint();
        int idx = s >= 0 ? s : (p >= 0 ? p : 0);
        String what = s >= 0 ? "segment " + (s + 1) : (p >= 0 ? "point " + (p + 1) : "whole loop");
        boolean outer = loop == geo.outerLoop();
        selectionLabel.setText("<html>loop " + (l + 1) + (outer ? " (outer boundary)" : " (hole / body)")
                + ", " + what + "<br>" + loop.size() + " points</html>");
        int type = loop.types.get(idx);
        String typeText = type + "  " + Geometry.typeName(type);
        typeCombo.setSelectedItem(typeText);
        for (String choice : TYPE_CHOICES) {
            if (parseType(choice) == type) {
                typeCombo.setSelectedItem(choice);
            }
        }
        double h = loop.sizes.get(idx);
        localSizeField.setText(Double.isNaN(h) ? "" : fmt(h));
        aleField.setText(Integer.toString(loop.aleTags.get(idx)));
    }

    private void applyAttributes(boolean wholeLoop) {
        int l = canvas.getSelectedLoop();
        if (l < 0) {
            error("Select a segment or a loop first (Select mode, click on a segment).");
            return;
        }
        Integer type = parseType((String) typeCombo.getSelectedItem());
        if (type == null) {
            error("The boundary type must be an integer (negative numbers are boundary conditions).");
            return;
        }
        double h = Double.NaN;
        int ale;
        try {
            String hs = localSizeField.getText().trim();
            if (!hs.isEmpty()) {
                h = Double.parseDouble(hs);
                if (h <= 0) {
                    throw new NumberFormatException("local size must be positive");
                }
            }
            ale = aleField.getText().trim().isEmpty() ? 0 : Integer.parseInt(aleField.getText().trim());
        } catch (NumberFormatException ex) {
            error("Wrong value: " + ex.getMessage());
            return;
        }
        Geometry.Loop loop = geo.loops.get(l);
        int s = canvas.getSelectedSegment();
        if (wholeLoop || s < 0) {
            loop.setAll(type, h, ale);
        } else {
            loop.types.set(s, type);
            loop.sizes.set(s, h);
            loop.aleTags.set(s, ale);
        }
        canvas.setMesh(null);
        canvas.repaint();
        info.setText("boundary attributes changed, generate the mesh again");
    }

    // ---------------------------------------------------------------- meshing
    private void generateOrCancel() {
        if (worker != null && !worker.isDone()) {
            cancelled.set(true);
            return;
        }
        if (!readParameters()) {
            return;
        }
        String problem = geo.validate();
        if (problem != null) {
            error(problem);
            return;
        }
        cancelled.set(false);
        generateButton.setText("Cancel");
        info.setText("meshing...");
        worker = new SwingWorker<TriMesh, String>() {
            @Override
            protected TriMesh doInBackground() throws Exception {
                Mesher mesher = new Mesher(geo, new Mesher.Progress() {
                    @Override
                    public void report(String message) {
                        publish(message);
                    }

                    @Override
                    public boolean isCancelled() {
                        return cancelled.get();
                    }
                });
                return mesher.generate();
            }

            @Override
            protected void process(List<String> chunks) {
                info.setText(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                generateButton.setText("Generate mesh");
                try {
                    TriMesh mesh = get();
                    canvas.setMesh(mesh);
                    info.setText("Mesh generated:\n" + mesh.statistics().replace(", ", "\n")
                            + "\n\nTriangles with an angle below 20° are highlighted.");
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    info.setText("Meshing failed: " + cause.getMessage());
                    if (!(cause instanceof Mesher.MeshingException)) {
                        cause.printStackTrace();
                    }
                }
            }
        };
        worker.execute();
    }

    // ---------------------------------------------------------------- files
    private void setGeometry(Geometry g, File file) {
        geo = g;
        geometryFile = file;
        canvas.setGeometry(g);
        loadParametersToFields();
        setTitle("FlowPro – 2D mesh generator" + (file != null ? " – " + file.getName() : ""));
    }

    private JFileChooser geometryChooser() {
        JFileChooser fc = new JFileChooser(geometryFile != null ? geometryFile.getParentFile() : flowProDir);
        fc.setFileFilter(new FileNameExtensionFilter("FlowPro 2D geometry (*.geo2d)", "geo2d"));
        return fc;
    }

    private void openGeometry() {
        JFileChooser fc = geometryChooser();
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                setGeometry(Geometry.load(fc.getSelectedFile()), fc.getSelectedFile());
            } catch (IOException ex) {
                error(ex.getMessage());
            }
        }
    }

    private void saveGeometry(boolean askName) {
        readParameters();
        File file = geometryFile;
        if (file == null || askName) {
            JFileChooser fc = geometryChooser();
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            file = fc.getSelectedFile();
            if (!file.getName().contains(".")) {
                file = new File(file.getPath() + ".geo2d");
            }
        }
        try {
            geo.save(file);
            geometryFile = file;
            setTitle("FlowPro – 2D mesh generator – " + file.getName());
            status.setText("geometry saved into " + file);
        } catch (IOException ex) {
            error(ex.getMessage());
        }
    }

    private void importPoints() {
        JFileChooser fc = new JFileChooser(flowProDir);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                Geometry.Loop loop = Geometry.importPoints(fc.getSelectedFile(), canvas.defaultType);
                if (loop.size() < 3) {
                    error("The file must contain at least 3 points (x y on each line).");
                    return;
                }
                geo.loops.add(loop);
                canvas.setMesh(null);
                canvas.zoomToFit();
            } catch (IOException ex) {
                error(ex.getMessage());
            }
        }
    }

    private void addNaca() {
        JTextField code = new JTextField("0012");
        JTextField chord = new JTextField("1");
        JTextField x0 = new JTextField("0");
        JTextField y0 = new JTextField("0");
        JTextField angle = new JTextField("0");
        JTextField n = new JTextField("100");
        Object[] form = {"NACA code (4 digits)", code, "chord", chord, "leading edge x", x0, "leading edge y", y0,
            "angle of attack [°]", angle, "points per side", n};
        if (JOptionPane.showConfirmDialog(this, form, "NACA airfoil", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            Geometry.Loop loop = Geometry.naca4(code.getText().trim(), Double.parseDouble(chord.getText()),
                    Double.parseDouble(x0.getText()), Double.parseDouble(y0.getText()),
                    Double.parseDouble(angle.getText()), Integer.parseInt(n.getText().trim()), Geometry.WALL);
            geo.loops.add(loop);
            canvas.setMesh(null);
            canvas.zoomToFit();
        } catch (RuntimeException ex) {
            error("Wrong input: " + ex.getMessage());
        }
    }

    private void addChannel() {
        JTextField xa = new JTextField("-2"), ya = new JTextField("-2"), xb = new JTextField("4"), yb = new JTextField("2");
        JCheckBox inviscid = new JCheckBox("inviscid walls", false);
        Object[] form = {"x min", xa, "y min", ya, "x max", xb, "y max", yb, inviscid};
        if (JOptionPane.showConfirmDialog(this, form, "Channel: inlet left, outlet right", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            Geometry.Loop loop = Geometry.rectangle(Double.parseDouble(xa.getText()), Double.parseDouble(ya.getText()),
                    Double.parseDouble(xb.getText()), Double.parseDouble(yb.getText()), Geometry.WALL);
            int wall = inviscid.isSelected() ? Geometry.INVISCID_WALL : Geometry.WALL;
            // points: (xa,ya) (xb,ya) (xb,yb) (xa,yb): bottom, right, top, left
            loop.types.set(0, wall);
            loop.types.set(1, Geometry.OUTLET);
            loop.types.set(2, wall);
            loop.types.set(3, Geometry.INLET);
            geo.loops.add(loop);
            canvas.setMesh(null);
            canvas.zoomToFit();
        } catch (RuntimeException ex) {
            error("Wrong input: " + ex.getMessage());
        }
    }

    private void exportMesh() {
        TriMesh mesh = canvas.getMesh();
        if (mesh == null) {
            error("Generate the mesh first.");
            return;
        }
        JFileChooser fc = new JFileChooser(flowProDir);
        fc.setDialogTitle("Select the FlowPro directory (containing directory 'simulations')");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setSelectedFile(flowProDir);
        if (fc.showDialog(this, "Select") != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File dir = fc.getSelectedFile();
        File simulations = new File(dir, "simulations");
        if (!simulations.isDirectory()) {
            if (!confirm("Directory " + dir + " does not contain directory 'simulations'. Create it?")) {
                return;
            }
        }
        flowProDir = dir;
        String name = JOptionPane.showInputDialog(this, "Name of the geometry (directory simulations/NAME):",
                geometryFile != null ? geometryFile.getName().replaceFirst("\\.geo2d$", "") : "myGeometry");
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        name = name.trim();
        File geometryDir = new File(simulations, name);
        File meshDir = new File(geometryDir, "mesh");
        if (new File(meshDir, "vertices.txt").exists()
                && !confirm("The mesh of geometry '" + name + "' already exists. Overwrite it?")) {
            return;
        }
        try {
            List<String> deleted = mesh.exportFlowPro(meshDir);
            readParameters();
            geo.save(new File(meshDir, "geometry.geo2d"));

            StringBuilder msg = new StringBuilder("The mesh was exported into " + meshDir + ".");
            if (!deleted.isEmpty()) {
                msg.append("\nFiles derived from the previous mesh were deleted: ").append(deleted);
            }
            // a simulation with a template of parameters
            File[] sims = geometryDir.listFiles(f -> f.isDirectory() && new File(f, "parameters.txt").isFile());
            File template = new File(simulations, "examples/GAMM/default/parameters.txt");
            if ((sims == null || sims.length == 0) && template.isFile()
                    && confirm(msg + "\n\nCreate simulation 'default' with parameters copied from examples/GAMM?")) {
                File simDir = new File(geometryDir, "default");
                simDir.mkdirs();
                Files.copy(template.toPath(), new File(simDir, "parameters.txt").toPath(), StandardCopyOption.REPLACE_EXISTING);
                msg.append("\nSimulation ").append(simDir).append(" was created, edit its parameters.txt "
                        + "(boundary conditions!) before running it.");
            }
            JOptionPane.showMessageDialog(this, msg.toString(), "Export", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            error("Export failed: " + ex.getMessage());
        }
    }

    private boolean confirm(String message) {
        return JOptionPane.showConfirmDialog(this, message, "Confirm", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE);
    }

    // ---------------------------------------------------------------- main
    /**
     * GUI, or batch mode: --batch geometry.geo2d meshDirectory
     */
    public static void main(String[] args) {
        if (args.length >= 1 && args[0].equals("--batch")) {
            if (args.length < 3) {
                System.err.println("usage: java -jar MeshGenerator2D.jar --batch geometry.geo2d meshDirectory");
                System.exit(2);
            }
            try {
                Geometry geo = Geometry.load(new File(args[1]));
                Mesher mesher = new Mesher(geo, new Mesher.Progress() {
                    @Override
                    public void report(String message) {
                        System.out.println(message);
                    }

                    @Override
                    public boolean isCancelled() {
                        return false;
                    }
                });
                TriMesh mesh = mesher.generate();
                List<String> deleted = mesh.exportFlowPro(new File(args[2]));
                System.out.println("mesh written into " + args[2] + (deleted.isEmpty() ? "" : ", deleted " + deleted));
            } catch (IOException | Mesher.MeshingException ex) {
                System.err.println("error: " + ex.getMessage());
                System.exit(1);
            }
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ex) {
                // default look and feel
            }
            MeshGeneratorApp app = new MeshGeneratorApp();
            app.setVisible(true);
            if (args.length >= 1) {
                try {
                    app.setGeometry(Geometry.load(new File(args[0])), new File(args[0]));
                } catch (IOException ex) {
                    app.error(ex.getMessage());
                }
            }
        });
    }
}
