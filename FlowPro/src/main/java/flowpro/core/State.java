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
    public double residuum;
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
        cflSetup = new CFLSetup(par.cfl, par.varyCFL);
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

        output.setProperty("order", Integer.toString(order));

        try (OutputStream out = new FileOutputStream(stateFilePath)) {
            output.store(out, null);
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
