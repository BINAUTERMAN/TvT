<script setup lang="ts">
import gpl from '../../public/licenses/GPL-2.0.txt?raw';
import apiLicense from '../../public/licenses/KuGouMusicApi.txt?raw';
import dependencies from '../../public/licenses/ThirdParty.txt?raw';
import airLyrics from '../../public/licenses/AirLyrics.txt?raw';
</script>
<template>
  <h2>开源许可</h2>
  <p class="muted">
    TvT以 GPL-2.0-only 发布。协议适配移植自 MakcRe/KuGouMusicApi（MIT），参考
    MoeKoeMusic 接口生态。
  </p>
  <details>
    <summary>GPL-2.0-only</summary>
    <pre class="license-text">{{ gpl }}</pre>
  </details>
  <details>
    <summary>KuGouMusicApi · MIT</summary>
    <pre class="license-text">{{ apiLicense }}</pre>
  </details>
  <details>
    <summary>其他第三方依赖</summary>
    <pre class="license-text">{{ dependencies }}</pre>
  </details>
  <details>
    <summary>AirLyrics · MIT</summary>
    <pre class="license-text">{{ airLyrics }}</pre>
  </details>
</template>
