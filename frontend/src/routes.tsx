import type { RouteObject } from 'react-router';
import { App } from './App';
import { MarketDataPage } from './components/MarketDataPage';
import { OrdersPage } from './components/OrdersPage';
import { PortfolioPage } from './components/PortfolioPage';
import { ToMarket } from './components/ToMarket';

/** Shared by `createBrowserRouter` (main.tsx) and `createMemoryRouter` (tests). */
export const routes: RouteObject[] = [
  {
    path: '/',
    element: <App />,
    children: [
      { index: true, element: <ToMarket /> },
      { path: 'market', element: <MarketDataPage /> },
      { path: 'portfolio', element: <PortfolioPage /> },
      { path: 'orders', element: <OrdersPage /> },
      { path: '*', element: <ToMarket /> },
    ],
  },
];
