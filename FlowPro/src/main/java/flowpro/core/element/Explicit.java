/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package flowpro.core.element;

/**
 *
 * @author obublik
 */
abstract public class Explicit extends TimeIntegrationElement {
    
    Explicit() {
        super();
    }
    
    abstract public int getNumberOfSteps();
    
    abstract public void computeExplicitStep(int step, double dt);

    /**
     * The stages are numbered 1, ..., getNumberOfSteps(). In the odd stages
     * the residual is evaluated, in the even ones the new values are copied
     * into W.
     *
     * @param step
     * @return true if the residual (and thus the artificial viscosity) is
     * evaluated in the stage
     */
    public boolean isResidualStage(int step) {
        return step % 2 == 1;
    }
    
    public boolean isImplicit(){
        return false;
    }
    
    public String getLocalSolverType(){
        return "localexplicit";
    }
}
