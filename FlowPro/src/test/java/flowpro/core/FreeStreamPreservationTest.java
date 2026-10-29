package flowpro.core;

import flowpro.core.element.Element;
import flowpro.core.solver.MasterSolver;
import java.io.File;
import static org.junit.Assert.assertTrue;
import org.junit.AfterClass;
import static org.junit.Assume.assumeTrue;
import org.junit.Test;

/**
 * Discrete geometric conservation law: a gas at rest in a closed square stays at
 * rest when the mesh deforms (deformation Test2D, the boundary does not move).
 * Uses the modules NavierStokes (Aerodynamics) and Rigid2D (RigidBody), the
 * test is skipped when they have not been built yet.
 */
public class FreeStreamPreservationTest {

    @AfterClass
    public static void cleanUp() {
        TestSimulation.deleteRecursively(new File(TestSimulation.ROOT + "gcl"));
    }

    private static double maxDeviation(int timeOrder, boolean gcl) throws Exception {
        String geometry = "gcl";
        TestSimulation.writeSquareMesh(geometry, 8, 10, -4, true);
        TestSimulation.writeParameters(geometry, "sim",
                "model = NavierStokes", "dimension = 2", "isFlowViscous = false",
                "cv = 1", "kappa = 1.4", "isInletSupersonic = false", "attackAngle = 0",
                "pIn0 = 1", "rhoIn0 = 1", "pOut = 1", // pOut = pIn0: gas at rest
                "movingMesh = true", "meshDeformationType = Test2D", "dynamicsModel = Rigid2D",
                "gcl = " + gcl,
                "continueComputation = false", "steps = 20", "saveRate = 1000",
                "spatialMethod = DGpure", "timeMethod = BDF", "timeOrder = " + timeOrder,
                "linearSolver = gmres", "preconditioner = ilu0", "iterativeSolverTol = 1e-12",
                "order = 2", "dt = 0.25", "threads = 2", "newtonIters = 2", "newtonIterTol = 1e-12",
                "penalty = 0", "dampTol = 0", "dampConst = 0");
        MasterSolver solver = TestSimulation.solver(geometry, "sim", null);
        solver.solve();
        double[] rest = {1, 0, 0, 2.5}; // rho, rho u, rho v, E = p / (kappa - 1)
        double deviation = 0;
        for (Element elem : solver.getMesh().getElems()) {
            for (int m = 0; m < 4; m++) {
                for (int j = 0; j < elem.nBasis; j++) {
                    deviation = Math.max(deviation, Math.abs(elem.W[m * elem.nBasis + j] - rest[m]));
                }
            }
        }
        System.out.printf("BDF%d, gcl = %b: max |W - W0| = %.3e%n", timeOrder, gcl, deviation);
        return deviation;
    }

    @Test
    public void gasAtRestOnDeformingMesh() throws Exception {
        assumeTrue(new File("modules/equations/Aerodynamics.jar").isFile()
                && new File("modules/dynamics/RigidBody.jar").isFile());
        for (int timeOrder = 1; timeOrder <= 2; timeOrder++) {
            assertTrue(maxDeviation(timeOrder, true) < 1e-10);
            // without the discrete GCL the uniform state is not preserved
            assertTrue(maxDeviation(timeOrder, false) > 1e-4);
        }
    }
}
