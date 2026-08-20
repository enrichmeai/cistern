package com.enrichmeai.cistern.mcp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;

/**
 * One MCP session over this process's stdio — and <strong>the transport boundary</strong>:
 * the one place in cistern-mcp where reactive code meets a blocking world (ground rule 3's
 * documented exception, in one place only).
 *
 * <p>Why a boundary exists at all: stdio's ends are not reactive. On one side, MCP frames
 * arrive on {@code stdin} and a launching client (Claude Desktop) signals shutdown by closing
 * the pipe — a blocking fact with no publisher. On the other, the process outlives {@code
 * main} only by blocking something. So exactly two calls here block, both on lifecycle
 * threads that serve no requests, never on a scheduler that does:
 *
 * <ul>
 *   <li>{@link #awaitInputClosed()} — the bridge's main thread parking until the client
 *       closes stdin;</li>
 *   <li>{@link #close()} — waiting, bounded, for the server's graceful close on the way out
 *       (the bridge's main thread, or Spring's shutdown thread in the embedded shape).</li>
 * </ul>
 *
 * Everything between the two — every frame, every tool call, every HTTP request — is fully
 * reactive; the SDK's stdio transport reads and writes on its own single-thread schedulers.
 *
 * <p><strong>stdout hygiene:</strong> stdout belongs to the MCP frames. The real stream is
 * captured for the transport before anything else can write to it, and {@code System.out} is
 * then redirected to stderr, so a stray {@code println} in any library corrupts a log, never
 * the protocol. (Logging is on stderr anyway: slf4j-simple in the bridge, and the
 * {@code mcp-stdio} Spring profile in cistern-app.)
 */
final class McpStdioSession implements AutoCloseable {

    /** Bounded patience for the graceful close; the pipe is gone, nobody is answering. */
    static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final McpAsyncServer server;
    private final CountDownLatch inputClosed;

    private McpStdioSession(McpAsyncServer server, CountDownLatch inputClosed) {
        this.server = server;
        this.inputClosed = inputClosed;
    }

    /**
     * Open the session on this process's stdio: capture stdout for the frames, redirect
     * {@code System.out} to stderr, start serving. Building the server is what starts the
     * transport reading stdin.
     */
    static McpStdioSession open(BearerCredential credential, PodAddress address) {
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(address, "address");
        PrintStream frames = System.out;
        System.setOut(new PrintStream(System.err, true));
        CountDownLatch inputClosed = new CountDownLatch(1);
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
                McpJsonDefaults.getMapper(), new EofSignalling(System.in, inputClosed), frames);
        return new McpStdioSession(McpFrontDoor.serve(transport, credential, address), inputClosed);
    }

    /**
     * Park the calling thread until the client closes stdin. Blocking, by design — see the
     * class comment; only the bridge's main thread calls this.
     */
    void awaitInputClosed() {
        try {
            inputClosed.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Close gracefully, waiting at most {@link #CLOSE_TIMEOUT} — the boundary's second call. */
    @Override
    public void close() {
        try {
            server.closeGracefully().block(CLOSE_TIMEOUT);
        } catch (RuntimeException ignored) {
            // the pipe is closed and the timeout has passed: there is nothing left to save,
            // and an exit path must not throw
            server.close();
        }
    }

    /** Counts down when stdin ends, whichever way it ends. */
    private static final class EofSignalling extends FilterInputStream {

        private static final int END_OF_STREAM = -1;

        private final CountDownLatch closed;

        private EofSignalling(InputStream in, CountDownLatch closed) {
            super(in);
            this.closed = closed;
        }

        @Override
        public int read() throws IOException {
            return signal(super.read());
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return signal(super.read(buffer, offset, length));
        }

        @Override
        public void close() throws IOException {
            closed.countDown();
            super.close();
        }

        private int signal(int result) {
            if (result == END_OF_STREAM) {
                closed.countDown();
            }
            return result;
        }
    }
}
