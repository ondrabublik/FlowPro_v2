package flowpro.mesher;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Mesh of triangles and quadrilaterals (blocks) and its export into the FlowPro
 * format.
 *
 * @author obublik
 */
public class TriMesh {

    public final double[][] points;
    public final int[][] elements; // triangles or quadrilaterals, counter-clockwise
    public final List<int[]> boundaryEdges; // {type, a, b, ale}
    public int[][] neighbours; // neighbours[i][k] across edge (v_k, v_k+1), boundary type if negative

    public TriMesh(double[][] points, int[][] elements, List<int[]> boundaryEdges) {
        this.points = points;
        this.elements = elements;
        this.boundaryEdges = boundaryEdges;
    }

    private static long key(int a, int b) {
        return a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
    }

    /**
     * @return true if the element is convex and counter-clockwise
     */
    private boolean isValid(int[] t) {
        int n = t.length;
        for (int k = 0; k < n; k++) {
            double[] a = points[t[(k + n - 1) % n]], b = points[t[k]], c = points[t[(k + 1) % n]];
            if ((b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]) <= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return interior angles of the element in degrees
     */
    public double[] angles(int[] t) {
        int n = t.length;
        double[] ang = new double[n];
        for (int k = 0; k < n; k++) {
            double[] a = points[t[k]], b = points[t[(k + 1) % n]], c = points[t[(k + n - 1) % n]];
            double ux = b[0] - a[0], uy = b[1] - a[1], vx = c[0] - a[0], vy = c[1] - a[1];
            ang[k] = Math.toDegrees(Math.atan2(Math.abs(ux * vy - uy * vx), ux * vx + uy * vy));
        }
        return ang;
    }

    public double minAngle(int[] t) {
        double min = 180;
        for (double a : angles(t)) {
            min = Math.min(min, a);
        }
        return min;
    }

    public double maxAngle(int[] t) {
        double max = 0;
        for (double a : angles(t)) {
            max = Math.max(max, a);
        }
        return max;
    }

    /**
     * @return true if the element should be highlighted as a poor one
     */
    public boolean isPoor(int[] t) {
        return t.length == 3 ? minAngle(t) < 20 : maxAngle(t) > 150;
    }

    /**
     * Laplacian smoothing of the interior vertices. A move is accepted only if
     * all the adjacent triangles stay valid and their minimal angle does not
     * decrease.
     */
    public void smooth(int iterations) {
        if (iterations <= 0) {
            return;
        }
        int n = points.length;
        boolean[] fixed = new boolean[n];
        for (int[] e : boundaryEdges) {
            fixed[e[1]] = true;
            fixed[e[2]] = true;
        }
        List<Set<Integer>> adjacentVertices = new ArrayList<>(n);
        List<List<Integer>> adjacentTriangles = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            adjacentVertices.add(new LinkedHashSet<>());
            adjacentTriangles.add(new ArrayList<>());
        }
        for (int t = 0; t < elements.length; t++) {
            int[] tri = elements[t];
            int m = tri.length;
            for (int k = 0; k < m; k++) {
                adjacentTriangles.get(tri[k]).add(t);
                adjacentVertices.get(tri[k]).add(tri[(k + 1) % m]);
                adjacentVertices.get(tri[k]).add(tri[(k + m - 1) % m]);
            }
        }
        for (int it = 0; it < iterations; it++) {
            for (int v = 0; v < n; v++) {
                if (fixed[v] || adjacentVertices.get(v).isEmpty()) {
                    continue;
                }
                double x = 0, y = 0;
                for (int w : adjacentVertices.get(v)) {
                    x += points[w][0];
                    y += points[w][1];
                }
                x /= adjacentVertices.get(v).size();
                y /= adjacentVertices.get(v).size();

                double oldX = points[v][0], oldY = points[v][1];
                double oldQuality = Double.MAX_VALUE;
                for (int t : adjacentTriangles.get(v)) {
                    oldQuality = Math.min(oldQuality, minAngle(elements[t]));
                }
                points[v][0] = x;
                points[v][1] = y;
                double newQuality = Double.MAX_VALUE;
                boolean valid = true;
                for (int t : adjacentTriangles.get(v)) {
                    if (!isValid(elements[t])) {
                        valid = false;
                        break;
                    }
                    newQuality = Math.min(newQuality, minAngle(elements[t]));
                }
                if (!valid || newQuality < oldQuality) {
                    points[v][0] = oldX;
                    points[v][1] = oldY;
                }
            }
        }
    }

    /**
     * Neighbours in the FlowPro convention: face k of an element is the edge
     * (v_k, v_(k+1) mod n), the value is the index of the neighbouring element
     * or the (negative) boundary type.
     */
    public void computeNeighbours() {
        Map<Long, Integer> boundaryType = new HashMap<>();
        for (int[] e : boundaryEdges) {
            boundaryType.put(key(e[1], e[2]), e[0]);
        }
        Map<Long, int[]> faces = new HashMap<>();
        neighbours = new int[elements.length][];
        for (int t = 0; t < elements.length; t++) {
            int n = elements[t].length;
            neighbours[t] = new int[n];
            for (int k = 0; k < n; k++) {
                long key = key(elements[t][k], elements[t][(k + 1) % n]);
                int[] other = faces.get(key);
                if (other == null) {
                    faces.put(key, new int[]{t, k});
                    neighbours[t][k] = boundaryType.getOrDefault(key, Geometry.WALL);
                } else {
                    neighbours[t][k] = other[0];
                    neighbours[other[0]][other[1]] = t;
                }
            }
        }
    }

    public String statistics() {
        double min = 180, max = 0, sum = 0;
        int nTri = 0, nQuad = 0, poor = 0;
        for (int[] t : elements) {
            double tmin = minAngle(t);
            min = Math.min(min, tmin);
            max = Math.max(max, maxAngle(t));
            sum += tmin;
            if (t.length == 3) {
                nTri++;
            } else {
                nQuad++;
            }
            if (isPoor(t)) {
                poor++;
            }
        }
        String counts = nQuad == 0 ? nTri + " triangles"
                : (nTri == 0 ? nQuad + " quadrilaterals" : nTri + " triangles, " + nQuad + " quadrilaterals");
        return String.format(Locale.ROOT, "%d vertices, %s, %d boundary edges, min angle %.1f deg, max angle %.1f deg, "
                + "mean min angle %.1f deg, poor elements (triangle angle < 20 deg, quadrilateral angle > 150 deg): %d",
                points.length, counts, boundaryEdges.size(), min, max,
                elements.length > 0 ? sum / elements.length : 0, poor);
    }

    /**
     * Uniform refinement: every triangle is divided into 4 triangles, every
     * quadrilateral into 4 quadrilaterals (the edges are halved).
     */
    public TriMesh refined() {
        List<double[]> pts = new ArrayList<>();
        for (double[] p : points) {
            pts.add(p.clone());
        }
        Map<Long, Integer> midpoints = new HashMap<>();
        BiFunction<Integer, Integer, Integer> mid = (a, b) -> midpoints.computeIfAbsent(key(a, b), k -> {
            pts.add(new double[]{(points[a][0] + points[b][0]) / 2, (points[a][1] + points[b][1]) / 2});
            return pts.size() - 1;
        });
        List<int[]> els = new ArrayList<>();
        for (int[] t : elements) {
            int n = t.length;
            int[] m = new int[n];
            for (int k = 0; k < n; k++) {
                m[k] = mid.apply(t[k], t[(k + 1) % n]);
            }
            if (n == 3) {
                els.add(new int[]{t[0], m[0], m[2]});
                els.add(new int[]{m[0], t[1], m[1]});
                els.add(new int[]{m[2], m[1], t[2]});
                els.add(new int[]{m[0], m[1], m[2]});
            } else {
                double cx = 0, cy = 0;
                for (int v : t) {
                    cx += points[v][0] / n;
                    cy += points[v][1] / n;
                }
                pts.add(new double[]{cx, cy});
                int c = pts.size() - 1;
                for (int k = 0; k < n; k++) {
                    els.add(new int[]{t[k], m[k], c, m[(k + n - 1) % n]});
                }
            }
        }
        List<int[]> boundary = new ArrayList<>();
        for (int[] e : boundaryEdges) {
            int m = mid.apply(e[1], e[2]);
            boundary.add(new int[]{e[0], e[1], m, e[3]});
            boundary.add(new int[]{e[0], m, e[2], e[3]});
        }
        return new TriMesh(pts.toArray(new double[0][]), els.toArray(new int[0][]), boundary);
    }

    private static List<String[]> readRows(File file) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    rows.add(line.split("\\s+"));
                }
            }
        }
        return rows;
    }

    /**
     * Reads a mesh in the FlowPro format written by exportFlowPro.
     *
     * @return null if the mesh does not exist or if it is not a mesh of
     * triangles and quadrilaterals (it cannot be shown by the generator then)
     */
    public static TriMesh loadFlowPro(File meshDir) throws IOException {
        File elementType = new File(meshDir, "elementType.txt");
        if (!new File(meshDir, "elements.txt").isFile() || !elementType.isFile()) {
            return null;
        }
        for (String[] row : readRows(elementType)) {
            if (!row[0].equals("3") && !row[0].equals("4")) {
                return null;
            }
        }
        try {
            List<String[]> rows = readRows(new File(meshDir, "vertices.txt"));
            double[][] points = new double[rows.size()][2];
            for (int i = 0; i < points.length; i++) {
                points[i][0] = Double.parseDouble(rows.get(i)[0]);
                points[i][1] = Double.parseDouble(rows.get(i)[1]);
            }
            rows = readRows(new File(meshDir, "elements.txt"));
            int[][] elements = new int[rows.size()][];
            for (int i = 0; i < elements.length; i++) {
                elements[i] = new int[rows.get(i).length];
                for (int k = 0; k < elements[i].length; k++) {
                    elements[i][k] = Integer.parseInt(rows.get(i)[k]);
                }
            }
            Map<Long, Integer> ale = new HashMap<>();
            File aleFile = new File(meshDir, "boundaryTypeALE.txt");
            if (aleFile.isFile()) {
                for (String[] row : readRows(aleFile)) {
                    ale.put(key(Integer.parseInt(row[1]), Integer.parseInt(row[2])), Integer.parseInt(row[0]));
                }
            }
            List<int[]> boundary = new ArrayList<>();
            File bt = new File(meshDir, "boundaryType.txt");
            if (bt.isFile()) {
                for (String[] row : readRows(bt)) {
                    int a = Integer.parseInt(row[1]), b = Integer.parseInt(row[2]);
                    boundary.add(new int[]{Integer.parseInt(row[0]), a, b, ale.getOrDefault(key(a, b), 0)});
                }
            }
            TriMesh mesh = new TriMesh(points, elements, boundary);
            File nb = new File(meshDir, "neighbors.txt");
            if (nb.isFile()) {
                rows = readRows(nb);
                if (rows.size() == elements.length) {
                    mesh.neighbours = new int[elements.length][];
                    for (int i = 0; i < elements.length; i++) {
                        mesh.neighbours[i] = new int[elements[i].length];
                        for (int k = 0; k < elements[i].length; k++) {
                            mesh.neighbours[i][k] = Integer.parseInt(rows.get(i)[k]);
                        }
                    }
                }
            }
            return mesh;
        } catch (RuntimeException ex) {
            throw new IOException("the mesh in " + meshDir + " cannot be read: " + ex.getMessage(), ex);
        }
    }

    private static String join(int[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            sb.append(i > 0 ? " " : "").append(values[i]);
        }
        return sb.append('\n').toString();
    }

    /**
     * Writes the mesh in the FlowPro format: vertices.txt, elements.txt,
     * elementType.txt, boundaryType.txt, neighbors.txt and, if some ALE tags
     * are defined, boundaryTypeALE.txt. Files derived from a previous mesh
     * (wall distance, blending functions, ...) are deleted.
     *
     * @return list of deleted files
     */
    public List<String> exportFlowPro(File meshDir) throws IOException {
        if (!meshDir.isDirectory() && !meshDir.mkdirs()) {
            throw new IOException("directory " + meshDir + " cannot be created");
        }
        if (neighbours == null) {
            computeNeighbours();
        }
        try (BufferedWriter w = new BufferedWriter(new FileWriter(new File(meshDir, "vertices.txt")))) {
            for (double[] p : points) {
                w.write(String.format(Locale.ROOT, "%.15g %.15g%n", p[0], p[1]));
            }
        }
        try (BufferedWriter w = new BufferedWriter(new FileWriter(new File(meshDir, "elements.txt")))) {
            for (int[] t : elements) {
                w.write(join(t));
            }
        }
        try (BufferedWriter w = new BufferedWriter(new FileWriter(new File(meshDir, "elementType.txt")))) {
            for (int[] t : elements) {
                w.write(t.length + "\n");
            }
        }
        try (BufferedWriter w = new BufferedWriter(new FileWriter(new File(meshDir, "boundaryType.txt")))) {
            for (int[] e : boundaryEdges) {
                w.write(e[0] + " " + e[1] + " " + e[2] + "\n");
            }
        }
        try (BufferedWriter w = new BufferedWriter(new FileWriter(new File(meshDir, "neighbors.txt")))) {
            for (int[] nb : neighbours) {
                w.write(join(nb));
            }
        }
        boolean hasAle = false;
        for (int[] e : boundaryEdges) {
            hasAle |= e[3] != 0;
        }
        File aleFile = new File(meshDir, "boundaryTypeALE.txt");
        if (hasAle) {
            try (BufferedWriter w = new BufferedWriter(new FileWriter(aleFile))) {
                for (int[] e : boundaryEdges) {
                    if (e[3] != 0) {
                        w.write(e[3] + " " + e[1] + " " + e[2] + "\n");
                    }
                }
            }
        }

        // files computed by FlowPro from the previous mesh are no longer valid
        List<String> deleted = new ArrayList<>();
        List<String> stale = new ArrayList<>(List.of("wallDistance.txt", "neighborsALE.txt",
                "blendingFunctions.txt", "TEshift.txt", "shift.txt", "externalField.txt"));
        if (!hasAle) {
            stale.add("boundaryTypeALE.txt");
        }
        for (String name : stale) {
            File f = new File(meshDir, name);
            if (f.isFile() && f.delete()) {
                deleted.add(name);
            }
        }
        return deleted;
    }
}
