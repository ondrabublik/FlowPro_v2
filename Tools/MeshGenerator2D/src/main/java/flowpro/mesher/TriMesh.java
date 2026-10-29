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

/**
 * Triangular mesh and its export into the FlowPro format.
 *
 * @author obublik
 */
public class TriMesh {

    public final double[][] points;
    public final int[][] triangles; // counter-clockwise
    public final List<int[]> boundaryEdges; // {type, a, b, ale}
    public int[][] neighbours; // neighbours[i][k] across edge (v_k, v_k+1), boundary type if negative

    public TriMesh(double[][] points, int[][] triangles, List<int[]> boundaryEdges) {
        this.points = points;
        this.triangles = triangles;
        this.boundaryEdges = boundaryEdges;
    }

    private static long key(int a, int b) {
        return a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
    }

    private double area2(int[] t) {
        double[] a = points[t[0]], b = points[t[1]], c = points[t[2]];
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
    }

    /**
     * @return angles of the triangle in degrees
     */
    public double[] angles(int[] t) {
        double[] ang = new double[3];
        for (int k = 0; k < 3; k++) {
            double[] a = points[t[k]], b = points[t[(k + 1) % 3]], c = points[t[(k + 2) % 3]];
            double ux = b[0] - a[0], uy = b[1] - a[1], vx = c[0] - a[0], vy = c[1] - a[1];
            ang[k] = Math.toDegrees(Math.atan2(Math.abs(ux * vy - uy * vx), ux * vx + uy * vy));
        }
        return ang;
    }

    public double minAngle(int[] t) {
        double[] a = angles(t);
        return Math.min(a[0], Math.min(a[1], a[2]));
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
        for (int t = 0; t < triangles.length; t++) {
            int[] tri = triangles[t];
            for (int k = 0; k < 3; k++) {
                adjacentTriangles.get(tri[k]).add(t);
                adjacentVertices.get(tri[k]).add(tri[(k + 1) % 3]);
                adjacentVertices.get(tri[k]).add(tri[(k + 2) % 3]);
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
                    oldQuality = Math.min(oldQuality, minAngle(triangles[t]));
                }
                points[v][0] = x;
                points[v][1] = y;
                double newQuality = Double.MAX_VALUE;
                boolean valid = true;
                for (int t : adjacentTriangles.get(v)) {
                    if (area2(triangles[t]) <= 0) {
                        valid = false;
                        break;
                    }
                    newQuality = Math.min(newQuality, minAngle(triangles[t]));
                }
                if (!valid || newQuality < oldQuality) {
                    points[v][0] = oldX;
                    points[v][1] = oldY;
                }
            }
        }
    }

    /**
     * Neighbours in the FlowPro convention: face k of a triangle is the edge
     * (v_k, v_(k+1) mod 3), the value is the index of the neighbouring element
     * or the (negative) boundary type.
     */
    public void computeNeighbours() {
        Map<Long, Integer> boundaryType = new HashMap<>();
        for (int[] e : boundaryEdges) {
            boundaryType.put(key(e[1], e[2]), e[0]);
        }
        Map<Long, int[]> faces = new HashMap<>();
        neighbours = new int[triangles.length][3];
        for (int t = 0; t < triangles.length; t++) {
            for (int k = 0; k < 3; k++) {
                long key = key(triangles[t][k], triangles[t][(k + 1) % 3]);
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
        int below20 = 0;
        for (int[] t : triangles) {
            double[] a = angles(t);
            double tmin = Math.min(a[0], Math.min(a[1], a[2]));
            min = Math.min(min, tmin);
            max = Math.max(max, Math.max(a[0], Math.max(a[1], a[2])));
            sum += tmin;
            if (tmin < 20) {
                below20++;
            }
        }
        return String.format(Locale.ROOT, "%d vertices, %d triangles, %d boundary edges, min angle %.1f deg, max angle %.1f deg, "
                + "mean min angle %.1f deg, triangles with angle < 20 deg: %d",
                points.length, triangles.length, boundaryEdges.size(), min, max,
                triangles.length > 0 ? sum / triangles.length : 0, below20);
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
     * triangles only (it cannot be shown by the generator then)
     */
    public static TriMesh loadFlowPro(File meshDir) throws IOException {
        File elementType = new File(meshDir, "elementType.txt");
        if (!new File(meshDir, "elements.txt").isFile() || !elementType.isFile()) {
            return null;
        }
        for (String[] row : readRows(elementType)) {
            if (!row[0].equals("3")) {
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
            int[][] triangles = new int[rows.size()][3];
            for (int i = 0; i < triangles.length; i++) {
                for (int k = 0; k < 3; k++) {
                    triangles[i][k] = Integer.parseInt(rows.get(i)[k]);
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
            TriMesh mesh = new TriMesh(points, triangles, boundary);
            File nb = new File(meshDir, "neighbors.txt");
            if (nb.isFile()) {
                rows = readRows(nb);
                if (rows.size() == triangles.length) {
                    mesh.neighbours = new int[triangles.length][3];
                    for (int i = 0; i < triangles.length; i++) {
                        for (int k = 0; k < 3; k++) {
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
            for (int[] t : triangles) {
                w.write(t[0] + " " + t[1] + " " + t[2] + "\n");
            }
        }
        try (BufferedWriter w = new BufferedWriter(new FileWriter(new File(meshDir, "elementType.txt")))) {
            for (int i = 0; i < triangles.length; i++) {
                w.write("3\n");
            }
        }
        try (BufferedWriter w = new BufferedWriter(new FileWriter(new File(meshDir, "boundaryType.txt")))) {
            for (int[] e : boundaryEdges) {
                w.write(e[0] + " " + e[1] + " " + e[2] + "\n");
            }
        }
        try (BufferedWriter w = new BufferedWriter(new FileWriter(new File(meshDir, "neighbors.txt")))) {
            for (int[] nb : neighbours) {
                w.write(nb[0] + " " + nb[1] + " " + nb[2] + "\n");
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
