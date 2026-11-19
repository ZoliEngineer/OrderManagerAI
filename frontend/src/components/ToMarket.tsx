import { Navigate, useLocation } from 'react-router';

/** Redirects to the market page, keeping `?account=` so a deep link to `/` still selects the account. */
export function ToMarket() {
  const { search } = useLocation();
  return <Navigate to={{ pathname: '/market', search }} replace />;
}
