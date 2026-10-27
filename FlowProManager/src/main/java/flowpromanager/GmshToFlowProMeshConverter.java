/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package flowpromanager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 *
 * @author obublik
 */
public class GmshToFlowProMeshConverter {
    
    String mshFile;
    
    GmshToFlowProMeshConverter(String mshFile) {
        this.mshFile = mshFile;
    }
    
    public void convert2D() {
        try (BufferedReader reader = new BufferedReader(new FileReader(mshFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // read nodes
                if (line.equals("$Nodes")) {
                    int nVertices = Integer.parseInt(reader.readLine());
                    try (BufferedWriter writer = new BufferedWriter(new FileWriter("vertices.txt"))) {
                        for (int i = 0; i < nVertices; i++) {
                            line = reader.readLine();
                            String[] tokens = line.split(" ");
                            writer.write(tokens[1] + " " + tokens[2] + " " + tokens[3]);
                            writer.newLine();
                        }
                        writer.close();
                    } catch (Exception e) {
                        System.out.println(" Error when reading vertices from .msh file! ");
                    }
                }
                // read elements, elements type and boundary conditions
                if (line.equals("$Elements")) {
                    int nElements = Integer.parseInt(reader.readLine());
                    try {
                        BufferedWriter eLementWriter = new BufferedWriter(new FileWriter("elements.txt"));
                        BufferedWriter typeWriter = new BufferedWriter(new FileWriter("elementType.txt"));
                        BufferedWriter boundaryWriter = new BufferedWriter(new FileWriter("boundaryType.txt"));
                        BufferedWriter boundaryALEWriter = new BufferedWriter(new FileWriter("boundaryTypeALE.txt"));
                        for (int i = 0; i < nElements; i++) {
                            line = reader.readLine();
                            String[] tokens = line.split(" ");
                            switch (Integer.parseInt(tokens[1])) {
                                case 1:
                                    boundaryWriter.write("-" + tokens[3] + " " + tokens[5] + " " + tokens[6]);
                                    boundaryWriter.newLine();
                                    break;
                                case 2:
                                    eLementWriter.write(tokens[5] + " " + tokens[6] + " " + tokens[7]);
                                    eLementWriter.newLine();
                                    typeWriter.write("3");
                                    typeWriter.newLine();
                                    break;
                                case 3:
                                    eLementWriter.write(tokens[7] + " " + tokens[8] + " " + tokens[9] + " " + tokens[10]);
                                    eLementWriter.newLine();
                                    typeWriter.write("4");
                                    typeWriter.newLine();
                                    break;

                            }
                        }
                        eLementWriter.close();
                        typeWriter.close();
                        boundaryWriter.close();
                    } catch (Exception e) {
                        System.out.println(" Error when reading vertices from .msh file! ");
                    }
                }
            }
            reader.close();
        } catch (Exception e) {

        }
    }
}
