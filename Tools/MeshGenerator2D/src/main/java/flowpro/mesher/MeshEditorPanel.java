package flowpro.mesher;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Editor of a 2D geometry and generator of its mesh: tool bar, drawing canvas
 * and the panel with the mesh parameters and the boundary conditions. It is
 * used by the standalone MeshGeneratorApp and by the FlowPro control GUI.
 *
 * @author obublik
 */
public class MeshEditorPanel extends JPanel {

    private static final String[] TYPE_CHOICES = {
        "-1  wall", "-2  inlet", "-3  outlet", "-4  inviscid wall"
    };

    private final MeshCanvas canvas;
    private Geometry geo = new Geometry();
    private boolean modified = false;
    private boolean loading = false;
    private Runnable changeListener = () -> {
    };

    private final JTextField meshSizeField = new JTextField(8);
    private final JTextField minAngleField = new JTextField(8);
    private final JTextField gradingField = new JTextField(8);
    private final JTextField smoothingField = new JTextField(8);
    private final JTextField gridField = new JTextField(8);
    private final JTextField circleField = new JTextField(8);
    private final JTextField regionSizeField = new JTextField(8);
    private final JTextField refinementField = new JTextField(8);
    private final JCheckBox blockTrianglesBox = new JCheckBox("divide blocks into triangles");
    private final JCheckBox snapBox = new JCheckBox("snap to grid");
    private final JCheckBox showMeshBox = new JCheckBox("show mesh", true);

    private final JLabel selectionLabel = new JLabel("nothing selected");
    private final JComboBox<String> typeCombo = new JComboBox<>(TYPE_CHOICES);
    private final JTextField localSizeField = new JTextField(8);
    private final JTextField aleField = new JTextField(8);
    private final JComboBox<String> defaultTypeCombo = new JComboBox<>(TYPE_CHOICES);

    private final JLabel blockLabel = new JLabel("no block selected");
    private final JTextField nxField = new JTextField(8);
    private final JTextField nyField = new JTextField(8);
    private final JTextField gxField = new JTextField(8);
    private final JTextField gyField = new JTextField(8);

    private final JTextArea info = new JTextArea(8, 26);
    private final JLabel status = new JLabel(" ");
    private final JButton generateButton = new JButton("Generate mesh");
    private JPanel meshSection;
    private int meshSectionRow;
    private SwingWorker<TriMesh, String> worker;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public MeshEditorPanel() {
        super(new BorderLayout());
        canvas = new MeshCanvas(geo);
        canvas.setStatusListener(s -> status.setText(s));
        canvas.setSelectionListener(this::updateSelectionPanel);
        canvas.setGeometryListener(() -> changed("geometry changed, generate the mesh again"));
        typeCombo.setEditable(true);
        defaultTypeCombo.setEditable(true);

        add(createToolBar(), BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        add(new JScrollPane(createSidePanel()), BorderLayout.EAST);
        add(status, BorderLayout.SOUTH);
        status.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));

        loadParametersToFields();
        DocumentListener parameterListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                parameterEdited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                parameterEdited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                parameterEdited();
            }
        };
        for (JTextField f : new JTextField[]{meshSizeField, minAngleField, gradingField, smoothingField, refinementField}) {
            f.getDocument().addDocumentListener(parameterListener);
        }
        blockTrianglesBox.addActionListener(e -> parameterEdited());
        info.setEditable(false);
        info.setLineWrap(true);
        info.setWrapStyleWord(true);
        info.setText(helpText());
    }

    public static String helpText() {
        return "Unstructured mesh: draw the outer boundary first (polygon, rectangle, circle, channel or NACA "
                + "profile), then the bodies (holes).\n"
                + "Polygon: click the points, double click or Enter closes it, Backspace removes the last point.\n\n"
                + "Block mesh (as blockMesh of OpenFOAM): in mode Block click the 4 corners of a block, clicking an "
                + "existing block vertex connects the blocks. Select a block (click inside it) to set its number "
                + "of cells and grading, select its face to set the boundary type or to curve it (drag the circle "
                + "in the middle of the face). If there are blocks, only they are meshed.\n\n"
                + "Select: click a point (drag to move) or a segment, drag on an empty place to select everything "
                + "in a box (Shift adds to the selection), then Delete removes it or the boundary type is applied "
                + "to all the selected segments. Double click a segment to insert a point.\n"
                + "Right or middle drag (or Ctrl + drag) pans, the wheel zooms.\n\n"
                + "Refinement: local mesh size of a segment, refinement regions (Refine box / circle) - the mesh "
                + "size grows with the distance from them according to 'grading' - and uniform refinement levels "
                + "(every level divides each element into 4).";
    }

    // ---------------------------------------------------------------- public interface
    public Geometry getGeometry() {
        return geo;
    }

    /**
     * Replaces the geometry (the mesh is discarded), the editor is not
     * modified afterwards.
     */
    public void setGeometry(Geometry g) {
        geo = g;
        canvas.setGeometry(g);
        loadParametersToFields();
        setModified(false);
    }

    public TriMesh getMesh() {
        return canvas.getMesh();
    }

    public void setMesh(TriMesh mesh) {
        canvas.setMesh(mesh);
    }

    public boolean isModified() {
        return modified;
    }

    public void setModified(boolean modified) {
        this.modified = modified;
    }

    /**
     * Called when the geometry, its parameters or the mesh are changed by the
     * user.
     */
    public void setChangeListener(Runnable listener) {
        changeListener = listener;
    }

    public void setInfo(String text) {
        info.setText(text);
    }

    public void zoomToFit() {
        canvas.zoomToFit();
    }

    public boolean isMeshing() {
        return worker != null && !worker.isDone();
    }

    /**
     * Adds a button below 'Generate mesh' (e.g. export or saving of the mesh).
     */
    public JButton addMeshAction(String text, Runnable action) {
        JButton b = new JButton(text);
        b.addActionListener(e -> action.run());
        GridBagConstraints mc = new GridBagConstraints();
        mc.gridx = 0;
        mc.gridy = meshSectionRow++;
        mc.gridwidth = 2;
        mc.fill = GridBagConstraints.HORIZONTAL;
        mc.insets = new Insets(2, 2, 2, 2);
        meshSection.add(b, mc);
        return b;
    }

    private void changed(String message) {
        info.setText(message);
        modified = true;
        changeListener.run();
    }

    private void parameterEdited() {
        if (!loading) {
            modified = true;
            changeListener.run();
        }
    }

    // ---------------------------------------------------------------- UI construction
    private JToolBar createToolBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        ButtonGroup group = new ButtonGroup();
        for (MeshCanvas.Mode mode : MeshCanvas.Mode.values()) {
            JToggleButton b = new JToggleButton(mode.label);
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
        bar.add(toolButton("Channel...", this::addChannel));
        bar.add(toolButton("NACA...", this::addNaca));
        bar.add(toolButton("Zoom to fit", canvas::zoomToFit));
        bar.add(toolButton("Delete selected", canvas::deleteSelection));
        return bar;
    }

    private static JButton toolButton(String text, Runnable action) {
        JButton b = new JButton(text);
        b.addActionListener(e -> action.run());
        return b;
    }

    private JPanel createSidePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 2, 2, 2);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.gridy = 0;

        meshSection = section("Mesh");
        addRow(meshSection, "mesh size h", meshSizeField, 0);
        addRow(meshSection, "min angle [°]", minAngleField, 1);
        addRow(meshSection, "grading", gradingField, 2);
        addRow(meshSection, "smoothing iterations", smoothingField, 3);
        addRow(meshSection, "uniform refinement levels", refinementField, 4);
        refinementField.setToolTipText("every level divides each element into 4 (blocks: doubles the numbers of cells)");
        GridBagConstraints mc = new GridBagConstraints();
        mc.gridx = 0;
        mc.gridy = 5;
        mc.gridwidth = 2;
        mc.anchor = GridBagConstraints.WEST;
        meshSection.add(blockTrianglesBox, mc);
        mc.fill = GridBagConstraints.HORIZONTAL;
        mc.insets = new Insets(4, 2, 2, 2);
        mc.gridy = 6;
        generateButton.addActionListener(e -> generateOrCancel());
        meshSection.add(generateButton, mc);
        mc.gridy = 7;
        JButton refineButton = new JButton("Refine x2 (one more level)");
        refineButton.addActionListener(e -> refineOnce());
        meshSection.add(refineButton, mc);
        mc.gridy = 8;
        showMeshBox.addActionListener(e -> {
            canvas.showMesh = showMeshBox.isSelected();
            canvas.repaint();
        });
        meshSection.add(showMeshBox, mc);
        meshSectionRow = 9;
        panel.add(meshSection, c);

        c.gridy++;
        JPanel sel = section("Selection (segments, faces, regions)");
        GridBagConstraints sc = new GridBagConstraints();
        sc.gridx = 0;
        sc.gridy = 0;
        sc.gridwidth = 2;
        sc.fill = GridBagConstraints.HORIZONTAL;
        sel.add(selectionLabel, sc);
        addRow(sel, "boundary type", typeCombo, 1);
        addRow(sel, "local size / region size", localSizeField, 2);
        localSizeField.setToolTipText("mesh size at a segment (empty = h) or in a refinement region");
        addRow(sel, "ALE body tag (0 = none)", aleField, 3);
        sc.gridy = 4;
        sc.gridwidth = 1;
        JButton applySeg = new JButton("Apply to selected");
        applySeg.setToolTipText("to the selected segments, block faces and refinement regions");
        applySeg.addActionListener(e -> applyAttributes(false));
        sel.add(applySeg, sc);
        sc.gridx = 1;
        JButton applyLoop = new JButton("Apply to loop / block");
        applyLoop.setToolTipText("to all the segments of the selected loop or all the faces of the selected block");
        applyLoop.addActionListener(e -> applyAttributes(true));
        sel.add(applyLoop, sc);
        panel.add(sel, c);

        c.gridy++;
        JPanel blk = section("Block");
        GridBagConstraints bc = new GridBagConstraints();
        bc.gridx = 0;
        bc.gridy = 0;
        bc.gridwidth = 2;
        bc.fill = GridBagConstraints.HORIZONTAL;
        blk.add(blockLabel, bc);
        addRow(blk, "cells along face 1", nxField, 1);
        addRow(blk, "cells along face 2", nyField, 2);
        addRow(blk, "grading along face 1", gxField, 3);
        addRow(blk, "grading along face 2", gyField, 4);
        gxField.setToolTipText("ratio of the last and the first cell from vertex 1 to vertex 2 (as simpleGrading of blockMesh)");
        gyField.setToolTipText("ratio of the last and the first cell from vertex 2 to vertex 3 (as simpleGrading of blockMesh)");
        bc.gridy = 5;
        JButton applyBlock = new JButton("Apply division (also to connected blocks)");
        applyBlock.addActionListener(e -> applyBlockDivision());
        blk.add(applyBlock, bc);
        bc.gridy = 6;
        bc.gridwidth = 1;
        JButton arcButton = new JButton("Arc face...");
        arcButton.setToolTipText("curves the selected face into a circular arc through a given point");
        arcButton.addActionListener(e -> arcFace());
        blk.add(arcButton, bc);
        bc.gridx = 1;
        JButton straightButton = new JButton("Straight face");
        straightButton.addActionListener(e -> straightFace());
        blk.add(straightButton, bc);
        panel.add(blk, c);

        c.gridy++;
        JPanel draw = section("Drawing");
        addRow(draw, "grid step", gridField, 0);
        addRow(draw, "points of circle", circleField, 1);
        addRow(draw, "size in new regions", regionSizeField, 2);
        regionSizeField.setToolTipText("mesh size in new refinement regions (empty = h / 4)");
        GridBagConstraints dc = new GridBagConstraints();
        dc.gridx = 0;
        dc.gridy = 3;
        dc.gridwidth = 2;
        dc.anchor = GridBagConstraints.WEST;
        snapBox.addActionListener(e -> readDrawingFields());
        draw.add(snapBox, dc);
        gridField.addActionListener(e -> readDrawingFields());
        circleField.addActionListener(e -> readDrawingFields());
        regionSizeField.addActionListener(e -> readDrawingFields());
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
        loading = true;
        meshSizeField.setText(fmt(geo.meshSize));
        minAngleField.setText(fmt(geo.minAngle));
        gradingField.setText(fmt(geo.grading));
        smoothingField.setText(Integer.toString(geo.smoothingIterations));
        refinementField.setText(Integer.toString(geo.refinementLevels));
        blockTrianglesBox.setSelected(geo.blockTriangles);
        gridField.setText(fmt(canvas.gridStep));
        circleField.setText(Integer.toString(canvas.circlePoints));
        loading = false;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6g", v).replaceAll("\\.?0+(e|$)", "$1");
    }

    /**
     * Copies the mesh parameters from the text fields into the geometry.
     */
    public boolean readParameters() {
        try {
            geo.meshSize = Double.parseDouble(meshSizeField.getText().trim());
            geo.minAngle = Double.parseDouble(minAngleField.getText().trim());
            geo.grading = Double.parseDouble(gradingField.getText().trim());
            geo.smoothingIterations = Integer.parseInt(smoothingField.getText().trim());
            geo.refinementLevels = Integer.parseInt(refinementField.getText().trim());
        } catch (NumberFormatException ex) {
            error("Wrong mesh parameter: " + ex.getMessage());
            return false;
        }
        geo.blockTriangles = blockTrianglesBox.isSelected();
        if (geo.meshSize <= 0 || geo.grading < 0) {
            error("The mesh size must be positive and the grading non-negative.");
            return false;
        }
        if (geo.refinementLevels < 0 || geo.refinementLevels > 6) {
            error("The number of uniform refinement levels must be between 0 and 6.");
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
            String rs = regionSizeField.getText().trim();
            canvas.regionSize = rs.isEmpty() ? Double.NaN : Double.parseDouble(rs);
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

    private void selectType(int type) {
        typeCombo.setSelectedItem(type + "  " + Geometry.typeName(type));
        for (String choice : TYPE_CHOICES) {
            if (parseType(choice) == type) {
                typeCombo.setSelectedItem(choice);
            }
        }
    }

    private void updateSelectionPanel() {
        updateBlockPanel();
        int l = canvas.getSelectedLoop();
        int b = canvas.getSelectedBlock();
        int r = canvas.getSelectedRegion();
        if (l >= 0 && l < geo.loops.size()) {
            Geometry.Loop loop = geo.loops.get(l);
            int s = canvas.getSelectedSegment();
            int p = canvas.getSelectedPoint();
            int idx = s >= 0 ? s : (p >= 0 ? p : 0);
            String what = s >= 0 ? "segment " + (s + 1) : (p >= 0 ? "point " + (p + 1) : "whole loop");
            boolean outer = loop == geo.outerLoop();
            selectionLabel.setText("<html>loop " + (l + 1) + (outer ? " (outer boundary)" : " (hole / body)")
                    + ", " + what + "<br>" + loop.size() + " points</html>");
            selectType(loop.types.get(idx));
            double h = loop.sizes.get(idx);
            localSizeField.setText(Double.isNaN(h) ? "" : fmt(h));
            aleField.setText(Integer.toString(loop.aleTags.get(idx)));
        } else if (b >= 0 && b < geo.blocks.size()) {
            Geometry.Block block = geo.blocks.get(b);
            int f = canvas.getSelectedFace();
            int k = Math.max(f, 0);
            boolean inner = geo.blocksAt(block.v[k], block.v[(k + 1) % 4]).size() > 1;
            selectionLabel.setText("<html>block " + (b + 1) + (f >= 0 ? ", face " + (f + 1)
                    + (inner ? " (inner face)" : " (boundary)") : ", all faces") + "</html>");
            selectType(block.types[k]);
            localSizeField.setText("");
            aleField.setText(Integer.toString(block.aleTags[k]));
        } else if (r >= 0 && r < geo.regions.size()) {
            Geometry.Region reg = geo.regions.get(r);
            selectionLabel.setText("refinement region " + (r + 1) + (reg.circle ? " (circle)" : " (box)"));
            localSizeField.setText(fmt(reg.h));
        } else {
            int segments = canvas.selectedSegments().size(), faces = canvas.selectedFaces().size();
            int regions = canvas.selectedRegions().size();
            if (segments + faces + regions == 0) {
                selectionLabel.setText(canvas.multiSelectionSize() > 0 ? "points selected" : "nothing selected");
            } else {
                selectionLabel.setText("<html>selected: " + segments + " segments, " + faces + " block faces, "
                        + regions + " regions</html>");
            }
        }
    }

    private void updateBlockPanel() {
        int b = canvas.getSelectedBlock();
        if (b < 0 || b >= geo.blocks.size()) {
            blockLabel.setText("<html>no block selected<br>(click inside a block)</html>");
            return;
        }
        Geometry.Block block = geo.blocks.get(b);
        blockLabel.setText("<html>block " + (b + 1) + "<br>face 1 = vertex 1 to 2, face 2 = vertex 2 to 3</html>");
        nxField.setText(Integer.toString(block.nx));
        nyField.setText(Integer.toString(block.ny));
        gxField.setText(fmt(block.gx));
        gyField.setText(fmt(block.gy));
    }

    private void applyAttributes(boolean wholeLoop) {
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
        int l = canvas.getSelectedLoop();
        int b = canvas.getSelectedBlock();
        if (wholeLoop) {
            if (l >= 0) {
                geo.loops.get(l).setAll(type, h, ale);
            } else if (b >= 0) {
                Geometry.Block block = geo.blocks.get(b);
                for (int k = 0; k < 4; k++) {
                    block.types[k] = type;
                    block.aleTags[k] = ale;
                }
            } else {
                error("Select a loop (click on its segment) or a block (click inside it) first.");
                return;
            }
        } else {
            List<int[]> segments = canvas.selectedSegments();
            List<int[]> faces = canvas.selectedFaces();
            Set<Integer> regions = canvas.selectedRegions();
            if (segments.isEmpty() && l >= 0) {
                // a point is selected: the whole loop
                for (int i = 0; i < geo.loops.get(l).size(); i++) {
                    segments.add(new int[]{l, i});
                }
            }
            if (segments.isEmpty() && faces.isEmpty() && regions.isEmpty()) {
                error("Select segments, block faces or refinement regions first (Select mode: click them "
                        + "or drag a box around them).");
                return;
            }
            if (!regions.isEmpty() && Double.isNaN(h)) {
                error("Enter the mesh size of the refinement region into 'local size / region size'.");
                return;
            }
            for (int[] seg : segments) {
                Geometry.Loop loop = geo.loops.get(seg[0]);
                loop.types.set(seg[1], type);
                loop.sizes.set(seg[1], h);
                loop.aleTags.set(seg[1], ale);
            }
            for (int[] face : faces) {
                geo.blocks.get(face[0]).types[face[1]] = type;
                geo.blocks.get(face[0]).aleTags[face[1]] = ale;
            }
            for (int r : regions) {
                geo.regions.get(r).h = h;
            }
        }
        canvas.setMesh(null);
        canvas.repaint();
        changed("attributes changed, generate the mesh again");
    }

    private void applyBlockDivision() {
        int b = canvas.getSelectedBlock();
        if (b < 0) {
            error("Select a block first (Select mode, click inside it).");
            return;
        }
        int nx, ny;
        double gx, gy;
        try {
            nx = Integer.parseInt(nxField.getText().trim());
            ny = Integer.parseInt(nyField.getText().trim());
            gx = Double.parseDouble(gxField.getText().trim());
            gy = Double.parseDouble(gyField.getText().trim());
        } catch (NumberFormatException ex) {
            error("Wrong value: " + ex.getMessage());
            return;
        }
        if (nx < 1 || ny < 1 || !(gx > 0) || !(gy > 0)) {
            error("The numbers of cells must be at least 1 and the gradings positive.");
            return;
        }
        // the connected blocks get the same division of their common faces
        geo.setDivision(b, 0, nx, gx);
        geo.setDivision(b, 1, ny, gy);
        canvas.setMesh(null);
        canvas.repaint();
        updateBlockPanel();
        changed("division of the blocks changed, generate the mesh again");
    }

    private int[] selectedFaceVertices() {
        int b = canvas.getSelectedBlock(), f = canvas.getSelectedFace();
        if (b < 0 || f < 0) {
            error("Select a face of a block first (Select mode, click on the face).");
            return null;
        }
        Geometry.Block block = geo.blocks.get(b);
        return new int[]{block.v[f], block.v[(f + 1) % 4]};
    }

    private void arcFace() {
        int[] e = selectedFaceVertices();
        if (e == null) {
            return;
        }
        Geometry.Arc arc = geo.arc(e[0], e[1]);
        double[] p = arc != null ? new double[]{arc.x, arc.y} : geo.edgePoint(e[0], e[1], 0.5);
        JTextField xf = new JTextField(fmt(p[0])), yf = new JTextField(fmt(p[1]));
        Object[] form = {"The face becomes a circular arc through its end points and the point", "x", xf, "y", yf};
        if (JOptionPane.showConfirmDialog(this, form, "Arc face", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            double x = Double.parseDouble(xf.getText().trim()), y = Double.parseDouble(yf.getText().trim());
            if (arc == null) {
                geo.arcs.add(new Geometry.Arc(e[0], e[1], x, y));
            } else {
                arc.x = x;
                arc.y = y;
            }
        } catch (NumberFormatException ex) {
            error("Wrong number: " + ex.getMessage());
            return;
        }
        canvas.setMesh(null);
        canvas.repaint();
        changed("face curved, generate the mesh again");
    }

    private void straightFace() {
        int[] e = selectedFaceVertices();
        if (e == null) {
            return;
        }
        Geometry.Arc arc = geo.arc(e[0], e[1]);
        if (arc != null) {
            geo.arcs.remove(arc);
            canvas.setMesh(null);
            canvas.repaint();
            changed("face straightened, generate the mesh again");
        }
    }

    private void refineOnce() {
        if (!readParameters()) {
            return;
        }
        if (geo.refinementLevels >= 6) {
            error("At most 6 uniform refinement levels.");
            return;
        }
        refinementField.setText(Integer.toString(geo.refinementLevels + 1));
        generateOrCancel();
    }

    // ---------------------------------------------------------------- meshing
    public void generateOrCancel() {
        if (isMeshing()) {
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
                    changed("Mesh generated:\n" + mesh.statistics().replace(", ", "\n")
                            + "\n\nPoor elements are highlighted."
                            + (geo.hasBlocks() && !geo.loops.isEmpty()
                            ? "\n\nOnly the blocks were meshed, the loops are ignored when there are blocks." : ""));
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

    // ---------------------------------------------------------------- shapes
    public void importPoints(File startDir) {
        JFileChooser fc = new JFileChooser(startDir);
        fc.setDialogTitle("Import a loop of points (x y per line)");
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                Geometry.Loop loop = Geometry.importPoints(fc.getSelectedFile(), canvas.defaultType);
                if (loop.size() < 3) {
                    error("The file must contain at least 3 points (x y on each line).");
                    return;
                }
                addLoop(loop);
            } catch (IOException ex) {
                error(ex.getMessage());
            }
        }
    }

    private void addLoop(Geometry.Loop loop) {
        geo.loops.add(loop);
        canvas.setMesh(null);
        canvas.zoomToFit();
        changed("geometry changed, generate the mesh again");
    }

    public void addNaca() {
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
            addLoop(Geometry.naca4(code.getText().trim(), Double.parseDouble(chord.getText()),
                    Double.parseDouble(x0.getText()), Double.parseDouble(y0.getText()),
                    Double.parseDouble(angle.getText()), Integer.parseInt(n.getText().trim()), Geometry.WALL));
        } catch (RuntimeException ex) {
            error("Wrong input: " + ex.getMessage());
        }
    }

    public void addChannel() {
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
            addLoop(loop);
        } catch (RuntimeException ex) {
            error("Wrong input: " + ex.getMessage());
        }
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE);
    }
}
