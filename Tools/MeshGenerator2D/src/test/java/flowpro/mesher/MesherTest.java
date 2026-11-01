package flowpro.mesher;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Block meshes, refinement regions and uniform refinement of the generator.
 *
 * @author obublik
 */
public class MesherTest {

    private static TriMesh generate(Geometry geo) throws Mesher.MeshingException {
        return new Mesher(geo, null).generate();
    }

    private static Geometry.Block block(Geometry geo, int v0, int v1, int v2, int v3, int nx, int ny) {
        Geometry.Block b = new Geometry.Block();
        b.v[0] = v0;
        b.v[1] = v1;
        b.v[2] = v2;
        b.v[3] = v3;
        b.nx = nx;
        b.ny = ny;
        geo.blocks.add(b);
        return b;
    }

    /**
     * two blocks [0,1]x[0,1] and [1,2]x[0,1] sharing the face x = 1
     */
    private static Geometry twoBlocks() {
        Geometry geo = new Geometry();
        double[][] v = {{0, 0}, {1, 0}, {2, 0}, {2, 1}, {1, 1}, {0, 1}};
        for (double[] p : v) {
            geo.blockVertices.add(p);
        }
        block(geo, 0, 1, 4, 5, 4, 3);
        block(geo, 1, 2, 3, 4, 5, 3);
        return geo;
    }

    private static double signedArea(TriMesh mesh, int[] el) {
        double a = 0;
        for (int k = 0; k < el.length; k++) {
            double[] p = mesh.points[el[k]], q = mesh.points[el[(k + 1) % el.length]];
            a += p[0] * q[1] - q[0] * p[1];
        }
        return a / 2;
    }

    private static double totalArea(TriMesh mesh) {
        double sum = 0;
        for (int[] el : mesh.elements) {
            double a = signedArea(mesh, el);
            assertTrue("element is not counter-clockwise", a > 0);
            sum += a;
        }
        return sum;
    }

    /**
     * every face is either shared by two elements or lies on the boundary
     */
    private static void checkConforming(TriMesh mesh) {
        Map<String, Integer> faces = new HashMap<>();
        for (int[] el : mesh.elements) {
            for (int k = 0; k < el.length; k++) {
                int a = el[k], b = el[(k + 1) % el.length];
                faces.merge(Math.min(a, b) + " " + Math.max(a, b), 1, Integer::sum);
            }
        }
        int boundary = 0;
        for (int n : faces.values()) {
            assertTrue("face of more than two elements", n <= 2);
            if (n == 1) {
                boundary++;
            }
        }
        assertEquals("boundary faces", boundary, mesh.boundaryEdges.size());
        mesh.computeNeighbours();
        for (int[] nb : mesh.neighbours) {
            for (int n : nb) {
                assertTrue(n >= -4);
            }
        }
    }

    @Test
    public void twoBlocksAreConforming() throws Exception {
        Geometry geo = twoBlocks();
        geo.blocks.get(0).types[3] = Geometry.INLET;
        geo.blocks.get(1).types[1] = Geometry.OUTLET;
        TriMesh mesh = generate(geo);
        assertEquals(4 * 3 + 5 * 3, mesh.elements.length);
        assertEquals(10 * 4, mesh.points.length);
        assertEquals(2.0, totalArea(mesh), 1e-12);
        checkConforming(mesh);
        int inlet = 0, outlet = 0;
        for (int[] e : mesh.boundaryEdges) {
            inlet += e[0] == Geometry.INLET ? 1 : 0;
            outlet += e[0] == Geometry.OUTLET ? 1 : 0;
        }
        assertEquals(3, inlet);
        assertEquals(3, outlet);
    }

    @Test
    public void divisionIsPropagatedToConnectedBlocks() {
        Geometry geo = twoBlocks();
        geo.setDivision(0, 1, 6, 2);
        Geometry.Block b = geo.blocks.get(1);
        assertEquals(6, b.ny);
        assertEquals(2, b.gy, 1e-12); // the same direction (from y = 0 to y = 1)
        assertEquals(5, b.nx); // the other direction is independent
        assertNull(geo.validate());
    }

    @Test
    public void inconsistentDivisionIsReported() {
        Geometry geo = twoBlocks();
        geo.blocks.get(1).ny = 7;
        assertNotNull(geo.validate());
    }

    @Test
    public void gradingOfSharedFaceIsConforming() throws Exception {
        Geometry geo = twoBlocks();
        geo.setDivision(0, 1, 8, 5);
        geo.setDivision(0, 0, 6, 0.2);
        TriMesh mesh = generate(geo);
        assertEquals(2.0, totalArea(mesh), 1e-12);
        checkConforming(mesh);
    }

    @Test
    public void annulusOfArcBlocks() throws Exception {
        // 4 blocks between the circles r = 0.5 and r = 2
        Geometry geo = new Geometry();
        for (int i = 0; i < 4; i++) {
            double phi = Math.PI / 2 * i;
            geo.blockVertices.add(new double[]{0.5 * Math.cos(phi), 0.5 * Math.sin(phi)});
            geo.blockVertices.add(new double[]{2 * Math.cos(phi), 2 * Math.sin(phi)});
        }
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            double mid = Math.PI / 2 * (i + 0.5);
            // inner circle, ray, outer circle, ray
            block(geo, 2 * i, 2 * i + 1, 2 * j + 1, 2 * j, 8, 6).types[3] = Geometry.WALL;
            geo.arcs.add(new Geometry.Arc(2 * i + 1, 2 * j + 1, 2 * Math.cos(mid), 2 * Math.sin(mid)));
            geo.arcs.add(new Geometry.Arc(2 * i, 2 * j, 0.5 * Math.cos(mid), 0.5 * Math.sin(mid)));
        }
        assertNull(geo.validate());
        geo.refinementLevels = 2;
        TriMesh mesh = generate(geo);
        assertEquals(4 * 8 * 6 * 16, mesh.elements.length);
        checkConforming(mesh);
        assertEquals(2 * 4 * 6 * 4, mesh.boundaryEdges.size());
        // the polygonal area converges to the area of the annulus
        assertEquals(Math.PI * (4 - 0.25), totalArea(mesh), 0.02);
        for (double[] p : mesh.points) {
            double r = Math.hypot(p[0], p[1]);
            assertTrue(r > 0.5 - 1e-9 && r < 2 + 1e-9);
        }
    }

    @Test
    public void blocksDividedIntoTriangles() throws Exception {
        Geometry geo = twoBlocks();
        geo.blockTriangles = true;
        TriMesh mesh = generate(geo);
        assertEquals(2 * 27, mesh.elements.length);
        for (int[] el : mesh.elements) {
            assertEquals(3, el.length);
        }
        assertEquals(2.0, totalArea(mesh), 1e-12);
        checkConforming(mesh);
    }

    private static Geometry channel() {
        Geometry geo = new Geometry();
        geo.loops.add(Geometry.rectangle(0, 0, 4, 2, Geometry.WALL));
        geo.meshSize = 0.4;
        return geo;
    }

    @Test
    public void uniformRefinementOfTriangles() throws Exception {
        Geometry geo = channel();
        TriMesh coarse = generate(geo);
        geo.refinementLevels = 1;
        TriMesh fine = generate(geo);
        assertEquals(4 * coarse.elements.length, fine.elements.length);
        assertEquals(2 * coarse.boundaryEdges.size(), fine.boundaryEdges.size());
        assertEquals(8.0, totalArea(fine), 1e-9);
        checkConforming(fine);
    }

    @Test
    public void refinementRegionMakesSmallerElements() throws Exception {
        Geometry geo = channel();
        TriMesh plain = generate(geo);
        geo.regions.add(Geometry.Region.circle(2, 1, 0.3, 0.05));
        TriMesh refined = generate(geo);
        assertTrue(refined.elements.length > 2 * plain.elements.length);
        checkConforming(refined);
        double maxEdge = 0;
        for (int[] el : refined.elements) {
            double cx = 0, cy = 0;
            for (int v : el) {
                cx += refined.points[v][0] / 3;
                cy += refined.points[v][1] / 3;
            }
            if (Math.hypot(cx - 2, cy - 1) < 0.25) {
                for (int k = 0; k < 3; k++) {
                    double[] p = refined.points[el[k]], q = refined.points[el[(k + 1) % 3]];
                    maxEdge = Math.max(maxEdge, Math.hypot(p[0] - q[0], p[1] - q[1]));
                }
            }
        }
        assertTrue("edge " + maxEdge, maxEdge > 0 && maxEdge < 0.1);
    }

    @Test
    public void geometryAndQuadMeshFilesRoundTrip() throws Exception {
        Geometry geo = twoBlocks();
        geo.arcs.add(new Geometry.Arc(2, 3, 2.3, 0.5));
        geo.regions.add(Geometry.Region.box(0.2, 0.2, 0.6, 0.4, 0.01));
        geo.regions.add(Geometry.Region.circle(1, 1, 0.5, 0.02));
        geo.setDivision(1, 1, 3, 1.5);
        geo.blocks.get(1).types[1] = Geometry.OUTLET;
        geo.blocks.get(1).aleTags[1] = 2;
        geo.refinementLevels = 1;
        geo.blockTriangles = false;
        File dir = Files.createTempDirectory("mesher").toFile();
        File file = new File(dir, "g.geo2d");
        geo.save(file);
        Geometry g = Geometry.load(file);
        assertEquals(geo.blockVertices.size(), g.blockVertices.size());
        assertEquals(2, g.blocks.size());
        assertEquals(1.5, g.blocks.get(1).gy, 1e-15);
        assertEquals(Geometry.OUTLET, g.blocks.get(1).types[1]);
        assertEquals(2, g.blocks.get(1).aleTags[1]);
        assertEquals(1, g.arcs.size());
        assertEquals(2, g.regions.size());
        assertTrue(g.regions.get(1).circle);
        assertEquals(1, g.refinementLevels);

        TriMesh mesh = generate(g);
        File meshDir = new File(dir, "mesh");
        mesh.exportFlowPro(meshDir);
        assertEquals("4", Files.readAllLines(new File(meshDir, "elementType.txt").toPath()).get(0));
        TriMesh loaded = TriMesh.loadFlowPro(meshDir);
        assertNotNull(loaded);
        assertEquals(mesh.elements.length, loaded.elements.length);
        assertEquals(4, loaded.neighbours[0].length);
        assertTrue(new File(meshDir, "boundaryTypeALE.txt").isFile());
    }
}
