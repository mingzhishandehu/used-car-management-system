package com.usedcar.video.provider;

/**
 * 视频供应商抽象。
 *
 * 为什么要有这个接口：真实的图生视频（Seedance / 聆眸 / 可灵）都要求 AK/SK 和真实计费，
 * 而它们提供的交互模式是一样的——「提交一个生成任务 → 拿到任务 ID → 反复查询直到出结果」。
 * 把这三个动作抽象出来，整个异步状态机就跟具体供应商解耦：
 * 拿到 AK/SK 时只新增一个实现类，改一行配置，状态机、超时、重试、补偿全都不用动。
 *
 * 这也是「AI 能力的生产调用必须先抽象」的具体体现——直接 new 一个 SDK 客户端塞进 Service，
 * 换供应商就等于重写业务逻辑。
 */
public interface VideoProvider {

    /** 供应商标识，落库到 video_task.provider 字段 */
    String name();

    /**
     * 提交生成任务。
     *
     * 注意：这是阻塞调用，可能耗时数十秒，必须在异步线程里执行，绝不能放在 HTTP 请求线程上。
     */
    SubmitResult submit(String imagePrompt, String copy);

    /**
     * 查询一次进度。这是幂等的只读操作，可以安全重试。
     */
    PollResult poll(String submitId);

    /** 提交结果 */
    record SubmitResult(boolean success, String submitId, String error) {

        public static SubmitResult ok(String submitId) {
            return new SubmitResult(true, submitId, null);
        }

        public static SubmitResult fail(String error) {
            return new SubmitResult(false, null, error);
        }
    }

    /** 轮询结果 */
    record PollResult(PollState state, String videoUrl, String error) {

        public static PollResult processing() {
            return new PollResult(PollState.PROCESSING, null, null);
        }

        public static PollResult done(String videoUrl) {
            return new PollResult(PollState.DONE, videoUrl, null);
        }

        public static PollResult failed(String error) {
            return new PollResult(PollState.FAILED, null, error);
        }
    }

    enum PollState {
        /** 上游还在生成 */
        PROCESSING,
        /** 生成完成，videoUrl 可用 */
        DONE,
        /** 上游明确报失败 */
        FAILED
    }
}
