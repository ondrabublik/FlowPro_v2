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
public class Rigid2DGACRnoref implements Dynamics {

    Equation eqn;

    private int nBodies;
    private Body[] bodies;
    private String simulationPath;
    private double[] xForce;
    private double[] yForce;
    private double[] momentum;
    public double dt;
    public double t;
    public double zLength;
    public double tKick;
    public double tKickForce;
    public String simID;

    // dynamic
    boolean dynamicComputation = false;
    protected double lRef = 1;
    protected double pRef = 1;
    protected double rhoRef = 1;
    protected double vRef = 1;
    protected double tRef = 1;
    protected double FRef = 1;
    protected double MomRef = 1;

    public void init(int nBodies, String simulationPath, String meshPath, Equation eqn) throws IOException {
        this.eqn = eqn;
        this.nBodies = nBodies;
        this.simulationPath = simulationPath;
        ScriptEvaluator jsEval = new ScriptEvaluator();
        bodies = new Body[nBodies];
        for (int i = 0; i < nBodies; i++) {
            bodies[i] = new Body(i, jsEval);
        }

        try {
            double[] refValues = eqn.getReferenceValues();
            lRef = refValues[0];
            pRef = refValues[1];
            rhoRef = refValues[2];
            tRef = refValues[4];
            FRef = pRef * lRef;
            MomRef = pRef * lRef * lRef;
        } catch (Exception e) {
            System.out.println("Cannot assign referential values to body dynamics!");
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
                bodies[i].XCenter = new double[]{rotationCenters[2 * i]/lRef, rotationCenters[2 * i + 1]/lRef};
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

        tKick = -1;
        if (props.containsKey("tKick")) {
            tKick = props.getDouble("tKick");
        }

        tKickForce = -1;
        if (props.containsKey("tKickForce")) {
            tKickForce = props.getDouble("tKickForce");
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

            for (int i = 0; i < nBodies; i++) {
                bodies[i].setBodyParameters(M, B, K);
            }

            dynamicComputation = true;

        } catch (Exception e) {
            System.out.println("Mass, stifness or damping matrix not defined!");
        }

        // load scripts
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

        simID = "";
        if (props.containsKey("simID")) {
            simID = props.getString("simID");
        }
    }

    public void computeBodyMove(double dtIn, double tIn, int innerIter, FluidForces[] fluFor) {
//        this.dt = tRef*dtIn;
//        this.t = tRef*tIn;
//        double[][] translationForce = fluFor.getTranslationForce();
//        this.xForce = Mat.times(translationForce[0],zLength*FRef);
//        this.yForce = Mat.times(translationForce[1],zLength*FRef);
//        double[][] rotationForce = fluFor.getRotationForce();
//        this.momentum = Mat.times(rotationForce[0],zLength*MomRef);
//
//        try {
//            if (dynamicComputation) {
//                for (int i = 0; i < nBodies; i++) {
//                    bodies[i].F = new double[]{xForce[i], yForce[i], momentum[i]};
//                    bodies[i].F = Mat.plusVec(bodies[i].F, bodies[i].timeDependentForces(t));
//                    double[] BU = Mat.times(bodies[i].B, bodies[i].U);
//                    double[] KX = Mat.times(bodies[i].K, bodies[i].X); 
//                    double[] aux = new double[3];
//                    double[] auxForces = new double[3];
//                    for (int j = 0; j < 3; j++) {
//                        aux[j] = -(BU[j] + KX[j]);
//                        auxForces[j] = dt*(bodies[i].F[j] + bodies[i].Fold[j])/2;
//                    }
//                    bodies[i].RHS = Mat.times(bodies[i].iM, aux);
//                    auxForces = Mat.times(bodies[i].iM, auxForces);
//                    // Two-step Adams–Bashforth
//                    for (int j = 0; j < bodies[i].X.length; j++) {
//                        if (bodies[i].freedom[j]) {
//                            bodies[i].Xnew[j] = bodies[i].X[j] + dt * (1.5 * bodies[i].U[j] - 0.5 * bodies[i].Uold[j]);
//                            bodies[i].Unew[j] = bodies[i].U[j] + dt * (1.5 * bodies[i].RHS[j] - 0.5 * bodies[i].RHSold[j]) + auxForces[j];
//                        }
//                    }
//                }
//            }
//
//            // kinematic forced body
//            if (t < tKick) {
//                for (int i = 0; i < nBodies; i++) {
//                    bodies[i].setActualKinematicCoordinates(t);
//                    for (int j = 0; j < bodies[i].X.length; j++) {
//                        if (bodies[i].freedom[j]) {
//                            bodies[i].Unew[j] = (bodies[i].Xnew[j] - bodies[i].X[j])/dt;
//                        }
//                    }
//                }
//            }
//        } catch (Exception e) {
//            System.out.println("formal error in JavaScript script ");
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
            mshMov[k] = new MeshMove(new double[]{bodies[k].Xnew[0]/lRef, bodies[k].Xnew[1]/lRef}, new double[]{bodies[k].Xnew[2]}, null, null);
        }
        return mshMov;
    }

    public double[][] getCenter() {
        double[][] center = new double[2][nBodies];
        for (int i = 0; i < nBodies; i++) {
            center[0][i] = bodies[i].XCenter[0]/lRef;
            center[1][i] = bodies[i].XCenter[1]/lRef;
        }
        return center;
    }

    public void savePositionsAndForces() {
        try (FileWriter fw = new FileWriter(simulationPath + "bodiesDynamic" + simID + ".txt", true);
                BufferedWriter bw = new BufferedWriter(fw);
                PrintWriter out = new PrintWriter(bw)) {
            String line = Double.toString(t);
            for (int i = 0; i < nBodies; i++) {
                line = line + " " + Double.toString(bodies[i].Xnew[0]) + " " + Double.toString(bodies[i].Xnew[1]) + " " + Double.toString(bodies[i].Xnew[2]) + " "
                        + Double.toString(xForce[i]) + " " + Double.toString(yForce[i]) + " " + Double.toString(momentum[i]);
            }
            out.println(line);
        } catch (IOException e) {
            //exception handling left as an exercise for the reader
        }
    }

    public class Body {

        ScriptEvaluator jsEval;
        String xMotion;
        String yMotion;
        String alphaMotion;
        String xTimeForce;
        String yTimeForce;
        String alphaTimeForce;
        boolean[] freedom;

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

        Body(int i, ScriptEvaluator jsEval) {
            X = new double[3];
            U = new double[3];
            XCenter = new double[3];

            Xnew = new double[3];
            Unew = new double[3];
            Uold = new double[3];
            RHS = new double[3];
            RHSold = new double[3];
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
            double[] Ft = new double[3];
            if (t < tKickForce) {
                if (xTimeForce != null) {
                    Ft[0] = jsEval.eval(xTimeForce, t);
                }
                if (yTimeForce != null) {
                    Ft[1] = jsEval.eval(yTimeForce, t);
                }
                if (alphaTimeForce != null) {
                    Ft[2] = jsEval.eval(alphaTimeForce, t);
                }
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
