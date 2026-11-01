package flowpro.mesher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generator of structured meshes of blocks (as blockMesh of OpenFOAM). The
 * faces of the blocks are divided according to the number of cells and the
 * grading (a face shared by two blocks is divided only once, so the mesh is
 * conforming), the interior points of a block are computed by the transfinite
 * interpolation of its four (straight or arc) faces.
 *
 * @author obublik
 */
public class BlockMesher {

    private final Geometry geo;
    private final List<double[]> points = new ArrayList<>();
    private int[] vertexNode;
    // nodes and parameters of the divided edges, from the lower vertex index to the higher one
    private final Map<Long, int[]> edgeNodes = new HashMap<>();
    private final Map<Long, double[]> edgeParameters = new HashMap<>();

    public BlockMesher(Geometry geo) {
        this.geo = geo;
    }

    private static long key(int a, int b) {
        return a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
    }

    /**
     * Parameters of the points dividing [0, 1] into n cells, the ratio of the
     * last and the first cell is g.
     */
    static double[] distribution(int n, double g) {
        double[] s = new double[n + 1];
        double r = n > 1 ? Math.pow(g, 1.0 / (n - 1)) : 1;
        double size = 1;
        for (int i = 0; i < n; i++) {
            s[i + 1] = s[i] + size;
            size *= r;
        }
        for (int i = 0; i <= n; i++) {
            s[i] /= s[n];
        }
        return s;
    }

    private int addPoint(double[] p) {
        points.add(p);
        return points.size() - 1;
    }

    /**
     * Divides the edge from block vertex a to b (if it was not divided by
     * another block yet).
     */
    private void divideEdge(int a, int b, int n, double g) {
        long key = key(a, b);
        if (edgeNodes.containsKey(key)) {
            return;
        }
        double[] s = distribution(n, g);
        if (a > b) {
            // stored from the lower vertex index
            double[] reversed = new double[n + 1];
            for (int i = 0; i <= n; i++) {
                reversed[i] = 1 - s[n - i];
            }
            s = reversed;
            int t = a;
            a = b;
            b = t;
        }
        int[] nodes = new int[n + 1];
        nodes[0] = vertexNode[a];
        nodes[n] = vertexNode[b];
        for (int i = 1; i < n; i++) {
            nodes[i] = addPoint(geo.edgePoint(a, b, s[i]));
        }
        edgeNodes.put(key, nodes);
        edgeParameters.put(key, s);
    }

    /**
     * nodes of the divided edge oriented from a to b
     */
    private int[] nodes(int a, int b) {
        int[] nodes = edgeNodes.get(key(a, b));
        if (a < b) {
            return nodes;
        }
        int[] r = new int[nodes.length];
        for (int i = 0; i < r.length; i++) {
            r[i] = nodes[nodes.length - 1 - i];
        }
        return r;
    }

    /**
     * parameters of the divided edge oriented from a to b
     */
    private double[] parameters(int a, int b) {
        double[] s = edgeParameters.get(key(a, b));
        if (a < b) {
            return s;
        }
        double[] r = new double[s.length];
        for (int i = 0; i < r.length; i++) {
            r[i] = 1 - s[s.length - 1 - i];
        }
        return r;
    }

    public TriMesh generate() throws Mesher.MeshingException {
        int factor = 1 << Math.max(0, geo.refinementLevels);
        vertexNode = new int[geo.blockVertices.size()];
        for (int i = 0; i < vertexNode.length; i++) {
            vertexNode[i] = addPoint(geo.blockVertices.get(i).clone());
        }
        for (Geometry.Block block : geo.blocks) {
            for (int k = 0; k < 4; k++) {
                divideEdge(block.v[k], block.v[(k + 1) % 4], block.cells(k) * factor, block.grading(k));
            }
        }

        List<int[]> elements = new ArrayList<>();
        List<int[]> boundary = new ArrayList<>();
        for (Geometry.Block block : geo.blocks) {
            int v0 = block.v[0], v1 = block.v[1], v2 = block.v[2], v3 = block.v[3];
            int n = block.nx * factor, m = block.ny * factor;
            int[] bottom = nodes(v0, v1), top = nodes(v3, v2), left = nodes(v0, v3), right = nodes(v1, v2);
            double[] sb = parameters(v0, v1), st = parameters(v3, v2), tl = parameters(v0, v3), tr = parameters(v1, v2);
            if (bottom.length != n + 1 || top.length != n + 1 || left.length != m + 1 || right.length != m + 1) {
                throw new Mesher.MeshingException("blocks sharing a face have a different number of cells on it");
            }
            double[] p00 = geo.blockVertices.get(v0), p10 = geo.blockVertices.get(v1);
            double[] p11 = geo.blockVertices.get(v2), p01 = geo.blockVertices.get(v3);
            int[][] grid = new int[n + 1][m + 1];
            for (int i = 0; i <= n; i++) {
                grid[i][0] = bottom[i];
                grid[i][m] = top[i];
            }
            for (int j = 0; j <= m; j++) {
                grid[0][j] = left[j];
                grid[n][j] = right[j];
            }
            for (int i = 1; i < n; i++) {
                for (int j = 1; j < m; j++) {
                    // intersection of the lines connecting the points of the opposite faces
                    double u = (sb[i] + tl[j] * (st[i] - sb[i])) / (1 - (tr[j] - tl[j]) * (st[i] - sb[i]));
                    double v = tl[j] + u * (tr[j] - tl[j]);
                    double[] b = geo.edgePoint(v0, v1, u), t = geo.edgePoint(v3, v2, u);
                    double[] l = geo.edgePoint(v0, v3, v), r = geo.edgePoint(v1, v2, v);
                    double[] p = new double[2];
                    for (int c = 0; c < 2; c++) {
                        p[c] = (1 - v) * b[c] + v * t[c] + (1 - u) * l[c] + u * r[c]
                                - ((1 - u) * (1 - v) * p00[c] + u * (1 - v) * p10[c] + (1 - u) * v * p01[c] + u * v * p11[c]);
                    }
                    grid[i][j] = addPoint(p);
                }
            }
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < m; j++) {
                    int a = grid[i][j], b = grid[i + 1][j], c = grid[i + 1][j + 1], d = grid[i][j + 1];
                    if (geo.blockTriangles) {
                        // division along the shorter diagonal
                        if (distance(a, c) <= distance(b, d)) {
                            elements.add(new int[]{a, b, c});
                            elements.add(new int[]{a, c, d});
                        } else {
                            elements.add(new int[]{a, b, d});
                            elements.add(new int[]{b, c, d});
                        }
                    } else {
                        elements.add(new int[]{a, b, c, d});
                    }
                }
            }
            for (int k = 0; k < 4; k++) {
                int a = block.v[k], b = block.v[(k + 1) % 4];
                if (geo.blocksAt(a, b).size() > 1) {
                    continue; // inner face
                }
                int[] side = nodes(a, b);
                for (int i = 0; i + 1 < side.length; i++) {
                    boundary.add(new int[]{block.types[k], side[i], side[i + 1], block.aleTags[k]});
                }
            }
        }
        return new TriMesh(points.toArray(new double[0][]), elements.toArray(new int[0][]), boundary);
    }

    private double distance(int a, int b) {
        double[] p = points.get(a), q = points.get(b);
        return Math.hypot(p[0] - q[0], p[1] - q[1]);
    }
}
