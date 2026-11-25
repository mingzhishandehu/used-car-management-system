<template>
  <svg
    class="nav-icon"
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    :stroke-width="strokeWidth"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <!-- 工作台：四块面板 -->
    <template v-if="name === 'dashboard'">
      <rect x="3.5" y="3.5" width="7" height="7" rx="2" />
      <rect x="13.5" y="3.5" width="7" height="4.5" rx="1.8" />
      <rect x="13.5" y="11" width="7" height="9.5" rx="2" />
      <rect x="3.5" y="13.5" width="7" height="7" rx="2" />
    </template>

    <!-- 车辆：车身 + 两轮 -->
    <template v-else-if="name === 'car'">
      <path d="M4.2 11.2l1.5-4A2.2 2.2 0 0 1 7.8 5.8h8.4a2.2 2.2 0 0 1 2.1 1.4l1.5 4" />
      <path d="M3.4 11.2h17.2a1 1 0 0 1 1 1v3.2a1 1 0 0 1-1 1H20M3.4 16.4H2.4a1 1 0 0 1-1-1v-3.2a1 1 0 0 1 1-1" />
      <path d="M6 16.4h12" />
      <path d="M7.7 5.9l-.7 5.3h10l-.7-5.3" opacity=".55" />
      <circle cx="7" cy="17.6" r="1.7" />
      <circle cx="17" cy="17.6" r="1.7" />
    </template>

    <!-- AI：机器人头 -->
    <template v-else-if="name === 'robot'">
      <rect x="4.2" y="8" width="15.6" height="11.4" rx="4" />
      <path d="M12 4.2V8" />
      <circle cx="12" cy="3.4" r="1.1" />
      <path d="M9.2 12.4v1.6M14.8 12.4v1.6" />
      <path d="M9.6 16.4h4.8" />
      <path d="M2.4 12.2v3.4M21.6 12.2v3.4" opacity=".6" />
    </template>

    <!-- 审批：带勾的板夹 -->
    <template v-else-if="name === 'approval'">
      <path d="M8.5 4.6H6.6A1.8 1.8 0 0 0 4.8 6.4v12.4a1.8 1.8 0 0 0 1.8 1.8h10.8a1.8 1.8 0 0 0 1.8-1.8V6.4a1.8 1.8 0 0 0-1.8-1.8h-1.9" />
      <rect x="8.5" y="3" width="7" height="3.6" rx="1.4" />
      <path d="M8.8 13.4l2.1 2.1 4.3-4.3" />
    </template>

    <!-- 预警：铃铛 -->
    <template v-else-if="name === 'bell'">
      <path d="M18 9.6a6 6 0 1 0-12 0c0 4.1-1.7 5.3-2.3 5.9-.3.3 0 .9.4.9h15.8c.5 0 .7-.6.4-.9-.6-.6-2.3-1.8-2.3-5.9Z" />
      <path d="M9.8 19.4a2.4 2.4 0 0 0 4.4 0" />
    </template>

    <!-- 员工：两个人 -->
    <template v-else-if="name === 'users'">
      <circle cx="9.2" cy="8.4" r="3.4" />
      <path d="M3 20c0-3.2 2.8-5.2 6.2-5.2s6.2 2 6.2 5.2" />
      <path d="M16.2 5.4a3.2 3.2 0 0 1 0 6.2M17.6 14.9c2.2.5 3.6 2.2 3.6 4.4" opacity=".7" />
    </template>

    <!-- 火花：AI 标记 -->
    <template v-else-if="name === 'spark'">
      <path d="M12 3.2l1.7 4.6 4.6 1.7-4.6 1.7L12 15.8l-1.7-4.6L5.7 9.5l4.6-1.7L12 3.2Z" />
      <path d="M18.4 15.4l.8 2.1 2.1.8-2.1.8-.8 2.1-.8-2.1-2.1-.8 2.1-.8.8-2.1Z" opacity=".65" />
    </template>

    <!-- 金币 -->
    <template v-else-if="name === 'coin'">
      <circle cx="12" cy="12" r="8.4" />
      <path d="M9.4 9.6l2.6 2.4 2.6-2.4M12 12v5M10.2 13.6h3.6M10.2 15.6h3.6" />
    </template>

    <!-- 折线图 -->
    <template v-else-if="name === 'chart'">
      <path d="M4 19.4V4.6M4 19.4h16" />
      <path d="M7.6 15.8l3.6-4.2 3 2.6 4.4-6" />
    </template>

    <!-- 盒子：库存 -->
    <template v-else-if="name === 'box'">
      <path d="M3.6 8.2L12 3.8l8.4 4.4v7.6L12 20.2l-8.4-4.4V8.2Z" />
      <path d="M3.6 8.2L12 12.6l8.4-4.4M12 12.6v7.6" opacity=".7" />
    </template>

    <!-- 灯泡：提示 -->
    <template v-else-if="name === 'bulb'">
      <path d="M9.2 17.4h5.6M10 20.2h4" />
      <path d="M12 3.4a6 6 0 0 0-3.4 10.9c.5.4.8 1 .8 1.6v.5h5.2v-.5c0-.6.3-1.2.8-1.6A6 6 0 0 0 12 3.4Z" />
    </template>

    <!-- 火焰：风险/预警 -->
    <template v-else-if="name === 'fire'">
      <path d="M12 3.2c.4 3-1.1 4.3-2.4 5.6-1.4 1.4-2.6 2.9-2.6 5.1a5.3 5.3 0 0 0 10.6 0c0-2-.8-3.3-1.7-4.5-.5 1-1.1 1.6-1.9 1.9.4-2.7-.6-5.6-2-8.1Z" />
    </template>

    <!-- 时钟 -->
    <template v-else-if="name === 'clock'">
      <circle cx="12" cy="12" r="8.6" />
      <path d="M12 7.4V12l3.2 2" />
    </template>

    <!-- 加号 -->
    <template v-else-if="name === 'plus'">
      <path d="M12 5.4v13.2M5.4 12h13.2" />
    </template>

    <!-- 右箭头 -->
    <template v-else-if="name === 'arrow'">
      <path d="M5 12h13.4M13.4 6.6L18.8 12l-5.4 5.4" />
    </template>

    <!-- 刷新 -->
    <template v-else-if="name === 'refresh'">
      <path d="M19.4 12a7.4 7.4 0 1 1-2.2-5.2" />
      <path d="M19.8 4.6v4.2h-4.2" />
    </template>

    <!-- 搜索 -->
    <template v-else-if="name === 'search'">
      <circle cx="11" cy="11" r="6.6" />
      <path d="M16 16l4.2 4.2" />
    </template>

    <!-- 退出 -->
    <template v-else-if="name === 'logout'">
      <path d="M14.6 5.4H6.8A1.8 1.8 0 0 0 5 7.2v9.6a1.8 1.8 0 0 0 1.8 1.8h7.8" />
      <path d="M14.4 12H21M18.2 8.8L21 12l-2.8 3.2" />
    </template>

    <!-- 兜底：小圆点，宁可画个点也不要整块空白 -->
    <template v-else>
      <circle cx="12" cy="12" r="3" />
    </template>
  </svg>
</template>

<script setup>
/**
 * 自定义线性图标。
 *
 * 为什么不用 Arco 图标：Arco 没有车辆图标（icon-car 不存在），
 * 混用会一半实心一半线性，视觉重量不一致。整套导航图标统一用 1.7 描边画，
 * 尺寸和颜色都吃 currentColor，父级改颜色即可联动。
 */
defineProps({
  name: { type: String, default: 'box' },
  size: { type: [Number, String], default: 18 },
  strokeWidth: { type: [Number, String], default: 1.7 }
})
</script>

<style scoped>
.nav-icon {
  display: block;
  flex: none;
}
</style>
