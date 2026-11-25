-- 旧库清理脚本：执行前备份；仅在不再需要历史任务和产物记录时手工运行。
-- 删除表会永久删除表内数据；不影响车辆、客户及库存业务表。
USE `used_car`;

DROP TABLE IF EXISTS `video_asset`;
DROP TABLE IF EXISTS `video_task`;
