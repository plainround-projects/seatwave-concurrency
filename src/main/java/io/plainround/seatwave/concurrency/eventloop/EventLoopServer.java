package io.plainround.seatwave.concurrency.eventloop;

import io.plainround.seatwave.concurrency.node.VirtualWorkers;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * A small line-based server on one event-loop thread, written with {@code java.nio}: one thread
 * watches every connection with a selector and reacts only when a socket is ready.
 *
 * <p>Protocol: the client sends {@code HOLD <seat>} and a newline; the server answers one line. A
 * request line longer than {@link #MAX_LINE_BYTES} closes the connection, so a slow or hostile
 * client cannot grow a buffer without bound. The server listens on the loopback address only.
 *
 * <p>Thread-safety: the selector, the channels and each connection's buffers are confined to the
 * loop thread. The only cross-thread traffic is the queue of finished replies, a thread-safe
 * bounded queue, followed by {@link Selector#wakeup()}; and, when that queue is full, closing the
 * channel, which is thread-safe.
 */
public final class EventLoopServer implements AutoCloseable {

  /** The longest request line accepted, in bytes. */
  public static final int MAX_LINE_BYTES = 64;

  private static final String REQUEST_PREFIX = "HOLD ";
  private static final String BUSY_REPLY = "503 busy";
  private static final String BAD_REQUEST_REPLY = "400 bad request";
  private static final int READ_BUFFER_BYTES = 256;

  private final LoopMode mode;
  private final BlockingWork work;
  private final Duration closeLimit;
  private final Selector selector;
  private final ServerSocketChannel serverChannel;
  private final VirtualWorkers offloadWorkers;
  private final BlockingQueue<FinishedReply> finishedReplies;
  private final Thread loopThread;
  private volatile boolean isRunning = true;

  /**
   * Opens the server on a free loopback port and starts its loop thread.
   *
   * @param mode where the blocking work runs
   * @param work the blocking part of each request
   * @param offloadMaxInFlight how many requests may run off the loop at once
   * @param closeLimit the longest {@link #close} waits for the loop thread
   * @throws IOException if the socket cannot be opened
   */
  public EventLoopServer(
      LoopMode mode, BlockingWork work, int offloadMaxInFlight, Duration closeLimit)
      throws IOException {
    this.mode = Objects.requireNonNull(mode, "mode");
    this.work = Objects.requireNonNull(work, "work");
    this.closeLimit = Objects.requireNonNull(closeLimit, "closeLimit");
    this.offloadWorkers = new VirtualWorkers(offloadMaxInFlight);
    // Bounded like every queue: the loop drains it on every wake-up, so it holds at most a burst.
    this.finishedReplies = new ArrayBlockingQueue<>(offloadMaxInFlight);
    this.selector = Selector.open();
    this.serverChannel = ServerSocketChannel.open();
    // Port 0 asks the operating system for any free port; loopback keeps every byte on the machine.
    serverChannel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    serverChannel.configureBlocking(false);
    serverChannel.register(selector, SelectionKey.OP_ACCEPT);
    this.loopThread = Thread.ofPlatform().name("seatwave-event-loop").start(this::runLoop);
  }

  /**
   * Returns the address clients connect to.
   *
   * @return the loopback address and the port the operating system chose
   * @throws IOException if the channel is closed
   */
  public InetSocketAddress address() throws IOException {
    return (InetSocketAddress) serverChannel.getLocalAddress();
  }

  /**
   * Stops the loop, waits up to the close limit for it, then closes every channel.
   *
   * @throws IOException if a channel fails to close, or {@link InterruptedIOException} if
   *     interrupted while waiting for the loop thread
   */
  @Override
  public void close() throws IOException {
    isRunning = false;
    selector.wakeup();
    try {
      if (!loopThread.join(closeLimit)) {
        throw new IllegalStateException("the event loop did not stop within " + closeLimit);
      }
    } catch (InterruptedException interrupted) {
      // Keep the interrupt visible to the caller, and report it the way I/O code does.
      Thread.currentThread().interrupt();
      InterruptedIOException stopped = new InterruptedIOException("interrupted while closing");
      stopped.initCause(interrupted);
      throw stopped;
    } finally {
      // Free the workers and the channels even when the loop thread did not stop in time.
      releaseResources();
    }
  }

  private void releaseResources() throws IOException {
    offloadWorkers.close();
    for (SelectionKey key : selector.keys()) {
      key.channel().close();
    }
    selector.close();
  }

  private void runLoop() {
    try {
      while (isRunning) {
        selector.select();
        sendFinishedReplies();
        Iterator<SelectionKey> ready = selector.selectedKeys().iterator();
        while (ready.hasNext()) {
          SelectionKey key = ready.next();
          ready.remove();
          handle(key);
        }
      }
    } catch (IOException loopFailed) {
      // The loop thread has no caller to throw to; failing loudly ends the thread with the cause.
      throw new UncheckedIOException("the event loop failed", loopFailed);
    }
  }

  private void handle(SelectionKey key) throws IOException {
    if (!key.isValid()) {
      return;
    }
    if (key.isAcceptable()) {
      accept();
    } else if (key.isReadable()) {
      read(key);
    } else if (key.isWritable()) {
      flush(key);
    }
  }

  private void accept() throws IOException {
    SocketChannel client = serverChannel.accept();
    if (client == null) {
      return;
    }
    client.configureBlocking(false);
    client.register(selector, SelectionKey.OP_READ, new Connection());
  }

  private void read(SelectionKey key) throws IOException {
    SocketChannel channel = (SocketChannel) key.channel();
    Connection connection = (Connection) key.attachment();
    ByteBuffer buffer = ByteBuffer.allocate(READ_BUFFER_BYTES);
    int bytesRead = channel.read(buffer);
    if (bytesRead < 0) {
      key.cancel();
      channel.close();
      return;
    }
    buffer.flip();
    while (buffer.hasRemaining()) {
      byte next = buffer.get();
      if (next == '\n') {
        String line = connection.takeLine();
        dispatch(key, line);
      } else if (!connection.append(next)) {
        // Over the limit: the bound on every read, so a client cannot hold memory without end.
        key.cancel();
        channel.close();
        return;
      }
    }
  }

  private void dispatch(SelectionKey key, String line) {
    if (!line.startsWith(REQUEST_PREFIX)) {
      queueReply(key, BAD_REQUEST_REPLY);
      return;
    }
    String seatLabel = line.substring(REQUEST_PREFIX.length()).strip();
    switch (mode) {
      case BLOCKING_IN_LOOP -> queueReply(key, holdOnThisThread(seatLabel));
      case OFFLOADED -> {
        boolean isAccepted =
            offloadWorkers.tryExecute(() -> {
              FinishedReply finished = new FinishedReply(key, holdOnThisThread(seatLabel));
              if (finishedReplies.offer(finished)) {
                // The loop may be asleep in select(); wake it so the reply goes out now.
                selector.wakeup();
              } else {
                // The loop is a full queue behind: close, so the client fails fast and
                // retries instead of waiting for a reply that has nowhere to go.
                closeChannel(key);
              }
            });
        if (!isAccepted) {
          queueReply(key, BUSY_REPLY);
        }
      }
    }
  }

  private String holdOnThisThread(String seatLabel) {
    try {
      return work.hold(seatLabel);
    } catch (InterruptedException interrupted) {
      // Only close interrupts the work: keep the flag and answer as a refusal.
      Thread.currentThread().interrupt();
      return BUSY_REPLY;
    }
  }

  private void sendFinishedReplies() {
    for (FinishedReply finished = finishedReplies.poll();
        finished != null;
        finished = finishedReplies.poll()) {
      if (finished.key().isValid()) {
        queueReply(finished.key(), finished.reply());
      }
    }
  }

  private void queueReply(SelectionKey key, String reply) {
    Connection connection = (Connection) key.attachment();
    connection.pendingReplies.add(ByteBuffer.wrap((reply + "\n").getBytes(StandardCharsets.UTF_8)));
    try {
      flush(key);
    } catch (IOException clientGone) {
      // The client closed its end; nothing is left to answer, so the connection is dropped.
      key.cancel();
      closeChannel(key);
    }
  }

  private static void flush(SelectionKey key) throws IOException {
    SocketChannel channel = (SocketChannel) key.channel();
    Connection connection = (Connection) key.attachment();
    while (!connection.pendingReplies.isEmpty()) {
      ByteBuffer next = connection.pendingReplies.peek();
      channel.write(next);
      if (next.hasRemaining()) {
        // The socket's send buffer is full: ask to be told when it drains, and stop for now.
        key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        return;
      }
      connection.pendingReplies.poll();
    }
    key.interestOps(SelectionKey.OP_READ);
  }

  private static void closeChannel(SelectionKey key) {
    try {
      key.channel().close();
    } catch (IOException alreadyBroken) {
      throw new UncheckedIOException("could not close a broken connection", alreadyBroken);
    }
  }

  private record FinishedReply(SelectionKey key, String reply) {}

  /** The bytes of one connection. Confined to the loop thread. */
  private static final class Connection {
    private final ByteBuffer lineBytes = ByteBuffer.allocate(MAX_LINE_BYTES);
    private final Queue<ByteBuffer> pendingReplies = new ArrayDeque<>();

    boolean append(byte next) {
      if (!lineBytes.hasRemaining()) {
        return false;
      }
      lineBytes.put(next);
      return true;
    }

    String takeLine() {
      lineBytes.flip();
      String line = StandardCharsets.UTF_8.decode(lineBytes).toString();
      lineBytes.clear();
      return line;
    }
  }
}
