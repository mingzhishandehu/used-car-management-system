package com.usedcar.agent.dto;

import jakarta.validation.constraints.NotBlank;

/** 老板发的一句话。不做长度上限：自然语言本来就不该按字数卡。 */
public record ChatRequest(
        @NotBlank(message = "消息不能为空") String content
) {
}
