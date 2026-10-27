package flowpro.user.dynamics;

import flowpro.user.auxiliary.ScriptEvaluator;
import flowpro.api.*;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import javax.script.ScriptException;

/**
 *
 * @author obublik
 */
public class Rigid2D implements Dynamics {

    Equation eqn;

    private int nBodies;
    private Body[] bodies;
    private String simulationPath;
    private FluidForces[] fluFor;
    public double dtOld;
    public double dt;
    public double t;
    public double zLength;
    public double tKick;

    // dynamic
    boolean dynamicComputation = false;
    protected double lRef = 1;
    protected double pRef = 1;
    protected double rhoRef = 1;
    protected double vRef = 1;
    protected double tRef = 1;
    protected double mRef;
    protected double IRef;
    protected double kRef;
    protected double torRef;
    protected double bRef;
    protected double torbRef;
    
    // metoda integrace
    protected int method;

    public void init(int nBodies, String simulationPath, String meshPath, Equation eqn) throws IOException {
        this.eqn = eqn;
        this.nBodies = nBodies;
        this.simulationPath = simulationPath;
        ScriptEvaluator jsEval = new ScriptEvaluator();
        bodies = new Body[nBodies];
        for (int i = 0; i < nBodies; i++) {
            bodies[i] = new Body(i, jsEval);
        }

        // clear file for results
        FlowProProperties props = new FlowProProperties();
        props.load(new FileInputStream(simulationPath + "parameters.txt"));
        if (props.containsKey("continueComputation")) {
            try {
                PrintWriter writer = new PrintWriter(simulationPath + "bodiesDynamic.txt");
                writer.print("");
                writer.close();
            } catch (IOException ioe) {
                System.out.println("Error while creating a new empty file :" + ioe);
            }
            try {
                PrintWriter writer = new PrintWriter(simulationPath + "bodiesUserDef.txt");
                writer.print("");
                writer.close();
            } catch (IOException ioe) {
                System.out.println("Error while creating a new empty file :" + ioe);
            }
        }

        // try to read body centers
        try {
            double[] rotationCenters = props.getDoubleArray("rotationCenters");
            for (int i = 0; i < nBodies; i++) {
                bodies[i].XCenter = new double[]{rotationCenters[2 * i], rotationCenters[2 * i + 1]}; //???? chyba
            }
        } catch (IOException ioe) {
            System.out.println("Body centers not defined!");
        }

        zLength = 1;
        if (props.containsKey("zLength")) {
            zLength = props.getDouble("zLength");
        } else {
            System.out.println("Length of 2D bodies in z coordinate is set to " + zLength);
        }

        tKick = Double.MAX_VALUE;
        if (props.containsKey("tKick")) {
            tKick = props.getDouble("tKick");
        }

        method = 1;
        if (props.containsKey("method")) {
            method = props.getInt("method");
        }
        
        try {
            double[] refValues = eqn.getReferenceValues();
            lRef = refValues[0];
            pRef = refValues[1];
            rhoRef = refValues[2];
            tRef = refValues[4];
        } catch (Exception e) {
            System.out.println("Cannot assign referential values to body dynamics!");
        }

        try {
            double[] m = props.getDoubleArray("M");
            double[] b = props.getDoubleArray("B");
            double[] k = props.getDoubleArray("K");
            int n = (int) Math.sqrt(m.length);
            double[][] M = new double[n][n];
            double[][] B = new double[n][n];
            double[][] K = new double[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    M[i][j] = m[n * i + j];
                    B[i][j] = b[n * i + j];
                    K[i][j] = k[n * i + j];
                }
            }

            double[][] iFRef = new double[][]{{1 / (pRef * lRef), 0, 0}, {0, 1 / (pRef * lRef), 0}, {0, 0, 1 / (pRef * lRef * lRef)}};
            double[][] LRef = new double[][]{{lRef, 0, 0}, {0, lRef, 0}, {0, 0, lRef * lRef}};
            // transfer to unreferential values
            M = Mat.times(Mat.times(Mat.times(iFRef, M), LRef), 1 / (tRef * tRef));
            B = Mat.times(Mat.times(Mat.times(iFRef, B), LRef), 1 / tRef);
            K = Mat.times(Mat.times(iFRef, K), LRef);

            for (int i = 0; i < nBodies; i++) {
                bodies[i].setBodyParameters(M, B, K);
            }

            dynamicComputation = true;

        } catch (Exception e) {
            System.out.println("WARNING! Mass, stifness or damping matrix not defined!");
        }

        // load scripts
        boolean[] xFreedom = null;
        boolean[] yFreedom = null;
        boolean[] alphaFreedom = null;
        try {
            if (props.containsKey("xFreedom")) {
                xFreedom = props.getBooleanArray("xFreedom");
            }
            if (props.containsKey("yFreedom")) {
                yFreedom = props.getBooleanArray("yFreedom");
            }
            if (props.containsKey("alphaFreedom")) {
                alphaFreedom = props.getBooleanArray("alphaFreedom");
            }
        } catch (Exception e) {
            System.out.println("Error in degrees of freedom " + e);
        }
        for (int i = 0; i < nBodies; i++) {
            if (xFreedom != null) {
                bodies[i].setXFreedom(xFreedom[i]);
            }
            if (yFreedom != null) {
                bodies[i].setYFreedom(yFreedom[i]);
            }
            if (alphaFreedom != null) {
                bodies[i].setAlphaFreedom(alphaFreedom[i]);
            }
        }

        String[] xMotion = null;
        String[] yMotion = null;
        String[] alphaMotion = null;
        try {
            if (props.containsKey("xMotion")) {
                xMotion = props.getStringArray("xMotion");
            }
            if (props.containsKey("yMotion")) {
                yMotion = props.getStringArray("yMotion");
            }
            if (props.containsKey("alphaMotion")) {
                alphaMotion = props.getStringArray("alphaMotion");
            }
        } catch (Exception e) {
            System.out.println("Error in kinematic scripts " + e);
        }
        for (int i = 0; i < nBodies; i++) {
            if (xMotion != null) {
                bodies[i].setXMotion(xMotion[i]);
            }
            if (yMotion != null) {
                bodies[i].setYMotion(yMotion[i]);
            }
            if (alphaMotion != null) {
                bodies[i].setAlphaMotion(alphaMotion[i]);
            }
        }

        String[] xTimeForce = null;
        String[] yTimeForce = null;
        String[] alphaTimeForce = null;
        try {
            if (props.containsKey("xExternForceInTime")) {
                xTimeForce = props.getStringArray("xExternForceInTime");
            }
            if (props.containsKey("yExternForceInTime")) {
                yTimeForce = props.getStringArray("yExternForceInTime");
            }
            if (props.containsKey("alphaExternForceInTime")) {
                alphaTimeForce = props.getStringArray("alphaExternForceInTime");
            }
        } catch (Exception e) {
            System.out.println("Error in kinematic scripts " + e);
        }
        for (int i = 0; i < nBodies; i++) {
            if (xTimeForce != null) {
                bodies[i].setXTimeForce(xTimeForce[i]);
            }
            if (yTimeForce != null) {
                bodies[i].setYTimeForce(yTimeForce[i]);
            }
            if (alphaTimeForce != null) {
                bodies[i].setAlphaTimeForce(alphaTimeForce[i]);
            }
        }
    }

    @Override
    public void computeBodyMove(double dt, double t, int innerIter, FluidForces[] fluFor) {
        this.fluFor = fluFor;
        this.t = t;
        this.dt = dt;
        if (t < 1e-12) {
            this.dtOld = dt;
        }
        double a1 = 1 + dt / dtOld / 2;
        double a2 = -dt / dtOld / 2;

        try {
            if (dynamicComputation) {
                for (int i = 0; i < nBodies; i++) {
                    double[] F = new double[]{zLength * fluFor[i].force[0],
                        zLength * fluFor[i].force[1], zLength * fluFor[i].force[0]};

                    if (innerIter == 0) {
                        System.arraycopy(F, 0, bodies[i].Fn, 0, bodies[i].Fnew.length);
                        bodies[i].Fn = Mat.plusVec(bodies[i].Fn, bodies[i].timeDependentForces(t));
                        for (int j = 0; j < bodies[i].Fn.length; j++) {
                            bodies[i].Fnew[j] = (1 + dt / dtOld) * bodies[i].Fn[j] - dt / dtOld * bodies[i].Fold[j];
                        }
                    } else {
                        System.arraycopy(F, 0, bodies[i].Fnew, 0, bodies[i].Fnew.length);
                        bodies[i].Fnew = Mat.plusVec(bodies[i].Fnew, bodies[i].timeDependentForces(t + dt));
                    }

                    int n = bodies[i].M.length;
                    double[][] A;
                    double[][] B;
                    double[] b;
                    double[] ux_old;
                    double[] ux_old2;
                    double[][] C;
                    double[] RHS;
                    double[] ux;
                    switch (method) {
                        case 1: // Two-step Adams–Bashforth
                            double[] BU = Mat.times(bodies[i].B, bodies[i].U);
                            double[] KX = Mat.times(bodies[i].K, bodies[i].X);

                            // hruza !!!!!!!!
                            double[] aux = new double[3];
                            double[] aux2 = new double[3];
                            for (int j = 0; j < 3; j++) {
                                aux[j] = -(BU[j] + KX[j]);
                                aux2[j] = dt * (bodies[i].Fnew[j] + bodies[i].Fn[j]) / 2;
                            }
                            bodies[i].RHS = Mat.times(bodies[i].iM, aux);
                            aux2 = Mat.times(bodies[i].iM, aux2);

                            // Two-step Adams–Bashforth
                            for (int j = 0; j < bodies[i].X.length; j++) {
                                if (bodies[i].freedom[j]) {
                                    bodies[i].Xnew[j] = bodies[i].X[j] + dt * (a1 * bodies[i].U[j] + a2 * bodies[i].Uold[j]);
                                    bodies[i].Unew[j] = bodies[i].U[j] + dt * (a1 * bodies[i].RHS[j] + a2 * bodies[i].RHSold[j])
                                            + aux2[j];
                                }
                            }
                            break;
                        case 2: //BDF 1
                            n = bodies[i].M.length;
                            A = new double[2 * n][2 * n];
                            B = new double[2 * n][2 * n];
                            b = new double[2 * n];
                            ux_old = new double[2 * n];
                            for (int j = 0; j < n; j++) {
                                for (int k = 0; k < n; k++) {
                                    A[j][k] = bodies[i].M[j][k] / dt;
                                    B[j][k] = bodies[i].B[j][k];
                                    B[j][k + n] = bodies[i].K[j][k];
                                }
                                ux_old[j] = bodies[i].U[j];
                                ux_old[j + n] = bodies[i].X[j];
                                b[j] = (bodies[i].Fnew[j] + bodies[i].Fn[j]) / 2;
                                A[j + n][j + n] = 1 / dt;
                                B[j + n][j] = -1;
                            }
                            C = Mat.plus(A, B);
                            RHS = Mat.plusVec(b, Mat.times(A, ux_old));
                            ux = Mat.lsolve(C, RHS, 2 * n);
                            for (int j = 0; j < bodies[i].X.length; j++) {
                                if (bodies[i].freedom[j]) {
                                    bodies[i].Unew[j] = ux[j];
                                    bodies[i].Xnew[j] = ux[j + n];
                                }
                            }
                            break;
                            
                        case 3: //BDF 2
                            n = bodies[i].M.length;
                            A = new double[2 * n][2 * n];
                            B = new double[2 * n][2 * n];
                            b = new double[2 * n];
                            ux_old = new double[2 * n];
                            ux_old2 = new double[2 * n];
                            for (int j = 0; j < n; j++) {
                                for (int k = 0; k < n; k++) {
                                    A[j][k] = bodies[i].M[j][k]/dt;
                                    B[j][k] = bodies[i].B[j][k];
                                    B[j][k + n] = bodies[i].K[j][k];
                                }
                                ux_old[j] = bodies[i].U[j];
                                ux_old[j + n] = bodies[i].X[j];
                                ux_old2[j] = bodies[i].Uold[j];
                                ux_old2[j + n] = bodies[i].Xold[j];
                                b[j] = (bodies[i].Fnew[j] + bodies[i].Fn[j]);
                                A[j + n][j + n] = 1/dt;
                                B[j + n][j] = -1;
                            }
                            C = Mat.plus(Mat.times(A,3), Mat.times(B,2));
                            RHS = Mat.plusVec(b, Mat.times(A, Mat.minusVec(Mat.times(ux_old,4), ux_old2)));
                            ux = Mat.lsolve(C, RHS, 2 * n);
                            for (int j = 0; j < bodies[i].X.length; j++) {
                                if (bodies[i].freedom[j]) {
                                    bodies[i].Unew[j] = ux[j];
                                    bodies[i].Xnew[j] = ux[j + n];
                                }
                            }
                            break;
                    }
                }
            }

            // kinematic forced body
            if (t * tRef < tKick) {
//				double b1 = (2 * dt + dtOld) / (dt * (dt + dtOld));  // 3/(2*dt); 
//				double b2 = -(dt + dtOld) / (dt * dtOld);  // -2/dt;
//				double b3 = dt / (dtOld * (dt + dtOld));  // 1/(2*dt);

                for (int i = 0; i < nBodies; i++) {
                    System.arraycopy(bodies[i].Xnew, 0, bodies[i].X, 0, 3);

                    bodies[i].setActualKinematicCoordinates(t + dt);

                    for (int j = 0; j < bodies[i].X.length; j++) {
                        if (bodies[i].freedom[j]) {
                            /* Rychlost telesa pri nakopnuti se pocitaji jen s prvnim radem. Rychlost se pouzije jen
							   v prvni casovem kroku po zapnuti interakce, tak to moc nevadi. */
                            bodies[i].Unew[j] = (bodies[i].Xnew[j] - bodies[i].X[j]) / dt;
                        }
                    }
                }
            }
        } catch (ScriptException ex) {
            ex.printStackTrace();
//            System.out.println("formal error in JavaScript script ");
        }
    }

    public void nextTimeLevel() {
        if (dynamicComputation) {
            dtOld = dt;
            for (int i = 0; i < nBodies; i++) {
                for (int j = 0; j < bodies[i].X.length; j++) {
                    bodies[i].Uold[j] = bodies[i].U[j];
                    bodies[i].Xold[j] = bodies[i].X[j];
                    bodies[i].RHSold[j] = bodies[i].RHS[j];
                    bodies[i].Fold[j] = bodies[i].Fn[j];
                    bodies[i].X[j] = bodies[i].Xnew[j];
                    bodies[i].U[j] = bodies[i].Unew[j];
                }
            }
        }
    }

    public MeshMove[] getMeshMove() {
        MeshMove[] mshMov = new MeshMove[nBodies];
        for (int k = 0; k < nBodies; k++) {
            mshMov[k] = new MeshMove(new double[]{bodies[k].Xnew[0], bodies[k].Xnew[1]}, new double[]{bodies[k].Xnew[2]}, null, null);
        }
        return mshMov;
    }

    public double[][] getCenter() { // XCenter neni updatovan ?????????
        double[][] center = new double[2][nBodies];
        for (int i = 0; i < nBodies; i++) {
            center[0][i] = bodies[i].XCenter[0];
            center[1][i] = bodies[i].XCenter[1];
        }
        return center;
    }

    @Override
    public void savePositionsAndForces() {
        try (FileWriter fw = new FileWriter(simulationPath + "bodiesDynamic.txt", true);
                BufferedWriter bw = new BufferedWriter(fw);
                PrintWriter out = new PrintWriter(bw)) {
            String line = Double.toString(t) + " ";
            for (int i = 0; i < nBodies; i++) {
                line += Double.toString(bodies[i].Xnew[0]) + " "
                        + Double.toString(bodies[i].Xnew[1]) + " "
                        + Double.toString(bodies[i].Xnew[2]) + " "
                        + Double.toString(zLength * fluFor[i].force[0]) + " "
                        + Double.toString(zLength * fluFor[i].force[1]) + " "
                        + Double.toString(zLength * fluFor[i].torque[0]) + " ";
            }
            out.println(line);
        } catch (IOException e) {
            System.out.println("Error while writing into bodiesDynamic.txt file.");
            //exception handling left as an exercise for the reader

        }
    }

    public class Body {

        ScriptEvaluator jsEval;
        boolean[] freedom;
        String xMotion;
        String yMotion;
        String alphaMotion;
        String xTimeForce;
        String yTimeForce;
        String alphaTimeForce;

        public double[] X;
        public double[] U;

        public double[][] M;
        public double[][] iM;
        public double[][] B;
        public double[][] K;

        public double[] XCenter;

        // old values
        public double[] Xnew;
        public double[] Xold;
        public double[] Unew;
        public double[] Uold;
        public double[] RHS;
        public double[] RHSold;
        public double[] Fnew;
        public double[] Fn;
        public double[] Fold;

        Body(int i, ScriptEvaluator jsEval) {
            X = new double[3];
            U = new double[3];
            XCenter = new double[2];

            Xnew = new double[3];
            Xold = new double[3];
            Unew = new double[3];
            Uold = new double[3];
            RHS = new double[3];
            RHSold = new double[3];
            Fnew = new double[3];
            Fn = new double[3];
            Fold = new double[3];
            freedom = new boolean[3];

            this.jsEval = jsEval;
        }

        void setBodyParameters(double[][] M, double[][] B, double[][] K) {
            this.M = M;
            this.B = B;
            this.K = K;
            iM = Mat.invert(M);
        }

        void setActualKinematicCoordinates(double t) throws ScriptException {
            t = t * tRef;
            if (xMotion != null) {
                Xnew[0] = jsEval.eval(xMotion, t);
            }
            if (yMotion != null) {
                Xnew[1] = jsEval.eval(yMotion, t);
            }
            if (alphaMotion != null) {
                Xnew[2] = jsEval.eval(alphaMotion, t);
            }
        }

        public double[] timeDependentForces(double t) throws ScriptException { // time dependent external forces
            t = t * tRef;
            double[] Ft = new double[3];
            if (xTimeForce != null) {
                Ft[0] = jsEval.eval(xTimeForce, t);
            }
            if (yTimeForce != null) {
                Ft[1] = jsEval.eval(yTimeForce, t);
            }
            if (alphaTimeForce != null) {
                Ft[2] = jsEval.eval(alphaTimeForce, t);
            }
            return Ft;
        }

        public void setXFreedom(boolean xFreedom) {
            freedom[0] = xFreedom;
        }

        public void setYFreedom(boolean yFreedom) {
            freedom[1] = yFreedom;
        }

        public void setAlphaFreedom(boolean alphaFreedom) {
            freedom[2] = alphaFreedom;
        }

        public void setXMotion(String xMotion) {
            this.xMotion = xMotion;
        }

        public void setYMotion(String yMotion) {
            this.yMotion = yMotion;
        }

        public void setAlphaMotion(String alphaMotion) {
            this.alphaMotion = alphaMotion;
        }

        public void setXTimeForce(String xTimeForce) {
            this.xTimeForce = xTimeForce;
        }

        public void setYTimeForce(String yTimeForce) {
            this.yTimeForce = yTimeForce;
        }

        public void setAlphaTimeForce(String alphaTimeForce) {
            this.alphaTimeForce = alphaTimeForce;
        }
    }
}
