package com.usedcar.agent.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地关键词抽取器，仅用于没配 API Key 时联调前端与写库链路。
 *
 * 不是「假 AI」：它和真实实现走同一个 IntakeExtractor 接口、同一套字段清洗、
 * 同一套确认后写库流程。区别只在「怎么把一句话变成字段」。
 * 正式演示把 USEDCAR_AI_PROVIDER 改回 siliconflow 即可，业务代码一行不动。
 */
@Service
@ConditionalOnProperty(name = "usedcar.ai.provider", havingValue = "mock")
public class MockIntakeExtractor implements IntakeExtractor {

    private static final List<String> BRANDS = List.of(
            "大众", "丰田", "本田", "宝马", "奔驰", "奥迪", "特斯拉", "比亚迪", "日产", "别克",
            "福特", "现代", "吉利", "长安", "哈弗", "五菱", "保时捷", "雷克萨斯", "沃尔沃",
            "马自达", "起亚", "雪佛兰", "标致", "领克", "理想", "蔚来", "小鹏");

    private static final Pattern VIN = Pattern.compile("[A-HJ-NPR-Z0-9]{17}");
    private static final Pattern PLATE = Pattern.compile("[\\u4e00-\\u9fa5][A-Z][A-Z0-9]{5}");
    private static final Pattern MONEY_WAN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(万|w|W)");
    private static final Pattern MONEY_YUAN = Pattern.compile("(\\d{4,8})\\s*(?:元|块)");
    private static final Pattern MILEAGE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*万?\\s*(?:公里|千米|km|KM)");
    private static final Pattern DATE = Pattern.compile("(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})");

    @Override
    public ExtractionResult extract(ExtractionContext context) {
        String text = context.userInput() == null ? "" : context.userInput().trim();
        Map<String, Object> fields = new LinkedHashMap<>();

        // 「改成 X」这类修改指令，mock 只处理最简单的「收车价改成 N 万」
        Matcher vin = VIN.matcher(text);
        if (vin.find()) {
            fields.put("vin", vin.group());
        }
        Matcher plate = PLATE.matcher(text);
        if (plate.find()) {
            fields.put("plateNo", plate.group());
        }
        Matcher mileage = MILEAGE.matcher(text);
        if (mileage.find()) {
            // 直接把整个匹配串交给 normalize：它既能处理「6.2万公里」，也能处理「62000公里」，
            // 自行拼接反而会把 62000 错拼成 62000万。
            fields.put("mileage", mileage.group(0));
        }

        Matcher moneyWan = MONEY_WAN.matcher(text);
        Matcher moneyYuan = MONEY_YUAN.matcher(text);
        if (moneyWan.find()) {
            fields.put("purchasePrice", moneyWan.group(1) + "万");
        } else if (moneyYuan.find()) {
            fields.put("purchasePrice", moneyYuan.group(1));
        }
        // 一句里同时出现「6.8万」和「挂牌 8 万」时，第二个数字当挂牌价
        if (moneyWan.find()) {
            fields.put("listPrice", moneyWan.group(1) + "万");
        }

        Matcher date = DATE.matcher(text);
        if (date.find()) {
            fields.put("purchaseDate", String.format("%s-%02d-%02d", date.group(1),
                    Integer.parseInt(date.group(2)), Integer.parseInt(date.group(3))));
        } else if (text.contains("今天")) {
            fields.put("purchaseDate", context.today().toString());
        }

        for (String brand : BRANDS) {
            int at = text.indexOf(brand);
            if (at >= 0) {
                fields.put("brand", brand);
                String rest = text.substring(at + brand.length())
                        .replaceAll("^(收了|收的|一台|一辆|一台车|的车|的|\\s)+", "")
                        .split("[，,。;；\\s]", 2)[0]
                        .replaceAll("(今天|昨天|收的|收了|一台|一辆)$", "")
                        .trim();
                if (!rest.isEmpty() && rest.length() <= 40 && !fields.containsKey("model")) {
                    fields.put("model", rest);
                }
                break;
            }
        }

        // 追问文案不在这里拼：交给 AiIntakeService.defaultReply()，它按 IntakeFieldSpec
        // 算出缺哪些字段并输出中文标签。之前这里直接把 fields.keySet() 拼进回复，
        // 老板在气泡里看到的会是 brand、model 这种内部字段名，属于界面缺陷，已修。
        return new ExtractionResult(fields, null);
    }
}
