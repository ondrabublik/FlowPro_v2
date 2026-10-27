package flowpro.user.functional;

import flowpro.api.ElementData;
import flowpro.api.FlowProProperties;
import flowpro.api.Functional;
import java.io.IOException;

/**
 *
 * @author obublik
 */
public class LiftCoefficient implements Functional {

    double kapa;
    
    @Override
    public int getN(){
        return 1;
    }
    
    @Override
    public void init(FlowProProperties props) throws IOException {
         this.kapa = props.getDouble("kappa");
    }

    @Override
    public double[] insideValue(double[] W, double[] dW, ElementData elemData) {
        return new double[]{0};
    }

    @Override
    public double[] boundaryValue(double[] W, double[] dW, double[] n, int TT, ElementData elemData) {
        if (TT == -1) {
            return new double[]{pressure(W) * n[1]};
        } else {
            return new double[]{0};
        }
    }

    public double pressure(double[] W) {
        double momentum2 = .0;
        int dim = 2;
        for (int d = 0; d < dim; ++d) {
            momentum2 += W[d + 1] * W[d + 1];
        }

        double p = (kapa - 1) * (W[dim + 1] - momentum2 / (2 * W[0]));
        return p;
    }
    
    @Override
    public double combineFunctionals(double[] functionals){
        return functionals[0];
    }
}
