package flowpro.core.solver;

import java.io.Serializable;

/**
 * Controls the evolution of the CFL number. One implementation shared by all
 * the solvers and by {@link flowpro.core.State}.
 *
 * Fixed CFL: the CFL number is increased linearly to the given value in 20
 * steps. Adaptive CFL: switched evolution relaxation (SER),
 * CFL_n = CFL_{n-1} * res_{n-1} / res_n, the change in one step is limited to
 * the interval [1/MAX_FACTOR, MAX_FACTOR] and the CFL number to [startCFL, maxCFL].
 *
 * @author obublik
 */
public class CFLSetup implements Serializable {

    static final double MAX_FACTOR = 2;

    // fixed CFL: the CFL number, adaptive CFL: the initial CFL number
    double cfl;
    boolean varyCFL;
    // upper limit of the adaptive CFL number
    double maxVaryCFL;
    // residuum of the previous step, negative when unknown
    double resOld = -1;

    public CFLSetup(double cfl, boolean varyCFL, double maxVaryCFL) {
        this.cfl = cfl;
        this.varyCFL = varyCFL;
        this.maxVaryCFL = maxVaryCFL;
    }

    public double getCFL(double actualCFL, double residuum) {
        if (!varyCFL) {
            return Math.min(actualCFL + cfl / 20, cfl);
        }

        // the initial residuum is Double.MAX_VALUE, i.e. not known
        boolean isResiduumKnown = residuum > 0 && residuum < Double.MAX_VALUE;
        if (actualCFL <= 0) {
            resOld = isResiduumKnown ? residuum : -1;
            return cfl;
        }

        double factor = 1;
        if (isResiduumKnown && resOld > 0) {
            factor = Math.max(1 / MAX_FACTOR, Math.min(resOld / residuum, MAX_FACTOR));
        }
        resOld = isResiduumKnown ? residuum : -1;

        // the residuum may grow e.g. in transients, thus the CFL number does not
        // decrease below the initial value (only a failure of the solver does so)
        double lowerLimit = Math.min(actualCFL, cfl);
        return Math.max(lowerLimit, Math.min(actualCFL * factor, maxVaryCFL));
    }

    public double reduceCFL(double actualCFL) {
        // the residuum of the failed step is not comparable, the CFL number is not
        // increased in the next step
        resOld = -1;
        return actualCFL / 1.5;
    }
}
