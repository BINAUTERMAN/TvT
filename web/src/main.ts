import { createApp } from 'vue';
import App from './App.vue';
import './design.css';
import { notify } from './store';
window.addEventListener('ting:message', (event) =>
  notify((event as CustomEvent<string>).detail),
);
createApp(App).mount('#app');
