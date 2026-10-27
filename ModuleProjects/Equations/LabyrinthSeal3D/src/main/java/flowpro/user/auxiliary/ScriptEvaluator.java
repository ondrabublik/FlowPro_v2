package flowpro.user.auxiliary;

import javax.script.ScriptEngineManager;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

/**
 *
 * @author obublik
 */
public class ScriptEvaluator {
    
    public ScriptEngine engine;
    
    public ScriptEvaluator(){
        ScriptEngineManager mgr = new ScriptEngineManager();
        this.engine = mgr.getEngineByName("JavaScript");
    }
    
    public double eval(String expresion, double t) throws ScriptException {
        engine.put("t", t);
        return (double)engine.eval(expresion);
    }
}
