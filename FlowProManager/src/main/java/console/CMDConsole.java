package console;

import java.io.*;

/**
 *
 * @author obublik
 */
public class CMDConsole {

    public CMDConsole(){
        
    }
    
    public void start() throws IOException {
        initPage();

        // creates a reader object
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            // read line from the user input
            System.out.print(">>: ");
            String str = br.readLine();
            String[] tokens = str.split(" ");

            switch (tokens[0]) {
                case ("args"):
                    args(tokens);
                    break;
                    
                case ("param"):
                    //TextEditor textEdit = new TextEditor();
                    //textEdit.readInFile("param.txt");
                    break;    
                    
                case ("run"):
                    run(tokens);
                    break;

                case ("close"):
                    vspace(50);
                    text("Program dgc closed!");
                    vspace(3);
                    System.exit(0);
                    break;

                case ("clc"):
                    vspace(100);
                    break;    
                    
                default:
                    System.out.println("Unrecognized option: " + str);
                    break;
            }
            vspace(1);
        }
    }
    
    /*args   save geometry and simulation name
        args(geometry, simulation) saves geometry and simulation name
        (parametrs for the JAVA application) into a text file.

        args(geometry) sets simulation as default.

        args prints current geometry and simulation name.*/
    public void args(String[] str) throws IOException{
        if(str.length == 1){
            String[] s = readFileLine("args.txt");
            System.out.println("Geometry name: " + s[0]);
            System.out.println("Simulation name: " + s[1]);
        } else {
            System.out.println("Setting simulation to default.");
            String simulation = "default"; 
        }
        
        /*path = sprintf('../simulations/%s/', geometry);
        if ~exist(path, 'dir')
            error('Geometry %s does not exist.', geometry);
        end
        path = strcat(path, simulation);
        if ~exist(path, 'dir')
            fprintf(1, 'Simulation %s/%s does not exist, ', geometry, simulation);
            fprintf(1, 'the simulation will be created after running the command "param".\n');
        end

        fout = fopen('../args.txt', 'w');
        fprintf(fout, '%s %s\n', geometry, simulation);
        fclose(fout);*/
    }
    
    public void run(String[] str) throws IOException{
        if(str.length == 1){
            runProcess("java -Xmx8000m -jar DGFEM2D.jar local");
        } else {
            runProcess("java -Xmx8000m -jar DGFEM2D.jar parallel master " + str[1]);
        }
    }
    
    public String[] readFileLine(String fileName) throws IOException{
        BufferedReader reader = new BufferedReader(new FileReader(fileName));
        String[] tokens = reader.readLine().split(" ");
        return tokens;
    }
    
    public void initPage() {
        vspace(50);
        System.out.println("**************************************");
        System.out.println("*        Console version 1.0         *");
        System.out.println("*              DGFEM2D               *");
        System.out.println("**************************************");
        vspace(3);
    }

    public void text(String str) {
        System.out.println("**************************************");
        System.out.println("*                                    *");
        System.out.println("*           " + str);
        System.out.println("*                                    *");
        System.out.println("**************************************");
    }

    public void vspace(int n) {
        for (int i = 0; i < n; i++) {
            System.out.println();
        }
    }
    
    private void printLines(String name, InputStream ins) throws IOException {
        String line;
        BufferedReader in = new BufferedReader(new InputStreamReader(ins));
        while ((line = in.readLine()) != null) {
            System.out.println(name + line);
        }
    }
    
    private void runProcess(String command) throws IOException {
        try {
            Process pro = Runtime.getRuntime().exec(command);
            System.out.println(command + ":");
            printLines("  stdout: ", pro.getInputStream());
            printLines("  stderr: ", pro.getErrorStream());
            pro.waitFor();
            System.out.println(command + " exitValue() " + pro.exitValue());
        } catch (InterruptedException ex) {
            System.err.println(ex);
        }
    }
}
