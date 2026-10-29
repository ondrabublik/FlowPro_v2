package flowpro.control;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tasks of the GUI. A task is directory simulations/GUI/NAME: one mesh
 * (directory mesh, geometry of the mesh generator geometry.geo2d) and any
 * number of computations (subdirectories with parameters.txt). For FlowPro
 * the task is a geometry named GUI/NAME and the computation a simulation.
 *
 * @author obublik
 */
final class Tasks {

    static final String DIR = "GUI";
    static final String MESH = "mesh";
    static final String GEOMETRY_FILE = "geometry.geo2d";
    /**
     * files describing a computation, the results are not copied
     */
    private static final String[] SETUP_FILES = {"parameters.txt", "initScript.js", "order.txt"};

    private Tasks() {
    }

    static File tasksDir(File flowProDir) {
        return new File(new File(flowProDir, "simulations"), DIR);
    }

    static List<String> list(File flowProDir) {
        List<String> names = new ArrayList<>();
        File[] dirs = tasksDir(flowProDir).listFiles(File::isDirectory);
        if (dirs != null) {
            Arrays.sort(dirs);
            for (File dir : dirs) {
                names.add(dir.getName());
            }
        }
        return names;
    }

    static List<String> computations(File taskDir) {
        List<String> names = new ArrayList<>();
        File[] dirs = taskDir.listFiles(f -> f.isDirectory() && new File(f, "parameters.txt").isFile());
        if (dirs != null) {
            Arrays.sort(dirs);
            for (File dir : dirs) {
                names.add(dir.getName());
            }
        }
        return names;
    }

    static boolean hasMesh(File taskDir) {
        return new File(new File(taskDir, MESH), "elements.txt").isFile();
    }

    static boolean hasResults(File computationDir) {
        return new File(computationDir, "W.txt").isFile();
    }

    /**
     * @return null if the name can be used for a task or a computation,
     * otherwise the reason why not
     */
    static String checkName(String name) {
        if (name.isEmpty()) {
            return "The name is empty.";
        }
        if (!name.matches("[A-Za-z0-9_][A-Za-z0-9_.\\-]*")) {
            return "Use only letters, digits, '_', '-' and '.' in the name.";
        }
        if (name.equalsIgnoreCase(MESH)) {
            return "The name '" + MESH + "' is reserved for the mesh.";
        }
        return null;
    }

    /**
     * All the directories in simulations which contain a mesh (FlowPro
     * geometries, including the tasks GUI/NAME).
     */
    static List<String> geometries(File flowProDir) {
        List<String> geometries = new ArrayList<>();
        collectGeometries(new File(flowProDir, "simulations"), "", geometries, 0);
        return geometries;
    }

    private static void collectGeometries(File dir, String prefix, List<String> geometries, int depth) {
        File[] dirs = dir.listFiles(File::isDirectory);
        if (dirs == null) {
            return;
        }
        Arrays.sort(dirs);
        for (File sub : dirs) {
            String name = prefix.isEmpty() ? sub.getName() : prefix + "/" + sub.getName();
            if (new File(sub, MESH).isDirectory()) {
                geometries.add(name);
            } else if (depth < 3) {
                collectGeometries(sub, name, geometries, depth + 1);
            }
        }
    }

    /**
     * Copies parameters.txt (and the initial condition script, the orders of
     * the elements) of a computation, not its results.
     */
    static void copyComputationSetup(File source, File target) throws IOException {
        Files.createDirectories(target.toPath());
        for (String name : SETUP_FILES) {
            File f = new File(source, name);
            if (f.isFile()) {
                Files.copy(f.toPath(), new File(target, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Creates a computation with the default parameters (subsonic flow of
     * the Euler equations, the boundary conditions must be checked).
     */
    static void createDefaultComputation(File target) throws IOException {
        Files.createDirectories(target.toPath());
        try (InputStream in = Tasks.class.getResourceAsStream("defaultParameters.txt")) {
            if (in == null) {
                throw new IOException("the template of the parameters is missing in the application");
            }
            Files.copy(in, new File(target, "parameters.txt").toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void deleteDirectory(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException ex) throws IOException {
                if (ex != null) {
                    throw ex;
                }
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
