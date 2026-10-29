package flowpro.core;

import flowpro.api.FlowProProperties;
import flowpro.core.LinearSolvers.LinearSolver;
import flowpro.core.LinearSolvers.preconditioners.Preconditioner;
import flowpro.core.element.Element;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Description of the parameters of file parameters.txt which are read by the
 * core of FlowPro (the parameters of the equations are read by the modules).
 * It is used for the validation of the file (misspelled names, wrong values)
 * and for printing the help (java -jar FlowPro.jar params).
 *
 * @author obublik
 */
public final class ParameterRegistry {

    public enum Type {
        INT, DOUBLE, BOOLEAN, STRING, DOUBLE_ARRAY, BOOLEAN_ARRAY, ENUM
    }

    public static final class Info {

        public final String name;
        public final Type type;
        public final String defaultValue; // null = required, "" = optional without default
        public final String category;
        public final String description;
        public final List<String> allowedValues;

        Info(String name, Type type, String defaultValue, String category, String description, String... allowedValues) {
            this.name = name;
            this.type = type;
            this.defaultValue = defaultValue;
            this.category = category;
            this.description = description;
            this.allowedValues = Arrays.asList(allowedValues);
        }

        public boolean isRequired() {
            return defaultValue == null;
        }
    }

    private static final Map<String, Info> PARAMS = new LinkedHashMap<>();

    private static void add(String name, Type type, String defaultValue, String category, String description, String... allowedValues) {
        PARAMS.put(name, new Info(name, type, defaultValue, category, description, allowedValues));
    }

    private static String[] names(Object[] values) {
        String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            names[i] = values[i].toString();
        }
        return names;
    }

    static {
        // model
        add("model", Type.STRING, null, "model", "name of the class of the equations (module), e.g. NavierStokes");

        // run control
        add("continueComputation", Type.BOOLEAN, null, "run", "true = continue from the saved state (state.txt, We.txt)");
        add("steps", Type.INT, null, "run", "number of time steps of this run");
        add("residuum", Type.DOUBLE, "-1", "run", "computation stops when the residuum drops below this value");
        add("tEnd", Type.DOUBLE, "", "run", "physical time at which the computation stops");
        add("saveRate", Type.INT, "", "run", "results are saved every saveRate steps");
        add("timeSaveRate", Type.DOUBLE, "", "run", "results are saved every timeSaveRate of physical time");
        add("animation", Type.BOOLEAN, "false", "run", "save every saved result into the directory animation");
        add("threads", Type.INT, null, "run", "number of threads");

        // discretisation
        add("spatialMethod", Type.ENUM, null, "discretisation", "spatial discretisation",
                names(Mesh.SpatialMethodType.values()));
        add("order", Type.INT, "", "discretisation", "order of accuracy in space (1 = piecewise constant), "
                + "can be given element-wise in file order.txt");
        add("timeMethod", Type.ENUM, null, "discretisation", "time integration, the order is given by timeOrder",
                "BDF", "BDFIncompressible", "RK");
        add("timeOrder", Type.INT, "1", "discretisation", "order of the time integration (BDF: 1-2, RK: 2-4)");
        add("CFL", Type.DOUBLE, "", "discretisation", "CFL number, CFL <= 0 switches the adaptive CFL on "
                + "(either CFL or dt has to be given)");
        add("maxCFL", Type.DOUBLE, "1e4", "discretisation", "upper limit of the adaptive CFL (CFL <= 0)");
        add("dt", Type.DOUBLE, "", "discretisation", "fixed time step (either CFL or dt has to be given)");
        add("volumeQuardatureOrder", Type.INT, "", "discretisation", "order of the volume quadrature (default = order of the element)");
        add("faceQuardatureOrder", Type.INT, "", "discretisation", "order of the face quadrature (default = order of the element)");
        add("curvedBoundary", Type.BOOLEAN, "false", "discretisation", "curved boundary elements");

        // nonlinear and linear solver
        add("newtonIters", Type.INT, null, "solver", "maximal number of Newton (inner) iterations in one time step");
        add("newtonIterTol", Type.DOUBLE, "1e-4", "solver", "tolerance of the Newton iterations");
        add("linearSolver", Type.ENUM, null, "solver", "linear solver", names(LinearSolver.LinearSolverType.values()));
        add("preconditioner", Type.ENUM, null, "solver", "preconditioner of the linear solver",
                names(Preconditioner.PreconditionerType.values()));
        add("iterativeSolverTol", Type.DOUBLE, "1e-2", "solver", "relative tolerance of the iterative linear solver");

        // stabilisation
        add("dampTol", Type.DOUBLE, "", "stabilisation", "shock sensor tolerance for the artificial viscosity, "
                + "<= 0 switches it off (required by DG methods)");
        add("dampConst", Type.DOUBLE, "", "stabilisation", "constant artificial viscosity (required by DG methods)");
        add("dampInnerTol", Type.DOUBLE, "0", "stabilisation", "tolerance of the inner (equation-wise) damping");
        add("dampInnerCoef", Type.DOUBLE_ARRAY, "", "stabilisation", "coefficients of the inner damping (one or one per equation)");
        add("penalty", Type.DOUBLE, "0", "stabilisation", "interior penalty constant");
        add("beta0", Type.DOUBLE, "2", "stabilisation", "constant of the direct discontinuous Galerkin method");
        add("FVMlimiter", Type.ENUM, "none", "stabilisation", "reconstruction limiter for order = 1 "
                + "(none = first order, noLimiter = unlimited reconstruction)", "none", "noLimiter", "barth", "venka");

        // mesh
        add("meshScale", Type.DOUBLE, "1", "mesh", "the mesh is multiplied by meshScale / lRef");
        add("lRef", Type.DOUBLE, "1", "mesh", "reference length");
        add("scaleDims", Type.BOOLEAN_ARRAY, "true,true,true", "mesh", "which coordinates are scaled");
        add("domainTransformationObject", Type.STRING, "", "mesh", "class transforming the mesh (module)");

        // moving mesh
        add("movingMesh", Type.BOOLEAN, "false", "moving mesh", "ALE computation on a moving mesh");
        add("dynamicsModel", Type.STRING, "none", "moving mesh", "class of the dynamics of the bodies (module)");
        add("meshDeformationType", Type.STRING, "none", "moving mesh", "type of the mesh deformation");

        // monitoring, optimisation
        add("solutionMonitor", Type.STRING, "", "monitor", "class of the solution monitor (module)");
        add("solutionAverage", Type.BOOLEAN, "false", "monitor", "provide element averages to the equations");
        add("functionalOfInterest", Type.STRING, "", "monitor", "class of the functional (optimisation export)");

        // parallel mode
        add("parallelSolverType", Type.ENUM, "", "parallel", "parallel solver (required in parallel mode)", "ksp", "schwartz");
        add("parallelPreconditioner", Type.STRING, "", "parallel", "parallel preconditioner (required in parallel mode)");
        add("overlap", Type.INT, "", "parallel", "number of overlapping layers (required in parallel mode)");
        add("schwarzIters", Type.INT, "1", "parallel", "number of Schwarz iterations");
        add("schwarzTol", Type.DOUBLE, "1e-6", "parallel", "tolerance of the Schwarz iterations");
    }

    private ParameterRegistry() {
        throw new UnsupportedOperationException();
    }

    public static Map<String, Info> getAll() {
        return PARAMS;
    }

    /**
     * Checks the parameters: values of the known parameters and names which
     * are probably misspelled names of known parameters.
     *
     * @param props loaded parameters
     * @param parallelMode
     * @return list of warnings (empty if everything is fine)
     */
    public static List<String> validate(FlowProProperties props, boolean parallelMode) {
        List<String> warnings = new ArrayList<>();

        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("%")) {
                continue; // line commented out by % (java.util.Properties knows only # and !)
            }
            Info info = PARAMS.get(key);
            if (info == null) {
                String similar = findSimilar(key);
                if (similar != null) {
                    warnings.add("parameter '" + key + "' is not known to FlowPro core, did you mean '" + similar + "'?");
                }
                continue;
            }
            String value = stripComment(props.getProperty(key));
            if (value.isEmpty()) {
                continue; // e.g. "pIn = " in the template files
            }
            String error = checkValue(info, value);
            if (error != null) {
                warnings.add("parameter '" + key + "' = '" + value + "': " + error);
            }
        }

        for (Info info : PARAMS.values()) {
            if (info.isRequired() && stripComment(props.getProperty(info.name)).isEmpty()) {
                warnings.add("required parameter '" + info.name + "' is missing (" + info.description + ")");
            }
        }
        boolean hasCFL = !stripComment(props.getProperty("CFL")).isEmpty();
        boolean hasDt = !stripComment(props.getProperty("dt")).isEmpty();
        if (!hasCFL && !hasDt) {
            warnings.add("either parameter 'CFL' or 'dt' has to be defined");
        }
        if (hasCFL && hasDt) {
            warnings.add("both 'CFL' and 'dt' are defined, 'CFL' is used");
        }
        if (parallelMode) {
            for (String name : new String[]{"parallelSolverType", "parallelPreconditioner", "overlap"}) {
                if (!props.containsKey(name)) {
                    warnings.add("parameter '" + name + "' is required in the parallel mode");
                }
            }
        }
        return warnings;
    }

    static String stripComment(String str) {
        if (str == null) {
            return "";
        }
        str = str.split("%", 2)[0];
        str = str.split("#", 2)[0];
        return str.trim();
    }

    private static String checkValue(Info info, String value) {
        try {
            switch (info.type) {
                case INT:
                    Integer.parseInt(value);
                    break;
                case DOUBLE:
                    Double.parseDouble(value);
                    break;
                case BOOLEAN:
                    if (!isBoolean(value)) {
                        return "true or false expected";
                    }
                    break;
                case DOUBLE_ARRAY:
                    for (String str : value.split(",")) {
                        Double.parseDouble(str.trim());
                    }
                    break;
                case BOOLEAN_ARRAY:
                    for (String str : value.split(",")) {
                        if (!isBoolean(str)) {
                            return "comma separated list of true/false expected";
                        }
                    }
                    break;
                case ENUM:
                    for (String allowed : info.allowedValues) {
                        if (allowed.equalsIgnoreCase(value)) {
                            return null;
                        }
                    }
                    return "allowed values are " + info.allowedValues;
                default:
                    break;
            }
        } catch (NumberFormatException ex) {
            return (info.type == Type.INT ? "an integer" : "a number") + " expected";
        }
        return null;
    }

    private static boolean isBoolean(String str) {
        String s = str.trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("false") || s.equals("1") || s.equals("0")
                || s.equals("yes") || s.equals("no");
    }

    /**
     * @return name of a known parameter which differs from the given one only
     * by the case or by at most two letters, null if there is none
     */
    static String findSimilar(String key) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String name : PARAMS.keySet()) {
            if (name.equalsIgnoreCase(key)) {
                return name;
            }
            int distance = levenshtein(name.toLowerCase(Locale.ROOT), key.toLowerCase(Locale.ROOT));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = name;
            }
        }
        int maxDistance = key.length() <= 4 ? 1 : 2;
        return bestDistance <= maxDistance ? best : null;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }

    /**
     * Prints all the known parameters grouped by categories.
     */
    public static void printHelp() {
        String category = null;
        for (Info info : PARAMS.values()) {
            if (!info.category.equals(category)) {
                category = info.category;
                System.out.println();
                System.out.println("[" + category + "]");
            }
            String def = info.isRequired() ? "required" : (info.defaultValue.isEmpty() ? "optional" : "default " + info.defaultValue);
            System.out.printf("  %-26s %-8s %-16s %s%n", info.name, info.type.toString().toLowerCase(Locale.ROOT), def, info.description);
            if (!info.allowedValues.isEmpty()) {
                System.out.printf("  %-26s %-8s %-16s values: %s%n", "", "", "", info.allowedValues);
            }
        }
        System.out.println();
        System.out.println("Parameters of the equations (e.g. kappa, pOut, ...) are described in the corresponding module.");
    }
}
