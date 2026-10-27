
package flowpro.user.solutionMonitor;

import flowpro.api.ElementData;
import flowpro.api.FlowProProperties;
import flowpro.api.SolutionMonitor;
import java.io.IOException;

/**
 *
 * @author obublik
 */
public class SliceVelocityAverages implements SolutionMonitor{

    int dim;
    int nSlice;
    
    @Override
    public void init(FlowProProperties props) throws IOException{
        dim = props.getInt("dimension");
        nSlice = 20;
    }
    
    @Override
    public int getNumberOfMonitoredValues(){
        return nSlice;
    }
    
    @Override
    public double[] insideValue(double[] W, double[] dW, ElementData elemData){
        double[] V = new double[nSlice];
        int i = (int)((nSlice-1)*elemData.currentX[1]);
        V[i] = W[1]/W[0];
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
