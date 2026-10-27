/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package console;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import javax.swing.JTextArea;

/**
 *
 * @author obublik
 */
public class Commands {

    private final JTextArea consoleTextPane;

    Commands(JTextArea consoleTextPane) {
        this.consoleTextPane = consoleTextPane;
    }

    public void command(String comm, String[] arguments) throws IOException {
        switch (comm) {
            case ("args"):
                args(arguments);
                break;

            case ("param"):
                //TextEditor textEdit = new TextEditor();
                //textEdit.readInFile("param.txt");
                break;

            case ("run"):
                run(arguments);
                break;

            case ("exit"):
            case ("close"):
                System.exit(0);
                break;

            case ("clc"):
                //vspace(100);
                break;

            default:
                consoleTextPane.append(System.lineSeparator() + "Unrecognized command: " + comm);
                break;
        }

    }

    public void args(String[] str) throws IOException {
        if (str == null) {
            String[] s = readFileLine("args.txt");
            consoleTextPane.append(System.lineSeparator() + "Geometry name: " + s[0]);
            consoleTextPane.append(System.lineSeparator() + "Simulation name: " + s[1]);
        } else {
            if (str.length == 1) {
                consoleTextPane.append(System.lineSeparator() + "Setting simulation to default.");
                FileWriter fw = new FileWriter("args.txt");
                try (BufferedWriter out = new BufferedWriter(fw)) {
                    String radka = str[0] + " " + "default";
                    out.write(radka);
                    out.newLine();
                    out.close();
                }
            }
            else if (str.length == 2) {
                FileWriter fw = new FileWriter("args.txt");
                try (BufferedWriter out = new BufferedWriter(fw)) {
                    String radka = str[0] + " " + str[1];
                    out.write(radka);
                    out.newLine();
                    out.close();
                }
            } else {
                consoleTextPane.append(System.lineSeparator() + "Too much input arguments!");
            }
        }
    }

    public void run(String[] str) throws IOException {
        if (str == null) {
            runProcess("java -Xmx8000m -jar DGFEM2D.jar local");
        } else {
            runProcess("java -Xmx8000m -jar DGFEM2D.jar parallel master " + str[0]);
        }
    }

    private void runProcess(String command) throws IOException {
        try {
            consoleTextPane.append(System.lineSeparator() + command + ":");
            Process pro = Runtime.getRuntime().exec(command);
            //printLines("  stdout: ", pro.getInputStream());
            //printLines("  stderr: ", pro.getErrorStream());
            pro.waitFor();
            consoleTextPane.append(System.lineSeparator() + command + " exitValue() " + pro.exitValue());
        } catch (InterruptedException ex) {
            consoleTextPane.append(System.lineSeparator() + ex);
        }
    }

    public String[] readFileLine(String fileName) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(fileName));
        String[] tokens = reader.readLine().split(" ");
        return tokens;
    }
}
