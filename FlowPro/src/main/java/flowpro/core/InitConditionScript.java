package flowpro.core;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import javax.script.ScriptException;

/**
 * Evaluates the initial condition defined by a JavaScript function
 * <pre>
 * function init(x) { return [rho, rho*u, rho*v, e]; }
 * </pre>
 * The function is evaluated at the centre of each element. It may return an
 * array of numbers or a string with numbers separated by spaces or commas.
 *
 * @author obublik
 */
public class InitConditionScript {

    private double[][] initW = null;

    InitConditionScript(double[][] PXY, int[][] TP, int nEqs, String scriptLocation) throws FileNotFoundException, ScriptException, NoSuchMethodException {
        File scriptFile = new File(scriptLocation);
        if (!scriptFile.isFile()) {
            throw new FileNotFoundException("file " + scriptLocation + " does not exist");
        }

        double[][] init = new double[TP.length][nEqs];

        try (Context context = Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build()) {
            context.eval(Source.newBuilder("js", scriptFile).build());

            Value jsFunction = context.getBindings("js").getMember("init");
            if (jsFunction == null || !jsFunction.canExecute()) {
                throw new NoSuchMethodException("function init(x) is not defined in " + scriptLocation);
            }

            int dim = PXY[0].length;
            for (int i = 0; i < TP.length; i++) {
                Value x = context.eval("js", "[]");
                int m = TP[i].length;
                for (int d = 0; d < dim; d++) {
                    double xd = 0;
                    for (int j = 0; j < m; j++) {
                        xd += PXY[TP[i][j]][d] / m;
                    }
                    x.setArrayElement(d, xd);
                }
                init[i] = toDoubleArray(jsFunction.execute(x), nEqs, i);
            }
        } catch (PolyglotException ex) {
            throw new ScriptException("error in script " + scriptLocation + ": " + ex.getMessage());
        } catch (IOException ex) {
            throw new FileNotFoundException("file " + scriptLocation + " cannot be read: " + ex.getMessage());
        }
        initW = init;
    }

    private static double[] toDoubleArray(Value result, int nEqs, int elem) throws ScriptException {
        double[] w = new double[nEqs];
        if (result.hasArrayElements()) {
            if (result.getArraySize() < nEqs) {
                throw new ScriptException("function init returned " + result.getArraySize()
                        + " values instead of " + nEqs + " (element " + elem + ")");
            }
            for (int k = 0; k < nEqs; k++) {
                w[k] = result.getArrayElement(k).asDouble();
            }
        } else {
            String[] ls = result.toString().trim().split("[\\s,]+");
            if (ls.length < nEqs) {
                throw new ScriptException("function init returned " + ls.length
                        + " values instead of " + nEqs + " (element " + elem + ")");
            }
            for (int k = 0; k < nEqs; k++) {
                w[k] = Double.parseDouble(ls[k]);
            }
        }
        return w;
    }

    public double[][] getInitW() {
        return initW;
    }
}
