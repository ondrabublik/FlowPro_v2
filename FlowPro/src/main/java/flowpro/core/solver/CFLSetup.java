package flowpro.core.solver;

import java.io.Serializable;

/**
 * Controls the evolution of the CFL number. One implementation shared by all
 * the solvers and by {@link flowpro.core.State}.
 *
 * @author obublik
 */
public class CFLSetup implements Serializable {

    double maxCFL;
    boolean varyCFL;
    double res = -1;
    double alfa = 0.1;
    int logResOld;

    public CFLSetup(double maxCFL, boolean varyCFL) {
        this.maxCFL = maxCFL;
        this.varyCFL = varyCFL;
    }

    public double getCFL(double actualCFL, double residuum) {
        if (varyCFL) {
            if (res == -1) {
                res = residuum;
                logResOld = 1000;
            } else {
                res = alfa * res + (1 - alfa) * residuum; // low pass filter
            }
            int logRes = (int) Math.log(res);
            if (logRes < logResOld) {
                maxCFL *= 1.3;
            } else {
                maxCFL *= 0.8;
            }
            logResOld = logRes;
            actualCFL += maxCFL / 5;
        } else {
            actualCFL += maxCFL / 20;
        }
        return Math.min(actualCFL, maxCFL);
    }

    public double reduceCFL(double actualCFL) {
        return actualCFL / 1.5;
    }
}
