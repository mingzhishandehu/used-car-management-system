package com.usedcar.vehicle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.usedcar.vehicle.domain.Vehicle;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 车辆数据访问。
 *
 * BaseMapper 已经提供了 insert / deleteById / updateById / selectById / selectPage 等方法，
 * 单表操作不需要写一行 SQL，也不需要 XML 文件。
 *
 * 复杂查询（例如仪表盘的多表聚合）再在本接口里加 @Select 或 XML，不要提前写。
 */
public interface VehicleMapper extends BaseMapper<Vehicle> {

    /**
     * 取当天已用的最大库存编号。
     *
     * 为什么手写 SQL 而不走 LambdaQueryWrapper：
     *   走 Wrapper 会被逻辑删除条件（deleted = 0）追加过滤，被删掉的车占用的编号查不出来，
     *   新生成的编号就会跟历史编号撞上 uk_stock_no，插入直接失败。
     *   这里必须绕过逻辑删除，看到全部历史编号。
     */
    @Select("SELECT MAX(stock_no) FROM vehicle WHERE stock_no LIKE CONCAT(#{prefix}, '%')")
    String maxStockNo(@Param("prefix") String prefix);
}
