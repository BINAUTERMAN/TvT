import { createApp } from 'vue';
import SurveyView from './components/SurveyView.vue';
import './design.css';
createApp(SurveyView, { standalone: true, available: true }).mount('#app');
