/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package flowpromanager;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;

/**
 *
 * @author obublik
 */
public class SimulationSetup {

    public static final String ARG_FILE_NAME = "args.txt";
    public static final String PARAMETER_FILE_NAME = "parameters.txt";
    public static final String STATE_FILE_NAME = "state.txt";
    public static final String REF_VALUE_FILE_NAME = "referenceValues.txt";

    public String geometryPath;
    public String meshPath;
    public String simulationPath;
    public String geometryName;
    public String simulationName;
    public int nVertices;
    public int nElements;
    public int[] nElemTypes = new int[5];
    public String nElementsPrint;
    public String date;
    public String steps;

    SimulationSetup() {
        refreshSimulationPath();
    }

    void refreshSimulationPath() {
        try {
            BufferedReader reader;
            reader = new BufferedReader(new FileReader(ARG_FILE_NAME));

            String line;
            String[] args;
            if ((line = reader.readLine()) != null) {
                args = line.split(" ");
                if (args.length != 2) {
                    throw new IOException("file " + ARG_FILE_NAME
                            + " must contain one line with exactly two arguments");
                }
                geometryName = args[0];
                simulationName = args[1];
            } else {
                throw new IOException("file " + ARG_FILE_NAME + " is empty");
            }

            geometryPath = "simulations/" + geometryName + "/";
            meshPath = "simulations/" + geometryName + "/mesh/";
            simulationPath = "simulations/" + geometryName + "/" + simulationName + "/";

        } catch (IOException e) {
            System.out.println(e);
        }

        try {
            nVertices = 0;
            BufferedReader reader = new BufferedReader(new FileReader(meshPath + "vertices.txt"));
            while(reader.readLine() != null){
                nVertices++;
            }
            reader.close();
            
            Arrays.fill(nElemTypes, 0);
            reader = new BufferedReader(new FileReader(meshPath + "elementType.txt"));
            nElements = 0;
            String line;
            while((line = reader.readLine()) != null){
                nElements++;
                switch (Integer.parseInt(line.replaceAll(" ", ""))) {
                    case 3:
                        nElemTypes[0]++;
                        break;
                    case 4:
                        nElemTypes[1]++;
                        break;
                    case 5:
                        nElemTypes[2]++;
                        break;
                    case 6:
                        nElemTypes[3]++;
                        break;
                    case 7:
                        nElemTypes[4]++;
                        break;
                }
            }
            reader.close();
            
            boolean first = true;
            nElementsPrint = nElements + " (";
            if (nElemTypes[0] > 0) {
                if (first) {
                    nElementsPrint += "triangles " + nElemTypes[0];
                    first = false;
                } else {
                    nElementsPrint += ", triangles " + nElemTypes[0];
                }
            }
            if (nElemTypes[1] > 0) {
                if (first) {
                    nElementsPrint += "squares " + nElemTypes[1];
                    first = false;
                } else {
                    nElementsPrint += ", squares " + nElemTypes[1];
                }
            }
            if (nElemTypes[2] > 0) {
                if (first) {
                    nElementsPrint += "tetrahedrals " + nElemTypes[2];
                    first = false;
                } else {
                    nElementsPrint += ", tetrahedrals " + nElemTypes[2];
                }
            }
            if (nElemTypes[3] > 0) {
                if (first) {
                    nElementsPrint += "hexahedrals " + nElemTypes[3];
                    first = false;
                } else {
                    nElementsPrint += ", hexahedrals " + nElemTypes[3];
                }
            }
            if (nElemTypes[4] > 0) {
                if (first) {
                    nElementsPrint += "prismatics " + nElemTypes[4];
                    first = false;
                } else {
                    nElementsPrint += ", prismatics " + nElemTypes[4];
                }
            }
            nElementsPrint += ")";
        } catch (Exception e) {
            System.out.println(" error reading mesh ");
        }
        
        try {
            BufferedReader reader;
            reader = new BufferedReader(new FileReader(simulationPath + STATE_FILE_NAME));

            date = reader.readLine();
            reader.readLine(); // transfer
            reader.readLine(); // t
            reader.readLine(); // CFL
            reader.readLine(); // CPU
            String[] tokens = (reader.readLine()).split("="); // steps
            steps = tokens[1];
            reader.readLine(); // residuum
            reader.readLine(); // order
            reader.close();

        } catch (IOException e) {
            System.out.println(e);
        }
    }

    String getMeshName() {
        return geometryName;
    }

    String getSimulationName() {
        return simulationName;
    }
}
