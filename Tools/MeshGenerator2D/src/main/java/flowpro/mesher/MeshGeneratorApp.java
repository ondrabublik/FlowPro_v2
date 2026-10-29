package flowpro.mesher;

import java.awt.BorderLayout;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Standalone GUI of the 2D mesh generator for FlowPro (the same editor is a
 * part of FlowProControl, where the meshes are stored directly in the tasks).
 *
 * @author obublik
 */
public class MeshGeneratorApp extends JFrame {

    private final MeshEditorPanel editor = new MeshEditorPanel();
    private File geometryFile;
    private File flowProDir;

    public MeshGeneratorApp() {
        super("FlowPro – 2D mesh generator");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        flowProDir = guessFlowProDir();

        editor.addMeshAction("Export to FlowPro...", this::exportMesh);
        setJMenuBar(createMenu());
        add(editor, BorderLayout.CENTER);

        setSize(1300, 850);
        setLocationRelativeTo(null);
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
        file.add(menuItem("Import points (x y per line)...", 0, () -> editor.importPoints(flowProDir)));
        file.add(menuItem("Export mesh to FlowPro...", KeyEvent.VK_E, this::exportMesh));
        file.addSeparator();
        file.add(menuItem("Exit", 0, () -> dispose()));
        bar.add(file);

        JMenu shapes = new JMenu("Shapes");
        shapes.add(menuItem("NACA 4-digit airfoil...", 0, editor::addNaca));
        shapes.add(menuItem("Rectangular channel (inlet / outlet / walls)...", 0, editor::addChannel));
        bar.add(shapes);

        JMenu view = new JMenu("View");
        view.add(menuItem("Zoom to fit", KeyEvent.VK_F, editor::zoomToFit));
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

    // ---------------------------------------------------------------- files
    private void setGeometry(Geometry g, File file) {
        editor.setGeometry(g);
        editor.setInfo(MeshEditorPanel.helpText());
        geometryFile = file;
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
        editor.readParameters();
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
            editor.getGeometry().save(file);
            geometryFile = file;
            editor.setModified(false);
            setTitle("FlowPro – 2D mesh generator – " + file.getName());
            editor.setInfo("geometry saved into " + file);
        } catch (IOException ex) {
            error(ex.getMessage());
        }
    }

    private void exportMesh() {
        TriMesh mesh = editor.getMesh();
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
            editor.readParameters();
            editor.getGeometry().save(new File(meshDir, "geometry.geo2d"));

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
