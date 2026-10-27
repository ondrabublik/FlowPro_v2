package flowpro.control;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * File parameters.txt as a list of lines. Lines "key = value % comment" can be
 * edited, all the other lines (comments, empty lines) are kept untouched, so
 * the file keeps its structure after saving.
 *
 * @author obublik
 */
public class ParameterFile {

    public static class Line {

        public String raw; // original text (for non-parameter lines)
        public String key; // null for comments and empty lines
        public String value;
        public String comment; // including the leading % or #, may be empty
        public boolean disabled; // parameter commented out by %
        public boolean modified; // unmodified lines are written exactly as they were read

        boolean isParameter() {
            return key != null;
        }

        String format() {
            if (key == null || (!modified && raw != null)) {
                return raw;
            }
            String text = key + " = " + value;
            if (!comment.isEmpty()) {
                text += "   " + comment;
            }
            return disabled ? "%" + text : text;
        }
    }

    public final File file;
    public final List<Line> lines = new ArrayList<>();

    public ParameterFile(File file) throws IOException {
        this.file = file;
        for (String raw : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            lines.add(parse(raw));
        }
    }

    static Line parse(String raw) {
        Line line = new Line();
        line.raw = raw;
        String text = raw.trim();
        boolean disabled = false;
        if (text.startsWith("%") && !text.startsWith("%%")) {
            // "%key = value" is a disabled parameter, "%%% section" is a comment
            String rest = text.substring(1).trim();
            int eq = rest.indexOf('=');
            if (eq > 0 && rest.substring(0, eq).trim().matches("[A-Za-z_][A-Za-z0-9_]*")) {
                text = rest;
                disabled = true;
            } else {
                return line;
            }
        }
        if (text.isEmpty() || text.startsWith("%") || text.startsWith("#")) {
            return line;
        }
        int eq = text.indexOf('=');
        if (eq <= 0) {
            return line;
        }
        String key = text.substring(0, eq).trim();
        if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return line;
        }
        String rest = text.substring(eq + 1);
        int c = indexOfComment(rest);
        line.key = key;
        line.value = (c >= 0 ? rest.substring(0, c) : rest).trim();
        line.comment = c >= 0 ? rest.substring(c).trim() : "";
        line.disabled = disabled;
        return line;
    }

    private static int indexOfComment(String s) {
        int p = s.indexOf('%');
        int h = s.indexOf('#');
        if (p < 0) {
            return h;
        }
        if (h < 0) {
            return p;
        }
        return Math.min(p, h);
    }

    public List<Line> parameters() {
        List<Line> params = new ArrayList<>();
        for (Line line : lines) {
            if (line.isParameter()) {
                params.add(line);
            }
        }
        return params;
    }

    public Line find(String key) {
        for (Line line : lines) {
            if (key.equals(line.key) && !line.disabled) {
                return line;
            }
        }
        for (Line line : lines) {
            if (key.equals(line.key)) {
                return line;
            }
        }
        return null;
    }

    public String get(String key) {
        Line line = find(key);
        return line != null && !line.disabled ? line.value : null;
    }

    /**
     * Sets (and enables) a parameter, a new line is appended if the parameter
     * does not exist.
     */
    public void set(String key, String value) {
        Line line = find(key);
        if (line == null) {
            line = new Line();
            line.key = key;
            line.comment = "";
            lines.add(line);
        }
        line.value = value;
        line.disabled = false;
        line.modified = true;
    }

    public void save() throws IOException {
        List<String> out = new ArrayList<>();
        for (Line line : lines) {
            out.add(line.format());
        }
        Files.write(file.toPath(), out, StandardCharsets.UTF_8);
    }
}
