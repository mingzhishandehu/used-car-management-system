package com.usedcar.video.compose;

import com.usedcar.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * FFmpeg 调用封装。
 *
 * 三个必须处理好的点，少一个都会在生产上出问题：
 *   1. 外部进程必须设超时。ffmpeg 卡住不返回时，若调用方线程无限等待，整个线程池会被拖死。
 *   2. stderr 必须消费。Process 的输出缓冲区满了之后，子进程会阻塞在 write 上，
 *      表现为「命令永远不会结束」——这是 Java 调外部进程最经典的坑。
 *   3. 退出码必须检查。ffmpeg 出错时退出码非 0，不检查就会把没生成好的文件当成成功。
 */
@Component
public class FfmpegRunner {

    private static final Logger log = LoggerFactory.getLogger(FfmpegRunner.class);

    /** 单次 ffmpeg 调用的超时秒数，正常合成一条 6 秒视频在 1~3 秒内完成 */
    private static final long TIMEOUT_SECONDS = 120;

    private final String ffmpegPath;

    public FfmpegRunner(@Value("${app.video.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    /**
     * 执行一条 ffmpeg 命令。
     *
     * @param args 不含可执行文件本身的参数列表
     */
    public void run(List<String> args) {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(ffmpegPath);
        command.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(command);
        // 合并 stderr 到 stdout，避免两个缓冲区都要单独消费
        pb.redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new BizException(500, "无法启动 ffmpeg，请检查是否已安装并在 PATH 中: " + e.getMessage());
        }

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // 只留最后若干行，ffmpeg 的日志很长，全存会吃内存
                output.append(line).append('\n');
                if (output.length() > 8000) {
                    output.delete(0, output.length() - 4000);
                }
            }
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BizException(500, "ffmpeg 执行超时（" + TIMEOUT_SECONDS + " 秒）");
            }
            if (process.exitValue() != 0) {
                log.error("ffmpeg 执行失败，退出码 {}，输出:\n{}", process.exitValue(), output);
                throw new BizException(500, "ffmpeg 执行失败，退出码 " + process.exitValue());
            }
        } catch (IOException e) {
            throw new BizException(500, "读取 ffmpeg 输出失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new BizException(500, "ffmpeg 执行被中断");
        }
    }

    /**
     * 取媒体文件时长（秒）。取不到时按 6 秒处理，不让探测失败阻断整条流水线。
     */
    public double probeDurationSeconds(Path file) {
        List<String> command = List.of(
                ffmpegPath.replace("ffmpeg", "ffprobe"),
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                file.toAbsolutePath().toString());

        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line.trim());
                }
            }
            process.waitFor(15, TimeUnit.SECONDS);
            return Double.parseDouble(sb.toString());
        } catch (Exception e) {
            log.warn("探测时长失败，按 6 秒处理: {}", e.getMessage());
            return 6.0;
        }
    }

    /** 确保目录存在，ffmpeg 不会自己建目录 */
    public static Path ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new BizException(500, "创建目录失败: " + dir);
        }
    }
}
