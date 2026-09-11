import { createApp } from 'vue';
import { autoAnimatePlugin } from '@formkit/auto-animate/vue';
import App from '@/App.vue';
import '@/style.css';

createApp(App).use(autoAnimatePlugin).mount('#app');
