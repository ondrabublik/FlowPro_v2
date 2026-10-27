package flowpro.user.dynamics;

import flowpro.api.*;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

/**
 *
 * @author obublik
 */
public class Elastic3DBeam implements Dynamics {

    Equation eqn;

    private int nBodies;
    private Body[] bodies;
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

    //Matlab
    MatlabClient mc;

    public void init(int nBodies, String simulationPath, String meshPath, Equation eqn) throws IOException {
        this.eqn = eqn;
        this.nBodies = nBodies;
        bodies = new Body[nBodies];
        for (int i = 0; i < nBodies; i++) {
            bodies[i] = new Body(i);
        }

        // clear file for results
        FlowProProperties props = new FlowProProperties();
        props.load(new FileInputStream(simulationPath + "parameters.txt"));

        // try to read body centers
        try {
            double[] rotationCenters = props.getDoubleArray("rotationCenters");
            for (int k = 0; k < nBodies; k++) {
                bodies[k].XCenter = new double[]{rotationCenters[3*k], rotationCenters[3*k + 1], rotationCenters[3*k + 2]};
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
        mRef = rhoRef * lRef * lRef;
        kRef = pRef;
        bRef = pRef * tRef;
        IRef = mRef * lRef * lRef;
        torRef = pRef * lRef * lRef;
        torbRef = pRef * lRef * lRef * tRef;

        dynamicComputation = true;

        // Launching Matlab
        try {
            mc = new MatlabClient();
            mc.init();
        } catch (Exception e) {
            System.out.println("Matlab init error " + e);
        }
    }
	
	public void computeBodyMove(double dt, double t, int newtonIter, FluidForces[] fluFor) {} // jenom abych mohl prelozit kod

    public void computeBodyMove(double dt, double t, int newtonIter, FluidForces fluFor) {
        this.dt = dt;
        this.t = t;

        for (int k = 0; k < nBodies; k++) {
            try {
                mc.computeDeformation(k, bodies[k], null, null, t, dt);
            } catch (Exception e) {
                System.out.println(e);
            }
            bodies[k].radialBasisCoefficients = computeRadialBasisFunctionCoefficients(bodies[k].boundaryPointsCoords, bodies[k].bodyDeformation);
        }
    }

    public MeshMove[] getMeshMove() {
        MeshMove[] mshMov = new MeshMove[nBodies];
        for (int k = 0; k < nBodies; k++) {
            mshMov[k] = new MeshMove(null, null, bodies[k].getRadialBasisCoefficients(), bodies[k].getBoundaryPointsCoords());
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

    public void nextTimeLevel() {
        try {
            mc.nextTimeLevel();
        } catch (Exception e) {
            System.out.println(e);
        }
    }

    public void savePositionsAndForces() {
    }

    double[][] computeRadialBasisFunctionCoefficients(double[][] boundaryPointsCoords, double[][] b) {
        int nPoints = boundaryPointsCoords.length;
        int dim = 3;
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
        return Math.max(1 - Math.abs(a[0] - b[0]),0);
    }
    

    public class Body {

        // deformation
        public int nBoundary;
        public double[][] radialBasisCoefficients;
        public double[][] boundaryPointsCoords;
        public double[][] bodyDeformation;
        public double[] XCenter;

        Body(int i) {
        }

        public double[][] getBoundaryPointsCoords() {
            return boundaryPointsCoords;
        }

        public double[][] getRadialBasisCoefficients() {
            return radialBasisCoefficients;
        }
    }

    class MatlabClient {

        Socket socket = null;
        ObjectOutputStream out;
        ObjectInputStream in;

        MatlabClient() {
            try (ServerSocket listener = new ServerSocket(5767)) {
                socket = listener.accept();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                out = new ObjectOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                out.writeObject("test");
                out.flush();
                in = new ObjectInputStream(new BufferedInputStream(socket.getInputStream()));
                in.readObject();
                System.out.println("Succesfully connect with Matlab ...");
            } catch (Exception e) {
                System.out.println(e);
            }
        }

        void init() throws IOException, ClassNotFoundException {
            out.writeObject("init");
            out.flush();
            in.readObject();
        }

        void computeDeformation(int k, Body body, double[][] forceCoord, double[][] rbfForceCoefficient, double t, double dt) throws IOException, ClassNotFoundException {
            out.writeObject("def");
            out.flush();
            out.writeInt(k);
            out.writeObject(forceCoord);
            out.writeObject(rbfForceCoefficient);
            out.writeDouble(t);
            out.writeDouble(dt);
            out.flush();
            body.boundaryPointsCoords = (double[][]) in.readObject();
            body.bodyDeformation = (double[][]) in.readObject();
            in.readObject();
        }

        void nextTimeLevel() throws IOException, ClassNotFoundException {
            out.writeObject("next");
            out.flush();
            in.readObject();
        }

        void close() throws IOException {
            socket.close();
        }
    }
}
