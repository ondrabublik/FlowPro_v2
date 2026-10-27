/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package flowpromanager;

import console.*;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.swing.SwingUtilities;

/**
 *
 * @author obublik
 */
public class FlowProManager {

    public static final String ARG_FILE_NAME = "args.txt";
    public SimulationSetup simulSetup;

    FlowProManager() {
        simulSetup = new SimulationSetup();
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {

        if (args == null || args.length == 0) {
            System.out.println("Not enough input arguments!");
            System.exit(0);
        }

        switch (args[0].toLowerCase()) {
            case "update": // update libraries in manifest file
                deleteFileFromZip("FlowPro.jar", "META-INF/MANIFEST.MF");
                deleteFileFromZip("FlowPro.zip", "FlowPro.jar");
                createManifest();
                saveFileIntoZip("FlowPro.jar", "MANIFEST.MF", "META-INF/");
                saveFileIntoZip("FlowPro.zip", "FlowPro.jar", "");
                break;

            case "createparamfile": // create parameters.txt file
                BufferedReader reader;
                reader = new BufferedReader(new FileReader(ARG_FILE_NAME));

                String line;
                String geometryName,
                 simulationName;
                if ((line = reader.readLine()) != null) {
                    args = line.split(" ");
                    if (args.length != 2) {
                        throw new IOException("file " + ARG_FILE_NAME
                                + " must contain one line with exactly two arguments");
                    }
                    geometryName = args[0];
                    simulationName = args[1];
                    String simulationPath = "simulations/" + geometryName + "/" + simulationName + "/";
                    createParameters(simulationPath);
                } else {
                    throw new IOException("file " + ARG_FILE_NAME + " is empty");
                }
                break;

            case "console": // start console
                new CMDConsole().start();
                break;

            case "gui": // start GUI
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        new FlowProGUI().setVisible(true);
                    }
                });
                break;
                
            case "gmshToFlowPro":
                GmshToFlowProMeshConverter gmsh2FlowPro = new GmshToFlowProMeshConverter(args[1]);
                gmsh2FlowPro.convert2D();
                break;
        }
    }

    public static void deleteFileFromZip(String zipFile, String filePath) throws IOException {
        /* Define ZIP File System Properies in HashMap */
        Map<String, String> zip_properties = new HashMap<>();
        /* We want to read an existing ZIP File, so we set this to False */
        zip_properties.put("create", "false");

        /* Specify the path to the ZIP File that you want to read as a File System */
        URI zip_disk = URI.create("jar:file:/" + correctChars(System.getProperty("user.dir")) + "/" + zipFile);
        /* Create ZIP file System */
        try (FileSystem zipfs = FileSystems.newFileSystem(zip_disk, zip_properties)) {
            /* Get the Path inside ZIP File to delete the ZIP Entry */
            Path pathInZipfile = zipfs.getPath(filePath);
            System.out.println("Deleting " + pathInZipfile.toUri());
            /* Execute Delete */
            Files.delete(pathInZipfile);
            System.out.println("File " + filePath + " was removed from " + zipFile);
        } catch (java.nio.file.NoSuchFileException e) {
            System.out.println("File " + filePath + " not found in " + zipFile);
        }
    }

    public static String correctChars(String in) {
        StringBuilder out = new StringBuilder(in);
        for (int i = 0; i < out.length(); i++) {
            if (out.charAt(i) == '\\') {
                out.setCharAt(i, '/');
            }
        }
        return out.toString();
    }

    public static void createManifest() {
        try {
            FileWriter fw = new FileWriter("MANIFEST.MF");
            try (BufferedWriter out = new BufferedWriter(fw)) {
                String radka = "Manifest-Version: 1.0";
                out.write(radka);
                out.newLine();
                radka = "Main-Class: flowpro.core.FlowProMain";
                out.write(radka);
                out.newLine();

                out.write("Class-Path:");
                int length = 11;
                File dir = new File("./lib");
                File[] filesList = dir.listFiles();
                for (File file : filesList) {
                    if (file.isFile()) {
                        length += (" lib/" + file.getName()).length();
                        if (length > 70) {
                            length = 0;
                            out.write(" ");
                            out.newLine();
                        }
                        out.write(" lib/" + file.getName());
                    }
                }
                
                dir = new File("modules");
                List<File> filesL = addFiles(null, dir);
                for (File file : filesL) {
                    if (file.isFile()) {
                        String relativeJarPath = file.toPath().toString();
                        length += relativeJarPath.length();
                        if (length > 70) {
                            length = 0;
                            out.write(" ");
                            out.newLine();
                        }
                        out.write(" " + relativeJarPath);
                    }
                }
                out.newLine();
                out.close();

                System.out.println("Manifest file was created!");
            }
        } catch (IOException e) {
            System.out.println(e);
        }
    }

    public static void saveFileIntoZip(String zipFileName, String fileName, String filePath) throws IOException {
        File zipFile = new File(zipFileName);
        File[] files = new File[1];
        files[0] = new File(fileName);

        // get a temp file
        File tempFile = File.createTempFile(zipFile.getName(), null);
        // delete it, otherwise you cannot rename your existing zip to it.
        tempFile.delete();
        boolean renameOk = zipFile.renameTo(tempFile);
        if (!renameOk) {
            throw new RuntimeException(
                    "could not rename the file " + zipFile.getAbsolutePath() + " to " + tempFile.getAbsolutePath());
        }
        byte[] buf = new byte[1024];
        ZipInputStream zin = new ZipInputStream(new FileInputStream(tempFile));
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zipFile));
        ZipEntry entry = zin.getNextEntry();
        while (entry != null) {
            String name = entry.getName();
            boolean notInFiles = true;
            for (File f : files) {
                if (f.getName().equals(name)) {
                    notInFiles = false;
                    break;
                }
            }
            if (notInFiles) { // Add ZIP entry to output stream.
                out.putNextEntry(new ZipEntry(name)); // Transfer bytes from the ZIP file to the output file
                int len;
                while ((len = zin.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }
            entry = zin.getNextEntry();
        } // Close the streams
        zin.close(); // Compress the files
        for (int i = 0; i < files.length; i++) {
            InputStream in = new FileInputStream(files[i]); // Add ZIP entry to output stream.
            out.putNextEntry(new ZipEntry(filePath + files[i].getName())); // Transfer bytes from the file to the ZIP file
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            } // Complete the entry
            out.closeEntry();
            in.close();
        } // Complete the ZIP file
        out.close();
        tempFile.delete();

        System.out.println("File " + fileName + " was updated!");
    }

    public static void createParameters(String simulationPath) throws IOException {
        File file = new File(simulationPath);
        if (!file.exists()) {
            if (file.mkdir()) {
                System.out.println("Directory is created!");
            } else {
                System.out.println("Failed to create directory!");
            }
        }

        file = new File(simulationPath + "parameters.txt");
        if (!file.exists()) {
            vspace(1);
            System.out.println("File parameters.txt will be create.");
            FileWriter fw = new FileWriter(simulationPath + "parameters.txt");
            try (BufferedWriter out = new BufferedWriter(fw)) {
                addToParametersFile(out, "FlowPro.jar", "templates/numericalParameters.txt");

                String eqsPath = "modules/equations/";
                String packageNameEqs = selectpackage(eqsPath);
                String templateEqs = selectTemplate(eqsPath + packageNameEqs);
                addToParametersFile(out, "FlowPro.jar", "templates/headerEquations.txt");
                addToParametersFile(out, eqsPath + packageNameEqs, templateEqs);

                if (ask("Moving body problem [y/N]? ").equalsIgnoreCase("y")) {
                    vspace(1);
                    String dynPath = "modules/dynamics/";
                    String packageNameDyn = selectpackage(dynPath);
                    String templateDyn = selectTemplate(dynPath + packageNameDyn);
                    addToParametersFile(out, "FlowPro.jar", "templates/headerDynamics.txt");
                    addToParametersFile(out, dynPath + packageNameDyn, templateDyn);
                }

                if (ask("Optimisation problem [y/N]? ").equalsIgnoreCase("y")) {
                    vspace(1);
                    String dynPath = "modules/optimisation/";
                    String packageNameOpt = selectpackage(dynPath);
                    String templateDyn = selectTemplate(dynPath + packageNameOpt);
                    addToParametersFile(out, "FlowPro.jar", "templates/headerOptimisation.txt");
                    addToParametersFile(out, dynPath + packageNameOpt, templateDyn);
                }
                out.close();
                System.out.println("File parameters.txt was create sucesfully.");
            }
        } else {
            System.out.println("File parameters.txt exist in " + simulationPath);
        }

    }

    public static void vspace(int n) {
        for (int i = 0; i < n; i++) {
            System.out.println();
        }
    }

    public static String selectpackage(String folder) throws IOException {
        int selection;
        File dir = new File(folder);
        File[] filesList = dir.listFiles();
        while (true) {
            System.out.println("List of packages:");
            for (int i = 0; i < filesList.length; i++) {
                if (filesList[i].isFile()) {
                    System.out.println(i + 1 + " " + filesList[i].getName());
                }
            }
            try {
                selection = Integer.valueOf(ask("Select package number: ")) - 1;
                vspace(1);
                break;
            } catch (Exception e) {
                System.out.println("Input must be a number!");
                vspace(1);
            }
        }
        return filesList[selection].getName();
    }

    public static String selectTemplate(String jarPath) throws IOException {
        JarFile jarFile = new JarFile(jarPath);
        Enumeration enu = jarFile.entries();
        int s = 0;
        while (enu.hasMoreElements()) {
            JarEntry entry = (JarEntry) enu.nextElement();
            String str = entry.getName();
            if (isTemplate(str) != null) {
                s++;
            }
        }
        String[] templates = new String[s];
        enu = jarFile.entries();
        s = 0;
        while (enu.hasMoreElements()) {
            JarEntry entry = (JarEntry) enu.nextElement();
            String str = entry.getName();
            if (isTemplate(str) != null) {
                templates[s] = str;
                s++;
            }
        }

        int selection;
        while (true) {
            System.out.println("List of templates:");
            for (int i = 0; i < templates.length; i++) {
                System.out.println(i + 1 + " " + templates[i]);
            }
            try {
                selection = Integer.valueOf(ask("Select template number: ")) - 1;
                vspace(1);
                break;
            } catch (Exception e) {
                System.out.println("Input must be a number!");
                vspace(1);
            }
        }

        return templates[selection];
    }

    public static String isTemplate(String str) {
        String[] parts = str.split("/");
        if ("templates".equalsIgnoreCase(parts[0]) && parts.length > 1) {
            return parts[1];
        } else {
            return null;
        }
    }

    public static void addToParametersFile(BufferedWriter outFile, String jarPath, String templatePath) throws IOException {
        try (JarFile jarFile = new JarFile(jarPath)) {
            JarEntry entry = jarFile.getJarEntry(templatePath);
            InputStream input = jarFile.getInputStream(entry);
            InputStreamReader isr = new InputStreamReader(input);
            try (BufferedReader reader = new BufferedReader(isr)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outFile.write(line);
                    outFile.newLine();
                }
            }
        }
    }

    public static String ask(String question) throws IOException {
        System.out.print(question);
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        return br.readLine();
    }

    public void setArgs(String geomName, String simulName) {
        try (FileWriter fw = new FileWriter("args.txt")) {
            try (BufferedWriter out = new BufferedWriter(fw)) {
                String radka = geomName + " " + simulName;
                out.write(radka);
                out.newLine();
                out.close();
            } catch (IOException e) {

            }
        } catch (IOException e) {

        }
    }

    public String[] getListOfSimulation() {
        LinkedList<String> list = new LinkedList<>();
        addToList(list, "simulations");
        int nList = list.size();
        String[] simulList = new String[nList];
        for (int i = 0; i < nList; i++) {
            simulList[i] = list.get(i);
        }
        return simulList;
    }

    public void addToList(LinkedList<String> list, String folder) {
        File dir = new File(folder);
        if (dir.exists()) {
            File[] filesList = dir.listFiles();
            boolean insideSimulation = false;
            for (File f : filesList) {
                if (f.exists() && f.isDirectory() && "mesh".equals(f.getName())) {
                    insideSimulation = true;
                }
            }
            if (insideSimulation) {
                for (File f : filesList) {
                    if (f.exists() && f.isDirectory() && !"mesh".equals(f.getName())) {
                        list.add(substractSim(folder) + "&" + f.getName());
                    }
                }
            } else {
                for (File f : filesList) {
                    if (f.exists() && f.isDirectory()) {
                        addToList(list, folder + "/" + f.getName());
                    }
                }
            }
        }
    }

    String substractSim(String name) {
        return name.substring(12);
    }

    public void importMesh(String geometryPath, String mshFile) {
        File mesh = new File(geometryPath + "mesh");
        mesh.mkdir();
        File sim = new File(geometryPath + "default");
        sim.mkdir();
        try (BufferedReader reader = new BufferedReader(new FileReader(mshFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // read nodes
                if (line.equals("$Nodes")) {
                    int nVertices = Integer.parseInt(reader.readLine());
                    try (BufferedWriter writer = new BufferedWriter(new FileWriter(geometryPath + "mesh/vertices.txt"))) {
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
                        BufferedWriter eLementWriter = new BufferedWriter(new FileWriter(geometryPath + "mesh/elements.txt"));
                        BufferedWriter typeWriter = new BufferedWriter(new FileWriter(geometryPath + "mesh/elementType.txt"));
                        BufferedWriter boundaryWriter = new BufferedWriter(new FileWriter(geometryPath + "mesh/boundaryType.txt"));
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

    public static List<File> addFiles(List<File> files, File dir) {
        if (files == null) {
            files = new LinkedList<File>();
        }
        if (!dir.isDirectory()) {
            files.add(dir);
            return files;
        }
        for (File file : dir.listFiles()) {
            addFiles(files, file);
        }
        return files;
    }
}
