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
public class Rigid3D implements Dynamics {

    Equation eqn;

    private int nBodies;
    private Body[] bodies;
    private String simulationPath;
    private double[] xForce;
    private double[] yForce;
    private double[] zForce;
    private double[] alphaMomentum;
    private double[] betaMomentum;
    private double[] gammaMomentum;
    public double dt;
    public double t;
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
            for (int i = 0; i < 3 * nBodies; i = i + 3) {
                bodies[i].XCenter = new double[]{rotationCenters[i], rotationCenters[i + 1], rotationCenters[i + 2]};
            }
        } catch (IOException ioe) {
            System.out.println("Body centers not defined!");
        }


        tKick = Double.MAX_VALUE;
        if (props.containsKey("tKick")) {
            tKick = props.getDouble("tKick");
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

            mRef = rhoRef * lRef * lRef;
            kRef = pRef;
            bRef = pRef * tRef;
            IRef = mRef * lRef * lRef;
            torRef = pRef * lRef * lRef;
            torbRef = pRef * lRef * lRef * tRef;
            //!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!! referencni hodnoty deviacnich momentu!!!!!!!!!!!
            double[][] MRef = new double[][]{{1 / mRef, 0, 0}, {0, 1 / mRef, 0}, {0, 0, 1 / IRef}};
            double[][] BRef = new double[][]{{1 / bRef, 0, 0}, {0, 1 / bRef, 0}, {0, 0, 1 / torbRef}};
            double[][] KRef = new double[][]{{1 / kRef, 0, 0}, {0, 1 / kRef, 0}, {0, 0, 1 / torRef}};

            // transfer to unreferential values
            M = Mat.times(M, MRef);
            B = Mat.times(B, BRef);
            K = Mat.times(K, KRef);

            for (int i = 0; i < nBodies; i++) {
                bodies[i].setBodyParameters(M, B, K);
            }

            dynamicComputation = true;

        } catch (Exception e) {
            System.out.println("Mass, stifness and damping matrixes are not defined!");
        }

        boolean[] xFreedom = null;
        boolean[] yFreedom = null;
        boolean[] zFreedom = null;
        boolean[] alphaFreedom = null;
        boolean[] bethaFreedom = null;
        boolean[] gammaFreedom = null;
        try {
            if (props.containsKey("xFreedom")) {
                xFreedom = props.getBooleanArray("xFreedom");
            }
            if (props.containsKey("yFreedom")) {
                yFreedom = props.getBooleanArray("yFreedom");
            }
            if (props.containsKey("zFreedom")) {
                zFreedom = props.getBooleanArray("zFreedom");
            }
            if (props.containsKey("alphaFreedom")) {
                alphaFreedom = props.getBooleanArray("alphaFreedom");
            }
            if (props.containsKey("bethaFreedom")) {
                bethaFreedom = props.getBooleanArray("bethaFreedom");
            }
            if (props.containsKey("gammaFreedom")) {
                gammaFreedom = props.getBooleanArray("gammaFreedom");
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
            if (zFreedom != null) {
                bodies[i].setZFreedom(zFreedom[i]);
            }
            if (alphaFreedom != null) {
                bodies[i].setAlphaFreedom(alphaFreedom[i]);
            }
            if (bethaFreedom != null) {
                bodies[i].setBethaFreedom(bethaFreedom[i]);
            }
            if (gammaFreedom != null) {
                bodies[i].setGammaFreedom(gammaFreedom[i]);
            }
        }

        // load scripts
        String[] xMotion = null;
        String[] yMotion = null;
        String[] zMotion = null;
        String[] alphaMotion = null;
        String[] betaMotion = null;
        String[] gammaMotion = null;
        try {
            if (props.containsKey("xMotion")) {
                xMotion = props.getStringArray("xMotion");
            }
            if (props.containsKey("yMotion")) {
                yMotion = props.getStringArray("yMotion");
            }
            if (props.containsKey("zMotion")) {
                zMotion = props.getStringArray("zMotion");
            }
            if (props.containsKey("alphaMotion")) {
                alphaMotion = props.getStringArray("alphaMotion");
            }
            if (props.containsKey("betaMotion")) {
                betaMotion = props.getStringArray("betaMotion");
            }
            if (props.containsKey("gammaMotion")) {
                gammaMotion = props.getStringArray("gammaMotion");
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
            if (zMotion != null) {
                bodies[i].setZMotion(zMotion[i]);
            }
            if (alphaMotion != null) {
                bodies[i].setAlphaMotion(alphaMotion[i]);
            }
            if (betaMotion != null) {
                bodies[i].setBetaMotion(betaMotion[i]);
            }
            if (gammaMotion != null) {
                bodies[i].setGammaMotion(gammaMotion[i]);
            }
        }
    }

    public void computeBodyMove(double dt, double t, int s, FluidForces[] fluFor) {
//        this.dt = dt;
//        this.t = t;
//        double[][] translationForce = fluFor.getTranslationForce();
//        this.xForce = translationForce[0];
//        this.yForce = translationForce[1];
//        this.zForce = translationForce[2];
//        double[][] rotationForce = fluFor.getRotationForce();
//        this.alphaMomentum = rotationForce[0];
//        this.betaMomentum = rotationForce[1];
//        this.gammaMomentum = rotationForce[2];
//
//        if (dynamicComputation) {
//            for (int i = 0; i < nBodies; i++) {
//                bodies[i].F = new double[]{zLength * xForce[i], zLength * yForce[i], zLength * zForce[i],
//                    zLength * alphaMomentum[i], zLength * betaMomentum[i], zLength * gammaMomentum[i]};
//                bodies[i].F = Mat.plusVec(bodies[i].F, bodies[i].timeDependentForces(i, t));
//                double[] BU = Mat.times(bodies[i].B, bodies[i].U);
//                double[] KX = Mat.times(bodies[i].K, bodies[i].X);
//                bodies[i].RHS = Mat.times(bodies[i].iM, Mat.plusMinMinVec(bodies[i].F, BU, KX));
//                // Two-step Adams–Bashforth
//                for (int j = 0; j < bodies[i].X.length; j++) {
//                    bodies[i].Xnew[j] = bodies[i].X[j] + dt * (1.5 * bodies[i].U[j] - 0.5 * bodies[i].Uold[j]);
//                    bodies[i].Unew[j] = bodies[i].U[j] + dt * (1.5 * bodies[i].RHS[j] - 0.5 * bodies[i].RHSold[j]) + dt * (bodies[i].F[j] + bodies[i].Fold[j]) / 2;
//                }
//            }
//        }
//
//        // kinematic forced body
//        if (t < tKick) {
//            try {
//                for (int i = 0; i < nBodies; i++) {
//                    bodies[i].setActualKinematicCoordinates(i, t);
//                }
//            } catch (Exception e) {
//                System.out.println("error in script for kinematic movement ");
//            }
//        }
    }

    public void nextTimeLevel() {
        if (dynamicComputation) {
            for (int i = 0; i < nBodies; i++) {
                for (int j = 0; j < bodies[i].X.length; j++) {
                    bodies[i].Uold[j] = bodies[i].U[j];
                    bodies[i].RHSold[j] = bodies[i].RHS[j];
                    bodies[i].Fold[j] = bodies[i].F[j];
                    bodies[i].X[j] = bodies[i].Xnew[j];
                    bodies[i].U[j] = bodies[i].Unew[j];
                }
            }
        }
    }

    public MeshMove[] getMeshMove() {
        MeshMove[] mshMov = new MeshMove[nBodies];
        for (int k = 0; k < nBodies; k++) {
            mshMov[k] = new MeshMove(new double[]{bodies[k].Xnew[0], bodies[k].Xnew[1], bodies[k].Xnew[2]}, new double[]{bodies[k].Xnew[3], bodies[k].Xnew[4], bodies[k].Xnew[5]}, null, null);
        }
        return mshMov;
    }

    public double[][] getCenter() {
        double[][] center = new double[3][nBodies];
        for (int i = 0; i < nBodies; i++) {
            center[0][i] = bodies[i].XCenter[0];
            center[1][i] = bodies[i].XCenter[1];
            center[2][i] = bodies[i].XCenter[2];
        }
        return center;
    }

    public void savePositionsAndForces() {
        try (FileWriter fw = new FileWriter(simulationPath + "bodiesDynamic.txt", true);
                BufferedWriter bw = new BufferedWriter(fw);
                PrintWriter out = new PrintWriter(bw)) {
            String line = Double.toString(t);
            for (int i = 0; i < nBodies; i++) {
                line = line + " " + Double.toString(bodies[i].Xnew[0]) + " " + Double.toString(bodies[i].Xnew[1]) + " " + Double.toString(bodies[i].Xnew[2]) + " "
                        + Double.toString(xForce[i]) + " " + Double.toString(yForce[i]) + " " + Double.toString(zForce[i]);
            }
            out.println(line);
        } catch (IOException e) {
            //exception handling left as an exercise for the reader
        }
    }

    public class Body {

        ScriptEvaluator jsEval;
        String xMotion, yMotion, zMotion;
        String alphaMotion, betaMotion, gammaMotion;

        public double[] X;
        public double[] U;

        public double[][] M;
        public double[][] iM;
        public double[][] B;
        public double[][] K;

        public double[] XCenter;

        // old values
        public double[] Xnew;
        public double[] Unew;
        public double[] Uold;
        public double[] RHS;
        public double[] RHSold;
        public double[] F;
        public double[] Fold;
        boolean[] freedom;

        Body(int i, ScriptEvaluator jsEval) {
            X = new double[6];
            U = new double[6];
            XCenter = new double[3];

            Xnew = new double[6];
            Unew = new double[6];
            Uold = new double[6];
            RHS = new double[6];
            RHSold = new double[6];
            Fold = new double[6];
            freedom = new boolean[6];

            this.jsEval = jsEval;
        }

        void setBodyParameters(double[][] M, double[][] B, double[][] K) {
            this.M = M;
            this.B = B;
            this.K = K;
            iM = Mat.invert(M);
        }

        void setActualKinematicCoordinates(double t) throws ScriptException {
            t *= tRef;
            if (xMotion != null) {
                Xnew[0] = jsEval.eval(xMotion, t) / lRef;
            }
            if (yMotion != null) {
                Xnew[1] = jsEval.eval(yMotion, t) / lRef;
            }
            if (zMotion != null) {
                Xnew[2] = jsEval.eval(zMotion, t) / lRef;
            }
            if (alphaMotion != null) {
                Xnew[3] = jsEval.eval(alphaMotion, t);
            }
            if (betaMotion != null) {
                Xnew[4] = jsEval.eval(betaMotion, t);
            }
            if (gammaMotion != null) {
                Xnew[5] = jsEval.eval(gammaMotion, t);
            }
        }

        public double[] timeDependentForces(int i, double t) { // time dependent external forces
            return new double[6];
        }

        public void setXFreedom(boolean free) {
            freedom[0] = free;
        }

        public void setYFreedom(boolean free) {
            freedom[1] = free;
        }

        public void setZFreedom(boolean free) {
            freedom[2] = free;
        }

        public void setAlphaFreedom(boolean free) {
            freedom[3] = free;
        }

        public void setBethaFreedom(boolean free) {
            freedom[4] = free;
        }

        public void setGammaFreedom(boolean free) {
            freedom[5] = free;
        }

        public void setXMotion(String motion) {
            this.xMotion = motion;
        }

        public void setYMotion(String motion) {
            this.yMotion = motion;
        }

        public void setZMotion(String motion) {
            this.zMotion = motion;
        }

        public void setAlphaMotion(String alphaMotion) {
            this.alphaMotion = alphaMotion;
        }

        public void setBetaMotion(String motion) {
            this.betaMotion = motion;
        }

        public void setGammaMotion(String motion) {
            this.gammaMotion = motion;
        }
    }
}
