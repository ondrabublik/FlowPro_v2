package flowpro.user.dynamics;

import flowpro.api.Dynamics;
import flowpro.api.Mat;
import flowpro.api.FlowProProperties;
import flowpro.api.Equation;
import flowpro.api.FluidForces;
import flowpro.api.MeshMove;
import flowpro.user.auxiliary.ScriptEvaluator;
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
public class Elastic2DBeam implements Dynamics {

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

        // read boundary points
        try {
            double[][] PXY = Mat.loadDoubleMatrix(meshPath + "vertices.txt"); // mesh vertices coordinates
            int[][] boundaryALE = Mat.loadIntMatrix(meshPath + "boundaryTypeALE.txt");
            for (int k = 0; k < nBodies; k++) {
                int[] aux = new int[PXY.length];
                for (int i = 0; i < boundaryALE.length; i++) {
                    if (boundaryALE[i][0] == k + 2) {
                        aux[boundaryALE[i][1]] = 1;
                        aux[boundaryALE[i][2]] = 1;
                    }
                }
                int s = 0;
                for (int i = 0; i < aux.length; i++) {
                    s += aux[i];
                }
                bodies[k].nBoundary = s;
                bodies[k].radialBasisCoefficients = null;
                bodies[k].boundaryPointsCoords = new double[bodies[k].nBoundary][2];
                s = 0;
                for (int i = 0; i < aux.length; i++) {
                    if (aux[i] == 1) {
                        bodies[k].boundaryPointsCoords[s] = PXY[i];
                        s++;
                    }
                }
            }
        } catch (IOException ioe) {
            System.out.println("Error in boundary points!" + ioe);
        }

        // try to read body centers
        try {
            double[] rotationCenters = props.getDoubleArray("rotationCenters");
            for (int i = 0; i < 2 * nBodies; i = i + 2) {
                bodies[i].XCenter = new double[]{rotationCenters[i], rotationCenters[i + 1]};
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

            try {
                double[] refValues = eqn.getReferenceValues();
                lRef = refValues[0];
                pRef = refValues[1];
                rhoRef = refValues[2];
                tRef = refValues[4];
            } catch (Exception e) {
                System.out.println("Cannot assign referential values to body dynamics!");
            }
            mRef = rhoRef * lRef * lRef;
            kRef = pRef;
            bRef = pRef * tRef;
            IRef = mRef * lRef * lRef;
            torRef = pRef * lRef * lRef;
            torbRef = pRef * lRef * lRef * tRef;

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
    }
	
	@Override
	public void computeBodyMove(double dt, double t, int newtonIter, FluidForces[] fluFor) {
        this.dt = dt;
        this.t = t;
		
		for (int b = 0; b < nBodies; b++) {
			double[][] rbfForceCoefficient = computeRadialBasisFunctionCoefficients(fluFor[b].stressVectors,
					fluFor[b].stressVectorPositions);
			
			double[][] bodyDeformation = bodies[b].computeDeformation(fluFor[b].stressVectorPositions, rbfForceCoefficient, t, dt);
			
			bodies[b].radialBasisCoefficients = computeRadialBasisFunctionCoefficients(bodies[b].boundaryPointsCoords,
					bodyDeformation);
		}        
    }

//    public void computeBodyMoveOld(double dt, double t, int newtonIter, FluidForces fluFor) {
//        this.dt = dt;
//        this.t = t;
//
//        double[][] boundaryForces = fluFor.getBoundaryForce();
//        if (boundaryForces != null) {
//            int[][] boundaryIndexes = fluFor.getFaceIndexes();
//            int nIndex = boundaryForces.length;
//            int dim = boundaryForces[0].length / 2;
//            int[] nbIndex = new int[nBodies];
//
//            // number of bodies faces
//            for (int i = 0; i < nIndex; i++) {
//                nbIndex[boundaryIndexes[i][2] - 2]++;
//            }
//            for (int k = 0; k < nBodies; k++) {
//                double[][] bodyForce = new double[dim][nbIndex[k]];
//                double[][] forceCoord = new double[nbIndex[k]][dim];
//                int s = 0;
//                for (int i = 0; i < nIndex; i++) {
//                    if (boundaryIndexes[i][2] == k + 2) {
//                        for (int j = 0; j < dim; j++) {
//                            bodyForce[j][i] = boundaryForces[s][j];
//                            forceCoord[i][j] = boundaryForces[s][j + dim];
//                        }
//                        s++;
//                    }
//                }
//                double[][] rbfForceCoefficient = computeRadialBasisFunctionCoefficients(forceCoord, bodyForce);
//                double[][] bodyDeformation = bodies[k].computeDeformation(forceCoord, rbfForceCoefficient, t, dt);
//                bodies[k].radialBasisCoefficients = computeRadialBasisFunctionCoefficients(bodies[k].boundaryPointsCoords, bodyDeformation);
//            }
//        } else { // external force free
//            for (int k = 0; k < nBodies; k++) {
//                double[][] bodyDeformation = bodies[k].computeDeformation(null, null, t, dt);
//                bodies[k].radialBasisCoefficients = computeRadialBasisFunctionCoefficients(bodies[k].boundaryPointsCoords, bodyDeformation);
//            }
//        }
//    }

    public void nextTimeLevel() {
        /*
         if (dynamicComputation) {
         for (int i = 0; i < nBodies; i++) {
         for (int j = 0; j < bodies[i].X.length; j++) {
         bodies[i].X[j] = bodies[i].Xnew[j];
         bodies[i].U[j] = bodies[i].Unew[j];
         }
         }
         }
         */
    }

    public MeshMove[] getMeshMove() {
        MeshMove[] mshMov = new MeshMove[nBodies];
        for (int k = 0; k < nBodies; k++) {
            mshMov[k] = new MeshMove(new double[]{bodies[k].X[0], bodies[k].X[1]}, new double[]{bodies[k].X[2]}, bodies[k].getRadialBasisCoefficients(), bodies[k].getBoundaryPointsCoords());
        }
        return mshMov;
    }

    public double[][] getCenter() {
        double[][] center = new double[2][nBodies];
        for (int i = 0; i < nBodies; i++) {
            center[0][i] = bodies[i].XCenter[0];
            center[1][i] = bodies[i].XCenter[1];
        }
        return center;
    }

    public void savePositionsAndForces() {

        try (FileWriter fw = new FileWriter(simulationPath + "bodiesDynamic.txt", true);
                BufferedWriter bw = new BufferedWriter(fw);
                PrintWriter out = new PrintWriter(bw)) {
            String line = Double.toString(t);
            for (int i = 0; i < nBodies; i++) {
                line = line + " " + Double.toString(bodies[i].u[bodies[i].u.length - 1]);
            }
            out.println(line);
        } catch (IOException e) {
            //exception handling left as an exercise for the reader
        }
    }

    double[][] computeRadialBasisFunctionCoefficients(double[][] boundaryPointsCoords, double[][] b) {
        int nPoints = boundaryPointsCoords.length;
        int dim = boundaryPointsCoords[0].length;
        double[][] rbfCoeff = new double[dim][nPoints];
        double[][] A = new double[nPoints][nPoints];
        for (int i = 0; i < nPoints; i++) {
            for (int j = 0; j < nPoints; j++) {
                A[i][j] = radialBasisFunction(boundaryPointsCoords[i], boundaryPointsCoords[j]);
            }
        }
        for (int d = 0; d < dim; d++) {
            rbfCoeff[d] = Mat.cg(A, b[d], 1e-6, 500);
        }
        return rbfCoeff;
    }

    public double radialBasisFunction(double[] a, double[] b) {
        double norm = 0;
        for (int i = 0; i < a.length; i++) {
            norm += (a[i] - b[i]) * (a[i] - b[i]);
        }
        norm = Math.sqrt(norm);
        return 1 - norm;
    }

    public class Body {

        ScriptEvaluator jsEval;
        String xMotion;
        String yMotion;
        String alphaMotion;

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
        public double[] RHSold;

        // deformation
        public int nBoundary;
        public double[][] radialBasisCoefficients;
        public double[][] boundaryPointsCoords;
        public double[] u;
        public double[] uOld;
        public double[] uOld2;
        double[][] Mglo, Bglo, Kglo;
        double[] Fe;

        Body(int i, ScriptEvaluator jsEval) {
            X = new double[3];
            U = new double[3];
            XCenter = new double[3];

            Xnew = new double[3];
            Unew = new double[3];
            Uold = new double[3];
            RHSold = new double[3];

            this.jsEval = jsEval;
        }

        void setBodyParameters(double[][] M, double[][] B, double[][] K) {
            this.M = M;
            this.B = B;
            this.K = K;
            iM = Mat.invert(M);
        }

        void setActualKinematicCoordinates(int i, double t) throws ScriptException {
            if (xMotion != null) {
                X[0] = jsEval.eval(xMotion, t);
            }
            if (yMotion != null) {
                X[1] = jsEval.eval(yMotion, t);
            }
            if (alphaMotion != null) {
                X[2] = jsEval.eval(alphaMotion, t);
            }
        }

        public double[] timeDependentForces(int i, double t) { // time dependent external forces
            return new double[3];
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

        public double[][] getBoundaryPointsCoords() {
            return boundaryPointsCoords;
        }

        public double[][] getRadialBasisCoefficients() {
            return radialBasisCoefficients;
        }

        private double[][] computeDeformation(double[][] coordForce, double[][] rbfForceCoefficient, double t, double dt) {
            double rho = 100;
            double Ap = 0.1;
            double L = 0.5;
            int n = 10;
            double[] y = Mat.linspace(0, L, n);
            double h = L / n;
            double EI = 0.1;
            int nu = 2 * n;

            if (t == 0) { // init
                u = new double[nu];
                uOld = new double[nu];
                uOld2 = new double[nu];

                double a = 4818;
                double g = 729 * h;
                double c = 1482;
                double d = -321 * h;
                double e = 172 * h * h;
                double f = -73 * h * h;

                double[][] Me = new double[][]{{a, g, c, d}, {g, e, -d, f}, {c, -d, a, -g}, {d, f, -g, e}};
                Me = Mat.times(Me, rho * Ap * h / 12600);
                double[][] Ke = new double[][]{{12, 6 * h, -12, 6 * h}, {6 * h, 4 * h * h, -6 * h, 2 * h * h}, {-12, -6 * h, 12, -6 * h}, {6 * h, 2 * h * h, -6 * h, 4 * h * h}};
                Ke = Mat.times(Ke, EI / (h * h * h));
                Fe = new double[]{1, h / 6, 1, -h / 6};
                Fe = Mat.times(Fe, h / 2);

                Mglo = new double[nu][nu];
                Kglo = new double[nu][nu];
                Bglo = new double[nu][nu];
                double alfa = 1;
                double beta = 1;
                for (int i = 0; i < n - 1; i++) {
                    for (int k = 0; k < 4; k++) {
                        for (int m = 0; m < 4; m++) {
                            Mglo[i * 2 + k][i * 2 + m] += Me[k][m];
                            Kglo[i * 2 + k][i * 2 + m] += Ke[k][m];
                            Bglo[i * 2 + k][i * 2 + m] += alfa * Me[k][m] + beta * Ke[k][m];
                        }
                    }
                }
            }

            double[] q = new double[n - 1];
            for (int i = 0; i < n - 1; i++) {
                double[] Xs = new double[]{0, (y[i] + y[i + 1]) / 2};
                if (t < 1) {
                    q[i] = 10;
                } else {
                    q[i] = 0;
                }
                /*
                 if (rbfForceCoefficient != null) {
                 for (int j = 0; j < rbfForceCoefficient.length; j++) {
                 q[i] += rbfForceCoefficient[j][0] * radialBasisFunction(Xs, coordForce[j]); // force in X direction
                 }
                 }*/
            }

            double[] F = new double[nu];
            for (int i = 0; i < n - 1; i++) {
                for (int k = 0; k < 4; k++) {
                    F[i * 2 + k] += q[i] * Fe[k];
                }
            }

            double[][] A = new double[nu - 2][nu - 2];
            double[] b = new double[nu - 2];
            double dt2 = dt * dt;
            for (int i = 0; i < nu - 2; i++) {
                b[i] = F[i + 2];
                for (int j = 0; j < nu - 2; j++) {
                    A[i][j] = (Mglo[i + 2][j + 2] / dt2 + Bglo[i + 2][j + 2] / dt + Kglo[i + 2][j + 2]);
                    b[i] += Mglo[i + 2][j + 2] * (2 * uOld[j + 2] - uOld2[j + 2]) / dt2 + Bglo[i + 2][j + 2] * uOld[j + 2] / dt;
                }
            }

            //double[] x = Mat.gmres(A, b, 50, 1e-9, 30);
            double[] x = Mat.lsolve(A, b, b.length);
            //System.out.println(Mat.sum(Mat.minusVec(Mat.times(A, x), b)));
            for (int i = 2; i < nu; i++) {
                uOld2[i] = uOld[i];
                uOld[i] = u[i];
                u[i] = x[i - 2];
            }

            double[][] def = new double[2][n];
            boundaryPointsCoords = new double[n][2];
            for (int i = 1; i < n; i++) {
                def[0][i] = -u[2 * i];
                boundaryPointsCoords[i][0] = def[0][i];
                boundaryPointsCoords[i][1] = y[i];
            }
            return def;
        }
    }
}
