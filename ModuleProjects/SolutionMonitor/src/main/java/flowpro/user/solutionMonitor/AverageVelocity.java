
package flowpro.user.solutionMonitor;

import flowpro.api.ElementData;
import flowpro.api.FlowProProperties;
import flowpro.api.SolutionMonitor;
import java.io.IOException;

/**
 *
 * @author obublik
 */
public class AverageVelocity implements SolutionMonitor{

    int dim;
    
    @Override
    public void init(FlowProProperties props) throws IOException{
        dim = props.getInt("dimension");
    }
    
    @Override
    public int getNumberOfMonitoredValues(){
        return dim;
    }
    
    @Override
    public double[] insideValue(double[] W, double[] dW, ElementData elemData){
        double[] V = new double[dim];
        for(int i = 0; i < dim; i++){
            V[i] = W[i+1]/W[0];
        }
        return V;
    }
    
    @Override
    public double[] boundaryValue(double[] W, double[] dW, double[] n, int TT, ElementData elemData){
        return null;
    }
    
    @Override
    public void combineMonitoredValues(double[] solutionMonitorValues){
    }
}
