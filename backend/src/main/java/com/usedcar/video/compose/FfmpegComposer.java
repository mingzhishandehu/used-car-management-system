package com.usedcar.video.compose;

import com.usedcar.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 视频合成：把口播文案烧成字幕，配上音轨，输出成片。
 *
 * 三个真实处理过的坑：
 *   1. 路径要转义。ffmpeg 的 filter 语法里冒号是参数分隔符，Windows 的 C:\ 会把它切断，
 *      必须写成 C\:/ 并且用正斜杠。
 *   2. 字幕用 SRT 文件而不是 drawtext 直接写文本。文案里有中文标点、引号、百分号时，
 *      drawtext 的转义规则极其难调，写错就整条命令失败；SRT 是纯文本文件，零转义。
 *   3. 必须显式 -map 指定音视频流。不指定的话，ffmpeg 可能只挑到一条流，
 *      输出一个没声音的视频，而且退出码还是 0，很难发现。
 */
@Component
public class FfmpegComposer {

    private static final Logger log = LoggerFactory.getLogger(FfmpegComposer.class);

    /** 每行字幕最多字数，超过就断行，否则一行字铺满屏幕看不清 */
    private static final int MAX_CHARS_PER_LINE = 16;

    /** 中文字体，Windows 自带。换机器时改这一处 */
    private static final String FONT_NAME = "Microsoft YaHei";

    private final FfmpegRunner ffmpegRunner;
    private final Path workRoot;

    public FfmpegComposer(FfmpegRunner ffmpegRunner,
                          @Value("${app.video.work-dir:./uploads/videos}") String workDir) {
        this.ffmpegRunner = ffmpegRunner;
        this.workRoot = FfmpegRunner.ensureDir(Path.of(workDir));
    }

    /**
     * 合成成片。
     *
     * @param taskId   任务 id，用来分目录
     * @param rawVideo 上游原始素材的本地路径
     * @param copy     口播文案，会被切成多行字幕
     * @return 成片路径
     */
    public Path compose(Long taskId, Path rawVideo, String copy) {
        if (!Files.exists(rawVideo)) {
            throw new BizException(500, "原始素材不存在: " + rawVideo);
        }

        Path taskDir = FfmpegRunner.ensureDir(workRoot.resolve(String.valueOf(taskId)));
        double duration = ffmpegRunner.probeDurationSeconds(rawVideo);

        Path subtitle = taskDir.resolve("subtitle.srt");
        writeSrt(subtitle, copy, duration);

        Path output = taskDir.resolve("final.mp4");
        String filter = String.format("subtitles=filename='%s':force_style='FontName=%s,FontSize=20,"
                        + "PrimaryColour=&HFFFFFF&,OutlineColour=&H90000000&,BorderStyle=1,Outline=1,Shadow=0,MarginV=28'",
                escapeFilterPath(subtitle), FONT_NAME);

        List<String> args = new ArrayList<>(List.of(
                "-y", "-hide_banner", "-loglevel", "error",
                "-i", rawVideo.toAbsolutePath().toString(),
                // 占位音轨：TTS 未接入时用静音轨补位，保证成片是「有音轨的视频」
                "-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo",
                "-vf", filter,
                "-map", "0:v:0",
                "-map", "1:a:0",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                "-c:a", "aac", "-b:a", "96k",
                // 音轨是无限长的，用 -shortest 按视频时长截断
                "-shortest",
                "-pix_fmt", "yuv420p",
                output.toAbsolutePath().toString()));

        ffmpegRunner.run(args);
        log.info("视频合成完成: task={} -> {}", taskId, output);
        return output;
    }

    /**
     * 把文案切成多行字幕，按时长平均分配时间轴。
     *
     * 按标点切句，再按字数断行；切不出内容时退回整段。
     */
    private void writeSrt(Path srtPath, String copy, double duration) {
        List<String> lines = splitLines(copy);
        if (lines.isEmpty()) {
            lines = List.of("暂无文案");
        }

        double per = duration / lines.size();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            double start = i * per;
            double end = (i + 1) * per;
            sb.append(i + 1).append('\n')
                    .append(formatTime(start)).append(" --> ").append(formatTime(end)).append('\n')
                    .append(lines.get(i)).append("\n\n");
        }

        try {
            Files.writeString(srtPath, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException(500, "写入字幕文件失败: " + e.getMessage());
        }
    }

    private List<String> splitLines(String copy) {
        List<String> result = new ArrayList<>();
        if (copy == null || copy.isBlank()) {
            return result;
        }
        // 先按句末标点切句，保留语义完整
        String[] sentences = copy.split("[。！？；\\n]+");
        for (String sentence : sentences) {
            String s = sentence.trim();
            if (s.isEmpty()) {
                continue;
            }
            // 单句太长再按逗号切，避免一行字铺满屏幕
            if (s.length() <= MAX_CHARS_PER_LINE) {
                result.add(s);
            } else {
                for (String part : s.split("[，,、]")) {
                    String p = part.trim();
                    if (!p.isEmpty()) {
                        result.add(p);
                    }
                }
            }
        }
        return result;
    }

    private String formatTime(double seconds) {
        int total = (int) Math.floor(seconds);
        int millis = (int) Math.round((seconds - total) * 1000);
        int h = total / 3600;
        int m = (total % 3600) / 60;
        int s = total % 60;
        return String.format("%02d:%02d:%02d,%03d", h, m, s, millis);
    }

    /**
     * 把 Windows 路径转成 ffmpeg filter 能接受的形式：
     *   C:\a\b.srt  →  C\:/a/b.srt
     * 正斜杠是因为反斜杠在 filter 里是转义符，冒号必须转义是因为它是参数分隔符。
     */
    private String escapeFilterPath(Path path) {
        String p = path.toAbsolutePath().toString().replace("\\", "/");
        return p.replace(":", "\\:");
    }
}
