import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { AppProviders } from '@/app/providers';
import { AppRoutes } from '@/routes';
import '@/styles/index.css';

const container = document.getElementById('root');
if (!container) {
  throw new Error('Root element not found — check index.html');
}

createRoot(container).render(
  <StrictMode>
    <AppProviders>
      <AppRoutes />
    </AppProviders>
  </StrictMode>,
);
