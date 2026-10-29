package flowpro.core;

import flowpro.api.FlowProProperties;
import flowpro.core.element.Element;
import flowpro.core.solver.CFLSetup;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;

/**
 *
 * @author obublik
 */
public class State implements Serializable {

    /**
     * when the time step is fixed and the linear solver repeatedly fails, the
     * time step is halved; the computation is aborted when it drops below
     * dt0 / MAX_DT_REDUCTION
     */
    private static final double MAX_DT_REDUCTION = 1024;

    /**
     * the residuum of each equation is related to its maximum over this number
     * of the first time steps (the residuum of the very first step is often
     * not representative, e.g. a uniform initial condition)
     */
    private static final int N_NORMALISATION_STEPS = 5;

    private boolean hasOrderChanged;

    public final String stateFilePath;
    public final int order; // order of accuracy in space

    public final boolean isTimeStepFixed;
    public final double dt0;
    private final double maxCFL;
    private final CFLSetup cflSetup;
    private double dt;

    public int steps; // number of time steps that has already been taken
    public double t;  // physical time
    public double currentCFL;
    // relative residuum, the maximum of equationResiduum
    public double residuum;
    // L2 norms of the spatial residuum of the individual equations related to
    // the norms in the first steps (residuumNormalisation)
    public double[] equationResiduum;
    private double[] residuumNormalisation;
    private int nNormalisationSteps;
    public long executionTime;  // current execution time
    public long transferTime;   // current transfer time

    public long initExecutionTime; // initial execution time
    public long initTransferTime;  // initial transfer time

    public State(String stateFilePath, Parameters par) throws IOException {
        this.stateFilePath = stateFilePath;
        this.order = par.order;
        hasOrderChanged = false;

        steps = 0;
        t = 0.0;
        currentCFL = 0.0;
        residuum = Double.MAX_VALUE;
        initExecutionTime = 0;
        executionTime = 0;
        transferTime = 0;

        maxCFL = par.cfl;
        cflSetup = new CFLSetup(par.cfl, par.varyCFL, par.maxCFL);
        isTimeStepFixed = par.isTimeStepFixed;
        dt0 = par.dt;

        if (isTimeStepFixed) {
            dt = dt0;
        }
    }

    public void load() throws IOException {
        try {
            FlowProProperties stateProperties = new FlowProProperties();
            stateProperties.load(stateFilePath);

            t = stateProperties.getDouble("t");
            steps = stateProperties.getInt("steps");
            initExecutionTime = stateProperties.getLong("CPU");
            initTransferTime = stateProperties.getLong("transfer");

            currentCFL = stateProperties.getDouble("CFL");

            // the normalisation of the residuum is kept when the computation continues
            if (stateProperties.containsKey("residuumNormalisation")) {
                String[] values = stateProperties.getString("residuumNormalisation").trim().split("[,\\s]+");
                residuumNormalisation = new double[values.length];
                for (int m = 0; m < values.length; m++) {
                    residuumNormalisation[m] = Double.parseDouble(values[m]);
                }
                nNormalisationSteps = N_NORMALISATION_STEPS;
            }

            int oldOrder = stateProperties.getInt("order");
            if (oldOrder != order) {
                hasOrderChanged = true;
            }
        } catch (IOException ex) {
            throw new IOException("file " + stateFilePath + " has a wrong format: "
                    + ex.getMessage(), ex);
        }
    }

    public void save() throws IOException {
        FlowProProperties output = new FlowProProperties();

        output.setProperty("t", Double.toString(t));
        output.setProperty("steps", Integer.toString(steps));
        output.setProperty("CPU", Long.toString(getOverallExecutionTime()));
        output.setProperty("transfer", Long.toString(getOverallTransferTime()));
        output.setProperty("residuum", Double.toString(residuum));
        output.setProperty("CFL", Double.toString(currentCFL));
        output.setProperty("dt", Double.toString(dt));
        if (residuumNormalisation != null && nNormalisationSteps >= N_NORMALISATION_STEPS) {
            StringBuilder sb = new StringBuilder();
            for (double r : residuumNormalisation) {
                sb.append(sb.length() > 0 ? "," : "").append(r);
            }
            output.setProperty("residuumNormalisation", sb.toString());
        }

        output.setProperty("order", Integer.toString(order));

        try (OutputStream out = new FileOutputStream(stateFilePath)) {
            output.store(out, null);
        }
    }

    /**
     * Sets the residuum from the L2 norms of the spatial residuum of the
     * individual equations. Each norm is related to its maximum over the first
     * N_NORMALISATION_STEPS steps, residuum is the maximum over the equations.
     *
     * @param norms L2 norms of the residuum of the equations
     */
    public void setResiduum(double[] norms) {
        int nEqs = norms.length;
        if (residuumNormalisation == null || residuumNormalisation.length != nEqs) {
            residuumNormalisation = new double[nEqs];
            nNormalisationSteps = 0;
        }
        if (nNormalisationSteps < N_NORMALISATION_STEPS) {
            for (int m = 0; m < nEqs; m++) {
                residuumNormalisation[m] = Math.max(residuumNormalisation[m], norms[m]);
            }
            nNormalisationSteps++;
        }

        // an equation with zero residuum in the first steps (e.g. a quantity which
        // is identically zero) is related to the largest normalisation
        double maxNormalisation = 0;
        for (double r : residuumNormalisation) {
            maxNormalisation = Math.max(maxNormalisation, r);
        }
        equationResiduum = new double[nEqs];
        residuum = 0;
        for (int m = 0; m < nEqs; m++) {
            double normalisation = residuumNormalisation[m] > 0 ? residuumNormalisation[m] : maxNormalisation;
            equationResiduum[m] = normalisation > 0 ? norms[m] / normalisation : norms[m];
            if (Double.isNaN(equationResiduum[m])) {
                residuum = Double.NaN;
            } else if (!Double.isNaN(residuum)) {
                residuum = Math.max(residuum, equationResiduum[m]);
            }
        }
    }

    public double getTimeStep(Element[] elems) {
        double temp = Double.MAX_VALUE;
        for (Element elem : elems) {
            double localEigenvalue = elem.calculateMaxEigenvalue();
            if (localEigenvalue > 0) {
                temp = Double.min(temp, elem.elemSize / localEigenvalue);
            }
        }

        if (isTimeStepFixed) {
            currentCFL = dt / temp;
        } else {
            dt = currentCFL * temp;
        }

        return dt;
    }

    public boolean hasOrderChanged() {
        return hasOrderChanged;
    }

    public long getOverallExecutionTime() {
        return initExecutionTime + executionTime;
    }

    public long getOverallTransferTime() {
        return initTransferTime + transferTime;
    }

    /**
     * Called after a successfully finished time step.
     */
    public void updateCFL() {
        if (isTimeStepFixed) {
            // recover the time step after previous reductions
            dt = Double.min(2 * dt, dt0);
        } else {
            currentCFL = cflSetup.getCFL(currentCFL, residuum);
        }
    }

    /**
     * Called when the linear (or nonlinear) solver fails.
     */
    public void reduceCFL() {
        if (isTimeStepFixed) {
            dt /= 2;
        } else {
            currentCFL = cflSetup.reduceCFL(currentCFL);
        }
    }

    /**
     * @return true if the time step (or CFL) has been reduced so much that
     * there is no point in continuing the computation
     */
    public boolean isConvergenceHopeless() {
        if (isTimeStepFixed) {
            return dt < dt0 / MAX_DT_REDUCTION;
        } else {
            return currentCFL > 0 && currentCFL < maxCFL / 20;
        }
    }
}
