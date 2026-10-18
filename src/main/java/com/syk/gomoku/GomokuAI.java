package com.syk.gomoku;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Rapfi AI engine wrapper - spawns native executable, communicates via stdin/stdout.
 *
 * Protocol:
 *   Plugin ? Engine:  START <size>
 *   Plugin ? Engine:  YXBOARD <moves> DONE
 *   Plugin ? Engine:  YXNBEST <n>
 *   Engine ? Plugin:  <x>,<y>
 *   Engine ? Plugin:  INFO EVAL <score>
 */
public class GomokuAI implements AutoCloseable {

    private final Logger logger;
    private final Path enginePath;
    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private Thread readerThread;
    private volatile boolean running;

    private Consumer<String> lineCallback;
    private CompletableFuture<int[]> moveFuture;
    private int boardSize = 15;
    /** 单步思考上限(ms)。不发给引擎它会无限深搜, 永远不会回棋。 */
    private int turnTimeoutMs = 3000;

    public GomokuAI(Logger logger, Path enginePath) {
        this.logger = logger;
        this.enginePath = enginePath;
    }

    public boolean start() {
        try {
            ProcessBuilder pb = new ProcessBuilder(enginePath.toAbsolutePath().toString());
            pb.directory(enginePath.getParent().toFile());
            pb.redirectErrorStream(false);
            process = pb.start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            running = true;

            readerThread = new Thread(this::readLoop, "GomokuAI-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            Thread stderrThread = new Thread(() -> {
                try (var err = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (running && (line = err.readLine()) != null)
                        logger.warning("[Rapfi] " + line);
                } catch (IOException ignored) {}
            }, "GomokuAI-stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();

            logger.info("Rapfi engine started: " + enginePath);
            return true;
        } catch (IOException e) {
            logger.severe("Failed to start Rapfi: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void close() {
        running = false;
        if (process != null) {
            send("END");
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            process.destroy();
            try { process.waitFor(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            process.destroyForcibly();
        }
    }

    public synchronized void send(String command) {
        if (writer == null || !running) return;
        try { writer.write(command); writer.newLine(); writer.flush(); }
        catch (IOException e) { logger.warning("Send failed: " + e.getMessage()); }
    }

    public void newGame(int size) {
        this.boardSize = size;
        send("START " + size);          // 引擎回 OK
    }

    /** 标准 Gomocup: BOARD 之后逐行 x,y,field (1=引擎方, 2=对手), 最后 DONE。 */
    public void sendBoard(int[][] board, int sideToMove) {
        // 必须先告诉引擎这一步最多想多久, 否则它会一直深搜不返回
        send("INFO timeout_turn " + turnTimeoutMs);
        send("INFO max_memory 268435456");
        send("BOARD");
        for (int r = 0; r < boardSize; r++) {
            for (int c = 0; c < boardSize; c++) {
                if (board[r][c] == 0) continue;
                int field = board[r][c] == sideToMove ? 1 : 2;
                send(c + "," + r + "," + field);      // 引擎坐标是 (x,y) = (列, 行)
            }
        }
        moveFuture = new CompletableFuture<>();
        send("DONE");                                   // 引擎在这之后回它的一步
    }

    /** DONE 之后引擎会自己回一步, 这里只把等待中的 future 交出去。 */
    public CompletableFuture<int[]> requestBestMove() {
        if (moveFuture == null) moveFuture = new CompletableFuture<>();
        return moveFuture;
    }

    /**
     * Stops the current search without terminating the engine. Rapfi's Gomocup
     * protocol accepts STOP / YXSTOP for exactly this; END quits the process.
     */
    public void stopThinking() {
        send("STOP");
        if (moveFuture != null && !moveFuture.isDone()) moveFuture.cancel(false);
    }

    public void setRule(int rule) { send("INFO RULE " + rule); }
    public void setThreads(int n) { send("INFO THREAD_NUM " + n); }
    public void setDepth(int d) { send("INFO MAX_DEPTH " + d); }
    public void setTurnTimeout(int ms) { send("INFO TIMEOUT_TURN " + ms); }

    private void readLoop() {
        try {
            String line;
            while (running && (line = reader.readLine()) != null)
                processLine(line.trim());
        } catch (IOException e) {
            if (running) logger.warning("Reader error: " + e.getMessage());
        }
    }

    private void processLine(String line) {
        if (line.isEmpty()) return;
        if (lineCallback != null) lineCallback.accept(line);

        int sp = line.indexOf(' ');
        if (sp == -1) {
            switch (line) {
                case "OK", "SWAP" -> {}
                default -> {
                    int ci = line.indexOf(',');
                    if (ci > 0 && moveFuture != null && !moveFuture.isDone()) {
                        try {
                            int x = Integer.parseInt(line.substring(0, ci));
                            int y = Integer.parseInt(line.substring(ci + 1));
                            moveFuture.complete(new int[]{x, y});
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
            return;
        }
        String head = line.substring(0, sp), tail = line.substring(sp + 1);
        switch (head) {
            case "MESSAGE" -> logger.fine("[Rapfi] " + tail);   // 引擎搜索进度, 别刷控制台
            case "ERROR" -> {
                logger.warning("[Rapfi] " + tail);
                if (moveFuture != null && !moveFuture.isDone())
                    moveFuture.completeExceptionally(new RuntimeException(tail));
            }
        }
    }

    public void setLineCallback(Consumer<String> cb) { this.lineCallback = cb; }
}
