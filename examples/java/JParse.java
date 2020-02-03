import java.io.*;
import java.util.*;
import javagrammar.*;

/**
 * A test harness for parsing Java files from 
 * the command line.
 */
public class JParse {

   static public void main(String args[]) {
      List<File> failures = new ArrayList<File>();
      List<File> successes = new ArrayList<File>();    
      boolean failureOccurred = false;
      JavaParser parser;
      if (args.length == 0) {
        usage();
      }
      List<File> files = new ArrayList<File>();
      for (String arg : args) {
          File file = new File(arg);
          if (!file.exists()) {
              System.err.println("File " + file + " does not exist.");
              continue;
          }
	   addFilesRecursively(files, file);
      }
      long startTime = System.currentTimeMillis();
      for (File file : files) {
          try {
             // A bit screwball, we'll dump the tree if there is only one arg. :-)
              parseFile(file, files.size() == 1);
          } 
          catch (Exception e) {
              System.err.println("Error processing file: " + file);
              e.printStackTrace();
	      failures.add(file);
              continue;
          }
          System.out.println(file.getName()  + " parsed successfully.");
          successes.add(file);
       }
       for (File file : failures) {
           System.out.println("Parse failed on: " + file);
       }
       System.out.println("\nParsed " + successes.size() + " files successfully");
       System.out.println("Failed on " + failures.size() + " files.");
       System.out.println("\nDuration: " + (System.currentTimeMillis() - startTime) + " milliseconds");
    }
      
   static public void parseFile(File file, boolean dumpTree) throws IOException, ParseException {
       FileReader fr = new FileReader(file);
       JavaParser parser = new JavaParser(fr);
       parser.setInputSource(file.toString());
       Node root=parser.CompilationUnit();
       if (dumpTree) {
           Nodes.dump(root, "");
       }
   }

   static public void addFilesRecursively(List<File> files, File file) {
       if (file.isDirectory()) {
           for (File f : file.listFiles()) {
	         addFilesRecursively(files, f);
	   }
       }
       else if (file.getName().endsWith("java") && !file.getName().endsWith("-info.java")) {
           files.add(file);
       }
   }
   
   
   static public void usage() {
       System.out.println("Usage: java JParse <sourcefiles or directories>");
       System.out.println("If you just pass it one java source file, it dumps the AST");
       System.exit(-1);
   }
}
