package com.usedcar.video.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.usedcar.video.domain.VideoTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 视频任务的数据访问。
 */
public interface VideoTaskMapper extends BaseMapper<VideoTask> {

    /**
     * 取「该推进」的任务。
     *
     * 两部分：
     *   1. 进行中且 next_poll_at 到期的，正常推进；
     *   2. 停在 PENDING 超过阈值的（staleBefore 之前创建），说明异步提交没成功，
     *      需要捡回来重试——否则这些任务会永远停在待提交，谁也不会再碰它。
     * LIMIT 50 是给上游留余地：一次打 500 个轮询请求，供应商会限流。
     */
    @Select("""
            SELECT * FROM video_task
            WHERE (status IN ('SUBMITTED', 'PROCESSING', 'DOWNLOADING', 'COMPOSING')
                   AND (next_poll_at IS NULL OR next_poll_at <= #{now}))
               OR (status = 'PENDING' AND created_at <= #{staleBefore})
            ORDER BY next_poll_at ASC
            LIMIT 50
            """)
    List<VideoTask> selectDueTasks(@Param("now") LocalDateTime now,
                                   @Param("staleBefore") LocalDateTime staleBefore);

    /**
     * 启动补偿：把所有「进行中」的任务捞出来。
     *
     * 服务重启的瞬间，内存里的线程池没了，但数据库里这些行还停在生成中的状态。
     * 不捞出来，它们会永远卡在那儿——这就是「只用 @Async 不用状态表」的必然后果。
     */
    @Select("""
            SELECT * FROM video_task
            WHERE status IN ('PENDING', 'SUBMITTED', 'PROCESSING', 'DOWNLOADING', 'COMPOSING')
            """)
    List<VideoTask> selectInFlight();
}
