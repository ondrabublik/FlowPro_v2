package litempi;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;

/**
 * ObjectInputStream which deserialises only the classes of FlowPro and of the
 * Java runtime. The data come from the network, an unrestricted Java
 * deserialisation would allow anybody who can connect to the port to execute
 * code (deserialisation gadgets).
 *
 * The allow-list can be changed by the system property flowpro.serialFilter
 * (syntax of java.io.ObjectInputFilter.Config.createFilter).
 *
 * @author obublik
 */
public class SafeObjectInputStream extends ObjectInputStream {

    // no maxdepth: the mesh is a deeply linked graph of elements (that is why
    // FlowPro runs with a large stack, -Xss10m)
    public static final String DEFAULT_FILTER = "flowpro.**;litempi.**;java.**;"
            + "org.apache.commons.lang3.tuple.*;org.json.*;!*";

    private static final ObjectInputFilter FILTER = ObjectInputFilter.Config.createFilter(
            System.getProperty("flowpro.serialFilter", DEFAULT_FILTER));

    public SafeObjectInputStream(InputStream in) throws IOException {
        super(in);
        setObjectInputFilter(FILTER);
    }
}
