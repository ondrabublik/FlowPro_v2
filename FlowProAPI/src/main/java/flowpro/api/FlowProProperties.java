package flowpro.api;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 *
 * @author ales
 */
public class FlowProProperties extends Properties {

	private String msg = "";

	/**
	 * values given on the command line (java -jar FlowPro.jar run -p key=value),
	 * they replace the values from the file overriddenFile
	 */
	private static final Map<String, String> OVERRIDES = new LinkedHashMap<>();
	private static String overriddenFile = null;

	/**
	 * Registers values which replace the values loaded from the given file
	 * (typically parameters.txt). All the objects which load the file (solver,
	 * equation, dynamics, ...) then see the same values.
	 */
	public static synchronized void setOverrides(String propertiesFilePath, Map<String, String> overrides) {
		overriddenFile = canonical(propertiesFilePath);
		OVERRIDES.clear();
		OVERRIDES.putAll(overrides);
	}

	public static synchronized Map<String, String> getOverrides() {
		return Collections.unmodifiableMap(new LinkedHashMap<>(OVERRIDES));
	}

	private static String canonical(String path) {
		try {
			return new File(path).getCanonicalPath();
		} catch (IOException ex) {
			return new File(path).getAbsolutePath();
		}
	}

	public void load(String propertiesFilePath) throws IOException {
		msg = "file " + propertiesFilePath + " has a wrong format: ";

		try (FileInputStream in = new FileInputStream(propertiesFilePath)) {
			super.load(in);
		}
		synchronized (FlowProProperties.class) {
			if (!OVERRIDES.isEmpty() && canonical(propertiesFilePath).equals(overriddenFile)) {
				putAll(OVERRIDES);
			}
		}
	}

    public String getString(String propName) throws IOException {
        try {
            return loseComments(super.getProperty(propName));
        } catch (NullPointerException ex) {
            throw new IOException(msg + "variable " + propName + " not found", ex);
        }
    }

    public int getInt(String propName) throws IOException {
        try {
            return Integer.parseInt(loseComments(super.getProperty(propName)));
        } catch (NullPointerException ex) {
            throw new IOException(msg + "variable " + propName + " not found", ex);
        } catch (NumberFormatException ex) {
            throw new IOException(msg + "variable " + propName + " does not contain an integer", ex);
        }
    }

    public long getLong(String propName) throws IOException {
        try {
            return Long.parseLong(loseComments(super.getProperty(propName)));
        } catch (NullPointerException ex) {
            throw new IOException(msg + "variable " + propName + " not found", ex);
        } catch (NumberFormatException ex) {
            throw new IOException(msg + "variable " + propName + " does not contain an integer", ex);
        }
    }

    public double getDouble(String propName) throws IOException {
        try {
            return Double.parseDouble(loseComments(super.getProperty(propName)));
        } catch (NullPointerException ex) {
            throw new IOException(msg + "variable " + propName + " not found", ex);
        } catch (NumberFormatException ex) {
            throw new IOException(msg + "variable " + propName + " does not contain a double", ex);
        }
    }

    public boolean getBoolean(String propName) throws IOException {
        try {
            return parseBoolean(loseComments(super.getProperty(propName)));
        } catch (NullPointerException ex) {
            throw new IOException(msg + "variable " + propName + " not found", ex);
        } catch (NumberFormatException ex) {
            throw new IOException(msg + "variable " + propName + " does not contain a boolean (true/false)", ex);
        }
    }

    /**
     * Unlike Boolean.parseBoolean, a misspelled value (e.g. "ture") is not
     * silently treated as false.
     */
    private static boolean parseBoolean(String str) {
        switch (str.trim().toLowerCase()) {
            case "true":
            case "1":
            case "yes":
                return true;
            case "false":
            case "0":
            case "no":
                return false;
            default:
                throw new NumberFormatException("not a boolean: " + str);
        }
    }    

    public double[] getDoubleArray(String propName) throws IOException {
        try {
            return string2doubleArray(loseComments(super.getProperty(propName)));
        } catch (NullPointerException ex) {
            throw new IOException(msg + "variable " + propName + " not found", ex);
        } catch (NumberFormatException ex) {
            throw new IOException(msg + "variable " + propName + " does not contain a double", ex);
        }
    }
    
    public boolean[] getBooleanArray(String propName) throws IOException {
        try {
            return string2booleanArray(loseComments(super.getProperty(propName)));
        } catch (NullPointerException ex) {
            throw new IOException(msg + "variable " + propName + " not found", ex);
        } catch (NumberFormatException ex) {
            throw new IOException(msg + "variable " + propName + " does not contain a double", ex);
        }
    }
    
    public String[] getStringArray(String propName) throws IOException {
        try {
            String str = (loseComments(super.getProperty(propName)));
            return str.split(",");
        } catch (NullPointerException ex) {
            throw new IOException(msg + "variable " + propName + " not found", ex);
        } catch (NumberFormatException ex) {
            throw new IOException(msg + "variable " + propName + " does not contain a double", ex);
        }
    }
	
	private String loseComments(String str) {
        str = str.split("%", 2)[0];
        str = str.split("#", 2)[0];
        str = str.trim();
        //str = str.split(";", 2)[0];

        return str;
    }

    private double[] string2doubleArray(String str) {
        String[] stringArray = str.split(",");
        double[] d = new double[stringArray.length];
        for (int i = 0; i < stringArray.length; i++) {
            String numberAsString = stringArray[i];
            d[i] = Double.parseDouble(numberAsString.trim());
        }
        return d;
    }
    
    private boolean[] string2booleanArray(String str) {
        String[] stringArray = str.split(",");
        boolean[] d = new boolean[stringArray.length];
        for (int i = 0; i < stringArray.length; i++) {
            String booleanAsString = stringArray[i];
            d[i] = parseBoolean(booleanAsString);
        }
        return d;
    }
}
