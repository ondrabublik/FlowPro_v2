package flowpro.core.quadrature;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Quadrature rules indexed by their order k. For all element types the rule of
 * order k is exact for polynomials of degree 2k - 1 (Gauss-Legendre rule with k
 * points in each direction on lines, squares and hexahedra, rules of degree
 * 2k - 1 on triangles and tetrahedra, on triangles 2k if the rule of degree
 * 2k - 1 has negative weights). The default order is the order of the
 * element p + 1, i.e. degree 2p + 1: the mass matrix (degree 2p) is integrated
 * exactly and the nonlinear fluxes are slightly over-integrated.
 *
 * @author obublik
 */
public class QuadratureCentral implements Serializable {

    public Quadrature[] qPoint;
    public Quadrature[] qLine;
    public Quadrature[] qTriangle;
    public Quadrature[] qSquare;
    public Quadrature[] qTetra;
    public Quadrature[] qHexa;
    public Quadrature[] qPrism;
    public Quadrature[] q25DSquare;

    // reasons why a rule could not be loaded, key = name of the array + order
    private final Map<String, String> loadErrors = new HashMap<>();

    public QuadratureCentral() throws IOException {
        int orderMax = 10;

        // load Gaussian quadrature
        qPoint = new Quadrature[orderMax + 1];
        qLine = new Quadrature[orderMax + 1];
        qTriangle = new Quadrature[orderMax + 1];
        qSquare = new Quadrature[orderMax + 1];
        qTetra = new Quadrature[orderMax + 1];
        qHexa = new Quadrature[orderMax + 1];
        qPrism = new Quadrature[orderMax + 1];
        q25DSquare = new Quadrature[orderMax + 1];

        for (int order = 1; order <= orderMax; order++) {
            qPoint[order] = new QuadraturePoint();
            // the files of the simplex rules are named by the exact degree
            int simplexDegree = 2 * order - 1;
            try {
                qLine[order] = new QuadratureLine("gaussRules/gauss/" + order + ".txt");
            } catch (Exception e) {
                loadErrors.put("qLine" + order, e.toString());
            }
            try {
                // the rules of degree 3 and 7 have negative weights, the rule of the
                // next degree (positive weights) is used instead
                qTriangle[order] = new QuadratureTriangle("gaussRules/gaussTri/" + simplexDegree + ".txt");
                if (hasNegativeWeight(qTriangle[order])) {
                    qTriangle[order] = new QuadratureTriangle("gaussRules/gaussTri/" + (simplexDegree + 1) + ".txt");
                }
            } catch (Exception e) {
                loadErrors.put("qTriangle" + order, e.toString());
            }
            try {
                qSquare[order] = new QuadratureSquare("gaussRules/gauss/" + order + ".txt");
            } catch (Exception e) {
                loadErrors.put("qSquare" + order, e.toString());
            }
            try {
                qTetra[order] = new QuadratureTetra("gaussRules/gaussTetra/" + simplexDegree + ".txt");
            } catch (Exception e) {
                loadErrors.put("qTetra" + order, e.toString());
            }
            try {
                qHexa[order] = new QuadratureHexa("gaussRules/gauss/" + order + ".txt");
            } catch (Exception e) {
                loadErrors.put("qHexa" + order, e.toString());
            }
            try {
                qPrism[order] = new QuadraturePrism(qTriangle[order], qLine[order]);
            } catch (Exception e) {
                loadErrors.put("qPrism" + order, e.toString());
            }
            try {
                q25DSquare[order] = new QuadratureSquare25D(order);
            } catch (Exception e) {
                loadErrors.put("q25DSquare" + order, e.toString());
            }
        }
    }

    private static boolean hasNegativeWeight(Quadrature q) {
        for (double w : q.weights) {
            if (w < 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the rule of the given order, an exception with the reason when it
     * is not available (previously null, which failed later with
     * NullPointerException)
     */
    public Quadrature rule(Quadrature[] rules, int order, String name) {
        if (order < 1 || order >= rules.length || rules[order] == null) {
            String reason = loadErrors.get(name + order);
            throw new IllegalArgumentException("quadrature rule " + name + " of order " + order
                    + " is not available" + (reason == null ? " (supported orders 1 - " + (rules.length - 1) + ")" : ": " + reason)
                    + "; check the order of the elements or parameters volumeQuardatureOrder/faceQuardatureOrder");
        }
        return rules[order];
    }
}
