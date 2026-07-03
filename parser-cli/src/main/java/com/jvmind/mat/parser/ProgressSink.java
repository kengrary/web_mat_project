package com.jvmind.mat.parser;

import com.google.gson.JsonObject;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 进度/事件输出目的地：stdout 或 --progress-file 指定的文件。
 * 每条事件一行 JSON（JSONL），线程安全（synchronized），每行 flush，
 * 保证 Python 侧能实时逐行读到。
 */
public final class ProgressSink implements Closeable {

    private final PrintStream stdout;       // 非 null 时写 stdout
    private final BufferedWriter file;      // 非 null 时写文件

    private ProgressSink(PrintStream stdout, BufferedWriter file) {
        this.stdout = stdout;
        this.file = file;
    }

    public static ProgressSink toStdout() {
        // 用独立的 UTF-8 PrintStream，避免平台默认编码问题
        return new ProgressSink(new PrintStream(System.out, true, StandardCharsets.UTF_8), null);
    }

    public static ProgressSink toFile(String path) {
        try {
            return new ProgressSink(null, new BufferedWriter(new FileWriter(path, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void write(JsonObject obj) {
        String line = obj.toString();
        try {
            if (stdout != null) {
                stdout.println(line);
                stdout.flush();
            }
            if (file != null) {
                file.write(line);
                file.newLine();
                file.flush();
            }
        } catch (IOException e) {
            // 进度写失败不应中断解析；降级到 stderr
            System.err.println("[parser-cli] failed to write progress: " + e);
        }
    }

    @Override
    public void close() {
        try {
            if (file != null) file.close();
        } catch (IOException ignore) {
            // best effort
        }
    }
}
