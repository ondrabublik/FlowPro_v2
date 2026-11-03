package flowpro.core.solver;

import flowpro.core.*;
import flowpro.api.Mat;
import flowpro.core.parallel.*;
import flowpro.api.Equation;
import static flowpro.core.FlowProMain.*;
import flowpro.core.element.Element;
import flowpro.core.element.Implicit;
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

	// L2 norm of the residuum of the time step (right hand side of the Newton iteration)
	private double rhsNorm() {
		double sum = 0;
		for (Element elem : elems) {
			if (elem.insideComputeDomain) {
				for (double r : elem.l2NormsSquared(((Implicit) elem.ti).RHS_loc)) {
					sum += r;
				}
			}
		}
		return Math.sqrt(sum);
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

		// previous time step, dto <= 0 until the first step is finished (the previous
		// time level is not known, BDF2 makes the first step as BDF1)
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
			// tEnd reached up to the round-off of the summed time (1200 * 0.1 < 120), the remaining
			// step dt ~ 1e-12 would spoil the BDF solution
			if (par.tEnd - state.t <= 1e-6 * dt) {
				break;
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

			// compute artificial viscosity (each element writes only its own data)
			boolean isFirstStep = isFirstIter;
			ParallelLoop.forEach(nElems, par.nThreads, i -> elems[i].limiter(isFirstStep));

			assembleTime = 0;
			solveTime = 0;
			double rhsNorm0 = 0; // norm of the residuum of the time step before the first Newton iteration
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
				if (s > 0) {
					// convergence of the Newton iterations: the residuum of the time step
					// (without the Jacobi matrix) related to the one before the first iteration
					assembler.assembleResiduum(dt, dto);
					double relativeRhs = rhsNorm0 > 0 ? rhsNorm() / rhsNorm0 : 0;
					LOG.info("   " + s + "-inner iteration, relative residuum = " + relativeRhs);
					if (relativeRhs < par.newtonIterTol) {
						assembleTime += System.currentTimeMillis() - startTime;
						break;
					}
				}
				assembler.assemble(dt, dto);
				assembleTime += System.currentTimeMillis() - startTime;
				if (s == 0) {
					// residuum R(W) of the solution at the beginning of the time step
					state.setResiduum(residuumNorms(elems, par.nThreads));
					rhsNorm0 = rhsNorm();
				}

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

				// ulozeni novych hodnot
				for (int i = 0; i < nElems; i++) {
					elems[i].updateW(x);
				}
				int nLimited = Element.positivityLimiter(elems, par);
				if (nLimited > 0) {
					LOG.info("   positivity limiter applied in " + nLimited + " elements");
				}
			}

			if (!Double.isFinite(state.residuum)) {
				LOG.error("computational error - residuum is not a finite number");
				break;
			}
			state.executionTime = watch.getTime();
			state.t += dt;
			dto = dt;
			appendResiduum(simulationPath, state.residuum, state.t, state.executionTime, state.equationResiduum);

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
