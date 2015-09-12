package com.github.ambry.tools.admin;

import com.codahale.metrics.MetricRegistry;
import com.github.ambry.clustermap.ClusterMap;
import com.github.ambry.clustermap.ClusterMapManager;
import com.github.ambry.clustermap.ReplicaId;
import com.github.ambry.commons.BlobId;
import com.github.ambry.commons.ServerErrorCode;
import com.github.ambry.config.ClusterMapConfig;
import com.github.ambry.config.ConnectionPoolConfig;
import com.github.ambry.config.SSLConfig;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.messageformat.BlobOutput;
import com.github.ambry.messageformat.BlobProperties;
import com.github.ambry.messageformat.MessageFormatException;
import com.github.ambry.messageformat.MessageFormatFlags;
import com.github.ambry.messageformat.MessageFormatRecord;
import com.github.ambry.network.BlockingChannelConnectionPool;
import com.github.ambry.network.ChannelOutput;
import com.github.ambry.network.ConnectedChannel;
import com.github.ambry.network.ConnectionPool;
import com.github.ambry.network.ConnectionPoolTimeoutException;
import com.github.ambry.network.Port;
import com.github.ambry.protocol.GetOptions;
import com.github.ambry.protocol.GetRequest;
import com.github.ambry.protocol.GetResponse;
import com.github.ambry.protocol.PartitionRequestInfo;
import com.github.ambry.tools.util.ToolUtil;
import com.github.ambry.utils.Utils;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;


/**
 * Tool to perform blob operations directly with the server for a blobid
 * Features supported so far are:
 * Get blob (in other words deserialize blob) for a given blobid from all replicas
 * Get blob (in other words deserialize blob) for a given blobid from replicas for a datacenter
 * Get blob (in other words deserialize blob) for a given blobid for a specific replica
 *
 */
public class BlobValidator {
  ConnectionPool connectionPool;
  ArrayList<String> sslEnabledDatacentersList;
  Map<String, Exception> invalidBlobs;

  public BlobValidator(ConnectionPool connectionPool, ArrayList<String> sslEnabledDatacentersList) {
    this.connectionPool = connectionPool;
    this.sslEnabledDatacentersList = sslEnabledDatacentersList;
    invalidBlobs = new HashMap<String, Exception>();
  }

  public static void main(String args[]) {
    try {
      OptionParser parser = new OptionParser();

      ArgumentAcceptingOptionSpec<String> hardwareLayoutOpt =
          parser.accepts("hardwareLayout", "The path of the hardware layout file").withRequiredArg()
              .describedAs("hardware_layout").ofType(String.class);

      ArgumentAcceptingOptionSpec<String> partitionLayoutOpt =
          parser.accepts("partitionLayout", "The path of the partition layout file").withRequiredArg()
              .describedAs("partition_layout").ofType(String.class);

      ArgumentAcceptingOptionSpec<String> typeOfOperationOpt = parser.accepts("typeOfOperation",
          "The type of operation to execute - VALIDATE_BLOB_ON_REPLICA/"
              + "/VALIDATE_BLOB_ON_DATACENTER/VALIDATE_BLOB_ON_ALL_REPLICASS").withRequiredArg()
          .describedAs("The type of file").ofType(String.class).defaultsTo("GET");

      ArgumentAcceptingOptionSpec<String> ambryBlobIdListOpt =
          parser.accepts("blobIds", "Comma separated blobIds to execute get on").withRequiredArg()
              .describedAs("Blob Ids").ofType(String.class);

      ArgumentAcceptingOptionSpec<String> replicaHostOpt =
          parser.accepts("replicaHost", "The replica host to execute get on").withRequiredArg()
              .describedAs("The host name").defaultsTo("localhost").ofType(String.class);

      ArgumentAcceptingOptionSpec<String> replicaPortOpt =
          parser.accepts("replicaPort", "The replica port to execute get on").withRequiredArg()
              .describedAs("The host name").defaultsTo("15088").ofType(String.class);

      ArgumentAcceptingOptionSpec<String> datacenterOpt =
          parser.accepts("datacenter", "Datacenter for which the replicas should be chosen from").withRequiredArg()
              .describedAs("The file name with absolute path").ofType(String.class);

      ArgumentAcceptingOptionSpec<String> expiredBlobsOpt =
          parser.accepts("includeExpiredBlob", "Included expired blobs too").withRequiredArg()
              .describedAs("Whether to include expired blobs while querying or not").defaultsTo("false")
              .ofType(String.class);

      ArgumentAcceptingOptionSpec<String> sslEnabledDatacentersOpt =
          parser.accepts("sslEnabledDatacenters", "SSL enabled data centers").withOptionalArg()
              .describedAs("The data centers that needs SSL to communicate").defaultsTo("").ofType(String.class);

      ArgumentAcceptingOptionSpec<String> sslKeystorePathOpt =
          parser.accepts("sslKeystorePath", "SSL key store path").withOptionalArg()
              .describedAs("The file path of SSL key store").defaultsTo("").ofType(String.class);

      ArgumentAcceptingOptionSpec<String> sslTruststorePathOpt =
          parser.accepts("sslTruststorePath", "SSL trust store path").withOptionalArg()
              .describedAs("The file path of SSL trust store").defaultsTo("").ofType(String.class);

      ArgumentAcceptingOptionSpec<String> sslKeystorePasswordOpt =
          parser.accepts("sslKeystorePassword", "SSL key store password").withOptionalArg()
              .describedAs("The password of SSL key store").defaultsTo("").ofType(String.class);

      ArgumentAcceptingOptionSpec<String> sslTruststorePasswordOpt =
          parser.accepts("sslTruststorePassword", "SSL trust store password").withOptionalArg()
              .describedAs("The password of SSL trust store").defaultsTo("").ofType(String.class);

      ArgumentAcceptingOptionSpec<String> verboseOpt =
          parser.accepts("verbose", "Verbosity").withRequiredArg().describedAs("Verbosity").defaultsTo("false")
              .ofType(String.class);

      OptionSet options = parser.parse(args);

      ArrayList<OptionSpec<?>> listOpt = new ArrayList<OptionSpec<?>>();
      listOpt.add(hardwareLayoutOpt);
      listOpt.add(partitionLayoutOpt);
      listOpt.add(typeOfOperationOpt);
      listOpt.add(ambryBlobIdListOpt);
      for (OptionSpec opt : listOpt) {
        if (!options.has(opt)) {
          System.err.println("Missing required argument \"" + opt + "\"");
          parser.printHelpOn(System.err);
          System.out.println("BlobValidator --hardwareLayout hl --partitionLayout pl --typeOfOperation " +
              "/VALIDATE_BLOB_ON_REPLICA/VALIDATE_BLOB_ON_DATACENTER/VALIDATE_BLOB_ON_ALL_REPLICAS/" +
              " --blobIds blobId --datacenter datacenter --replicaHost replicaHost " +
              "--replicaPort replicaPort --includeExpiredBlob true/false");
          System.exit(1);
        }
      }

      ToolUtil.sslOptsCheck(options, parser, sslEnabledDatacentersOpt, sslKeystorePathOpt, sslTruststorePathOpt,
          sslKeystorePasswordOpt, sslTruststorePasswordOpt);
      Properties sslProperties = ToolUtil
          .createSSLProperties(options.valueOf(sslEnabledDatacentersOpt), options.valueOf(sslKeystorePathOpt),
              options.valueOf(sslKeystorePasswordOpt), options.valueOf(sslTruststorePathOpt),
              options.valueOf(sslTruststorePasswordOpt));
      Properties connectionPoolProperties = ToolUtil.createConnectionPoolProperties();
      SSLConfig sslConfig = new SSLConfig(new VerifiableProperties(sslProperties));
      ConnectionPoolConfig connectionPoolConfig =
          new ConnectionPoolConfig(new VerifiableProperties(connectionPoolProperties));
      ConnectionPool connectionPool =
          new BlockingChannelConnectionPool(connectionPoolConfig, sslConfig, new MetricRegistry());

      boolean verbose = Boolean.parseBoolean(options.valueOf(verboseOpt));
      String hardwareLayoutPath = options.valueOf(hardwareLayoutOpt);
      String partitionLayoutPath = options.valueOf(partitionLayoutOpt);
      if (verbose) {
        System.out.println("Hardware layout and partition layout parsed");
      }
      ClusterMap map = new ClusterMapManager(hardwareLayoutPath, partitionLayoutPath,
          new ClusterMapConfig(new VerifiableProperties(new Properties())));

      String blobIdListStr = options.valueOf(ambryBlobIdListOpt);
      ArrayList<String> blobList = new ArrayList<String>();
      if (blobIdListStr.contains(",")) {
        String[] blobArray = blobIdListStr.split(",");
        blobList.addAll(Arrays.asList(blobArray));
      } else {
        blobList.add(blobIdListStr);
      }
      if (verbose) {
        System.out.println("Blob Id " + blobList);
      }
      String datacenter = options.valueOf(datacenterOpt);
      if (verbose) {
        System.out.println("Datacenter " + datacenter);
      }
      String typeOfOperation = options.valueOf(typeOfOperationOpt);
      if (verbose) {
        System.out.println("Type of Operation " + typeOfOperation);
      }
      String replicaHost = options.valueOf(replicaHostOpt);
      if (verbose) {
        System.out.println("ReplciaHost " + replicaHost);
      }
      ;
      boolean expiredBlobs = Boolean.parseBoolean(options.valueOf(expiredBlobsOpt));
      if (verbose) {
        System.out.println("Exp blobs " + expiredBlobs);
      }
      int replicaPort = Integer.parseInt(options.valueOf(replicaPortOpt));
      if (verbose) {
        System.out.println("ReplicPort " + replicaPort);
      }

      String sslEnabledDatacenters = options.valueOf(sslEnabledDatacentersOpt);
      ArrayList<String> sslEnabledDatacentersList = Utils.splitString(sslEnabledDatacenters, ",");
      BlobValidator blobValidator = new BlobValidator(connectionPool, sslEnabledDatacentersList);

      ArrayList<BlobId> blobIdList = blobValidator.generateBlobId(blobList, map);
      if (typeOfOperation.equalsIgnoreCase("VALIDATE_BLOB_ON_REPLICA")) {
        blobValidator.validate(new String[]{replicaHost});
        blobValidator.validateBlobOnReplica(blobIdList, map, replicaHost, replicaPort, expiredBlobs);
      } else if (typeOfOperation.equalsIgnoreCase("VALIDATE_BLOB_ON_DATACENTER")) {
        blobValidator.validate(new String[]{datacenter});
        blobValidator.validateBlobOnDatacenter(blobIdList, map, datacenter, expiredBlobs);
      } else if (typeOfOperation.equalsIgnoreCase("VALIDATE_BLOB_ON_ALL_REPLICAS")) {
        blobValidator.validateBlobOnAllReplicas(blobIdList, map, expiredBlobs);
      } else {
        System.out.println("Invalid Type of Operation ");
        System.exit(1);
      }
    } catch (Exception e) {
      System.out.println("Closed with error " + e);
    }
  }

  /**
   * Validates that elements of values are not null
   * @param values
   */
  public void validate(String[] values) {
    for (String value : values) {
      if (value == null) {
        System.out.println("Value " + value + " has to be set");
        System.exit(0);
      }
    }
  }

  private ArrayList<BlobId> generateBlobId(ArrayList<String> blobIdListStr, ClusterMap map)
      throws IOException {
    ArrayList<BlobId> blobIdList = new ArrayList<BlobId>();
    for (String blobIdStr : blobIdListStr) {
      try {
        BlobId blobId = new BlobId(blobIdStr, map);
        blobIdList.add(blobId);
      } catch (IOException e) {
        System.out.println("IOException thrown for blobId " + blobIdStr);
        invalidBlobs.put(blobIdStr, e);
      } catch (IllegalArgumentException e) {
        System.out.println("IllegalArgumentException thrown for blobId " + blobIdStr);
        invalidBlobs.put(blobIdStr, e);
      }
    }
    return blobIdList;
  }

  private void validateBlobOnAllReplicas(ArrayList<BlobId> blobIdList, ClusterMap clusterMap, boolean expiredBlobs) {
    Map<BlobId, Map<ServerErrorCode, ArrayList<ReplicaId>>> resultMap =
        new HashMap<BlobId, Map<ServerErrorCode, ArrayList<ReplicaId>>>();
    for (BlobId blobId : blobIdList) {
      System.out.println("Validating blob " + blobId + " on all replicas \n");
      validateBlobOnAllReplicas(blobId, clusterMap, expiredBlobs, resultMap);
      System.out.println();
    }
    System.out.println("\nOverall Summary \n");
    for (BlobId blobId : resultMap.keySet()) {
      Map<ServerErrorCode, ArrayList<ReplicaId>> resultSet = resultMap.get(blobId);
      System.out.println(blobId);
      for (ServerErrorCode result : resultSet.keySet()) {
        System.out.println(result + " -> " + resultSet.get(result));
      }
      System.out.println();
    }
    if (invalidBlobs.size() != 0) {
      System.out.println("Invalid blobIds " + invalidBlobs);
    }
  }

  private void validateBlobOnAllReplicas(BlobId blobId, ClusterMap clusterMap, boolean expiredBlobs,
      Map<BlobId, Map<ServerErrorCode, ArrayList<ReplicaId>>> resultMap) {
    Map<ServerErrorCode, ArrayList<ReplicaId>> responseMap = new HashMap<ServerErrorCode, ArrayList<ReplicaId>>();
    for (ReplicaId replicaId : blobId.getPartition().getReplicaIds()) {
      ServerErrorCode serverErrorCode = null;
      try {
        ServerErrorCode errorCode = validateBlobOnReplica(blobId, clusterMap, replicaId, expiredBlobs);
        serverErrorCode = errorCode;
      } catch (MessageFormatException e) {
        serverErrorCode = ServerErrorCode.Data_Corrupt;
      } catch (IOException e) {
        serverErrorCode = ServerErrorCode.IO_Error;
      } catch (Exception e) {
        serverErrorCode = ServerErrorCode.Unknown_Error;
      }
      if (responseMap.containsKey(serverErrorCode)) {
        responseMap.get(serverErrorCode).add(replicaId);
      } else {
        ArrayList<ReplicaId> replicaList = new ArrayList<ReplicaId>();
        replicaList.add(replicaId);
        responseMap.put(serverErrorCode, replicaList);
      }
    }
    System.out.println("\nSummary ");
    for (ServerErrorCode serverErrorCode : responseMap.keySet()) {
      System.out.println(serverErrorCode + ": " + responseMap.get(serverErrorCode));
    }
    resultMap.put(blobId, responseMap);
  }

  private void validateBlobOnDatacenter(ArrayList<BlobId> blobIdList, ClusterMap clusterMap, String datacenter,
      boolean expiredBlobs) {
    Map<BlobId, Map<ServerErrorCode, ArrayList<ReplicaId>>> resultMap =
        new HashMap<BlobId, Map<ServerErrorCode, ArrayList<ReplicaId>>>();
    for (BlobId blobId : blobIdList) {
      System.out.println("Validating blob " + blobId + " on datacenter " + datacenter + "\n");
      validateBlobOnDatacenter(blobId, clusterMap, datacenter, expiredBlobs, resultMap);
      System.out.println();
    }
    System.out.println("\nOverall Summary \n");
    for (BlobId blobId : resultMap.keySet()) {
      Map<ServerErrorCode, ArrayList<ReplicaId>> resultSet = resultMap.get(blobId);
      System.out.println(blobId);
      for (ServerErrorCode serverErrorCode : resultSet.keySet()) {
        System.out.println(serverErrorCode + " -> " + resultSet.get(serverErrorCode));
      }
      System.out.println();
    }
  }

  private void validateBlobOnDatacenter(BlobId blobId, ClusterMap clusterMap, String datacenter, boolean expiredBlobs,
      Map<BlobId, Map<ServerErrorCode, ArrayList<ReplicaId>>> resultMap) {
    Map<ServerErrorCode, ArrayList<ReplicaId>> responseMap = new HashMap<ServerErrorCode, ArrayList<ReplicaId>>();
    for (ReplicaId replicaId : blobId.getPartition().getReplicaIds()) {
      if (replicaId.getDataNodeId().getDatacenterName().equalsIgnoreCase(datacenter)) {
        ServerErrorCode serverErrorCode = null;
        try {
          ServerErrorCode errorCode = validateBlobOnReplica(blobId, clusterMap, replicaId, expiredBlobs);
          serverErrorCode = errorCode;
        } catch (MessageFormatException e) {
          serverErrorCode = ServerErrorCode.Data_Corrupt;
        } catch (IOException e) {
          serverErrorCode = ServerErrorCode.IO_Error;
        } catch (Exception e) {
          serverErrorCode = ServerErrorCode.Unknown_Error;
        }
        if (responseMap.containsKey(serverErrorCode)) {
          responseMap.get(serverErrorCode).add(replicaId);
        } else {
          ArrayList<ReplicaId> replicaList = new ArrayList<ReplicaId>();
          replicaList.add(replicaId);
          responseMap.put(serverErrorCode, replicaList);
        }
      }
    }
    System.out.println("\nSummary ");
    for (ServerErrorCode serverErrorCode : responseMap.keySet()) {
      System.out.println(serverErrorCode + ": " + responseMap.get(serverErrorCode));
    }
    resultMap.put(blobId, responseMap);
  }

  private void validateBlobOnReplica(ArrayList<BlobId> blobIdList, ClusterMap clusterMap, String replicaHost,
      int replicaPort, boolean expiredBlobs)
      throws Exception {
    Map<BlobId, ServerErrorCode> resultMap = new HashMap<BlobId, ServerErrorCode>();

    // find the replicaId based on given host name and port number
    for (BlobId blobId : blobIdList) {
      ReplicaId targetReplicaId = null;
      for (ReplicaId replicaId : blobId.getPartition().getReplicaIds()) {
        if (replicaId.getDataNodeId().getHostname().equals(replicaHost)) {
          Port port = replicaId.getDataNodeId().getPortToConnectTo(sslEnabledDatacentersList);
          if (port.getPort() == replicaPort) {
            targetReplicaId = replicaId;
            break;
          }
        }
      }
      if (targetReplicaId == null) {
        throw new Exception("Can not find blob " + blobId.getID() + "in host " + replicaHost);
      }
      System.out.println("Validating blob " + blobId + " on replica " + replicaHost + ":" + replicaPort + "\n");
      ServerErrorCode response = null;
      try {
        response = validateBlobOnReplica(blobId, clusterMap, targetReplicaId, expiredBlobs);
        if (response == ServerErrorCode.No_Error) {
          System.out.println("Successfully read the blob " + blobId);
        } else {
          System.out.println("Failed to read the blob " + blobId + " due to " + response);
        }
        resultMap.put(blobId, response);
      } catch (MessageFormatException e) {
        resultMap.put(blobId, ServerErrorCode.Data_Corrupt);
      } catch (IOException e) {
        resultMap.put(blobId, ServerErrorCode.IO_Error);
      } catch (Exception e) {
        resultMap.put(blobId, ServerErrorCode.Unknown_Error);
      }
      System.out.println();
    }
    System.out.println("\nOverall Summary \n");
    for (BlobId blobId : resultMap.keySet()) {
      System.out.println(blobId + " :: " + resultMap.get(blobId));
    }
  }

  private ServerErrorCode validateBlobOnReplica(BlobId blobId, ClusterMap clusterMap, ReplicaId replicaId, boolean expiredBlobs)
      throws MessageFormatException, IOException, ConnectionPoolTimeoutException, InterruptedException {
    ArrayList<BlobId> blobIds = new ArrayList<BlobId>();
    blobIds.add(blobId);
    ConnectedChannel connectedChannel = null;
    AtomicInteger correlationId = new AtomicInteger(1);

    PartitionRequestInfo partitionRequestInfo = new PartitionRequestInfo(blobId.getPartition(), blobIds);
    ArrayList<PartitionRequestInfo> partitionRequestInfos = new ArrayList<PartitionRequestInfo>();
    partitionRequestInfos.add(partitionRequestInfo);

    GetOptions getOptions = (expiredBlobs) ? GetOptions.Include_Expired_Blobs : GetOptions.None;

    try {
      Port port = replicaId.getDataNodeId().getPortToConnectTo(sslEnabledDatacentersList);
      connectedChannel = connectionPool.checkOutConnection(replicaId.getDataNodeId().getHostname(), port, 10000);

      GetRequest getRequest =
          new GetRequest(correlationId.incrementAndGet(), "readverifier", MessageFormatFlags.BlobProperties,
              partitionRequestInfos, getOptions);
      System.out.println(
          "----- Contacting " + replicaId.getDataNodeId().getHostname() + ":" + port.toString() + " -------");
      System.out.println("Get Request to verify replica blob properties : " + getRequest);
      GetResponse getResponse = null;

      getResponse = getGetResponseFromStream(connectedChannel, getRequest, clusterMap);
      if (getResponse == null) {
        System.out.println(" Get Response from Stream to verify replica blob properties is null ");
        System.out.println(blobId + " STATE FAILED");
        connectedChannel = null;
        return ServerErrorCode.Unknown_Error;
      }
      ServerErrorCode serverResponseCode = getResponse.getPartitionResponseInfoList().get(0).getErrorCode();
      System.out.println("Get Response from Stream to verify replica blob properties : " + getResponse.getError());
      if (getResponse.getError() != ServerErrorCode.No_Error || serverResponseCode != ServerErrorCode.No_Error) {
        System.out.println("getBlobProperties error on response " + getResponse.getError() +
            " error code on partition " + serverResponseCode +
            " ambryReplica " + replicaId.getDataNodeId().getHostname() + " port " + port.toString() +
            " blobId " + blobId);
        if (serverResponseCode == ServerErrorCode.Blob_Not_Found) {
          return ServerErrorCode.Blob_Not_Found;
        } else if (serverResponseCode == ServerErrorCode.Blob_Deleted) {
          return serverResponseCode;
        } else if (serverResponseCode == ServerErrorCode.Blob_Expired) {
          if (getOptions != GetOptions.Include_Expired_Blobs) {
            return serverResponseCode;
          }
        } else {
          return serverResponseCode;
        }
      } else {
        BlobProperties properties = MessageFormatRecord.deserializeBlobProperties(getResponse.getInputStream());
        System.out.println(
            "Blob Properties : Content Type : " + properties.getContentType() + ", OwnerId : " + properties.getOwnerId()
                +
                ", Size : " + properties.getBlobSize() + ", CreationTimeInMs : " + properties.getCreationTimeInMs() +
                ", ServiceId : " + properties.getServiceId() + ", TTL : " + properties.getTimeToLiveInSeconds());
      }

      getRequest = new GetRequest(correlationId.incrementAndGet(), "readverifier", MessageFormatFlags.BlobUserMetadata,
          partitionRequestInfos, getOptions);
      System.out.println("Get Request to check blob usermetadata : " + getRequest);
      getResponse = null;
      getResponse = getGetResponseFromStream(connectedChannel, getRequest, clusterMap);
      if (getResponse == null) {
        System.out.println(" Get Response from Stream to verify replica blob usermetadata is null ");
        System.out.println(blobId + " STATE FAILED");
        connectedChannel = null;
        return ServerErrorCode.Unknown_Error;
      }
      System.out.println("Get Response to check blob usermetadata : " + getResponse.getError());

      serverResponseCode = getResponse.getPartitionResponseInfoList().get(0).getErrorCode();
      if (getResponse.getError() != ServerErrorCode.No_Error || serverResponseCode != ServerErrorCode.No_Error) {
        System.out.println("usermetadata get error on response " + getResponse.getError() +
            " error code on partition " + serverResponseCode +
            " ambryReplica " + replicaId.getDataNodeId().getHostname() + " port " + port.toString() +
            " blobId " + blobId);
        if (serverResponseCode == ServerErrorCode.Blob_Not_Found) {
          return serverResponseCode;
        } else if (serverResponseCode == ServerErrorCode.Blob_Deleted) {
          return serverResponseCode;
        } else if (serverResponseCode == ServerErrorCode.Blob_Expired) {
          if (getOptions != GetOptions.Include_Expired_Blobs) {
            return serverResponseCode;
          }
        } else {
          return serverResponseCode;
        }
      } else {
        ByteBuffer userMetadata = MessageFormatRecord.deserializeUserMetadata(getResponse.getInputStream());
        System.out.println("Usermetadata deserialized. Size " + userMetadata.capacity());
      }

      getRequest = new GetRequest(correlationId.incrementAndGet(), "readverifier", MessageFormatFlags.Blob,
          partitionRequestInfos, getOptions);
      System.out.println("Get Request to get blob : " + getRequest);
      getResponse = null;
      getResponse = getGetResponseFromStream(connectedChannel, getRequest, clusterMap);
      if (getResponse == null) {
        System.out.println(" Get Response from Stream to verify replica blob is null ");
        System.out.println(blobId + " STATE FAILED");
        connectedChannel = null;
        return ServerErrorCode.Unknown_Error;
      }
      System.out.println("Get Response to get blob : " + getResponse.getError());
      serverResponseCode = getResponse.getPartitionResponseInfoList().get(0).getErrorCode();
      if (getResponse.getError() != ServerErrorCode.No_Error || serverResponseCode != ServerErrorCode.No_Error) {
        System.out.println("blob get error on response " + getResponse.getError() +
            " error code on partition " + serverResponseCode +
            " ambryReplica " + replicaId.getDataNodeId().getHostname() + " port " + port.toString() +
            " blobId " + blobId);
        if (serverResponseCode == ServerErrorCode.Blob_Not_Found) {
          return serverResponseCode;
        } else if (serverResponseCode == ServerErrorCode.Blob_Deleted) {
          return serverResponseCode;
        } else if (serverResponseCode == ServerErrorCode.Blob_Expired) {
          if (getOptions != GetOptions.Include_Expired_Blobs) {
            return serverResponseCode;
          }
        } else {
          return serverResponseCode;
        }
      } else {
        BlobOutput blobOutput = MessageFormatRecord.deserializeBlob(getResponse.getInputStream());
        byte[] blobFromAmbry = new byte[(int) blobOutput.getSize()];
        int blobSizeToRead = (int) blobOutput.getSize();
        int blobSizeRead = 0;
        while (blobSizeRead < blobSizeToRead) {
          blobSizeRead += blobOutput.getStream().read(blobFromAmbry, blobSizeRead, blobSizeToRead - blobSizeRead);
        }
        System.out.println("BlobContent deserialized. Size " + blobOutput.getSize());
      }
      return ServerErrorCode.No_Error;
    } catch (MessageFormatException mfe) {
      System.out.println("MessageFormat Exception Error " + mfe);
      throw mfe;
    } catch (IOException e) {
      System.out.println("IOException " + e);
      throw e;
    } finally {
      if (connectedChannel != null) {
        connectionPool.checkInConnection(connectedChannel);
      }
    }
  }

  /**
   * Method to send request and receive response to and from a blocking channel. If it fails, blocking channel is destroyed
   *
   * @param blockingChannel
   * @param getRequest
   * @param clusterMap
   * @return
   */
  public static GetResponse getGetResponseFromStream(ConnectedChannel blockingChannel, GetRequest getRequest,
      ClusterMap clusterMap) {
    GetResponse getResponse = null;
    try {
      blockingChannel.send(getRequest);
      ChannelOutput channelOutput = blockingChannel.receive();
      InputStream stream = channelOutput.getInputStream();
      getResponse = GetResponse.readFrom(new DataInputStream(stream), clusterMap);
    } catch (Exception exception) {
      blockingChannel = null;
      exception.printStackTrace();
      System.out.println("Exception Error" + exception);
      return null;
    }
    return getResponse;
  }
}
