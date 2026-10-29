package flowpro.core.solver;

import flowpro.core.*;
import flowpro.api.Mat;
import flowpro.core.parallel.*;
import flowpro.api.Equation;
import static flowpro.core.FlowProMain.*;
import flowpro.api.Dynamics;
import flowpro.api.FluidForces;
import flowpro.core.meshDeformation.*;
import flowpro.core.parallel.Domain.Subdomain;
import litempi.*;

import java.io.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.lang3.time.StopWatch;

/**
 * tento program resi Navierovy-Stokesovy rovnice na nestrukturovane siti pomoci
 * nespojite Galerkinovy metody
 */
public class SchwartzImplicitSolver extends MasterSolver {

    private static final Logger LOG = LoggerFactory.getLogger(MasterSolver.class);
    private static final StopWatch tempWatch = new StopWatch();
    private static final StopWatch tempWatch2 = new StopWatch();

//    private final Object lock;
    // common parameters
    private final Deformation dfm;
    private final Dynamics dyn;
    private final Equation eqn;
    private final Parameters par;

    // master and loner parameters
    private Mesh[] meshes;
    private final State state;
    private final Domain domain;
    private final String simulationPath;
    private final MPIMaster mpi;

    // slave and loner parameters
    private final Mesh mesh;
//    private Solution solution;

    /**
     * Constructor for master and local.
     *
     * @param mpi
     * @param simulationPath
     * @param meshes
     * @param dyn
     * @param eqn
     * @param par
     * @param state
     * @param domain
     */
    public SchwartzImplicitSolver(MPIMaster mpi, String simulationPath, Mesh[] meshes, Dynamics dyn,
            Equation eqn, Parameters par, State state, Domain domain) {
        this.mpi = mpi;
        this.simulationPath = simulationPath;
        this.meshes = meshes;
        this.dyn = dyn;
        this.eqn = eqn;
        this.par = par;
        this.state = state;
        this.domain = domain;
        mesh = meshes[0];
        this.dfm = mesh.getDfm();
    }


    @Override
    public Mesh getMesh() {
        return mesh;
    }

    private String infoToString(int totalSteps, double dt) throws IOException {
        String timeStr = millisecsToTime(state.getOverallExecutionTime());

        return String.format("%d/%d  resid: %.2e,  dt: %.1e,  t: %.2f,  CFL: %1.2f,  CPU: %s",
                state.steps, totalSteps, state.residuum, dt, state.t, state.currentCFL, timeStr);
    }

    private Solution collectSolution(MPIMaster mpi) throws MPIException {
        mpi.sendAll(new MPIMessage(Tag.SOLUTION_REQ));
        Solution[] sols = new Solution[mpi.nSlaves];
        for (int d = 0; d < mpi.nSlaves; ++d) {
            sols[d] = (Solution) mpi.receive(d, Tag.SOLUTION).getData();
        }
        return new Solution(sols, domain);
    }

    private void exchangeData(LiteElement[] liteElems, MPIMaster mpi) throws MPIException {
        // downloading data from the slave nodes into the central structure
        mpi.sendAll(new MPIMessage(Tag.DATA_REQ));
        for (int d = 0; d < domain.nDoms; ++d) {
            int[] mapL2G = domain.getSubdomain(d).mapL2G;
            tempWatch.resume();
            LiteElement[] dataRcv = (LiteElement[]) mpi.receive(d, Tag.DATA_SLAVE_TO_MASTER).getData();
            tempWatch.suspend();
            for (LiteElement dataRcv1 : dataRcv) {
                //liteElems[mapL2G[dataRcv1.index]] = dataRcv1;
                liteElems[mapL2G[dataRcv1.index]] = new LiteElement(dataRcv1.index, dataRcv1.y);
            }
        }

        // uploading data from the central structure onto the slave nodes
        for (int d = 0; d < domain.nDoms; ++d) {
            Subdomain subdom = domain.getSubdomain(d);
            int[] mapG2L = subdom.mapG2L;
            int[] load = subdom.load;
            LiteElement[] dataSend = new LiteElement[load.length];
            for (int i = 0; i < load.length; i++) {
                dataSend[i] = new LiteElement(mapG2L[load[i]], liteElems[load[i]].y);
                //dataSend[i] = liteElems[load[i]];
                //dataSend[i].index = mapG2L[load[i]];
            }
            tempWatch2.resume();
            mpi.send(new MPIMessage(Tag.DATA_MASTER_TO_SLAVE, dataSend), d);
            tempWatch2.suspend();
        }
    }

    @Override
    public Solution solve() throws MPIException, IOException {
		if (par.isTimeStepFixed) {
			throw new IOException("fixed time step (parameter dt) is not supported by the parallel solvers, use parameter CFL");
		}
        int nDoms = domain.nDoms;
        
        StopWatch watch = new StopWatch();
        StopWatch transferWatch = new StopWatch();
        tempWatch.start();
        tempWatch.suspend();
        tempWatch2.start();
        tempWatch2.suspend();
        double dto = -1; // previous time step, <= 0 until the first step is finished
        CFLSetup cflObj = new CFLSetup(par.cfl, par.varyCFL, par.maxCFL);
        int tsr = firstTimeSaveIndex(state.t, par.timeSaveRate); // for time save rate

        try {
            LOG.info("sending initial data");
            for (int d = 0; d < nDoms; ++d) {
                mpi.send(new MPIMessage(Tag.INIT_DATA, meshes[d]), d);
                meshes[d] = null;  // garbage collector should clear the memory
            }
            meshes = null;  // garbage collector should clear the memory
            mpi.waitForAll(Tag.DATA_INITIALIZED);

            // central structure
            LiteElement[] liteElems = new LiteElement[domain.nElems];
            boolean convergesNewton = true;
            boolean convergesSchwarz = true;
            int totalSteps = state.steps + par.steps;

            LOG.info("computation has started...");

            // save zero iteration
            if (par.animation && state.steps == 0) {
                Solution solution = collectSolution(mpi);
                saveAnimationData(solution, state.steps);
            }

            watch.start();
            transferWatch.start();
            transferWatch.suspend();
            outerloop:
            for (++state.steps; state.steps <= totalSteps && state.residuum > par.residuum
                    && state.t < par.tEnd; ++state.steps) {
                if (convergesNewton) {
                    state.currentCFL = cflObj.getCFL(state.currentCFL, state.residuum);
                }
                // zastavovaci podminka
                if (state.currentCFL < (par.cfl / 20)) {
                    LOG.error("algorithm does not converge - aborting computation");
                    break;
                }

                mpi.sendAll(new MPIMessage(Tag.TIME_STEP_REQ, state.currentCFL));
                double dt = Mat.min(mpi.receiveAllDouble(Tag.TIME_STEP));

                // solution monitor
                if (par.solutionMonitorOn) {
                    double[] monitorGlobal = null;
                    mpi.sendAll(new MPIMessage(Tag.GETSOLUTIONMONITOR));
                    for (int d = 0; d < nDoms; ++d) {
                        if (d == 0) {
                            monitorGlobal = (double[]) mpi.receive(d, Tag.LOCALSOLUTIONMONITOR).getData();
                        } else {
                            double[] monitorLocal = (double[]) mpi.receive(d, Tag.LOCALSOLUTIONMONITOR).getData();
                            for (int i = 0; i < monitorGlobal.length; i++) {
                                monitorGlobal[i] += monitorLocal[i];
                            }
                        }
                    }
                    mpi.sendAll(new MPIMessage(Tag.SETSOLUTIONMONITOR, (Object) monitorGlobal));
                    mpi.waitForAll(Tag.SOLUTIONMONITORSET);
                }

                for (int newtonIter = 0; newtonIter < par.newtonIters; newtonIter++) {
                    // mesh deformation
                    if (par.movingMesh) {
                        mpi.sendAll(new MPIMessage(Tag.ALE_CALCULATE_FORCES, dyn.getMeshMove()));
                        ForcesAndDisplacements[] forDis = new ForcesAndDisplacements[nDoms];
                        for (int d = 0; d < nDoms; ++d) {
                            forDis[d] = (ForcesAndDisplacements) mpi.receive(d, Tag.FORCES).getData();
                        }
                        dyn.computeBodyMove(dt, state.t, newtonIter, forDis[0].combine(forDis));
                        mpi.sendAll(new MPIMessage(Tag.ALE_NEW_MESH_POSITION, new ForcesAndDisplacements(dt, dto, dyn.getMeshMove())));
                        mpi.waitForAll(Tag.MESH_POSITION_UPDATED);
                    }

                    mpi.sendAll(new MPIMessage(Tag.ASSEMBLE_AND_SOLVE, new double[]{dt, dto}));
                    convergesNewton = true;
                    for (int d = 0; d < nDoms; ++d) {
                        GmresResult results = (GmresResult) mpi.receive(d, Tag.GMRES_RESULT).getData();
                        if (results.converges == false) {
                            convergesNewton = false;
                        }
                    }

                    transferWatch.resume();
                    exchangeData(liteElems, mpi);
                    mpi.waitForAll(Tag.DATA_UPDATED);
                    transferWatch.suspend();

                    if (convergesNewton) {
                        double schwarzResid = Double.MAX_VALUE;
                        for (int schwarzIter = 1; schwarzIter < par.schwarzIters
                                && schwarzResid > par.schwarzTol; ++schwarzIter) {

                            mpi.sendAll(new MPIMessage(Tag.SOLVE, dt));
                            schwarzResid = 0.0;
                            convergesSchwarz = true;
                            for (int d = 0; d < nDoms; ++d) {
                                GmresResult results = (GmresResult) mpi.receive(d, Tag.GMRES_RESULT).getData();
                                if (results.converges == false) {
                                    convergesSchwarz = false;
                                    break;
                                }
                                schwarzResid += results.schwarzResid;
                            }
                            schwarzResid /= nDoms;

                            transferWatch.resume();
                            exchangeData(liteElems, mpi);
                            mpi.waitForAll(Tag.DATA_UPDATED);
                            transferWatch.suspend();

                            System.out.printf("     %d.   resid: %.2e\n", (schwarzIter + 1), schwarzResid);
                        }
                    }

                    if (!convergesNewton || !convergesSchwarz) {
                        mpi.sendAll(new MPIMessage(Tag.PREVIOUS_TIME_LEVEL));
                        state.currentCFL = cflObj.reduceCFL(state.currentCFL);
                        --state.steps;
                        LOG.warn("GMRES does not converge, CFL reduced to " + state.currentCFL);
                        continue outerloop;
                    }

                    mpi.sendAll(new MPIMessage(Tag.UPDATE_NEWTON));
                    mpi.waitForAll(Tag.NEWTON_UPDATED);
                }

                mpi.sendAll(new MPIMessage(Tag.LIMITER_and_NEXT_TIME_LEVEL));
                double resid = 0.0;
                for (int d = 0; d < nDoms; ++d) {
                    resid += (double) mpi.receive(d, Tag.RESIDUUM).getData();
                }
                state.residuum = resid / nDoms;
                if (state.residuum == 0) {
                    LOG.error(" computation error ");
                    break;
                }

                if (par.movingMesh) {
                    mpi.sendAll(new MPIMessage(Tag.ALE_NEXT_TIME_LEVEL));
                    mpi.waitForAll(Tag.NEW_MESH_POSITION_UPDATED);
                    dyn.nextTimeLevel();
                    dyn.savePositionsAndForces();
                }

                dto = dt;
                state.t += dt;
                state.executionTime = watch.getTime();
                appendResiduum(simulationPath, state.residuum, state.t, state.executionTime);
                state.transferTime = transferWatch.getTime();
                String info = infoToString(totalSteps, dt);
                if ((state.steps % par.saveRate) == 0 || (state.t > tsr*par.timeSaveRate)) {
                    Solution solution = collectSolution(mpi);
                    if (par.animation) {
                        saveAnimationData(solution, state.steps);
                    }
                    saveData(solution);
                    LOG.info(info);
                    tsr = firstTimeSaveIndex(state.t, par.timeSaveRate);
                }
                System.out.printf(info);
                System.out.println();
                if ((state.steps % 50) == 0) {
                    mpi.sendAll(new MPIMessage(Tag.RESET_OUTPUT_WRITER));
                    mpi.reset();
                }
                if (isStopRequested(simulationPath)) {
                	LOG.info("stop requested - finishing computation");
                	++state.steps;
                	break;
                }
            }
            watch.stop();
            state.executionTime = watch.getTime();
            state.transferTime = transferWatch.getTime();
            --state.steps;
            LOG.info("computation has finished in " + millisecsToTime(state.executionTime));
            LOG.info("primani: {}", tempWatch.toString());
            LOG.info("odesilani: {}", tempWatch2.toString());

            LOG.info("collecting data");
            Solution solution = collectSolution(mpi);

            return solution;
        } finally {
            mpi.sendAll(new MPIMessage(Tag.CLOSE));
            mpi.close();
        }
    }

    @Override
    public void testDynamic(double dt, int newtonIter) throws IOException {
        double t = 0;
        for (int step = 0; step <= par.steps; step++) {
            FluidForces[] fluidForces = new FluidForces[dfm.nBodies];
			for (int b = 0; b < dfm.nBodies; b++) {
				fluidForces[b] = new FluidForces(new double[2], new double[1]);
			}
            dyn.computeBodyMove(dt, t, newtonIter, fluidForces);  
            dyn.nextTimeLevel();
            dyn.savePositionsAndForces();
            t += dt;
            System.out.println(step + "-th iteration t = " + t);
        }
    }

    @Override
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
