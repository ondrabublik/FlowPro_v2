package flowpro.core;

import flowpro.core.element.Element;
import flowpro.core.element.Implicit;
import flowpro.core.solver.MasterSolver;
import java.io.File;
import java.util.Random;
import static org.junit.Assert.assertTrue;
import org.junit.AfterClass;
import org.junit.Test;

/**
 * The analytic Jacobi matrix of the residuum (DGjacobi, DGpureFast, Element.residuumJacobi)
 * compared with finite differences of the residuum. The test equation is
 * linear, thus the finite differences are exact up to rounding. Both the
 * diagonal block (ADiag) and the blocks of the neighbours (ANeighs) are
 * stored as -dR/dW, transposed: A[perturbed dof][residuum dof].
 */
public class JacobianTest {

    @AfterClass
    public static void cleanUp() {
        TestSimulation.deleteRecursively(new File(TestSimulation.ROOT + "jacobian"));
    }

    private static double maxRelativeDifference(String method, int p, double diffusion) throws Exception {
        String geometry = "jacobian";
        TestSimulation.writeSquareMesh(geometry, 3, 1, -2, false);
        TestSimulation.writeParameters(geometry, "sim", TestSimulation.scalarParameters(method, p + 1, diffusion));
        MasterSolver solver = TestSimulation.solver(geometry, "sim", null);
        Element[] elems = solver.getMesh().getElems();

        Random random = new Random(1);
        for (Element elem : elems) {
            for (int i = 0; i < elem.W.length; i++) {
                elem.W[i] = random.nextDouble();
            }
        }
        for (Element elem : elems) {
            elem.limiter(false);
        }

        double h = 1e-6;
        double maxDiff = 0;
        double maxEntry = 0;
        for (Element elem : elems) {
            int n = elem.W.length;
            Implicit ti = (Implicit) elem.ti;
            double[][] aDiag = new double[n][n];
            Implicit.Neighbour[] neighbours = new Implicit.Neighbour[elem.nFaces];
            for (int k = 0; k < elem.nFaces; k++) {
                int nR = elem.TT[k] > -1 ? elems[elem.TT[k]].nBasis : 0;
                neighbours[k] = ti.new Neighbour(elem.TT[k], elem.nBasis, nR, 1);
            }
            elem.residuumJacobi(aDiag, neighbours);

            double[] r0 = residuum(elem);
            double[] v = new double[n];
            for (int i = 0; i < n; i++) {
                v[i] = h;
                double[] rh = new double[n];
                elem.residuum(v, rh, new double[elem.nFaces][]);
                v[i] = 0;
                for (int j = 0; j < n; j++) {
                    double fd = -(rh[j] - r0[j]) / h;
                    maxDiff = Math.max(maxDiff, Math.abs(fd - aDiag[i][j]));
                    maxEntry = Math.max(maxEntry, Math.abs(fd));
                }
            }
            for (int k = 0; k < elem.nFaces; k++) {
                if (elem.TT[k] < 0) {
                    continue;
                }
                double[] wNeighbour = elems[elem.TT[k]].W;
                for (int i = 0; i < wNeighbour.length; i++) {
                    wNeighbour[i] += h;
                    double[] rh = residuum(elem);
                    wNeighbour[i] -= h;
                    for (int j = 0; j < n; j++) {
                        double fd = -(rh[j] - r0[j]) / h;
                        maxDiff = Math.max(maxDiff, Math.abs(fd - neighbours[k].A[i][j]));
                        maxEntry = Math.max(maxEntry, Math.abs(fd));
                    }
                }
            }
        }
        System.out.printf("%s, p = %d, k = %.2f: max |analytic - FD| / max |FD| = %.3e%n", method, p, diffusion, maxDiff / maxEntry);
        return maxDiff / maxEntry;
    }

    private static double[] residuum(Element elem) {
        double[] r = new double[elem.W.length];
        elem.residuum(new double[elem.W.length], r, new double[elem.nFaces][]);
        return r;
    }

    @Test
    public void analyticMatchesFiniteDifferences() throws Exception {
        for (String method : new String[]{"DGjacobi", "DGpureFast"}) {
            for (int p = 1; p <= 2; p++) {
                assertTrue(method + ", p = " + p, maxRelativeDifference(method, p, 0) < 1e-6);
                assertTrue(method + ", p = " + p + ", diffusion", maxRelativeDifference(method, p, 0.1) < 1e-6);
            }
        }
    }
}
