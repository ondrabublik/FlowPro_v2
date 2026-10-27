package flowpro.user.dynamics;

import flowpro.api.*;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

/**
 *
 * @author obublik
 */
public class Rigid2DMatlabInterface implements Dynamics {

    Equation eqn;

    private int nBodies;
    private String simulationPath;
    private double[] xForce;
    private double[] yForce;
    private double[] torque;
    public double dt;
    public double t;
    double[] Xnew;
    double[] Ynew;
    double[] alfa;

    double[] XCenter0;
    double[] YCenter0;

    // dynamic
    boolean dynamicComputation = false;
    protected double lRef = 1;
    protected double pRef = 1;
    protected double rhoRef = 1;
    protected double vRef = 1;
    protected double tRef = 1;

    //Matlab
    MatlabClient mc;

    public void init(int nBodies, String simulationPath, String meshPath, Equation eqn) throws IOException {
        this.eqn = eqn;
        this.nBodies = nBodies;
        this.simulationPath = simulationPath;

        Xnew = new double[nBodies];
        Ynew = new double[nBodies];
        alfa = new double[nBodies];
        
        xForce = new double[nBodies];
        yForce = new double[nBodies];
        torque = new double[nBodies];

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
        XCenter0 = new double[nBodies];
        YCenter0 = new double[nBodies];
        try {
            double[] rotationCenters = props.getDoubleArray("rotationCenters");
            for (int i = 0; i < nBodies; i++) {
                XCenter0[i] = rotationCenters[2 * i];
                YCenter0[i] = rotationCenters[2 * i + 1];
            }
        } catch (IOException ioe) {
            System.out.println("Body centers not defined!");
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

        // Launching Matlab
        try {
            mc = new MatlabClient();
            mc.init();
        } catch (Exception e) {
            System.out.println("Matlab init error " + e);
        }
    }

    public void computeBodyMove(double dt, double t, int s, FluidForces[] fluFor) {
        this.dt = dt;
        this.t = t;
        for (int i = 0; i < nBodies; i++) {
            xForce[i] = fluFor[i].force[0];
            yForce[i] = fluFor[i].force[1];
            torque[i] = fluFor[i].torque[0];
        }
        try {
            mc.computeBodyMovement();
        } catch (Exception e) {
            System.out.println(e);
        }
    }

    public void nextTimeLevel() {
        if (dynamicComputation) {
            try {
                mc.nextTimeLevel();
            } catch (Exception e) {
                System.out.println(e);
            }
        }
    }

    public MeshMove[] getMeshMove() {
        MeshMove[] mshMov = new MeshMove[nBodies];
        for (int k = 0; k < nBodies; k++) {
            mshMov[k] = new MeshMove(new double[]{Xnew[k], Ynew[k]}, new double[]{alfa[k]}, null, null);
        }
        return mshMov;
    }

    public double[][] getCenter() {
        double[][] center = new double[2][nBodies];
        for (int i = 0; i < nBodies; i++) {
            center[0][i] = XCenter0[i];
            center[1][i] = YCenter0[i];
        }
        return center;
    }

    public void savePositionsAndForces() {
        try (FileWriter fw = new FileWriter(simulationPath + "bodiesDynamic.txt", true);
                BufferedWriter bw = new BufferedWriter(fw);
                PrintWriter out = new PrintWriter(bw)) {
            String line = Double.toString(t);
            for (int i = 0; i < nBodies; i++) {
                line = line + " " + Double.toString(Xnew[i]) + " " + Double.toString(Ynew[i]) + " " + Double.toString(alfa[i]) + " "
                        + Double.toString(xForce[i]) + " " + Double.toString(yForce[i]) + " " + Double.toString(torque[i]);
            }
            out.println(line);
        } catch (IOException e) {
            //exception handling left as an exercise for the reader
        }
    }

    class MatlabClient {

        Socket socket = null;
        ObjectOutputStream out;
        ObjectInputStream in;

        MatlabClient() {
            try (ServerSocket listener = new ServerSocket(5768)) {
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
            out.writeInt(nBodies);
            out.flush();
            in.readObject();
        }

        void computeBodyMovement() throws IOException, ClassNotFoundException {
            out.writeObject("def");
            out.flush();
            out.writeDouble(t);
            out.writeDouble(dt);
            out.writeObject(xForce);
            out.writeObject(yForce);
            out.writeObject(torque);
            out.flush();

            if (nBodies > 1) {
                Xnew = (double[]) in.readObject();
                Ynew = (double[]) in.readObject();
                alfa = (double[]) in.readObject();
            } else {
                Xnew[0] = (double) in.readObject();
                Ynew[0] = (double) in.readObject();
                alfa[0] = (double) in.readObject();
            }
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
