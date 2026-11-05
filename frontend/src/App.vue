<template>
  <router-view />
</template>

<script setup>
/**
 * 根组件。
 * 只放一个出口，布局交给 BasicLayout，登录页自己独立占满全屏。
 */
</script>

<style>
html,
body,
#app {
  height: 100%;
  margin: 0;
  font-family: system-ui, -apple-system, 'Segoe UI', 'Microsoft YaHei', sans-serif;
}
</style>
