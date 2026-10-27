package flowpro.core.solver;

import flowpro.core.*;
import flowpro.api.Mat;
import flowpro.core.parallel.*;
import flowpro.api.Equation;
import static flowpro.core.FlowProMain.*;
import flowpro.core.element.Element;
import flowpro.api.Dynamics;
import flowpro.api.FluidForces;
import flowpro.core.LinearSolvers.LinearSolver;
import flowpro.core.meshDeformation.*;

import java.io.*;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.lang3.time.StopWatch;

/**
 * tento program resi Navierovy-Stokesovy rovnice na nestrukturovane siti pomoci nespojite Galerkinovy metody
 */
public class LocalImplicitSolver extends MasterSolver {

	private static final Logger LOG = LoggerFactory.getLogger(MasterSolver.class);

//    private final Object lock;
	// common parameters
	private final Deformation dfm;
	private final Dynamics dyn;
	private final Equation eqn;
	private final Parameters par;

	// master and loner parameters
	private final State state;
	private final String simulationPath;

	// slave and loner parameters
	private final Mesh mesh;
	private final Element[] elems;
	private final int dofs;
//    private Solution solution;

	/**
	 * Constructor for master and local.
	 *
	 * @param simulationPath
	 * @param meshes
	 * @param dyn
	 * @param eqn
	 * @param par
	 * @param state
	 * @param domain
	 */
	public LocalImplicitSolver(String simulationPath, Mesh[] meshes, Dynamics dyn,
			Equation eqn, Parameters par, State state, Domain domain) {
		this.simulationPath = simulationPath;
		this.dyn = dyn;
		this.eqn = eqn;
		this.par = par;
		this.state = state;
		mesh = meshes[0];
		elems = mesh.getElems();
		this.dofs = mesh.dofs;
		this.dfm = mesh.getDfm();
	}

	@Override
	public Mesh getMesh() {
		return mesh;
	}

	private void copyWo2W() {
		for (Element elem : elems) {
			elem.copyWo2W();
		}
	}

	private void copyW2Wo() {
		for (Element elem : elems) {
			elem.copyW2Wo();
		}
	}

	private double calculateResiduumW(double dt) {
		double resid = 0;
		for (Element elem : elems) {
			resid += elem.calculateResiduumW(dt);
		}

		return resid / elems.length;
	}

	private String infoToString(int totalSteps, double dt, long assembleTime, long solveTime) throws IOException {
		String timeStr = millisecsToTime(state.getOverallExecutionTime());

		return String.format("%d/%d  resid: %.2e,  dt: %.1e,  t: %.2f,  CFL: %1.2f,  CPU: %s, AT: %dms, ST: %dms",
				state.steps, totalSteps, state.residuum, dt, state.t, state.currentCFL, timeStr, assembleTime, solveTime);
	}

	@Override
	public Solution solve() throws IOException {
		int nElems = elems.length;
		LinearSolver linSolver = LinearSolver.factory(elems, par);
		JacobiAssembler assembler = new JacobiAssembler(elems, par);
		double[] x = new double[dofs];
		StopWatch watch = new StopWatch();

		double dto = -1;
		boolean converges = true;
		int totalSteps = state.steps + par.steps;
		long assembleTime = 0;
		long solveTime = 0;
		boolean isFirstIter = true;
		int tsr = firstTimeSaveIndex(state.t, par.timeSaveRate); // for time save rate
		double[][][] verticesBackup = null; // mesh position at the beginning of the time step

		// save zero iteration
		if (par.animation && state.steps == 0) {
			Solution solution = mesh.getSolution();
			saveAnimationData(solution, state.steps);
		}

		LOG.info("computation has started...");
		watch.start();
		outerloop:
		for (++state.steps; state.steps <= totalSteps && state.residuum > par.residuum
				&& state.t < par.tEnd; ++state.steps) {

			if (converges) {
				state.updateCFL();
			}
			// zastavovaci podminka (works both for fixed time step and for CFL)
			if (state.isConvergenceHopeless()) {
				LOG.error("algorithm does not converge - aborting computation");
				if (verticesBackup != null) {
					restoreMeshPosition(verticesBackup);
				}
				break;
			}
			if (par.movingMesh) {
				verticesBackup = backupMeshPosition();
			}

			// nastaveni dt
			double dt = state.getTimeStep(elems);
			if (dto <= 0) {
				dto = dt;
			}
			if (state.t + dt > par.tEnd) {
				dt = par.tEnd - state.t;
			}
			// set equation object state
			eqn.setState(state.t + dt, dt);

			//compute domain 
			if (par.solutionMonitorOn) {
				mesh.computeSolutionMonitor();
			}

			// compute artificial viscosity
			for (int i = 0; i < nElems; i++) {
				elems[i].limiter(isFirstIter);
			}

			assembleTime = 0;
			solveTime = 0;
			for (int s = 0; s < par.newtonIters; s++) {  // vnitrni iterace (Newton)
				// mesh deformation
				if (par.movingMesh) {
					dfm.calculateForces(elems, dyn.getMeshMove());
					dyn.computeBodyMove(dt, state.t, s, dfm.getFluidForces());
					dfm.newMeshPositionAndVelocity(elems, elems[0].ti.getOrder(), dt, dto, dyn.getMeshMove());
					if (isFirstIter) {
						dfm.relaxFirstIteration(elems, dt);
					}
					dfm.recalculateMesh(elems);
				}

				long startTime = System.currentTimeMillis();
				assembler.assemble(dt, dto);
				assembleTime += System.currentTimeMillis() - startTime;

				// reseni soustavy rovnic
				Arrays.fill(x, 0.0);
				startTime = System.currentTimeMillis();
				converges = linSolver.solve(x);
				solveTime += System.currentTimeMillis() - startTime;

				if (!converges) {
					copyWo2W();
					state.reduceCFL();
					--state.steps;
					if (state.isTimeStepFixed) {
						LOG.warn("GMRES does not converge, time step reduced");
					} else {
						LOG.warn("GMRES does not converge, CFL reduced to " + state.currentCFL);
					}
					continue outerloop;
				}

				state.residuum = Mat.L1Norm(x) / nElems;
				if (par.newtonIters > 1) {
					LOG.info("   " + s + "-inner iteration, reziduum = " + state.residuum);
				}

				// ulozeni novych hodnot
				for (int i = 0; i < nElems; i++) {
					elems[i].updateW(x);
				}

				if (state.residuum < par.newtonIterTol) {
					break;
				}
			}

			state.residuum = calculateResiduumW(dt);
			if (state.residuum == 0) {
				LOG.error("computational error - residuum is equal to zero");
				break;
			}
			state.executionTime = watch.getTime();
			state.t += dt;
			dto = dt;
			appendResiduum(simulationPath, state.residuum, state.t, state.executionTime);

			copyW2Wo();
			mesh.updateTime(dt);
			if (par.movingMesh) {
				dfm.nextTimeLevel(elems);
				dyn.nextTimeLevel();
				dyn.savePositionsAndForces();
				for (int i = 0; i < nElems; i++) {
					elems[i].nextTimeLevelMassMatrixes();
				}
			}

			String info = infoToString(totalSteps, dt, assembleTime, solveTime);
			if ((state.steps % par.saveRate) == 0 || (state.t > tsr * par.timeSaveRate)) {
				Solution solution = mesh.getSolution();
				if (par.animation) {
					saveAnimationData(solution, state.steps);
				}
				saveData(solution);
				LOG.info(info);
				tsr = firstTimeSaveIndex(state.t, par.timeSaveRate);
			}
			System.out.printf(info);
			System.out.println();
			isFirstIter = false;

			if (isStopRequested(simulationPath)) {
				LOG.info("stop requested - finishing computation");
				++state.steps;
				break;
			}
		}
		watch.stop();

		--state.steps;
		state.executionTime = watch.getTime();
		LOG.info("computation has finished in " + millisecsToTime(state.executionTime));
		Solution solution = mesh.getSolution();

		return solution;
	}

	private double[][][] backupMeshPosition() {
		double[][][] backup = new double[elems.length][][];
		for (int i = 0; i < elems.length; i++) {
			backup[i] = new double[elems[i].nVertices][];
			for (int j = 0; j < elems[i].nVertices; j++) {
				backup[i][j] = elems[i].vertices[j].clone();
			}
		}
		return backup;
	}

	private void restoreMeshPosition(double[][][] backup) {
		for (int i = 0; i < elems.length; i++) {
			for (int j = 0; j < elems[i].nVertices; j++) {
				System.arraycopy(backup[i][j], 0, elems[i].vertices[j], 0, backup[i][j].length);
			}
		}
		dfm.recalculateMesh(elems);
	}

	@Override
	public void testDynamic(double dt, int newtonIter) throws IOException {
		eqn.saveReferenceValues(simulationPath + REF_VALUE_FILE_NAME);
		double t = 0;
		for (int step = 0; step <= par.steps; step++) {
			FluidForces[] fluidForces = new FluidForces[dfm.nBodies];
			for (int b = 0; b < dfm.nBodies; b++) {
                double[] F = new double[2];
                F[1] = 0.2*Math.sin(2*Math.PI*0.1671*t);
				fluidForces[b] = new FluidForces(F, new double[1]);
			}
			dyn.computeBodyMove(dt, t, newtonIter, fluidForces);
			dyn.nextTimeLevel();
			dyn.savePositionsAndForces();
			t += dt;
			System.out.println(step + "-th iteration t = " + t);
		}
	}

	public void saveData(Solution sol) throws IOException {
		state.save();
		eqn.saveReferenceValues(simulationPath + REF_VALUE_FILE_NAME);
		Mat.save(sol.avgW, simulationPath + "W.txt");
		Mat.save(sol.W, simulationPath + "We.txt");
		Mat.save(mesh.getArtificialViscosity(), simulationPath + "artificialViscosity.txt");
		if (par.movingMesh) {
			Mat.save(sol.vertices, simulationPath + "PXY.txt");
			Mat.save(sol.meshVelocity, simulationPath + "UXY.txt");
		}
//            File[] content = new File(simulationPath + "output").listFiles();
//            if (content != null) {
//                for (File file : content) {
//                    file.delete();
//                }
//            }

		LOG.info("results have been saved into " + simulationPath);
	}

	public void saveAnimationData(Solution sol, int step) throws IOException {
		File directory = new File(simulationPath + "animation");
		if (!directory.exists()) {
			directory.mkdir();
		}
		Mat.save(sol.avgW, simulationPath + "animation/W" + String.format("%08d", step) + ".txt");
		if (par.order > 1) {
			Mat.save(sol.W, simulationPath + "animation/We" + String.format("%08d", step) + ".txt");
		}
		if (par.movingMesh) {
			Mat.save(sol.vertices, simulationPath + "animation/vertices" + String.format("%08d", step) + ".txt");
			Mat.save(sol.meshVelocity, simulationPath + "animation/verticesVelocity" + String.format("%08d", step) + ".txt");
		}
		LOG.info("results have been saved into " + simulationPath);
	}

}
