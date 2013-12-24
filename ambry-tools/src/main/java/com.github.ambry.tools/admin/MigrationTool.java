package com.github.ambry.tools.admin;

import com.github.ambry.clustermap.ClusterMap;
import com.github.ambry.clustermap.ClusterMapManager;
import com.github.ambry.coordinator.AmbryCoordinator;
import com.github.ambry.coordinator.Coordinator;
import com.github.ambry.messageformat.BlobProperties;
import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.io.File;
import java.io.IOException;

/**
 * Tool to migration from a source to Ambry
 */
public class MigrationTool {

  public static void directoryWalk(String path ,
                                   String prefix,
                                   boolean ignorePrefix,
                                   Coordinator coordinator,
                                   FileWriter migrationLogger) {
    File root = new File( path );
    File[] list = root.listFiles();

    if (list == null) return;

    for ( File f : list ) {
      if ( f.isDirectory()) {
        if (ignorePrefix || f.getName().startsWith(prefix)) {
          directoryWalk(f.getAbsolutePath(), prefix, true, coordinator, migrationLogger);
        }
      }
      else {
        System.out.println( "File: " + f.getAbsoluteFile() );
        BlobProperties props = new BlobProperties(f.length(), "migration");
        byte[] usermetadata = new byte[1];
        FileInputStream stream = null;
        try {
          stream = new FileInputStream(f);
          long startMs = System.currentTimeMillis();
          String id = coordinator.putBlob(props, ByteBuffer.wrap(usermetadata), stream);
          System.out.println("Time taken to put " + (System.currentTimeMillis() - startMs));
          migrationLogger.write("blobId|" + id + "|source|" + f.getAbsolutePath() + "\n");
        }
        catch (FileNotFoundException e) {
          System.out.println("File not found path : " + f.getAbsolutePath() + " exception : " + e);
        }
        catch (IOException e) {
          System.out.println("IOException when writing to migration log " + e );
        }
        finally {
          try {
            if (stream != null)
              stream.close();
          }
          catch (Exception e) {
            System.out.println("Error while closing file stream " + e);
          }
        }
      }
    }
  }

  public static void main(String args[]) {
    FileWriter migrationLogger = null;
    try {
      OptionParser parser = new OptionParser();
      ArgumentAcceptingOptionSpec<String> rootDirectoryOpt =
              parser.accepts("rootDirectory", "The root folder from which all the files will be migrated")
                    .withRequiredArg()
                    .describedAs("root_directory")
                    .ofType(String.class);

      ArgumentAcceptingOptionSpec<String> folderPrefixInRootOpt =
              parser.accepts("folderPrefixInRoot", "The prefix of the folders in the root path that needs to be moved")
                    .withRequiredArg()
                    .describedAs("folder_prefix_in_root")
                    .ofType(String.class);

      ArgumentAcceptingOptionSpec<String> hardwareLayoutOpt =
              parser.accepts("hardwareLayout", "The path of the hardware layout file")
                    .withRequiredArg()
                    .describedAs("hardware_layout")
                    .ofType(String.class);

      ArgumentAcceptingOptionSpec<String> partitionLayoutOpt =
              parser.accepts("partitionLayout", "The path of the partition layout file")
                    .withRequiredArg()
                    .describedAs("partition_layout")
                    .ofType(String.class);

      ArgumentAcceptingOptionSpec<Boolean> verboseLoggingOpt =
              parser.accepts("enableVerboseLogging", "Enables verbose logging")
                    .withOptionalArg()
                    .describedAs("Enable verbose logging")
                    .ofType(Boolean.class)
                    .defaultsTo(false);

      OptionSet options = parser.parse(args);

      ArrayList<OptionSpec<?>> listOpt = new ArrayList<OptionSpec<?>>();
      listOpt.add(rootDirectoryOpt);
      listOpt.add(folderPrefixInRootOpt);
      listOpt.add(hardwareLayoutOpt);
      listOpt.add(partitionLayoutOpt);

      for(OptionSpec opt : listOpt) {
        if(!options.has(opt)) {
          System.err.println("Missing required argument \"" + opt + "\"");
          parser.printHelpOn(System.err);
          System.exit(1);
        }
      }

      String rootDirectory = options.valueOf(rootDirectoryOpt);
      String folderPrefixInRoot = options.valueOf(folderPrefixInRootOpt);
      String hardwareLayoutPath = options.valueOf(hardwareLayoutOpt);
      String partitionLayoutPath = options.valueOf(partitionLayoutOpt);
      ClusterMap map = new ClusterMapManager(hardwareLayoutPath, partitionLayoutPath);
      File logFile = new File(System.getProperty("user.dir"), "migrationlog");
      migrationLogger = new FileWriter(logFile);
      boolean enableVerboseLogging = options.has(verboseLoggingOpt) ? true : false;
      if (enableVerboseLogging)
        System.out.println("Enabled verbose logging");
      Coordinator coordinator = new AmbryCoordinator(map);
      directoryWalk(rootDirectory, folderPrefixInRoot, false, coordinator, migrationLogger);
    }
    catch (Exception e) {
      System.err.println("Error on exit " + e);
    }
    finally {
      if (migrationLogger != null) {
        try {
          migrationLogger.close();
        }
        catch (Exception e) {
          System.out.println("Error when closing the writer");
        }
      }
    }
  }
}