package com.github.ambry.shared;

import com.github.ambry.config.ConnectionPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketException;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

class BlockingChannelInfo {
  private final ArrayBlockingQueue<BlockingChannel> blockingChannelAvailableConnections;
  private final ArrayBlockingQueue<BlockingChannel> blockingChannelActiveConnections;
  private final AtomicInteger numberOfConnections;
  private final ConnectionPoolConfig config;
  private final ReadWriteLock rwlock;
  private final Object lock;
  private final String host;
  private final int port;
  private final Logger logger = LoggerFactory.getLogger(getClass());

  public BlockingChannelInfo(ConnectionPoolConfig config, String host, int port) {
    this.config = config;
    this.blockingChannelAvailableConnections =
            new ArrayBlockingQueue<BlockingChannel>(config.connectionPoolMaxConnectionsPerHost);
    this.blockingChannelActiveConnections =
            new ArrayBlockingQueue<BlockingChannel>(config.connectionPoolMaxConnectionsPerHost);
    this.numberOfConnections = new AtomicInteger(0);
    this.rwlock = new ReentrantReadWriteLock();
    this.lock = new Object();
    this.host = host;
    this.port = port;
    logger.info("Starting blocking channel info for host {} and port {}", host, port);
  }

  public void addBlockingChannel(BlockingChannel blockingChannel) {
    rwlock.readLock().lock();
    try {
      blockingChannelActiveConnections.remove(blockingChannel);
      blockingChannelAvailableConnections.add(blockingChannel);
      logger.trace("Adding connection back to pool. Current available connections {} Current active connections {}",
              blockingChannelAvailableConnections.size(), blockingChannelActiveConnections.size());
    }
    finally {
      rwlock.readLock().unlock();
    }
  }

  public BlockingChannel getBlockingChannel(long timeoutInMs)
          throws InterruptedException, ConnectionPoolTimeoutException {
    rwlock.readLock().lock();
    try {
      // check if the max connections for this queue has reached or if there are any connections available
      // in the available queue. The check in available queue is approximate and it could not have any
      // connections when polled. In this case we just depend on an existing connection being placed back in
      // the available pool
      if (numberOfConnections.get() == config.connectionPoolMaxConnectionsPerHost ||
          blockingChannelAvailableConnections.size() > 0) {
        BlockingChannel channel = blockingChannelAvailableConnections.poll(timeoutInMs, TimeUnit.MILLISECONDS);
        if (channel == null) {
          logger.error("Timed out trying to get a connection for host {} and port {}", host, port);
          throw new ConnectionPoolTimeoutException("Could not get a connection to host " + host + " and port " + port);
        }
        blockingChannelActiveConnections.add(channel);
        return channel;
      }
      synchronized (lock) {
        // if the number of connections created for this host and port is less than the max allowed
        // connections, we create a new one and add it to the available queue
        if (numberOfConnections.get() < config.connectionPoolMaxConnectionsPerHost) {
          BlockingChannel channel = new BlockingChannel(host,
                                                        port,
                                                        config.connectionPoolReadBufferSizeBytes,
                                                        config.connectionPoolWriteBufferSizeBytes,
                                                        config.connectionPoolReadTimeoutMs);
          channel.connect();
          blockingChannelAvailableConnections.add(channel);
          numberOfConnections.incrementAndGet();
          logger.trace("Creating a new connection for host {} and port {}. Number of connections {}",
                  host, port, numberOfConnections.get());
        }
      }
      BlockingChannel channel = blockingChannelAvailableConnections.poll(timeoutInMs, TimeUnit.MILLISECONDS);
      if (channel == null) {
        logger.error("Timed out trying to get a connection for host {} and port {}", host, port);
        throw new ConnectionPoolTimeoutException("Could not get a connection to host " + host + " and port " + port);
      }
      blockingChannelActiveConnections.add(channel);
      return channel;
    }
    catch (SocketException e) {
      logger.error("Socket exception when trying to connect to remote host {} and port {}", host, port);
      throw new ConnectionPoolTimeoutException("Socket exception when trying to connect to remote host "
                                               + host + " port "  + port, e);
    }
    catch (IOException e) {
      logger.error("IOException when trying to connect to the remote host {} and port {}", host, port);
      throw new ConnectionPoolTimeoutException("IOException when trying to connect to remote host "
                                               + host + " port " + port, e);
    }
    finally {
      rwlock.readLock().unlock();
    }
  }

  public void destroyBlockingChannel(BlockingChannel blockingChannel) {
    rwlock.readLock().lock();
    try {
      boolean changed = blockingChannelActiveConnections.remove(blockingChannel);
      if (!changed) {
        logger.error("Invalid connection being destroyed. " +
                "Channel does not belong to this queue. queue host {} port {} channel host {} port {}",
                host, port, blockingChannel.getRemoteHost(), blockingChannel.getRemotePort());
        throw new IllegalArgumentException("Invalid connection. Channel does not belong to this queue");
      }
      blockingChannel.disconnect();
      // we ensure we maintain the current count of connections to the host
      BlockingChannel channel = new BlockingChannel(blockingChannel.getRemoteHost(),
                                                    blockingChannel.getRemotePort(),
                                                    config.connectionPoolReadBufferSizeBytes,
                                                    config.connectionPoolWriteBufferSizeBytes,
                                                    config.connectionPoolReadTimeoutMs);
      channel.connect();
      logger.trace("Destroying connection and adding new connection for host {} port {}", host, port);
      blockingChannelAvailableConnections.add(channel);
    }
    catch (Exception e) {
      logger.error("Connection failure to remote host {} and port {} when destroying and recreating the connection"
                   , host, port);
      synchronized (lock) {
        // decrement the number of connections to the host and port. we were not able to maintain the count
        numberOfConnections.decrementAndGet();
      }
    }
    finally {
      rwlock.readLock().unlock();
    }
  }

  public void cleanup() {
    rwlock.writeLock().lock();
    logger.info("Cleaning all active and available connections for host {} and port {}", host, port);
    try {
      for (BlockingChannel channel : blockingChannelActiveConnections) {
        channel.disconnect();
      }
      blockingChannelActiveConnections.clear();
      for (BlockingChannel channel : blockingChannelAvailableConnections) {
        channel.disconnect();
      }
      blockingChannelAvailableConnections.clear();
      numberOfConnections.set(0);
      logger.info("Cleaning completed for all active and available connections for host {} and port {}", host, port);
    }
    finally {
      rwlock.writeLock().unlock();
    }

  }
}

/**
 * A connection pool that uses BlockingChannel as the underlying connection.
 * It is responsible for all the connection management. It helps to
 * checkout a new connection, checkin an existing connection that has been
 * checked out and destroy a connection in the case of an error
 */
public final class BlockingChannelConnectionPool implements ConnectionPool {

  private final Map<String, BlockingChannelInfo> connections;
  private final ConnectionPoolConfig config;
  private final Logger logger = LoggerFactory.getLogger(getClass());

  public BlockingChannelConnectionPool(ConnectionPoolConfig config) {
    connections = new ConcurrentHashMap<String, BlockingChannelInfo>();
    this.config = config;
  }

  @Override
  public void start() {
    logger.info("BlockingChannelConnectionPool started");
  }

  @Override
  public void shutdown() {
    logger.info("Shutting down the BlockingChannelConnectionPool");
    for (Map.Entry<String, BlockingChannelInfo> channels : connections.entrySet()) {
      channels.getValue().cleanup();
    }
  }

  @Override
  public ConnectedChannel checkOutConnection(String host, int port, long timeoutInMs)
          throws IOException, InterruptedException, ConnectionPoolTimeoutException {
    BlockingChannelInfo blockingChannelInfo = connections.get(host+port);
    if (blockingChannelInfo == null) {
      synchronized (this) {
        blockingChannelInfo = connections.get(host+port);
        if (blockingChannelInfo == null) {
          logger.trace("Creating new blocking channel info for host {} and port {}", host, port);
          blockingChannelInfo = new BlockingChannelInfo(config, host, port);
          connections.put(host+port, blockingChannelInfo);
        }
      }
    }
    return blockingChannelInfo.getBlockingChannel(timeoutInMs);
  }

  @Override
  public void checkInConnection(ConnectedChannel connectedChannel) {
    BlockingChannelInfo blockingChannelInfo =
            connections.get(connectedChannel.getRemoteHost()+connectedChannel.getRemotePort());
    if (blockingChannelInfo == null) {
      logger.error("Unexpected state in connection pool. Host {} and port {} not found to checkin connection",
              connectedChannel.getRemoteHost(), connectedChannel.getRemotePort());
      throw new IllegalArgumentException("Connection does not belong to the pool");
    }
    blockingChannelInfo.addBlockingChannel((BlockingChannel)connectedChannel);
  }

  @Override
  public void destroyConnection(ConnectedChannel connectedChannel) {
    BlockingChannelInfo blockingChannelInfo =
            connections.get(connectedChannel.getRemoteHost()+connectedChannel.getRemotePort());
    if (blockingChannelInfo == null) {
      logger.error("Unexpected state in connection pool. Host {} and port {} not found to checkin connection",
              connectedChannel.getRemoteHost(), connectedChannel.getRemotePort());
      throw new IllegalArgumentException("Connection does not belong to the pool");
    }
    blockingChannelInfo.destroyBlockingChannel((BlockingChannel) connectedChannel);
  }
}
